package com.syh.luna.android;

import com.syh.luna.core.Index;
import com.syh.luna.core.Json;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 密钥仓：hook 在宿主进程里抓到的 encrypt_info 落盘到
 *   /data/data/com.luna.music/files/luna_export/keys.json
 * 导出时再合并进索引 —— 这样「缺 key」的歌不用非得播放一次。
 */
public final class KeyVault {

    public static final String DIR = "files/luna_export";
    public static final String FILE = "keys.json";

    private KeyVault() { }

    public static File inHost(File hostDataDir) {
        return new File(new File(hostDataDir, DIR), FILE);
    }

    /** 独立模式：su 暂存过来的副本 */
    public static File inStage(File stageRoot) {
        return new File(new File(stageRoot, "files_luna_export"), FILE);
    }

    /** 读取已抓到的密钥 */
    public static Map<String, String> loadK(File keysJson) {
        return loadMap(keysJson, "kid2spade");
    }

    public static Map<String, String> loadV(File keysJson) {
        return loadMap(keysJson, "vid2spade");
    }

    private static Map<String, String> loadMap(File f, String field) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (f == null || !f.isFile()) return out;
        try {
            Map<String, Object> root = Json.parseObject(Index.readAll(f));
            Map<String, Object> m = root == null ? null : Json.asMap(root.get(field));
            if (m != null) for (Map.Entry<String, Object> e : m.entrySet())
                out.put(e.getKey(), Json.str(e.getValue()));
        } catch (Exception ignore) { }
        return out;
    }

    /** hook 侧调用：把新抓到的密钥并进 keys.json（幂等、加锁、不改已有值） */
    public static synchronized void merge(File keysJson, Map<String, String> kid2spade,
                                          Map<String, String> vid2spade) {
        Map<String, String> k = loadMap(keysJson, "kid2spade");
        Map<String, String> v = loadMap(keysJson, "vid2spade");
        int added = 0;
        if (kid2spade != null) for (Map.Entry<String, String> e : kid2spade.entrySet()) {
            String key = e.getKey().toLowerCase(java.util.Locale.ROOT);
            if (e.getValue() != null && !k.containsKey(key)) { k.put(key, e.getValue()); added++; }
        }
        if (vid2spade != null) for (Map.Entry<String, String> e : vid2spade.entrySet()) {
            if (e.getValue() != null && !v.containsKey(e.getKey())) { v.put(e.getKey(), e.getValue()); added++; }
        }
        if (added == 0) return;
        StringBuilder sb = new StringBuilder();
        sb.append("{\n \"kid2spade\": ").append(mapJson(k)).append(",\n");
        sb.append(" \"vid2spade\": ").append(mapJson(v)).append("\n}");
        try {
            Index.writeAll(keysJson, sb.toString());
        } catch (Exception ignore) { }
    }

    private static String mapJson(Map<String, String> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean f = true;
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (!f) sb.append(", ");
            f = false;
            sb.append(Json.escape(e.getKey())).append(": ").append(Json.escape(e.getValue()));
        }
        return sb.append('}').toString();
    }

    /** 把密钥仓合并进索引（不覆盖已有） */
    public static int apply(Index idx, File keysJson) {
        int n = 0;
        for (Map.Entry<String, String> e : loadMap(keysJson, "kid2spade").entrySet()) {
            if (!idx.kid2spade.containsKey(e.getKey())) { idx.kid2spade.put(e.getKey(), e.getValue()); n++; }
        }
        for (Map.Entry<String, String> e : loadMap(keysJson, "vid2spade").entrySet()) {
            if (!idx.vid2spade.containsKey(e.getKey())) { idx.vid2spade.put(e.getKey(), e.getValue()); n++; }
        }
        return n;
    }

    /** 从一段响应文本里扫出 encrypt_info / spade_a；返回是否抓到东西 */
    @SuppressWarnings("unchecked")
    public static boolean harvest(String text, Map<String, String> kidOut, Map<String, String> vidOut) {
        if (text == null || text.length() < 40) return false;
        if (text.indexOf("spade_a") < 0) return false;
        boolean got = false;
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c != '{' && c != '[') continue;
            Object o;
            try {
                o = Json.parseStrict(text.substring(i));
            } catch (Throwable t) {
                continue;
            }
            if (o == null) continue;
            List<Object> stack = new ArrayList<Object>();
            stack.add(o);
            while (!stack.isEmpty()) {
                Object cur = stack.remove(stack.size() - 1);
                if (cur instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) cur;
                    String spade = Json.str(m, "spade_a");
                    if (spade != null && !spade.isEmpty()) {
                        String kid = Json.str(m, "kid");
                        if (kid != null && !kid.isEmpty()) {
                            kidOut.put(kid.toLowerCase(java.util.Locale.ROOT), spade); got = true;
                        }
                        String vid = Json.str(m, "video_id");
                        if (vid != null && !vid.isEmpty()) { vidOut.put(vid, spade); got = true; }
                    }
                    for (Object v : m.values()) if (v instanceof Map || v instanceof List) stack.add(v);
                } else if (cur instanceof List) {
                    for (Object v : (List<Object>) cur) if (v instanceof Map || v instanceof List) stack.add(v);
                }
            }
            break;      // 与 python 侧一致：只取第一个能整串解析的 JSON
        }
        return got;
    }
}
