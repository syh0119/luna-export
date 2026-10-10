package com.syh.luna.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MP4 box 解析 —— 逐字节等价于 Python luna_decrypt.py 的 boxes/walk/find_box/trak 定位。
 */
public final class Mp4 {

    private Mp4() { }

    public static final class Box {
        public final byte[] type;
        public final int pos;
        public final int size;
        Box(byte[] t, int p, int s) { type = t; pos = p; size = s; }
        public String typeStr() { return new String(type, java.nio.charset.StandardCharsets.ISO_8859_1); }
        public boolean is(byte[] t) { return Arrays.equals(type, t); }
    }

    static final Set<String> CONTAINERS = new HashSet<String>(Arrays.asList(
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta"));
    static final Set<String> COUNT8 = new HashSet<String>(Arrays.asList("stsd", "dref"));
    static final Set<String> AUDIO_ENTRY = new HashSet<String>(Arrays.asList(
            "enca", "mp4a", "fLaC", "alac", "Opus"));
    static final int AUDIO_ENTRY_HDR = 28;

    public static final byte[] VIDEO_FOURCC_LIST = null; // 仅占位，见 VIDEO
    public static final Set<String> VIDEO = new HashSet<String>(Arrays.asList(
            "avc1", "avc3", "hvc1", "hev1", "mp4v", "vp08", "vp09",
            "av01", "encv", "dvav", "dvh1", "dvhe", "mp4s"));

    public static byte[] b4(String s) {
        return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    // ------------------------------------------------------------------ 基本
    public static List<Box> boxes(byte[] b, int start, int end) {
        List<Box> out = new ArrayList<Box>();
        // python 的切片越界是容错的；这里显式夹紧，行为等价
        if (start < 0) start = 0;
        if (end > b.length) end = b.length;
        int p = start;
        while (p + 8 <= end) {
            long sz = u32(b, p);
            byte[] t = new byte[4];
            System.arraycopy(b, p + 4, t, 0, 4);
            long hdr = 8;
            if (sz == 1) {
                if (p + 16 > b.length) break;
                sz = u64(b, p + 8);
                hdr = 16;
            }
            if (sz < hdr || sz > Integer.MAX_VALUE) break;
            out.add(new Box(t, p, (int) sz));
            p += (int) sz;
        }
        return out;
    }

    public static long u32(byte[] b, int o) {
        if (o + 4 > b.length) return -1;
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16)
                | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    public static long u64(byte[] b, int o) {
        return (u32(b, o) << 32) | u32(b, o + 4);
    }

    public static int u8(byte[] b, int o) { return o < b.length ? b[o] & 0xFF : 0; }

    public static int u16(byte[] b, int o) {
        return o + 2 <= b.length ? ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF) : 0;
    }

    public static int indexOf(byte[] hay, byte[] needle, int from, int to) {
        if (needle.length == 0) return -1;
        int end = Math.min(to, hay.length) - needle.length;
        for (int i = Math.max(0, from); i <= end; i++) {
            boolean hit = true;
            for (int k = 0; k < needle.length; k++) {
                if (hay[i + k] != needle[k]) { hit = false; break; }
            }
            if (hit) return i;
        }
        return -1;
    }

    public static int indexOf(byte[] hay, byte[] needle, int from) {
        return indexOf(hay, needle, from, hay.length);
    }

    // ------------------------------------------------------------------ walk
    public static final class Hit {
        public final int pos;
        public final int size;
        Hit(int p, int s) { pos = p; size = s; }
    }

    public static List<Hit> walk(byte[] b, byte[] name, int start, int end) {
        List<Hit> out = new ArrayList<Hit>();
        walkInto(b, name, start, end, out);
        return out;
    }

    private static void walkInto(byte[] b, byte[] name, int start, int end, List<Hit> out) {
        for (Box bx : boxes(b, start, end)) {
            if (bx.is(name)) out.add(new Hit(bx.pos, bx.size));
            String t = bx.typeStr();
            if (CONTAINERS.contains(t)) walkInto(b, name, bx.pos + 8, bx.pos + bx.size, out);
            else if (COUNT8.contains(t)) walkInto(b, name, bx.pos + 16, bx.pos + bx.size, out);
            else if (AUDIO_ENTRY.contains(t)) walkInto(b, name, bx.pos + 8 + AUDIO_ENTRY_HDR, bx.pos + bx.size, out);
        }
    }

    /** luna_meta.py 的 walk（容器集合略不同：没有 AUDIO_ENTRY 分支） */
    public static List<Hit> walkTag(byte[] b, byte[] name, int start, int end) {
        List<Hit> out = new ArrayList<Hit>();
        walkTagInto(b, name, start, end, out);
        return out;
    }

    private static void walkTagInto(byte[] b, byte[] name, int start, int end, List<Hit> out) {
        for (Box bx : boxes(b, start, end)) {
            if (bx.is(name)) out.add(new Hit(bx.pos, bx.size));
            String t = bx.typeStr();
            if (CONTAINERS.contains(t)) walkTagInto(b, name, bx.pos + 8, bx.pos + bx.size, out);
            else if (COUNT8.contains(t)) walkTagInto(b, name, bx.pos + 16, bx.pos + bx.size, out);
        }
    }

    /** Python find_box(b, name, end)：返回 (fourcc 下标, size) 或 null */
    public static int[] findBox(byte[] b, byte[] name, int end) {
        int j = indexOf(b, name, 0, end);
        while (j > 0) {
            long sz = u32(b, j - 4);
            if (sz >= 8 && sz <= end - (j - 4)) return new int[]{j, (int) sz};
            j = indexOf(b, name, j + 1, end);
        }
        return null;
    }

    public static int[] findBox(byte[] b, byte[] name) {
        return findBox(b, name, b.length);
    }

    public static int[] moov(byte[] b) {
        for (Box bx : boxes(b, 0, b.length)) {
            if (bx.is(b4("moov"))) return new int[]{bx.pos, bx.size};
        }
        return null;
    }

    public static int[] kidOf(byte[] b) {
        int[] r = audioRange(b);
        byte[] tag = b4("tenc");
        int i = indexOf(b, tag, r[0], r[1]);
        while (i > 0 && i < r[1]) {
            long sz = u32(b, i - 4);
            if (sz == 32 || sz == 36) return new int[]{i + 12, i + 28};
            i = indexOf(b, tag, i + 1, r[1]);
        }
        return null;
    }

    public static String kidHex(byte[] b) {
        int[] k = kidOf(b);
        if (k == null) return null;
        StringBuilder sb = new StringBuilder(32);
        for (int i = k[0]; i < k[1]; i++) sb.append(String.format("%02x", b[i] & 0xFF));
        return sb.toString();
    }

    // ------------------------------------------------------------------ 轨道
    /** 每个 trak 的 (handler, sample_entry_fourcc) */
    public static List<String[]> trakList(byte[] b) {
        List<String[]> out = new ArrayList<String[]>();
        int[] m = moov(b);
        if (m == null) return out;
        for (Box bx : boxes(b, m[0] + 8, m[0] + m[1])) {
            if (!bx.is(b4("trak"))) continue;
            String h = null;
            List<Hit> hs = walk(b, b4("hdlr"), bx.pos + 8, bx.pos + bx.size);
            if (!hs.isEmpty()) h = new String(b, hs.get(0).pos + 16, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            String f = null;
            List<Hit> ss = walk(b, b4("stsd"), bx.pos + 8, bx.pos + bx.size);
            if (!ss.isEmpty()) {
                int o = ss.get(0).pos + 8 + 4 + 4 + 4;
                if (o + 4 <= b.length) f = new String(b, o, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
            out.add(new String[]{h, f});
        }
        return out;
    }

    public static boolean hasVideo(byte[] b) {
        for (String[] t : trakList(b)) {
            if ("vide".equals(t[0])) return true;
            if (t[1] != null && VIDEO.contains(t[1])) return true;
        }
        return false;
    }

    /** 音频轨(handler=soun) 的 [start,end)；找不到退回整个文件 */
    public static int[] audioRange(byte[] b) {
        int[] m = moov(b);
        if (m != null) {
            for (Box bx : boxes(b, m[0] + 8, m[0] + m[1])) {
                if (!bx.is(b4("trak"))) continue;
                List<Hit> hs = walk(b, b4("hdlr"), bx.pos + 8, bx.pos + bx.size);
                if (!hs.isEmpty()) {
                    int o = hs.get(0).pos + 16;
                    if (o + 4 <= b.length && b[o] == 's' && b[o + 1] == 'o'
                            && b[o + 2] == 'u' && b[o + 3] == 'n') {
                        return new int[]{bx.pos + 8, bx.pos + bx.size};
                    }
                }
            }
        }
        return new int[]{0, b.length};
    }
}
