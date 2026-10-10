package com.syh.luna.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标签 / 封面 / 歌词写入 —— 逐字节等价于 Python luna_meta.py。
 *
 * FLAC: 追加 VORBIS_COMMENT(type 4, 全部长度小端) + PICTURE(type 6, 大端)
 * M4A : 重写 moov 挂 udta/meta/ilst，并按 mdat 位移修正 stco/co64 绝对偏移
 */
public final class Tags {

    private Tags() { }

    public static final String TOOL = "luna_decrypt";

    // ================================================================ 通用
    /** Python str.strip() 的近似：Unicode 空白 */
    public static String pyStrip(String s) {
        if (s == null) return "";
        int a = 0, b = s.length();
        while (a < b && isPySpace(s.charAt(a))) a++;
        while (b > a && isPySpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    public static boolean isPySpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Python re 的 \s+ -> 单个空格 */
    public static String collapseWs(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean prev = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isPySpace(c)) {
                if (!prev) sb.append(' ');
                prev = true;
            } else {
                sb.append(c);
                prev = false;
            }
        }
        return sb.toString();
    }

    private static final String BAD = "\\/:*?\"<>|\r\n\t";

    public static String safe(String name, String fallback) {
        name = fixMojibake(pyStrip(name));
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(BAD.indexOf(c) >= 0 ? '_' : c);
        }
        String s = collapseWs(sb.toString());
        if (s.length() > 120) s = s.substring(0, 120);   // 注意：不裁断代理对
        return s.isEmpty() ? fallback : s;
    }

    /** 修复「UTF-8 被按 latin-1 读」的乱码；失败原样返回 */
    public static String fixMojibake(String s) {
        if (s == null || s.isEmpty()) return s;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 0xFF) return s;            // python 会抛 UnicodeEncodeError
        }
        try {
            byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
            CharsetDecoder d = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return d.decode(ByteBuffer.wrap(b)).toString();
        } catch (Exception e) {
            return s;
        }
    }

    // ================================================================ 歌词
    // python 的 `^(...)(.*)$` 里 `.` 只排除 \n；
    // Java 的 `.` 还会排除 \r \u0085 \u2028 \u2029 → 用 [^\n] 精确对齐。
    private static final Pattern KRC = Pattern.compile("\\[([0-9]+),([0-9]+)\\]([^\\n]*)");
    private static final Pattern KRC_WORD = Pattern.compile(">([^<]*)");

    public static String krcToLrc(String krc) {
        if (krc == null || krc.isEmpty()) return "";
        List<String> lines = new ArrayList<String>();
        for (String raw : krc.replace("\r\n", "\n").split("\n", -1)) {
            String s = pyStrip(raw);
            if (s.isEmpty()) continue;
            Matcher m = KRC.matcher(s);
            if (!m.find() || m.start() != 0) {          // 等价 python 的 re.match
                if (s.startsWith("[")) lines.add(s);
                continue;
            }
            long start = Long.parseLong(m.group(1));
            String body = m.group(3);
            String text;
            if (body.indexOf('<') >= 0) {
                StringBuilder sb = new StringBuilder();
                Matcher w = KRC_WORD.matcher(body);
                while (w.find()) sb.append(w.group(1));
                text = sb.toString();
            } else {
                text = body;
            }
            text = pyStrip(text.replace("\u200b", ""));
            if (!text.isEmpty()) {
                lines.add(fmtTs(start) + text);
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append('\n');
            out.append(lines.get(i));
        }
        return out.toString();
    }

    /**
     * 对齐 python 的 '%02d:%05.2f'。
     * CPython 对 float 是「按二进制精确值正确舍入(half-even)」；
     * Java 的 String.format 会先取最短十进制再 HALF_UP，25.395 之类会差 1 个厘秒。
     */
    static String fmtTs(long start) {
        long mm = start / 60000;
        java.math.BigDecimal bd = new java.math.BigDecimal((start % 60000) / 1000.0)
                .setScale(2, java.math.RoundingMode.HALF_EVEN);
        String frac = bd.toPlainString();
        while (frac.length() < 5) frac = "0" + frac;
        return String.format(Locale.US, "[%02d:%s]", mm, frac);
    }

    // ================================================================ 标签归一
    public static LinkedHashMap<String, String> normTags(Map<String, String> tags) {
        LinkedHashMap<String, String> out = new LinkedHashMap<String, String>();
        if (tags == null) return out;
        for (Map.Entry<String, String> e : tags.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            out.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue());
        }
        return out;
    }

    private static String tag(Map<String, String> t, String name, String def) {
        String v = t.get(name.toUpperCase(Locale.ROOT));
        return (v == null || v.isEmpty()) ? def : v;
    }

    // ================================================================ 图片信息
    /** {mime, w, h, depth, colors} */
    public static Object[] imageInfo(byte[] d) {
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            int i = 2;
            while (i + 9 < d.length) {
                if ((d[i] & 0xFF) != 0xFF) { i++; continue; }
                int mk = d[i + 1] & 0xFF;
                if (mk == 0xD8 || mk == 0x01 || (mk >= 0xD0 && mk <= 0xD7)) { i += 2; continue; }
                if (i + 4 > d.length) break;
                int ln = ((d[i + 2] & 0xFF) << 8) | (d[i + 3] & 0xFF);
                if (mk == 0xC0 || mk == 0xC1 || mk == 0xC2 || mk == 0xC3 || mk == 0xC5
                        || mk == 0xC6 || mk == 0xC7 || mk == 0xC9 || mk == 0xCA || mk == 0xCB
                        || mk == 0xCD || mk == 0xCE || mk == 0xCF) {
                    int h = ((d[i + 5] & 0xFF) << 8) | (d[i + 6] & 0xFF);
                    int w = ((d[i + 7] & 0xFF) << 8) | (d[i + 8] & 0xFF);
                    return new Object[]{"image/jpeg", w, h, 24, 0};
                }
                i += 2 + ln;
            }
            return new Object[]{"image/jpeg", 0, 0, 24, 0};
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (d.length >= 8 && java.util.Arrays.equals(slice(d, 0, 8), png)) {
            long w = Mp4.u32(d, 16), h = Mp4.u32(d, 20);
            int bd = Mp4.u8(d, 24), ct = Mp4.u8(d, 25);
            return new Object[]{"image/png", (int) w, (int) h, bd * (ct == 6 ? 4 : 3), 0};
        }
        return new Object[]{"application/octet-stream", 0, 0, 24, 0};
    }

    private static byte[] slice(byte[] b, int from, int to) {
        from = Math.max(0, from); to = Math.min(to, b.length);
        if (to < from) to = from;
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, to - from);
        return r;
    }

    private static byte[] u32be(long n) {
        return new byte[]{(byte) (n >> 24), (byte) (n >> 16), (byte) (n >> 8), (byte) n};
    }

    private static byte[] u32le(long n) {
        return new byte[]{(byte) n, (byte) (n >> 8), (byte) (n >> 16), (byte) (n >> 24)};
    }

    // ================================================================ FLAC
    private static byte[] metaBlock(int btype, byte[] payload, boolean last) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(payload.length + 4);
        o.write((last ? 0x80 : 0) | btype);
        o.write((payload.length >> 16) & 0xFF);
        o.write((payload.length >> 8) & 0xFF);
        o.write(payload.length & 0xFF);
        o.write(payload, 0, payload.length);
        return o.toByteArray();
    }

    /** VORBIS_COMMENT：vendor 长度 / 条目数 / 每项长度 全部小端 */
    static byte[] vorbisComment(Map<String, String> tags) {
        byte[] vendor = TOOL.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream o = new ByteArrayOutputStream(256);
        try {
            o.write(u32le(vendor.length));
            o.write(vendor);
            List<byte[]> items = new ArrayList<byte[]>();
            for (Map.Entry<String, String> e : tags.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty()) {
                    items.add((e.getKey() + "=" + e.getValue()).getBytes(StandardCharsets.UTF_8));
                }
            }
            o.write(u32le(items.size()));
            for (byte[] it : items) {
                o.write(u32le(it.length));
                o.write(it);
            }
        } catch (Exception ignore) { }
        return o.toByteArray();
    }

    static byte[] flacPicture(byte[] cover) {
        Object[] ii = imageInfo(cover);
        String mime = (String) ii[0];
        ByteArrayOutputStream o = new ByteArrayOutputStream(cover.length + 64);
        try {
            o.write(u32be(3));                                   // front cover
            byte[] mb = mime.getBytes(StandardCharsets.US_ASCII);
            o.write(u32be(mb.length)); o.write(mb);
            o.write(u32be(0));                                   // description
            o.write(u32be((Integer) ii[1]));
            o.write(u32be((Integer) ii[2]));
            o.write(u32be((Integer) ii[3]));
            o.write(u32be((Integer) ii[4]));
            o.write(u32be(cover.length)); o.write(cover);
        } catch (Exception ignore) { }
        return o.toByteArray();
    }

    public static byte[] flacEmbed(byte[] flac, Map<String, String> tagsIn, byte[] cover) {
        if (flac.length < 4 || flac[0] != 'f' || flac[1] != 'L'
                || flac[2] != 'a' || flac[3] != 'C') return flac;
        Map<String, String> tags = normTags(tagsIn);
        int p = 4;
        List<int[]> meta = new ArrayList<int[]>();     // {type, size}
        List<byte[]> payload = new ArrayList<byte[]>();
        while (p + 4 <= flac.length) {
            int hdr = flac[p] & 0xFF;
            int ln = ((flac[p + 1] & 0xFF) << 16) | ((flac[p + 2] & 0xFF) << 8) | (flac[p + 3] & 0xFF);
            if (p + 4 + ln > flac.length) break;
            meta.add(new int[]{hdr & 0x7f, ln});
            payload.add(slice(flac, p + 4, p + 4 + ln));
            p += 4 + ln;
            if ((hdr & 0x80) != 0) break;
        }
        byte[] frames = slice(flac, p, flac.length);

        List<byte[]> blocks = new ArrayList<byte[]>();
        List<Integer> types = new ArrayList<Integer>();
        for (int i = 0; i < meta.size(); i++) {
            int t = meta.get(i)[0];
            if (t == 4 || t == 6) continue;
            types.add(t); blocks.add(payload.get(i));
        }
        types.add(4); blocks.add(vorbisComment(tags));
        if (cover != null && cover.length > 0) {
            types.add(6); blocks.add(flacPicture(cover));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(flac.length + 65536);
        try {
            out.write(new byte[]{'f', 'L', 'a', 'C'});
            for (int i = 0; i < blocks.size(); i++) {
                out.write(metaBlock(types.get(i), blocks.get(i), i == blocks.size() - 1));
            }
            out.write(frames);
        } catch (Exception ignore) { }
        return out.toByteArray();
    }

    // ================================================================ M4A
    private static byte[] atom(String t, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(payload.length + 8);
        try {
            o.write(u32be(8 + payload.length));
            o.write(t.getBytes(StandardCharsets.ISO_8859_1));
            o.write(payload);
        } catch (Exception ignore) { }
        return o.toByteArray();
    }

    private static byte[] dataAtom(int wkType, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(payload.length + 16);
        try {
            o.write(u32be(wkType));
            o.write(new byte[4]);
            o.write(payload);
        } catch (Exception ignore) { }
        return atom("data", o.toByteArray());
    }

    private static byte[] textItem(String name, String text) {
        if (text == null || text.isEmpty()) return new byte[0];
        return atom(name, dataAtom(1, text.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] buildUdta(Map<String, String> tagsIn, byte[] cover) {
        Map<String, String> t = normTags(tagsIn);
        ByteArrayOutputStream ilst = new ByteArrayOutputStream(1024);
        try {
            ilst.write(textItem("\u00a9nam", tag(t, "title", null)));
            ilst.write(textItem("\u00a9ART", tag(t, "artist", null)));
            ilst.write(textItem("\u00a9alb", tag(t, "album", null)));
            ilst.write(textItem("\u00a9lyr", tag(t, "lyrics", null)));
            ilst.write(textItem("\u00a9too", TOOL));
            if (cover != null && cover.length > 0) {
                Object[] ii = imageInfo(cover);
                int wk = "image/png".equals(ii[0]) ? 14 : 13;
                ilst.write(atom("covr", dataAtom(wk, cover)));
            }
        } catch (Exception ignore) { }

        ByteArrayOutputStream hb = new ByteArrayOutputStream();
        try {
            hb.write(new byte[4]);
            hb.write(new byte[4]);
            hb.write("mdir".getBytes(StandardCharsets.ISO_8859_1));
            hb.write("appl".getBytes(StandardCharsets.ISO_8859_1));
            hb.write(new byte[9]);
        } catch (Exception ignore) { }

        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        try {
            meta.write(new byte[4]);
            meta.write(atom("hdlr", hb.toByteArray()));
            meta.write(atom("ilst", ilst.toByteArray()));
        } catch (Exception ignore) { }
        return atom("udta", atom("meta", meta.toByteArray()));
    }

    public static byte[] m4aEmbed(byte[] mp4, Map<String, String> tags, byte[] cover) {
        List<Mp4.Box> top = Mp4.boxes(mp4, 0, mp4.length);
        int[] ftyp = null, moov = null, mdat = null;
        for (Mp4.Box bx : top) {
            String t = bx.typeStr();
            if ("ftyp".equals(t) && ftyp == null) ftyp = new int[]{bx.pos, bx.size};
            else if ("moov".equals(t) && moov == null) moov = new int[]{bx.pos, bx.size};
            else if ("mdat".equals(t) && mdat == null) mdat = new int[]{bx.pos, bx.size};
        }
        if (moov == null || mdat == null) return mp4;

        byte[] moovBytes = slice(mp4, moov[0], moov[0] + moov[1]);
        byte[] body = slice(moovBytes, 8, moovBytes.length);
        ByteArrayOutputStream newBody = new ByteArrayOutputStream(body.length + 4096);
        try {
            for (Mp4.Box bx : Mp4.boxes(body, 0, body.length)) {
                if ("udta".equals(bx.typeStr())) continue;
                newBody.write(body, bx.pos, bx.size);
            }
        } catch (Exception ignore) { }

        ByteArrayOutputStream nb = new ByteArrayOutputStream();
        try {
            nb.write(newBody.toByteArray());
            nb.write(buildUdta(tags, cover));
        } catch (Exception ignore) { }
        byte[] newMoov = atom("moov", nb.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream(mp4.length + newMoov.length);
        try {
            if (ftyp != null) out.write(mp4, ftyp[0], ftyp[1]);
        } catch (Exception ignore) { }
        byte[] cur = out.toByteArray();
        int moovStart = cur.length;
        try {
            out.write(newMoov);
        } catch (Exception ignore) { }
        cur = out.toByteArray();
        int moovEnd = cur.length;
        int newMdat = cur.length;
        int delta = newMdat - mdat[0];
        try {
            out.write(mp4, mdat[0], mdat[1]);
        } catch (Exception ignore) { }

        byte[] buf = out.toByteArray();
        if (delta != 0) {
            String[][] names = {{"stco", "4"}, {"co64", "8"}};
            for (String[] nw : names) {
                int width = Integer.parseInt(nw[1]);
                for (Mp4.Hit h : Mp4.walkTag(buf, Mp4.b4(nw[0]), moovStart, moovEnd)) {
                    long n = Mp4.u32(buf, h.pos + 12);
                    for (long i = 0; i < n; i++) {
                        int o = h.pos + 16 + (int) (width * i);
                        if (o + width > buf.length) break;
                        long v;
                        if (width == 4) v = Mp4.u32(buf, o) + delta;
                        else v = Mp4.u64(buf, o) + delta;
                        for (int k = 0; k < width; k++) {
                            buf[o + k] = (byte) (v >> (8 * (width - 1 - k)));
                        }
                    }
                }
            }
        }
        return buf;
    }
}
