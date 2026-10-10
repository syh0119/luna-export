package com.syh.luna.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * CENC(AES-128-CTR) 解析与解密 —— 逐字节等价于 Python luna_decrypt.py。
 */
public final class Cenc {

    private Cenc() { }

    public static final class Sample {
        public final long off;
        public final int size;
        Sample(long o, int s) { off = o; size = s; }
    }

    static int u24(byte[] b, int o) {
        if (o + 3 > b.length) return 0;
        return ((b[o] & 0xFF) << 16) | ((b[o + 1] & 0xFF) << 8) | (b[o + 2] & 0xFF);
    }

    static byte[] slice(byte[] b, int from, int to) {
        from = Math.max(0, from);
        to = Math.min(to, b.length);
        if (to < from) to = from;
        byte[] r = new byte[to - from];
        System.arraycopy(b, from, r, 0, to - from);
        return r;
    }

    public static String classify(byte[] b) {
        if (Mp4.hasVideo(b)) return "video";
        int[] r = Mp4.audioRange(b);
        int[] fb = Mp4.findBox(b, Mp4.b4("frma"), r[1]);
        if (fb != null && fb[0] >= r[0]) {
            String f = new String(b, fb[0] + 4, 4, StandardCharsets.ISO_8859_1);
            if ("fLaC".equals(f)) return "flac";
            if ("mp4a".equals(f)) return "aac";
            if (Mp4.VIDEO.contains(f)) return "video";
        }
        fb = Mp4.findBox(b, Mp4.b4("frma"));
        if (fb != null) {
            String f = new String(b, fb[0] + 4, 4, StandardCharsets.ISO_8859_1);
            if ("fLaC".equals(f)) return "flac";
            if ("mp4a".equals(f)) return "aac";
            if (Mp4.VIDEO.contains(f)) return "video";
        }
        if (Mp4.indexOf(b, Mp4.b4("tenc"), 0) > 0) return "aac";
        return "plain";
    }

    // ------------------------------------------------------------------ 样本表
    public static List<Sample> parseTrack(byte[] b) {
        int[] r = Mp4.audioRange(b);
        List<Mp4.Hit> stbl = Mp4.walk(b, Mp4.b4("stbl"), r[0], r[1]);
        if (stbl.isEmpty()) return new ArrayList<Sample>();
        Mp4.Hit st = stbl.get(0);
        Map<String, List<Mp4.Box>> out = new LinkedHashMap<String, List<Mp4.Box>>();
        for (Mp4.Box bx : Mp4.boxes(b, st.pos + 8, st.pos + st.size)) {
            List<Mp4.Box> l = out.get(bx.typeStr());
            if (l == null) { l = new ArrayList<Mp4.Box>(); out.put(bx.typeStr(), l); }
            l.add(bx);
        }
        if (!out.containsKey("stsz")) return new ArrayList<Sample>();
        Mp4.Box stsz = out.get("stsz").get(0);
        long defaultSize = Mp4.u32(b, stsz.pos + 12);
        long count = Mp4.u32(b, stsz.pos + 16);
        List<Integer> sizes = new ArrayList<Integer>();
        if (defaultSize != 0) {
            if (count > 1000000L) return new ArrayList<Sample>();
            for (long i = 0; i < count; i++) sizes.add((int) defaultSize);
        } else {
            int off = stsz.pos + 20;
            for (long i = 0; i < count; i++) sizes.add((int) Mp4.u32(b, off + (int) (4 * i)));
        }

        List<Long> chunks = new ArrayList<Long>();
        if (out.containsKey("co64")) {
            Mp4.Box c = out.get("co64").get(0);
            long n = Mp4.u32(b, c.pos + 12);
            for (long i = 0; i < n; i++) chunks.add(Mp4.u64(b, c.pos + 16 + (int) (8 * i)));
        } else if (out.containsKey("stco")) {
            Mp4.Box c = out.get("stco").get(0);
            long n = Mp4.u32(b, c.pos + 12);
            for (long i = 0; i < n; i++) chunks.add(Mp4.u32(b, c.pos + 16 + (int) (4 * i)));
        } else {
            return new ArrayList<Sample>();
        }

        if (!out.containsKey("stsc")) return new ArrayList<Sample>();
        Mp4.Box stsc = out.get("stsc").get(0);
        long n = Mp4.u32(b, stsc.pos + 12);
        List<int[]> stscList = new ArrayList<int[]>();
        for (long i = 0; i < n; i++) {
            stscList.add(new int[]{
                    (int) Mp4.u32(b, stsc.pos + 16 + (int) (12 * i)),
                    (int) Mp4.u32(b, stsc.pos + 20 + (int) (12 * i))});
        }
        if (stscList.isEmpty()) return new ArrayList<Sample>();

        List<Integer> chunkSizes = new ArrayList<Integer>();
        int idx = 0;
        for (int ci = 1; ci <= chunks.size(); ci++) {
            while (idx + 1 < stscList.size() && stscList.get(idx + 1)[0] <= ci) idx++;
            chunkSizes.add(stscList.get(idx)[1]);
        }

        List<Sample> table = new ArrayList<Sample>();
        int si = 0;
        for (int ci = 0; ci < chunkSizes.size(); ci++) {
            long off = chunks.get(ci);
            int spc = chunkSizes.get(ci);
            for (int k = 0; k < spc; k++) {
                if (si >= sizes.size()) break;
                table.add(new Sample(off, sizes.get(si)));
                off += sizes.get(si);
                si++;
            }
        }
        return table;
    }

    public static List<byte[]> parseIvs(byte[] b) {
        int[] r = Mp4.audioRange(b);
        List<byte[]> out = new ArrayList<byte[]>();
        for (Mp4.Hit h : Mp4.walk(b, Mp4.b4("senc"), r[0], r[1])) {
            int flags = u24(b, h.pos + 9);
            long n = Mp4.u32(b, h.pos + 12);
            int off = h.pos + 16;
            for (long i = 0; i < n; i++) {
                if (off + 8 > h.pos + h.size) break;
                out.add(slice(b, off, off + 8));
                off += 8;
                if ((flags & 2) != 0) {
                    int cnt = Mp4.u16(b, off);
                    off += 2 + 6 * cnt;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ crypto
    public static byte[] ctr(byte[] key, byte[] iv8, byte[] data) {
        try {
            byte[] iv = new byte[16];
            System.arraycopy(iv8, 0, iv, 0, 8);
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("AES-CTR failed: " + e, e);
        }
    }

    /** 返回解密后的 .flac 完整字节 */
    public static byte[] decryptFlac(byte[] buf, byte[] key, List<Sample> table, List<byte[]> ivs, int n) {
        int[] r = Mp4.findBox(buf, Mp4.b4("dfLa"));
        if (r == null) throw new IllegalStateException("no dfLa");
        byte[] payload = slice(buf, r[0] + 4, r[0] - 4 + r[1]);
        byte[] meta = slice(payload, 4, payload.length);
        List<byte[]> blkHdr = new ArrayList<byte[]>();
        List<byte[]> blkData = new ArrayList<byte[]>();
        int p = 0;
        while (p + 4 <= meta.length) {
            int hdr = meta[p] & 0xFF;
            int ln = ((meta[p + 1] & 0xFF) << 16) | ((meta[p + 2] & 0xFF) << 8) | (meta[p + 3] & 0xFF);
            if (p + 4 + ln > meta.length) break;
            blkHdr.add(new byte[]{(byte) hdr});
            blkData.add(slice(meta, p + 4, p + 4 + ln));
            p += 4 + ln;
        }
        if (blkHdr.isEmpty()) throw new IllegalStateException("empty dfLa metadata");

        // 直接按输入大小预留：ByteBuf 是 1.5 倍增长，47 MB 的曲目在扩容瞬间
        // 会同时持有「旧数组 + 新数组」（约 67 MB + 47 MB）。预留后全程只分配一次。
        ByteBuf out = new ByteBuf(buf.length + 65536);
        out.put(Mp4.b4("fLaC"));
        for (int i = 0; i < blkHdr.size(); i++) {
            int h = (blkHdr.get(i)[0] & 0x7f) | (i == blkHdr.size() - 1 ? 0x80 : 0);
            byte[] d = blkData.get(i);
            out.put((byte) h);
            out.put((byte) ((d.length >> 16) & 0xFF));
            out.put((byte) ((d.length >> 8) & 0xFF));
            out.put((byte) (d.length & 0xFF));
            out.put(d);
        }
        for (int i = 0; i < n; i++) {
            Sample s = table.get(i);
            byte[] enc = slice(buf, (int) s.off, (int) s.off + s.size);
            out.put(ctr(key, ivs.get(i), enc));
        }
        return out.toArray();
    }

    /** 返回解密并改名后的完整 mp4 字节 */
    public static byte[] decryptAac(byte[] buf, byte[] key, List<Sample> table, List<byte[]> ivs, int n) {
        for (int i = 0; i < n; i++) {
            Sample s = table.get(i);
            byte[] enc = slice(buf, (int) s.off, (int) s.off + s.size);
            byte[] dec = ctr(key, ivs.get(i), enc);
            System.arraycopy(dec, 0, buf, (int) s.off, s.size);
        }
        int moovEnd = buf.length;
        for (Mp4.Box bx : Mp4.boxes(buf, 0, buf.length)) {
            if (bx.is(Mp4.b4("mdat"))) { moovEnd = bx.pos; break; }
        }
        String[][] repl = {
                {"enca", "mp4a"}, {"sinf", "free"}, {"senc", "free"},
                {"saio", "free"}, {"saiz", "free"}};
        for (String[] pr : repl) {
            byte[] tag = Mp4.b4(pr[0]);
            byte[] to = Mp4.b4(pr[1]);
            int j = Mp4.indexOf(buf, tag, 0, moovEnd);
            while (j >= 0) {
                System.arraycopy(to, 0, buf, j, 4);
                j = Mp4.indexOf(buf, tag, j + 4, moovEnd);
            }
        }
        return buf;
    }

    /** 简单可增长字节缓冲 */
    public static final class ByteBuf {
        private byte[] a;
        private int n;

        public ByteBuf(int cap) { a = new byte[cap]; }

        private void ensure(int extra) {
            if (n + extra <= a.length) return;
            int cap = a.length;
            while (cap < n + extra) cap = cap + (cap >> 1) + 1024;
            a = Arrays.copyOf(a, cap);
        }

        public void put(byte b) { ensure(1); a[n++] = b; }

        public void put(byte[] b) { ensure(b.length); System.arraycopy(b, 0, a, n, b.length); n += b.length; }

        /** 正好装满时直接交出底层数组，省掉一次整份拷贝（几十 MB 级别的差别） */
        public byte[] toArray() { return n == a.length ? a : Arrays.copyOf(a, n); }

        public int size() { return n; }
    }
}
