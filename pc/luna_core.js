// luna_core.js — 汽水音乐 CENC/FLAC 解密 + 标签内嵌（纯 JS，移植自 luna_decrypt.py / luna_meta.py）
// 导出：decrypt(enc, key, meta) -> { data, ext, kind }   meta: {title, artist, album, lyric, cover(Buffer|url 已下载)}
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.__LUNA_CORE__ = factory();
})(typeof self !== 'undefined' ? self : globalThis, function () {
  const crypto = require('crypto');

  const CONTAINERS = ['moov', 'trak', 'mdia', 'minf', 'stbl', 'edts', 'dinf', 'udta'];
  const COUNT8 = ['stsd', 'dref'];
  const AUDIO_ENTRY = ['enca', 'mp4a', 'fLaC', 'alac', 'Opus'];
  const AUDIO_ENTRY_HDR = 28;
  const VIDEO_FOURCC = ['avc1', 'avc3', 'hvc1', 'hev1', 'mp4v', 'vp08', 'vp09', 'av01', 'encv', 'dvav', 'dvh1', 'dvhe', 'mp4s'];
  const u32 = n => { const b = Buffer.alloc(4); b.writeUInt32BE(n >>> 0); return b; };

  // ---------------- MP4 box 基础 ----------------
  function boxes(b, start, end) {
    const out = [];
    let p = start;
    while (p + 8 <= end) {
      let sz = b.readUInt32BE(p);
      const t = b.toString('latin1', p + 4, p + 8);
      let hdr = 8;
      if (sz === 1) { sz = Number(b.readBigUInt64BE(p + 8)); hdr = 16; }
      if (sz < hdr) break;
      out.push([t, p, sz]);
      p += sz;
    }
    return out;
  }
  function walk(b, name, start, end) {
    const out = [];
    for (const [t, p, sz] of boxes(b, start, end)) {
      if (t === name) out.push([p, sz]);
      if (CONTAINERS.includes(t)) out.push(...walk(b, name, p + 8, p + sz));
      else if (COUNT8.includes(t)) out.push(...walk(b, name, p + 16, p + sz));
      else if (AUDIO_ENTRY.includes(t)) out.push(...walk(b, name, p + 8 + AUDIO_ENTRY_HDR, p + sz));
    }
    return out;
  }
  function findBox(b, name, end) {
    if (end == null) end = b.length;
    let j = b.indexOf(name, 0, 'latin1');
    if (j < 0) j = b.indexOf(Buffer.from(name, 'latin1'));
    while (j > 0) {
      if (j + 4 <= end) {
        const sz = b.readUInt32BE(j - 4);
        if (sz >= 8 && sz <= end - (j - 4)) return [j, sz];
      }
      const nx = b.indexOf(Buffer.from(name, 'latin1'), j + 1);
      if (nx <= j || nx >= end) break;
      j = nx;
    }
    return [null, null];
  }
  function moovOf(b) {
    for (const [t, p, sz] of boxes(b, 0, b.length)) if (t === 'moov') return [p, sz];
    return null;
  }
  function trakList(b) {
    const m = moovOf(b);
    if (!m) return [];
    const out = [];
    for (const [t, p, sz] of boxes(b, m[0] + 8, m[0] + m[1])) {
      if (t !== 'trak') continue;
      let h = null, f = null;
      const hs = walk(b, 'hdlr', p + 8, p + sz);
      if (hs.length) h = b.toString('latin1', hs[0][0] + 16, hs[0][0] + 20);
      const ss = walk(b, 'stsd', p + 8, p + sz);
      if (ss.length) { const o = ss[0][0] + 8 + 4 + 4 + 4; f = b.toString('latin1', o, o + 4); }
      out.push([h, f]);
    }
    return out;
  }
  function hasVideo(b) {
    for (const [h, f] of trakList(b)) if (h === 'vide' || VIDEO_FOURCC.includes(f)) return true;
    return false;
  }
  function audioRange(b) {
    const m = moovOf(b);
    if (m) {
      for (const [t, p, sz] of boxes(b, m[0] + 8, m[0] + m[1])) {
        if (t !== 'trak') continue;
        const hs = walk(b, 'hdlr', p + 8, p + sz);
        if (hs.length && b.toString('latin1', hs[0][0] + 16, hs[0][0] + 20) === 'soun') return [p + 8, p + sz];
        break;
      }
    }
    return [0, b.length];
  }
  function kidOf(b) {
    const [s, e] = audioRange(b);
    const tenc = Buffer.from('tenc', 'latin1');
    let i = b.indexOf(tenc, s);
    while (i > 0 && i < e) {
      if ([32, 36].includes(b.readUInt32BE(i - 4))) return b.toString('hex', i + 12, i + 28);
      i = b.indexOf(tenc, i + 1);
    }
    return null;
  }
  function parseTrack(b) {
    const [s0, e0] = audioRange(b);
    const stbls = walk(b, 'stbl', s0, e0);
    if (!stbls.length) return [];
    const [sp, ssz] = stbls[0];
    const s = sp + 8, e = sp + ssz;
    const out = {};
    for (const [t, p, sz] of boxes(b, s, e)) (out[t] = out[t] || []).push([p, sz]);
    if (!out.stsz) return [];
    let [p] = out.stsz[0];
    const defaultSize = b.readUInt32BE(p + 12);
    const count = b.readUInt32BE(p + 16);
    let sizes;
    if (defaultSize) {
      if (count > 1000000) return [];
      sizes = new Array(count).fill(defaultSize);
    } else {
      const off = p + 20;
      sizes = [];
      for (let i = 0; i < count; i++) sizes.push(b.readUInt32BE(off + 4 * i));
    }
    let chunks;
    if (out.co64) {
      [p] = out.co64[0];
      const n = b.readUInt32BE(p + 12);
      chunks = [];
      for (let i = 0; i < n; i++) chunks.push(Number(b.readBigUInt64BE(p + 16 + 8 * i)));
    } else if (out.stco) {
      [p] = out.stco[0];
      const n = b.readUInt32BE(p + 12);
      chunks = [];
      for (let i = 0; i < n; i++) chunks.push(b.readUInt32BE(p + 16 + 4 * i));
    } else return [];
    if (!out.stsc) return [];
    [p] = out.stsc[0];
    const n = b.readUInt32BE(p + 12);
    const stsc = [];
    for (let i = 0; i < n; i++) stsc.push([b.readUInt32BE(p + 16 + 12 * i), b.readUInt32BE(p + 20 + 12 * i)]);
    if (!stsc.length) return [];
    const chunkSizes = [];
    let idx = 0;
    for (let ci = 1; ci <= chunks.length; ci++) {
      while (idx + 1 < stsc.length && stsc[idx + 1][0] <= ci) idx++;
      chunkSizes.push(stsc[idx][1]);
    }
    const table = [];
    let si = 0;
    for (let ci = 0; ci < chunkSizes.length; ci++) {
      let off = chunks[ci];
      for (let k = 0; k < chunkSizes[ci]; k++) {
        if (si >= sizes.length) break;
        table.push([off, sizes[si]]); off += sizes[si]; si++;
      }
    }
    return table;
  }
  function parseIvs(b) {
    const [s0, e0] = audioRange(b);
    const out = [];
    for (const [p, sz] of walk(b, 'senc', s0, e0)) {
      const flags = b.readUInt32BE(p + 8) & 0xffffff;
      const n = b.readUInt32BE(p + 12);
      let off = p + 16;
      for (let i = 0; i < n; i++) {
        if (off + 8 > p + sz) break;
        out.push(Buffer.from(b.subarray(off, off + 8)));
        off += 8;
        if (flags & 2) { const cnt = b.readUInt16BE(off); off += 2 + 6 * cnt; }
      }
    }
    return out;
  }
  function classify(b) {
    if (hasVideo(b)) return 'video';
    const [s0, e0] = audioRange(b);
    let r = findBox(b, Buffer.from('frma', 'latin1'), e0);
    if (r[0] != null && r[0] >= s0) {
      const f = b.toString('latin1', r[0] + 4, r[0] + 8);
      if (f === 'fLaC') return 'flac';
      if (f === 'mp4a') return 'aac';
      if (VIDEO_FOURCC.includes(f)) return 'video';
    }
    r = findBox(b, Buffer.from('frma', 'latin1'));
    if (r[0] != null) {
      const f = b.toString('latin1', r[0] + 4, r[0] + 8);
      if (f === 'fLaC') return 'flac';
      if (f === 'mp4a') return 'aac';
      if (VIDEO_FOURCC.includes(f)) return 'video';
    }
    if (b.indexOf(Buffer.from('tenc', 'latin1')) > 0) return 'aac';
    return 'plain';
  }

  // ---------------- 解密 ----------------
  function ctr(key, iv8, data) {
    const c = crypto.createDecipheriv('aes-128-ctr', key, Buffer.concat([iv8, Buffer.alloc(8)]));
    return Buffer.concat([c.update(data), c.final()]);
  }
  function decryptFlac(buf, key, table, ivs, n) {
    const r = findBox(buf, Buffer.from('dfLa', 'latin1'));
    if (r[0] == null) throw new Error('no dfLa');
    const j = r[0], sz = r[1];
    const payload = buf.subarray(j + 4, j - 4 + sz);
    const meta = payload.subarray(4);
    const blocks = [];
    let p = 0;
    while (p + 4 <= meta.length) {
      const hdr = meta[p]; const ln = (meta[p + 1] << 16) | (meta[p + 2] << 8) | meta[p + 3];
      if (p + 4 + ln > meta.length) break;
      blocks.push([hdr, Buffer.from(meta.subarray(p + 4, p + 4 + ln))]); p += 4 + ln;
    }
    if (!blocks.length) throw new Error('empty dfLa metadata');
    const parts = [Buffer.from('fLaC', 'latin1')];
    blocks.forEach(([hdr, data], i) => {
      const h = (hdr & 0x7f) | (i === blocks.length - 1 ? 0x80 : 0);
      const hb = Buffer.alloc(4); hb[0] = h; hb.writeUIntBE(data.length, 1, 3);
      parts.push(hb, data);
    });
    for (let i = 0; i < n; i++) {
      const [off, s] = table[i];
      parts.push(ctr(key, ivs[i], Buffer.from(buf.subarray(off, off + s))));
    }
    return Buffer.concat(parts);
  }
  function decryptAac(buf, key, table, ivs, n) {
    const b = Buffer.from(buf);
    for (let i = 0; i < n; i++) {
      const [off, s] = table[i];
      ctr(key, ivs[i], Buffer.from(b.subarray(off, off + s))).copy(b, off);
    }
    let moovEnd = b.length;
    for (const [t, p] of boxes(b, 0, b.length)) if (t === 'mdat') { moovEnd = p; break; }
    for (const [tag, repl] of [['enca', 'mp4a'], ['sinf', 'free'], ['senc', 'free'], ['saio', 'free'], ['saiz', 'free']]) {
      const tb = Buffer.from(tag, 'latin1'), rb = Buffer.from(repl, 'latin1');
      let j = b.indexOf(tb, 0);
      while (j >= 0 && j < moovEnd) { rb.copy(b, j); j = b.indexOf(tb, j + 4); }
    }
    return b;
  }

  // ---------------- 标签 ----------------
  function normTags(tags) {
    const out = {};
    for (const [k, v] of Object.entries(tags || {})) {
      if (v == null) continue;
      if (String(v)) out[String(k).toUpperCase()] = String(v);
    }
    return out;
  }
  function imageInfo(d) {
    if (d.length > 2 && d[0] === 0xff && d[1] === 0xd8) {
      let i = 2;
      while (i + 9 < d.length) {
        if (d[i] !== 0xff) { i++; continue; }
        const mk = d[i + 1];
        if (mk === 0xd8 || mk === 0x01 || (mk >= 0xd0 && mk <= 0xd7)) { i += 2; continue; }
        if (i + 4 > d.length) break;
        const ln = d.readUInt16BE(i + 2);
        const SOF = [0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf];
        if (SOF.includes(mk)) return ['image/jpeg', d.readUInt16BE(i + 7), d.readUInt16BE(i + 5), 24, 0];
        i += 2 + ln;
      }
      return ['image/jpeg', 0, 0, 24, 0];
    }
    if (d.length > 24 && d[0] === 0x89 && d.toString('latin1', 1, 4) === 'PNG') {
      const w = d.readUInt32BE(16), h = d.readUInt32BE(20), bd = d[24], ct = d[25];
      return ['image/png', w, h, bd * (ct === 6 ? 4 : 3), 0];
    }
    return ['application/octet-stream', 0, 0, 24, 0];
  }
  const u32le = n => { const b = Buffer.alloc(4); b.writeUInt32LE(n >>> 0); return b; };
  function vorbisComment(tags) {
    const vendor = Buffer.from('luna_decrypt', 'latin1');
    const parts = [u32le(vendor.length), vendor];
    const items = [];
    for (const [k, v] of Object.entries(tags)) if (v) items.push(Buffer.from(k + '=' + v, 'utf8'));
    parts.push(u32le(items.length));
    for (const it of items) parts.push(u32le(it.length), it);
    return Buffer.concat(parts);
  }
  function flacPicture(cover) {
    const [mime, w, h, depth, colors] = imageInfo(cover);
    return Buffer.concat([
      u32(3), u32(mime.length), Buffer.from(mime, 'latin1'), u32(0),
      u32(w), u32(h), u32(depth), u32(colors), u32(cover.length), cover,
    ]);
  }
  function metaBlock(btype, payload, last) {
    const hb = Buffer.alloc(4);
    hb[0] = (last ? 0x80 : 0) | btype;
    hb.writeUIntBE(payload.length, 1, 3);
    return Buffer.concat([hb, payload]);
  }
  function flacEmbed(flac, tags, cover) {
    if (flac.toString('latin1', 0, 4) !== 'fLaC') return flac;
    tags = normTags(tags);
    let p = 4;
    const blocks = [];
    while (p + 4 <= flac.length) {
      const hdr = flac[p]; const ln = (flac[p + 1] << 16) | (flac[p + 2] << 8) | flac[p + 3];
      if (p + 4 + ln > flac.length) break;
      blocks.push([hdr & 0x7f, Buffer.from(flac.subarray(p + 4, p + 4 + ln))]);
      p += 4 + ln;
      if (hdr & 0x80) break;
    }
    const frames = flac.subarray(p);
    const keep = blocks.filter(([bt]) => bt !== 4 && bt !== 6);
    keep.push([4, vorbisComment(tags)]);
    if (cover) keep.push([6, flacPicture(cover)]);
    const parts = [Buffer.from('fLaC', 'latin1')];
    keep.forEach(([bt, payload], i) => parts.push(metaBlock(bt, payload, i === keep.length - 1)));
    parts.push(frames);
    return Buffer.concat(parts);
  }
  function atom(t, payload) { return Buffer.concat([u32(8 + payload.length), Buffer.from(t, 'latin1'), payload]); }
  function dataAtom(wk, payload) { return atom('data', Buffer.concat([u32(wk), Buffer.alloc(4), payload])); }
  function textItem(name, text) {
    if (!text) return Buffer.alloc(0);
    return atom(name, dataAtom(1, Buffer.from(text, 'utf8')));
  }
  function buildUdta(tags, cover) {
    const t = normTags(tags);
    const g = k => t[k.toUpperCase()] || '';
    const parts = [
      textItem('\xa9nam', g('title')), textItem('\xa9ART', g('artist')),
      textItem('\xa9alb', g('album')), textItem('\xa9lyr', g('lyrics')),
      textItem('\xa9too', 'luna_export'),
    ];
    if (cover) {
      const [mime] = imageInfo(cover);
      parts.push(atom('covr', dataAtom(mime === 'image/png' ? 14 : 13, cover)));
    }
    const hdlr = atom('hdlr', Buffer.concat([Buffer.alloc(8), Buffer.from('mdir', 'latin1'), Buffer.from('appl', 'latin1'), Buffer.alloc(9)]));
    const meta = atom('meta', Buffer.concat([Buffer.alloc(4), hdlr, atom('ilst', Buffer.concat(parts))]));
    return atom('udta', meta);
  }
  function m4aEmbed(mp4, tags, cover) {
    const top = boxes(mp4, 0, mp4.length);
    let ftyp = null, moov = null, mdat = null;
    for (const [t, p, sz] of top) {
      if (t === 'ftyp' && !ftyp) ftyp = [p, sz];
      else if (t === 'moov' && !moov) moov = [p, sz];
      else if (t === 'mdat' && !mdat) mdat = [p, sz];
    }
    if (!moov || !mdat) return mp4;
    const moovBytes = mp4.subarray(moov[0], moov[0] + moov[1]);
    const body = moovBytes.subarray(8);
    const keep = [];
    for (const [t, p, sz] of boxes(body, 0, body.length)) if (t !== 'udta') keep.push(body.subarray(p, p + sz));
    const newMoov = atom('moov', Buffer.concat([...keep, buildUdta(tags, cover)]));
    const out = [];
    if (ftyp) out.push(mp4.subarray(ftyp[0], ftyp[0] + ftyp[1]));
    const moovStart = out.reduce((a, b) => a + b.length, 0);
    out.push(newMoov);
    const moovEnd = moovStart + newMoov.length;
    const newMdat = moovEnd;
    const delta = newMdat - mdat[0];
    out.push(mp4.subarray(mdat[0], mdat[0] + mdat[1]));
    const buf = Buffer.concat(out);
    if (delta) {
      for (const [name, width] of [['stco', 4], ['co64', 8]]) {
        for (const [p] of walk(buf, name, moovStart, moovEnd)) {
          const n = buf.readUInt32BE(p + 12);
          for (let i = 0; i < n; i++) {
            const o = p + 16 + width * i;
            if (width === 4) buf.writeUInt32BE(buf.readUInt32BE(o) + delta, o);
            else buf.writeBigUInt64BE(buf.readBigUInt64BE(o) + BigInt(delta), o);
          }
        }
      }
    }
    return buf;
  }

  // ---------------- 歌词 ----------------
  // 双重编码修复（UTF-8 被当 latin-1 解释的乱码）
  function fixMojibake(s) {
    if (!s || typeof s !== 'string') return s;
    try {
      const dec = Buffer.from(s, 'latin1').toString('utf8');
      if (!dec.includes('\ufffd')) return dec;
    } catch (e) {}
    return s;
  }
  // KRC -> LRC:  [startMs,durMs]<off,dur,0>字<off,dur,0>字...  =>  [mm:ss.xx]字字...
  function krcToLrc(krc) {
    const out = [];
    for (const raw of String(krc || '').replace(/\r\n/g, '\n').split('\n')) {
      const s = raw.trim();
      if (!s) continue;
      const m = s.match(/^\[(\d+),(\d+)\](.*)$/);
      if (!m) { if (s.startsWith('[')) out.push(s); continue; }
      const start = parseInt(m[1], 10);
      const body = m[3];
      let text = body.includes('<')
        ? (body.match(/>([^<]*)/g) || []).map(x => x.slice(1)).join('')
        : body;
      text = text.replace(/\u200b/g, '').trim();
      if (text) {
        const mm = String(Math.floor(start / 60000)).padStart(2, '0');
        const ss = (start % 60000 / 1000).toFixed(2).padStart(5, '0');
        out.push(`[${mm}:${ss}]${text}`);
      }
    }
    return out.join('\n');
  }

  // ---------------- 顶层 API ----------------
  function decrypt(enc, keyHex, meta) {
    const b = Buffer.from(enc);
    const kind = classify(b);
    const table = parseTrack(b);
    const ivs = parseIvs(b);
    const n = Math.min(table.length, ivs.length);
    const key = Buffer.from(keyHex, 'hex');
    if (key.length !== 16) throw new Error('bad key');
    if (!table.length || n === 0) throw new Error('no samples/ivs');
    let data, ext;
    if (kind === 'flac') { data = decryptFlac(b, key, table, ivs, n); ext = '.flac'; }
    else { data = decryptAac(b, key, table, ivs, n); ext = '.m4a'; }
    const tags = {
      TITLE: meta.title || '', ARTIST: meta.artist || '',
      ALBUM: meta.album || '', LYRICS: krcToLrc(fixMojibake(meta.lyric || '')),
    };
    data = ext === '.flac' ? flacEmbed(data, tags, meta.coverBuf) : m4aEmbed(data, tags, meta.coverBuf);
    return { data, ext, kind, samples: n };
  }

  return { decrypt, classify, parseTrack, parseIvs, flacEmbed, m4aEmbed, imageInfo, kidOf };
});
