package com.syh.luna.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 索引（kid->spade_a / vid->spade_a / md5->vid / vid->元数据）与封面查找。
 * 数据来源：设备上 app 自己落盘的 keva / NetCacheLoader / sqlite。
 */
public final class Index {

    public final Map<String, String> kid2spade = new LinkedHashMap<String, String>();
    public final Map<String, String> vid2spade = new LinkedHashMap<String, String>();
    /** md5 -> [(vid, quality)] */
    public final Map<String, List<String[]>> md5toVid = new LinkedHashMap<String, List<String[]>>();
    public final Map<String, Map<String, Object>> vidMeta = new LinkedHashMap<String, Map<String, Object>>();

    // ------------------------------------------------------------------ 载入
    @SuppressWarnings("unchecked")
    public static Index load(File json) throws IOException {
        Index idx = new Index();
        if (json == null || !json.isFile()) return idx;
        Map<String, Object> root = Json.parseObject(readAll(json));
        if (root == null) return idx;
        Map<String, Object> k2s = Json.asMap(root.get("kid2spade"));
        if (k2s != null) for (Map.Entry<String, Object> e : k2s.entrySet())
            idx.kid2spade.put(e.getKey(), Json.str(e.getValue()));
        Map<String, Object> v2s = Json.asMap(root.get("vid2spade"));
        if (v2s != null) for (Map.Entry<String, Object> e : v2s.entrySet())
            idx.vid2spade.put(e.getKey(), Json.str(e.getValue()));
        Map<String, Object> m2v = Json.asMap(root.get("md5_2_vid"));
        if (m2v != null) for (Map.Entry<String, Object> e : m2v.entrySet()) {
            List<Object> lst = Json.asList(e.getValue());
            List<String[]> arr = new ArrayList<String[]>();
            if (lst != null) for (Object o : lst) {
                List<Object> pair = Json.asList(o);
                if (pair != null && pair.size() >= 1) {
                    String q = pair.size() > 1 ? Json.str(pair.get(1)) : "";
                    arr.add(new String[]{Json.str(pair.get(0)), q});
                }
            }
            idx.md5toVid.put(e.getKey(), arr);
        }
        Map<String, Object> vm = Json.asMap(root.get("vid_meta"));
        if (vm != null) for (Map.Entry<String, Object> e : vm.entrySet()) {
            Map<String, Object> m = Json.asMap(e.getValue());
            if (m != null) idx.vidMeta.put(e.getKey(), m);
        }
        return idx;
    }

    public static String readAll(File f) throws IOException {
        byte[] d = readBytes(f);
        return new String(d, StandardCharsets.UTF_8);
    }

    public static byte[] readBytes(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readBytes(in, (int) Math.min(Integer.MAX_VALUE, f.length()));
        } finally {
            try { in.close(); } catch (IOException ignore) { }
        }
    }

    /** 读完整流；hint 只是初始容量提示（可传 -1） */
    public static byte[] readBytes(InputStream in, int hint) throws IOException {
        int cap = hint > 0 ? Math.min(hint, 1 << 22) : 65536;
        byte[] buf = new byte[Math.max(cap, 4096)];
        int n = 0, r;
        byte[] tmp = new byte[65536];
        while ((r = in.read(tmp)) > 0) {
            if (n + r > buf.length) {
                byte[] nb = new byte[Math.max(buf.length * 2, n + r)];
                System.arraycopy(buf, 0, nb, 0, n);
                buf = nb;
            }
            System.arraycopy(tmp, 0, buf, n, r);
            n += r;
        }
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    public static void writeAll(File f, String text) throws IOException {
        File p = f.getParentFile();
        if (p != null) p.mkdirs();
        java.io.OutputStream o = new java.io.FileOutputStream(f);
        try { o.write(text.getBytes(StandardCharsets.UTF_8)); } finally {
            try { o.close(); } catch (IOException ignore) { }
        }
    }

    // ------------------------------------------------------------------ 查询
    public static final class KeyHit {
        public String spade;
        public String src;
        public String kid;
        public String vid;
    }

    public KeyHit lookupKey(byte[] b, String md5) {
        KeyHit h = new KeyHit();
        h.kid = Mp4.kidHex(b);
        if (h.kid != null && kid2spade.containsKey(h.kid)) {
            h.spade = kid2spade.get(h.kid);
            h.src = "kid:" + h.kid;
            return h;
        }
        List<String[]> cand = md5toVid.get(md5);
        if (cand != null) {
            for (String[] cv : cand) {
                if (vid2spade.containsKey(cv[0])) {
                    h.spade = vid2spade.get(cv[0]);
                    h.src = "vid:" + cv[0];
                    h.vid = cv[0];
                    return h;
                }
            }
        }
        return h;
    }

    public static final class MetaHit {
        public Map<String, Object> meta = Collections.emptyMap();
        public String vid;
    }

    public MetaHit fullMeta(String md5, String vid) {
        MetaHit h = new MetaHit();
        List<String[]> lst = md5toVid.get(md5);
        if (vid == null && lst != null && !lst.isEmpty()) vid = lst.get(0)[0];
        h.vid = vid;
        if (vid != null) {
            Map<String, Object> m = vidMeta.get(vid);
            if (m != null) h.meta = m;
        }
        return h;
    }

    // ------------------------------------------------------------------ 序列化
    /** 导出为 python luna_index.json 同构的 JSON（用于桌面比对） */
    public String toJson() {
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("{\n \"kid2spade\": ").append(strMapJson(kid2spade)).append(",\n");
        sb.append(" \"vid2spade\": ").append(strMapJson(vid2spade)).append(",\n");
        sb.append(" \"md5_2_vid\": {");
        boolean f = true;
        for (Map.Entry<String, List<String[]>> e : md5toVid.entrySet()) {
            if (!f) sb.append(", ");
            f = false;
            sb.append(Json.escape(e.getKey())).append(": [");
            boolean g = true;
            for (String[] p : e.getValue()) {
                if (!g) sb.append(", ");
                g = false;
                sb.append('[').append(Json.escape(p[0])).append(", ").append(Json.escape(p[1])).append(']');
            }
            sb.append(']');
        }
        sb.append("},\n \"vid_meta\": {");
        f = true;
        for (Map.Entry<String, Map<String, Object>> e : vidMeta.entrySet()) {
            if (!f) sb.append(", ");
            f = false;
            sb.append(Json.escape(e.getKey())).append(": ").append(objJson(e.getValue()));
        }
        sb.append("}\n}");
        return sb.toString();
    }

    private static String strMapJson(Map<String, String> m) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean f = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!f) sb.append(", ");
            f = false;
            sb.append(Json.escape(e.getKey())).append(": ").append(Json.escape(e.getValue()));
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static String objJson(Object o) {
        if (o == null) return "null";
        if (o instanceof String) return Json.escape((String) o);
        if (o instanceof Boolean) return String.valueOf(o);
        if (o instanceof Long || o instanceof Integer) return String.valueOf(o);
        if (o instanceof Double) {
            double d = (Double) o;
            return (d == Math.floor(d) && !Double.isInfinite(d))
                    ? String.valueOf((long) d) : String.valueOf(d);
        }
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean f = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!f) sb.append(", ");
                f = false;
                sb.append(Json.escape(e.getKey())).append(": ").append(objJson(e.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean f = true;
            for (Object v : (List<Object>) o) {
                if (!f) sb.append(", ");
                f = false;
                sb.append(objJson(v));
            }
            return sb.append(']').toString();
        }
        return Json.escape(String.valueOf(o));
    }

    // ------------------------------------------------------------------ 封面
    public static final class Covers implements Pipeline.CoverProvider {
        /** sha1(cover_url) -> 原图本地路径 */
        public final Map<String, String> map = new LinkedHashMap<String, String>();
        public File thumbDir;
        private final Map<String, byte[]> memo = new LinkedHashMap<String, byte[]>();

        public static Covers load(File coverMapJson, File thumbDir) throws IOException {
            Covers c = new Covers();
            c.thumbDir = thumbDir;
            if (coverMapJson != null && coverMapJson.isFile()) {
                Map<String, Object> m = Json.parseObject(readAll(coverMapJson));
                if (m != null) for (Map.Entry<String, Object> e : m.entrySet())
                    c.map.put(e.getKey(), Json.str(e.getValue()));
            }
            return c;
        }

        /** 返回已归一化(<=1200px JPEG, 带缓存)的封面字节；无则 null */
        public byte[] get(Map<String, Object> cov) {
            return bytes(cov);
        }

        public byte[] bytes(Map<String, Object> cov) {
            if (cov == null) return null;
            String uri = Json.str(cov, "uri");
            if (uri == null || uri.isEmpty()) return null;
            String key = coverKey(cov);
            if (key == null) return null;
            byte[] cached = memo.get(key);
            if (cached != null) return cached.length == 0 ? null : cached;

            String path = map.get(key);
            byte[] out = null;
            if (path != null) {
                File orig = new File(path);
                if (orig.isFile()) {
                    try {
                        byte[] raw = readBytes(orig);
                        if (thumbDir != null) {
                            File th = new File(thumbDir, sha1hex(raw) + "_1200_q88.jpg");
                            if (th.isFile()) out = readBytes(th);
                            else out = raw;
                        } else {
                            out = raw;
                        }
                    } catch (IOException ignore) { }
                }
            }
            memo.put(key, out == null ? new byte[0] : out);
            return out;
        }
    }

    public static String coverUrl(Map<String, Object> cov, String spec) {
        List<Object> urls = Json.asList(cov.get("urls"));
        String base = (urls != null && !urls.isEmpty())
                ? Json.str(urls.get(0)) : "https://p3-luna.douyinpic.com/img/";
        String prefix = Json.str(cov, "prefix");
        if (prefix == null || prefix.isEmpty()) prefix = "tplv-b829550vbb";
        return base + Json.str(cov, "uri") + "~" + prefix + "-" + spec;
    }

    public static String coverKey(Map<String, Object> cov) {
        String u = coverUrl(cov, "image.image");
        return u == null ? null : sha1hex(u.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha1hex(byte[] d) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(d);
            StringBuilder sb = new StringBuilder(40);
            for (byte b : h) sb.append(String.format(Locale.US, "%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
