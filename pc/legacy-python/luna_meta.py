#!/usr/bin/env python3
"""把标题/歌手/专辑/歌词/封面写进 FLAC 与 M4A 容器。

FLAC : 追加 VORBIS_COMMENT(type 4) + PICTURE(type 6) 元数据块
M4A  : 重写 moov，挂 udta/meta/ilst(©nam ©ART ©alb ©lyr covr)，
       并按 moov 尺寸变化修正 stco/co64 绝对偏移。
"""
import os, re, glob, json, hashlib

HERE = os.path.dirname(os.path.abspath(__file__))
COVER_DIR = os.path.join(HERE, 'luna', 'covers')
COVER_MAP = os.path.join(HERE, 'cover_map.json')
THUMB_DIR = os.path.join(HERE, 'luna', 'covers_thumb')
COVER_MAX_PX = 1200
COVER_QUALITY = 88

# ------------------------------------------------------------------ 封面索引
def cover_url(cov, spec='image.image'):
    urls = cov.get('urls') or ['https://p3-luna.douyinpic.com/img/']
    base = urls[0]
    prefix = cov.get('prefix') or 'tplv-b829550vbb'
    return '%s%s~%s-%s' % (base, cov['uri'], prefix, spec)


def cover_key(cov):
    return hashlib.sha1(cover_url(cov).encode()).hexdigest()


def load_cover_map():
    if os.path.exists(COVER_MAP):
        try:
            return json.load(open(COVER_MAP, encoding='utf-8'))
        except Exception:
            return {}
    return {}


# ------------------------------------------------- 封面归一化（≤1200px JPEG）
def normalize_cover(data, max_px=COVER_MAX_PX, quality=COVER_QUALITY):
    """缩到 <=max_px 的 JPEG；无 Pillow / 失败 / 反而变大时原样返回。"""
    try:
        from PIL import Image
    except ImportError:
        return data
    import io
    try:
        im = Image.open(io.BytesIO(data))
        im.load()
    except Exception:
        return data
    if im.mode not in ('RGB', 'L'):
        im = im.convert('RGB')
    if max(im.size) > max_px:
        im.thumbnail((max_px, max_px), Image.LANCZOS)
    buf = io.BytesIO()
    try:
        im.save(buf, 'JPEG', quality=quality, optimize=True, progressive=True)
    except Exception:
        return data
    out = buf.getvalue()
    return out if len(out) < len(data) else data


def cached_cover(path, max_px=COVER_MAX_PX, quality=COVER_QUALITY):
    """读封面文件 -> 归一化 -> 按 sha1(原文)+参数缓存到 covers_thumb/"""
    try:
        raw = open(path, 'rb').read()
    except OSError:
        return None
    key = hashlib.sha1(raw).hexdigest()
    cp = os.path.join(THUMB_DIR, '%s_%d_q%d.jpg' % (key, max_px, quality))
    if os.path.exists(cp):
        try:
            return open(cp, 'rb').read()
        except OSError:
            pass
    out = normalize_cover(raw, max_px, quality)
    try:
        os.makedirs(THUMB_DIR, exist_ok=True)
        open(cp, 'wb').write(out)
    except OSError:
        pass
    return out


# ------------------------------------------------------------------ 标签键归一
def norm_tags(tags):
    """统一成大写键，兼容调用方传 TITLE / title 两种写法。"""
    out = {}
    for k, v in (tags or {}).items():
        if v is None:
            continue
        v = v if isinstance(v, str) else str(v)
        if v:
            out[str(k).upper()] = v
    return out


def _tag(tags, name, default=''):
    return tags.get(name.upper(), default) or default


# ------------------------------------------------------------------ 歌词 KRC->LRC
def krc_to_lrc(krc):
    """KRC:  [startMs,durMs]<off,dur,0>字<off,dur,0>字...
        ->   [mm:ss.xx]字字..."""
    lines = []
    for raw in krc.replace('\r\n', '\n').split('\n'):
        s = raw.strip()
        if not s:
            continue
        m = re.match(r'^\[(\d+),(\d+)\](.*)$', s)
        if not m:
            if s.startswith('['):          # 已经是 LRC
                lines.append(s)
            continue
        start = int(m.group(1))
        body = m.group(3)
        text = ''.join(re.findall(r'>([^<]*)', body)) if '<' in body else body
        text = text.replace('\u200b', '').strip()
        if text:
            lines.append('[%02d:%05.2f]%s' % (start // 60000, (start % 60000) / 1000.0, text))
    return '\n'.join(lines)


# ------------------------------------------------------------------ 图片信息
def image_info(data):
    if data[:2] == b'\xff\xd8':
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1; continue
            mk = data[i+1]
            if mk in (0xD8, 0x01) or 0xD0 <= mk <= 0xD7:
                i += 2; continue
            if i + 4 > len(data): break
            ln = int.from_bytes(data[i+2:i+4], 'big')
            if mk in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
                h = int.from_bytes(data[i+5:i+7], 'big')
                w = int.from_bytes(data[i+7:i+9], 'big')
                return ('image/jpeg', w, h, 24, 0)
            i += 2 + ln
        return ('image/jpeg', 0, 0, 24, 0)
    if data[:8] == b'\x89PNG\r\n\x1a\n':
        w = int.from_bytes(data[16:20], 'big'); h = int.from_bytes(data[20:24], 'big')
        bd = data[24]; ct = data[25]
        return ('image/png', w, h, bd * (4 if ct == 6 else 3), 0)
    return ('application/octet-stream', 0, 0, 24, 0)


# ================================================================== FLAC
def _u32be(n): return int(n).to_bytes(4, 'big')


def _u32le(n): return int(n).to_bytes(4, 'little')


def _meta_block(btype, payload, last=False):
    hdr = bytes([(0x80 if last else 0) | btype])
    return hdr + len(payload).to_bytes(3, 'big') + payload


# Vorbis comment: 所有长度/计数一律 **小端**（vendor 长度、条目数、每项长度）
def _vorbis_comment(tags):
    vendor = b'luna_decrypt'
    out = bytearray()
    out += _u32le(len(vendor)) + vendor
    items = []
    for k, v in tags.items():
        if v:
            items.append(('%s=%s' % (k, v)).encode('utf-8'))
    out += _u32le(len(items))
    for it in items:
        out += _u32le(len(it)) + it
    return bytes(out)


def _flac_picture(cover):
    mime, w, h, depth, colors = image_info(cover)
    out = bytearray()
    out += _u32be(3)                       # type: front cover
    out += _u32be(len(mime)) + mime.encode()
    out += _u32be(0)                       # description
    out += _u32be(w) + _u32be(h) + _u32be(depth) + _u32be(colors)
    out += _u32be(len(cover)) + cover
    return bytes(out)


def flac_embed(flac, tags, cover=None):
    """flac = bytes starting with 'fLaC' (already decrypted, no picture yet)."""
    if flac[:4] != b'fLaC':
        return flac
    tags = norm_tags(tags)
    p = 4
    blocks = []
    while p + 4 <= len(flac):
        hdr = flac[p]; ln = int.from_bytes(flac[p+1:p+4], 'big')
        if p + 4 + ln > len(flac):
            break
        blocks.append((hdr & 0x7f, flac[p+4:p+4+ln], bool(hdr & 0x80)))
        p += 4 + ln
        if hdr & 0x80:
            break
    frames = flac[p:]
    # 去掉已有 VORBIS_COMMENT / PICTURE
    blocks = [b for b in blocks if b[0] not in (4, 6)]
    blocks.append((4, _vorbis_comment(tags), False))
    if cover:
        blocks.append((6, _flac_picture(cover), False))
    out = bytearray(b'fLaC')
    for i, (bt, payload, _) in enumerate(blocks):
        out += _meta_block(bt, payload, last=(i == len(blocks) - 1))
    out += frames
    return bytes(out)


# ================================================================== M4A
def _atom(t, payload):
    return (8 + len(payload)).to_bytes(4, 'big') + t + payload


def _data_atom(wk_type, payload):
    # 'data' box: version(1)+flags(3) = well-known type, locale(4)=0, then payload
    return _atom(b'data', wk_type.to_bytes(4, 'big') + b'\x00' * 4 + payload)


def _text_item(name, text):
    if not text:
        return b''
    return _atom(name, _data_atom(1, text.encode('utf-8')))


def _build_udta(tags, cover=None):
    t = norm_tags(tags)
    ilst = bytearray()
    ilst += _text_item(b'\xa9nam', _tag(t, 'title'))
    ilst += _text_item(b'\xa9ART', _tag(t, 'artist'))
    ilst += _text_item(b'\xa9alb', _tag(t, 'album'))
    ilst += _text_item(b'\xa9lyr', _tag(t, 'lyrics'))
    ilst += _text_item(b'\xa9too', 'luna_decrypt')
    if cover:
        mime, w, h, depth, colors = image_info(cover)
        wk = 14 if mime == 'image/png' else 13
        ilst += _atom(b'covr', _data_atom(wk, cover))
    hdlr = _atom(b'hdlr', b'\x00' * 4 + b'\x00' * 4 + b'mdir' + b'appl' + b'\x00' * 9)
    meta = _atom(b'meta', b'\x00' * 4 + hdlr + _atom(b'ilst', bytes(ilst)))
    return _atom(b'udta', meta)


CONTAINERS = {b'moov', b'trak', b'mdia', b'minf', b'stbl', b'edts', b'dinf', b'udta'}
COUNT8 = {b'stsd', b'dref'}


def _boxes(b, start, end):
    p = start
    while p + 8 <= end:
        sz = int.from_bytes(b[p:p+4], 'big'); t = bytes(b[p+4:p+8])
        if sz == 1:
            sz = int.from_bytes(b[p+8:p+16], 'big'); hdr = 16
        else:
            hdr = 8
        if sz < hdr: break
        yield t, p, sz
        p += sz


def m4a_embed(mp4, tags, cover=None):
    top = list(_boxes(mp4, 0, len(mp4)))
    ftyp = moov = mdat = None
    for t, p, sz in top:
        if t == b'ftyp' and ftyp is None: ftyp = (p, sz)
        elif t == b'moov' and moov is None: moov = (p, sz)
        elif t == b'mdat' and mdat is None: mdat = (p, sz)
    if not moov or not mdat:
        return mp4
    moov_bytes = bytes(mp4[moov[0]:moov[0] + moov[1]])
    body = moov_bytes[8:]
    new_body = bytearray()
    for t, p, sz in _boxes(body, 0, len(body)):
        if t == b'udta':      # 丢掉原 udta，换成我们自己的
            continue
        new_body += body[p:p + sz]
    new_moov = _atom(b'moov', bytes(new_body) + _build_udta(tags, cover))

    out = bytearray()
    if ftyp:
        out += mp4[ftyp[0]:ftyp[0] + ftyp[1]]
    moov_start = len(out)
    out += new_moov
    moov_end = len(out)
    new_mdat = len(out)
    delta = new_mdat - mdat[0]
    out += mp4[mdat[0]:mdat[0] + mdat[1]]

    if delta:
        for name, width in ((b'stco', 4), (b'co64', 8)):
            for p, sz in walk(out, name, moov_start, moov_end):
                n = int.from_bytes(out[p+12:p+16], 'big')
                for i in range(n):
                    o = p + 16 + width * i
                    v = int.from_bytes(out[o:o+width], 'big') + delta
                    out[o:o+width] = v.to_bytes(width, 'big')
    return bytes(out)


def walk(b, name, start=0, end=None):
    if end is None: end = len(b)
    for t, p, sz in _boxes(b, start, end):
        if t == name: yield p, sz
        if t in CONTAINERS: yield from walk(b, name, p + 8, p + sz)
        elif t in COUNT8: yield from walk(b, name, p + 16, p + sz)
