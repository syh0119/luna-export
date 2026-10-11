#!/usr/bin/env python3
"""汽水音乐 .mdl -> .flac / .m4a 离线解密器（含歌词+封面写入）

设计目标：像 fq-hook 那样「一键出成品」。

流程
  1. 判定容器：frma=fLaC -> FLAC-in-MP4；frma=mp4a -> AAC-in-MP4；无 tenc -> 未加密 M4A
  2. 取密钥：tenc.KID -> luna_index.kid2spade ／ 兜底 md5 文件名 -> vid -> vid2spade
  3. 密钥派生：spade_a --(SpadeDecryptor)--> 32 hex -> 16B AES-128 key
  4. 逐样本 AES-128-CTR 解密（senc 的 8B IV 后补 8 个 0 组成 16B 计数器块）
  5. 写标签（仅完整曲目）：TITLE/ARTIST/ALBUM/LYRICS(KRC->LRC) + 封面
       FLAC -> 追加 VORBIS_COMMENT(type 4) + PICTURE(type 6)
       M4A  -> 重写 moov 挂 udta/meta/ilst 并修正 stco/co64 偏移
  6. 出文件：
       FLAC  -> "fLaC" + dfLa 元数据块 + 解密后的帧   => 标准 .flac
       AAC   -> 原位把 enca 改名 mp4a、sinf/senc/saio/saiz 改名 free（尺寸不变，offset 不动）
       plain -> 原样 + 写标签 => .m4a

用法
  python luna_decrypt.py <file.mdl> [out_dir]                 # 单文件
  python luna_decrypt.py --batch <mdl_dir> <out_dir>          # 批量（默认只出完整曲目）
        [--include-partial]   # 顺便把残片也写到 <out_dir>/_partial
        [--lrc]               # 额外写出同名 .lrc 歌词文件
        [--no-tag]            # 不写标签/封面
  python luna_decrypt.py --report <mdl_dir>                   # 只体检不落盘
"""
import sys, os, json, glob, re, collections
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from _spade import from_spade_a
import luna_meta as META

HERE = os.path.dirname(os.path.abspath(__file__))
INDEX_PATH = os.path.join(HERE, 'luna_index.json')

CONTAINERS = {b'moov', b'trak', b'mdia', b'minf', b'stbl', b'edts', b'dinf', b'udta'}
COUNT8 = {b'stsd', b'dref'}
AUDIO_ENTRY = {b'enca', b'mp4a', b'fLaC', b'alac', b'Opus'}
AUDIO_ENTRY_HDR = 28   # 8 box header + 28 bytes AudioSampleEntry

VIDEO_FOURCC = {b'avc1', b'avc3', b'hvc1', b'hev1', b'mp4v', b'vp08', b'vp09',
                b'av01', b'encv', b'dvav', b'dvh1', b'dvhe', b'mp4s'}


# ---------------------------------------------------------------- MP4 parsing
def boxes(b, start, end):
    p = start
    while p + 8 <= end:
        sz = int.from_bytes(b[p:p+4], 'big')
        t = bytes(b[p+4:p+8])
        if sz == 1:
            sz = int.from_bytes(b[p+8:p+16], 'big'); hdr = 16
        else:
            hdr = 8
        if sz < hdr: break
        yield t, p, sz
        p += sz


def walk(b, name, start=0, end=None):
    if end is None: end = len(b)
    for t, p, sz in boxes(b, start, end):
        if t == name: yield p, sz
        if t in CONTAINERS: yield from walk(b, name, p+8, p+sz)
        elif t in COUNT8: yield from walk(b, name, p+16, p+sz)
        elif t in AUDIO_ENTRY: yield from walk(b, name, p+8+AUDIO_ENTRY_HDR, p+sz)


def find_box(b, name, end=None):
    """first occurrence of a 4cc at a plausible box position, restricted to `end`"""
    if end is None: end = len(b)
    j = b.find(name, 0, end)
    while j > 0:
        sz = int.from_bytes(b[j-4:j], 'big')
        if 8 <= sz <= end - (j-4):
            return j, sz
        j = b.find(name, j+1, end)
    return None, None


def kid_of(b):
    s, e = audio_range(b)
    i = b.find(b'tenc', s, e)
    while 0 < i < e:
        if int.from_bytes(b[i-4:i], 'big') in (32, 36):
            return b[i+12:i+28].hex()
        i = b.find(b'tenc', i+1, e)
    return None


# ---------------------------------------------------------------- 轨道定位
def _moov(b):
    for t, p, sz in boxes(b, 0, len(b)):
        if t == b'moov':
            return p, sz
    return None


def trak_list(b):
    """[(handler_fourcc, sample_entry_fourcc)] 每个 trak 一条"""
    m = _moov(b)
    if not m:
        return []
    out = []
    for t, p, sz in boxes(b, m[0]+8, m[0]+m[1]):
        if t != b'trak':
            continue
        h = None
        for hp, hsz in walk(b, b'hdlr', p+8, p+sz):
            h = bytes(b[hp+16:hp+20]); break
        f = None
        for sp, ssz in walk(b, b'stsd', p+8, p+sz):
            o = sp + 8 + 4 + 4 + 4
            f = bytes(b[o:o+4]); break
        out.append((h, f))
    return out


def has_video(b):
    for h, f in trak_list(b):
        if h == b'vide' or f in VIDEO_FOURCC:
            return True
    return False


def audio_range(b):
    """音频轨(handler=soun)的 [start, end)；找不到就退回整个文件"""
    m = _moov(b)
    if m:
        for t, p, sz in boxes(b, m[0]+8, m[0]+m[1]):
            if t != b'trak':
                continue
            for hp, hsz in walk(b, b'hdlr', p+8, p+sz):
                if bytes(b[hp+16:hp+20]) == b'soun':
                    return p+8, p+sz
                break
    return 0, len(b)


def parse_track(b):
    s0, e0 = audio_range(b)
    stbl = None
    for p, sz in walk(b, b'stbl', s0, e0):
        stbl = (p, sz); break
    if not stbl: return []
    s, e = stbl[0]+8, stbl[0]+stbl[1]
    out = {}
    for t, p, sz in boxes(b, s, e): out.setdefault(t, []).append((p, sz))
    if b'stsz' not in out: return []
    p, sz = out[b'stsz'][0]
    default_size = int.from_bytes(b[p+12:p+16], 'big'); count = int.from_bytes(b[p+16:p+20], 'big')
    if default_size:
        if count > 1_000_000: return []
        sizes = [default_size]*count
    else:
        off = p+20; sizes = [int.from_bytes(b[off+4*i:off+4*i+4], 'big') for i in range(count)]
    if b'co64' in out:
        p, sz = out[b'co64'][0]; n = int.from_bytes(b[p+12:p+16], 'big')
        chunks = [int.from_bytes(b[p+16+8*i:p+24+8*i], 'big') for i in range(n)]
    elif b'stco' in out:
        p, sz = out[b'stco'][0]; n = int.from_bytes(b[p+12:p+16], 'big')
        chunks = [int.from_bytes(b[p+16+4*i:p+20+4*i], 'big') for i in range(n)]
    else:
        return []
    if b'stsc' not in out: return []
    p, sz = out[b'stsc'][0]; n = int.from_bytes(b[p+12:p+16], 'big')
    stsc = [(int.from_bytes(b[p+16+12*i:p+20+12*i], 'big'),
             int.from_bytes(b[p+20+12*i:p+24+12*i], 'big')) for i in range(n)]
    if not stsc: return []
    chunks_sizes = []; idx = 0
    for ci in range(1, len(chunks)+1):
        while idx+1 < len(stsc) and stsc[idx+1][0] <= ci: idx += 1
        chunks_sizes.append(stsc[idx][1])
    table = []; si = 0
    for ci, spc in enumerate(chunks_sizes):
        off = chunks[ci]
        for _ in range(spc):
            if si >= len(sizes): break
            table.append((off, sizes[si])); off += sizes[si]; si += 1
    return table


def parse_ivs(b):
    s0, e0 = audio_range(b)
    out = []
    for p, sz in walk(b, b'senc', s0, e0):
        flags = int.from_bytes(b[p+9:p+12], 'big'); n = int.from_bytes(b[p+12:p+16], 'big')
        off = p+16
        for _ in range(n):
            if off+8 > p+sz: break
            out.append(bytes(b[off:off+8])); off += 8
            if flags & 2:
                cnt = int.from_bytes(b[off:off+2], 'big'); off += 2+6*cnt
    return out


def classify(b):
    """flac / aac / plain / video（MV 直接忽略）"""
    if has_video(b):
        return 'video'
    s0, e0 = audio_range(b)
    j, sz = find_box(b, b'frma', e0)
    if j is not None and j >= s0:
        f = bytes(b[j+4:j+8])
        if f == b'fLaC': return 'flac'
        if f == b'mp4a': return 'aac'
        if f in VIDEO_FOURCC: return 'video'
    j, sz = find_box(b, b'frma')
    if j is not None:
        f = bytes(b[j+4:j+8])
        if f == b'fLaC': return 'flac'
        if f == b'mp4a': return 'aac'
        if f in VIDEO_FOURCC: return 'video'
    if b.find(b'tenc') > 0:
        return 'aac'
    return 'plain'


# ---------------------------------------------------------------- crypto
def ctr(key, iv8, data):
    c = Cipher(algorithms.AES(key), modes.CTR(iv8 + b'\x00'*8))
    d = c.decryptor()
    return d.update(data) + d.finalize()


def decrypt_flac(buf, key, table, ivs, n):
    j, sz = find_box(buf, b'dfLa')
    if j is None: raise RuntimeError("no dfLa")
    payload = bytes(buf[j+4:j-4+sz])          # FullBox: 4B version/flags + metadata blocks
    meta = payload[4:]
    blocks = []; p = 0
    while p + 4 <= len(meta):
        hdr = meta[p]; ln = int.from_bytes(meta[p+1:p+4], 'big')
        if p+4+ln > len(meta): break
        blocks.append((hdr, bytes(meta[p+4:p+4+ln]))); p += 4+ln
    if not blocks: raise RuntimeError("empty dfLa metadata")
    out = bytearray(b'fLaC')
    for i, (hdr, data) in enumerate(blocks):
        h = (hdr & 0x7f) | (0x80 if i == len(blocks)-1 else 0)
        out += bytes([h]) + len(data).to_bytes(3, 'big') + data
    for i in range(n):
        off, s = table[i]
        out += ctr(key, ivs[i], bytes(buf[off:off+s]))
    return bytes(out)


def decrypt_aac(buf, key, table, ivs, n):
    for i in range(n):
        off, s = table[i]
        buf[off:off+s] = ctr(key, ivs[i], bytes(buf[off:off+s]))
    moov_end = len(buf)
    for t, p, sz in boxes(buf, 0, len(buf)):
        if t == b'mdat': moov_end = p; break
    for tag, repl in ((b'enca', b'mp4a'), (b'sinf', b'free'),
                      (b'senc', b'free'), (b'saio', b'free'), (b'saiz', b'free')):
        j = buf.find(tag, 0, moov_end)
        while j >= 0:
            buf[j:j+4] = repl
            j = buf.find(tag, j+4, moov_end)
    return bytes(buf)


# ---------------------------------------------------------------- index / naming
def load_index():
    if os.path.exists(INDEX_PATH):
        return json.load(open(INDEX_PATH, encoding='utf-8'))
    return {'kid2spade': {}, 'vid2spade': {}, 'md5_2_vid': {}, 'vid_meta': {}}


def lookup_key(b, md5, idx):
    k = kid_of(b)
    if k and k in idx['kid2spade']:
        return idx['kid2spade'][k], 'kid:' + k, k, None
    for vid, q in idx['md5_2_vid'].get(md5, []):
        if vid in idx['vid2spade']:
            return idx['vid2spade'][vid], 'vid:' + vid, k, vid
    return None, None, k, None


def full_meta(md5, idx, vid=None):
    """返回 (meta_dict, vid)。meta 含 title/artist/album/duration/lyric/cover。"""
    if not vid:
        for v, q in idx.get('md5_2_vid', {}).get(md5, []):
            vid = v; break
    return (idx.get('vid_meta', {}).get(vid, {}) if vid else {}), vid


_COVERS = None


def covers():
    global _COVERS
    if _COVERS is None:
        _COVERS = META.load_cover_map()
    return _COVERS


def build_tags(meta):
    """KRC -> LRC，返回 (tags, lrc)"""
    lrc = META.krc_to_lrc(meta.get('lyric') or '')
    tags = {'TITLE': meta.get('title') or '', 'ARTIST': meta.get('artist') or '',
            'ALBUM': meta.get('album') or '', 'LYRICS': lrc}
    return tags, lrc


def cover_bytes(meta):
    """取封面（已归一化为 ≤1200px JPEG）。"""
    cov = meta.get('cover')
    if not isinstance(cov, dict) or not cov.get('uri'):
        return None
    p = covers().get(META.cover_key(cov))
    if p and os.path.exists(p):
        return META.cached_cover(p)
    return None


BAD = re.compile(r'[\\/:*?"<>|\r\n\t]')


def fix_mojibake(s):
    if not isinstance(s, str) or not s: return s
    try:
        return s.encode('latin-1').decode('utf-8')
    except (UnicodeEncodeError, UnicodeDecodeError):
        return s


def safe(name, fallback):
    name = fix_mojibake((name or '').strip())
    name = BAD.sub('_', name)
    name = re.sub(r'\s+', ' ', name)[:120]
    return name or fallback


def unique_path(out, overwrite):
    if overwrite or not os.path.exists(out):
        return out
    base, ext = os.path.splitext(out)
    k = 0
    while os.path.exists(out):
        k += 1; out = '%s (%d)%s' % (base, k, ext)
    return out


def process(path, outdir, idx, dry=False, overwrite=True, tag=True, want_lrc=False):
    name = os.path.basename(path)
    md5 = name[:-4] if name.lower().endswith('.mdl') else name
    raw = open(path, 'rb').read()
    if len(raw) < 1024 or not raw[4:8].isalnum():
        return dict(md5=md5, kind='junk', samples=0, ivs=0, file_size=len(raw),
                    data_size=0, complete=False, out=None, key_src=None, kid=None,
                    vid=None, title=None, artist=None, album=None,
                    tagged=False, has_cover=False)
    b = bytearray(raw)
    kind = classify(b)
    table = parse_track(b)
    ivs = parse_ivs(b)
    n = min(len(table), len(ivs))
    size_sum = sum(s for _, s in table)
    max_off = max((o+s for o, s in table), default=0)
    if kind in ('plain', 'video'):
        # 无 senc：样本不加密，完整性只看数据是否落在文件内
        complete = bool(table) and size_sum <= len(b) and max_off <= len(b)
    else:
        complete = bool(table) and size_sum <= len(b) and max_off <= len(b) and n == len(table)

    if kind == 'video':
        # MV（vide+soun 双轨）——按需求忽略，不进成品库
        return dict(md5=md5, kind='video', samples=len(table), ivs=len(ivs),
                    file_size=len(b), data_size=size_sum, complete=complete, out=None,
                    key_src=None, kid=None, vid=None, title=None, artist=None,
                    album=None, tagged=False, has_cover=False)

    info = dict(md5=md5, kind=kind, samples=len(table), ivs=len(ivs),
                file_size=len(b), data_size=size_sum, complete=complete,
                out=None, key_src=None, kid=None, vid=None,
                title=None, artist=None, album=None,
                tagged=False, has_cover=False)

    # ---- 元数据
    sp, src, kid, vid = lookup_key(b, md5, idx)
    info.update(key_src=src, kid=kid, vid=vid)
    meta, vid2 = full_meta(md5, idx, vid)
    info.update(title=meta.get('title'), artist=meta.get('artist'),
                album=meta.get('album'), vid=vid or vid2)
    tags, lrc = build_tags(meta)
    # 封面按需获取（dry-run 与残片都不必读图）

    def emit(data, ext):
        """写文件 + （可选）写 .lrc 侧车"""
        base = safe('%s - %s' % (info['artist'], info['title'])
                    if info['title'] else md5, md5)
        out = unique_path(os.path.join(outdir, base + ext), overwrite)
        open(out, 'wb').write(data)
        info['out'] = out
        info['decrypted_bytes'] = len(data)
        if want_lrc and lrc:
            lp = os.path.splitext(out)[0] + '.lrc'
            open(lp, 'w', encoding='utf-8').write(lrc)
            info['lrc'] = lp

    if kind == 'plain':
        if not dry:
            data = bytes(b)
            if tag:
                cov = cover_bytes(meta)
                data = META.m4a_embed(data, tags, cov)
                info['tagged'] = bool(tags.get('TITLE') or tags.get('ARTIST'))
                info['has_cover'] = bool(cov)
            emit(data, '.m4a')
        return info

    if not sp:
        return info
    if not table:
        info['key_src'] = src + ' (no sample table)'
        return info

    hexkey = from_spade_a(sp)
    if len(hexkey) != 32:
        info['key_src'] = src + ' (bad spade)'
        return info
    key = bytes.fromhex(hexkey)
    eff = n
    if not complete:
        eff = sum(1 for i in range(n) if table[i][0] + table[i][1] <= len(b))
    if dry:
        return info

    if kind == 'flac':
        data = decrypt_flac(b, key, table, ivs, eff)
        ext = '.flac'
    else:
        data = decrypt_aac(bytearray(b), key, table, ivs, eff)
        ext = '.m4a'

    # 只有完整曲目才写标签（残片的 moov 声明与真实数据不符，写标签没有意义）
    if tag and complete:
        cov = cover_bytes(meta)
        if ext == '.flac':
            data = META.flac_embed(data, tags, cov)
        else:
            data = META.m4a_embed(data, tags, cov)
        info['tagged'] = bool(tags.get('TITLE') or tags.get('ARTIST'))
        info['has_cover'] = bool(cov)
    emit(data, ext)
    return info


def main():
    raw = sys.argv[1:]
    mode = None
    for m in ('--report', '--batch'):
        if m in raw:
            mode = m
    pos = [a for a in raw if not a.startswith('--')]
    flags = {a for a in raw if a.startswith('--')}
    tag = '--no-tag' not in flags
    want_lrc = '--lrc' in flags
    inc_partial = '--include-partial' in flags
    idx = load_index()

    if mode == '--report':
        src = pos[0] if pos else '.'
        rows = [process(f, '', idx, dry=True) for f in sorted(glob.glob(os.path.join(src, '*.mdl')))]
        cnt = collections.Counter()
        for r in rows:
            ok = bool(r['key_src'] and 'bad' not in r['key_src'] and 'no sample' not in r['key_src'])
            cnt[(r['kind'], 'KEY' if ok else 'NOKEY', 'complete' if r['complete'] else 'partial')] += 1
        for k, v in sorted(cnt.items()): print("  %-28s %d" % ('/'.join(k), v))
        print("  total %d  with title %d" % (len(rows), sum(1 for r in rows if r['title'])))
        return

    if mode == '--batch':
        if len(pos) < 2:
            print("usage: luna_decrypt.py --batch <mdl_dir> <out_dir> [--include-partial] [--lrc] [--no-tag]")
            return
        src, dst = pos[0], pos[1]
        os.makedirs(dst, exist_ok=True)
        files = sorted(glob.glob(os.path.join(src, '*.mdl')))
        ok = skip = part = 0
        report = []
        for f in files:
            probe = process(f, dst, idx, dry=True)
            if probe['kind'] == 'junk':
                skip += 1
                print("JUNK %s (size=%d)" % (probe['md5'], probe['file_size']))
                continue
            if probe['kind'] == 'video':
                part += 1
                print("VIDEO(skip) %-8s MV vide+soun   %s" % (probe['md5'][:8], probe['title'] or '-'))
                continue
            if not probe['complete'] and not inc_partial:
                part += 1
                print("PARTIAL(skip) %-8s %-5s %s" % (
                    probe['md5'][:8], probe['kind'], probe['title'] or '-'))
                continue
            outdir = dst if probe['complete'] else os.path.join(dst, '_partial')
            os.makedirs(outdir, exist_ok=True)
            r = process(f, outdir, idx, tag=tag, want_lrc=want_lrc)
            report.append(r)
            if r['out']:
                ok += 1
                print("OK   %-8s %-5s %-9s tag=%s cov=%s %s" % (
                    r['md5'][:8], r['kind'],
                    'complete' if r['complete'] else 'PARTIAL',
                    'Y' if r['tagged'] else '-', 'Y' if r['has_cover'] else '-',
                    os.path.basename(r['out'])))
            else:
                skip += 1
                print("SKIP %-8s %-5s %-9s key=%s kid=%s" % (
                    r['md5'][:8], r['kind'],
                    'complete' if r['complete'] else 'PARTIAL', r['key_src'], r['kid']))
        json.dump(report, open(os.path.join(dst, '_decrypt_report.json'), 'w',
                               encoding='utf-8'), ensure_ascii=False, indent=1)
        print("done: ok=%d skip=%d partial_skipped=%d" % (ok, skip, part))
        return

    if pos:
        outdir = pos[1] if len(pos) > 1 else os.path.join(HERE, 'luna', 'out')
        os.makedirs(outdir, exist_ok=True)
        r = process(pos[0], outdir, idx, tag=tag, want_lrc=want_lrc)
        print(json.dumps(r, ensure_ascii=False, indent=1))
        return
    print(__doc__)


if __name__ == '__main__':
    main()
