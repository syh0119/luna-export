package com.syh.luna.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从「文本 blob 列表」抽取索引 —— 逐条等价于 Python _luna_index.py 的
 * walk_json / note_encrypt / note_track / note_videomodel。
 *
 * 纯 Java，不依赖 Android：blob 由各平台自己产出（Android = sqlite 的 TEXT 列 + 原文文件），
 * 因此这部分可以在桌面上完整验证。
 */
public final class Scanner {

    public static final Pattern RE_MD5KEY =
            Pattern.compile("^(v[0-9a-zA-Z]{10,})\\|([^|]*)\\|([0-9a-f]{32})$");
    private static final Pattern RE_JSON_START = Pattern.compile("[\\{\\[]");
    /** 32 位十六进制 —— 宿主用 file_hash 表示 .mdl 的文件名 */
    private static final Pattern RE_MD5 = Pattern.compile("^[0-9a-fA-F]{32}$");

    private final Index idx = new Index();
    private final Map<String, Set<String>> md5sets = new LinkedHashMap<String, Set<String>>();

    /** 当前 blob 里出现过的 `file_hash`（就是 .mdl 的文件名） */
    private final List<String> curHashes = new ArrayList<String>();
    /** 当前 blob 里出现过的 track vid */
    private final List<String> curVids = new ArrayList<String>();

    public int blobCount;
    public int jsonCount;

    public Index index() { return idx; }

    /** python 的 `a or b`（空 dict / 空串 / 空 list 视为假） */
    private static Object truthy(Object a, Object b) {
        if (a == null) return b;
        if (a instanceof String && ((String) a).isEmpty()) return b;
        if (a instanceof Map && ((Map<?, ?>) a).isEmpty()) return b;
        if (a instanceof List && ((List<?>) a).isEmpty()) return b;
        return a;
    }

    /** 等价 python put()：仅当 v 非空且原键不存在时写入 */
    private static void put(Map<String, Object> m, String k, Object v) {
        if (v == null) return;
        if (v instanceof String && ((String) v).isEmpty()) return;
        if (!m.containsKey(k)) m.put(k, v);
    }

    /** 单个 blob 最多解析多少字节 JSON（防病态输入把扫描拖死） */
    private static final int BUDGET = 8 << 20;
    /** 单个 blob 最多尝试多少个起点 */
    private static final int MAX_TRY = 40000;

    /** 一次性扫一批。**注意**：这要求调用方把所有 blob 同时留在内存里 ——
     *  宿主的索引目录有 28 MB+（读成 String 还要翻倍），而 com.luna.music 是
     *  **32 位进程**，很容易被撑爆。能用 {@link #feed} 就优先用。 */
    public static Scanner scan(List<String> blobs) {
        Scanner s = new Scanner();
        for (String b : blobs) s.feed(b);
        s.finish();
        return s;
    }

    /**
     * 逐条喂入 —— 调用方读完一条即可丢弃，峰值只等于「最大的一条 blob」。
     * 这是给 Android 侧准备的（见 IndexBuilder）。
     */
    public void feed(String b) {
        if (b == null || b.isEmpty()) return;
        blobCount++;
        curHashes.clear();
        curVids.clear();
        scanText(b);

        // 宿主的 `playableJson` 里**嵌着被转义的 JSON**：
        //     "...l=2026...\"video_meta\":{\"file_hash\":\"2a42224df81e...\"}..."
        // 原文扫不出这段（它在字符串值内部），所以把 \" 还原成 " 再扫一遍 ——
        // 这样才拿得到 file_hash，也就是 .mdl 的文件名。
        // 这是「有密钥、能导出、但歌名查不出来」的根因。
        if (b.indexOf("\\\"") >= 0 && b.length() < (1 << 20)) {
            scanText(b.replace("\\\"", "\""));
        }
        linkHashes();
    }

    /** 在文本里反复抠 JSON 片段，逐个 walk */
    private void scanText(String b) {
        Matcher m = RE_JSON_START.matcher(b);
        int from = 0, budget = BUDGET, tries = 0;
        while (from < b.length() && budget > 0 && tries < MAX_TRY && m.find(from)) {
            int st = m.start();
            if (b.length() - st < 30) break;
            tries++;
            Json.Prefix p = Json.parsePrefix(b, st);
            if (p == null) {
                from = st + 1;          // 这个 '{' 不是合法 JSON 起点，往后挪一格
                continue;
            }
            walkJson(p.value, 0);
            jsonCount++;
            budget -= p.consumed;
            from = st + p.consumed;     // 跳过已吃掉的部分，继续找下一个
        }
    }

    /**
     * 把「同一条记录里的 file_hash」和「同一条记录里的 track vid」关联起来。
     *
     * 一条 `playableJson` 描述的就是一首歌，所以里面的 file_hash 和 track 必然同源。
     * 只在**恰好一个 vid** 时才关联（有歌单混进来时宁可不做，也别连错）。
     */
    private void linkHashes() {
        if (curHashes.isEmpty() || curVids.size() != 1) return;
        String vid = curVids.get(0);
        for (String h : curHashes) {
            Set<String> set = md5sets.get(h);
            if (set == null) { set = new LinkedHashSet<String>(); md5sets.put(h, set); }
            set.add(vid + "\u0000" + "lossless");
        }
    }

    /** 收尾：把攒下的 md5→vid 集合落进索引 */
    public Index finish() {
        for (Map.Entry<String, Set<String>> e : md5sets.entrySet()) {
            List<String> l = new ArrayList<String>(e.getValue());
            Collections.sort(l);
            List<String[]> out = new ArrayList<String[]>();
            for (String v : l) {
                int p = v.indexOf('\u0000');
                out.add(new String[]{v.substring(0, p), v.substring(p + 1)});
            }
            idx.md5toVid.put(e.getKey(), out);
        }
        md5sets.clear();        // 已经搬进 idx，别留着占内存
        return idx;
    }

    @SuppressWarnings("unchecked")
    private void walkJson(Object o, int depth) {
        if (depth > 12) return;
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (m.containsKey("encrypt_info"))
                noteEncrypt(Json.asMap(m.get("encrypt_info")), Json.str(m, "video_id"));
            if (m.get("spade_a") instanceof String) noteEncrypt(m, null);
            if (m.get("track") instanceof Map) noteTrack((Map<String, Object>) m.get("track"));
            if (m.containsKey("video_list")) noteVideoModel(m, null);
            // file_hash = .mdl 的文件名；和同一条记录里的 track 关联后就能补出 md5→vid
            Object fh = m.get("file_hash");
            if (fh instanceof String && RE_MD5.matcher((String) fh).matches()
                    && !curHashes.contains(fh)) curHashes.add((String) fh);
            for (Map.Entry<String, Object> e : m.entrySet()) {
                Matcher mm = RE_MD5KEY.matcher(e.getKey());
                if (mm.matches()) {
                    Set<String> set = md5sets.get(mm.group(3));
                    if (set == null) { set = new LinkedHashSet<String>(); md5sets.put(mm.group(3), set); }
                    set.add(mm.group(1) + "\u0000" + mm.group(2));
                }
                walkJson(e.getValue(), depth + 1);
            }
        } else if (o instanceof List) {
            for (Object v : (List<Object>) o) walkJson(v, depth + 1);
        }
    }

    private void noteEncrypt(Map<String, Object> ei, String vid) {
        if (ei == null) return;
        String spade = Json.str(ei, "spade_a");
        if (spade == null || spade.isEmpty()) return;
        String k = Json.str(ei, "kid");
        k = k == null ? "" : k.toLowerCase(Locale.ROOT);
        if (!k.isEmpty()) idx.kid2spade.put(k, spade);
        if (vid != null && !vid.isEmpty() && !idx.vid2spade.containsKey(vid))
            idx.vid2spade.put(vid, spade);
    }

    private void noteVideoModel(Map<String, Object> v, String vid) {
        if (v == null) return;
        if (vid == null) vid = Json.str(v, "video_id");
        List<Object> vl = Json.asList(v.get("video_list"));
        if (vl == null) return;
        for (Object e : vl) {
            Map<String, Object> em = Json.asMap(e);
            if (em == null) continue;
            noteEncrypt(Json.asMap(em.get("encrypt_info")), vid);
        }
    }

    @SuppressWarnings("unchecked")
    private void noteTrack(Map<String, Object> t) {
        if (t == null) return;
        String vid = Json.str(t, "vid");
        if (vid == null || vid.isEmpty()) return;
        if (!curVids.contains(vid)) curVids.add(vid);
        Map<String, Object> m = idx.vidMeta.get(vid);
        if (m == null) { m = new LinkedHashMap<String, Object>(); idx.vidMeta.put(vid, m); }

        put(m, "title", Tags.fixMojibake(Json.str(t, "name")));
        Map<String, Object> album = Json.asMap(t.get("album"));
        put(m, "album", Tags.fixMojibake(album == null ? null : Json.str(album, "name")));
        List<Object> arts = Json.asList(t.get("artists"));
        if (arts != null && !arts.isEmpty()) {
            Map<String, Object> a0 = Json.asMap(arts.get(0));
            if (a0 != null) put(m, "artist", Tags.fixMojibake(Json.str(a0, "name")));
        }
        Object dur = t.get("duration");
        if (dur != null) put(m, "duration", dur);

        Object ly = truthy(t.get("track_lyric"), t.get("lyric"));
        String content = null;
        if (ly instanceof Map) {
            Map<String, Object> lm = (Map<String, Object>) ly;
            content = Json.str(lm, "lyric");
            if (content == null) content = Json.str(lm, "content");
            if (content == null) content = Json.str(lm, "lrc");
            put(m, "lyric_trans", Tags.fixMojibake(Json.str(lm, "translation")));
        } else if (ly instanceof String) {
            content = (String) ly;
        }
        if (content != null && !Tags.pyStrip(content).isEmpty())
            put(m, "lyric", Tags.fixMojibake(content));

        Map<String, Object> cov = album == null ? null : Json.asMap(album.get("url_cover"));
        if (cov == null || Json.str(cov, "uri") == null) cov = Json.asMap(t.get("url_cover"));
        if (cov != null && Json.str(cov, "uri") != null) {
            Map<String, Object> c = new LinkedHashMap<String, Object>();
            c.put("uri", Json.str(cov, "uri"));
            String tp = Json.str(cov, "template_prefix");
            c.put("prefix", (tp == null || tp.isEmpty()) ? "tplv-b829550vbb" : tp);
            List<Object> urls = Json.asList(cov.get("urls"));
            if (urls == null || urls.isEmpty())
                urls = Collections.singletonList((Object) "https://p3-luna.douyinpic.com/img/");
            c.put("urls", urls);
            put(m, "cover", c);
        }

        Map<String, Object> pl = Json.asMap(t.get("player_info"));
        if (pl != null) {
            String vm = Json.str(pl, "video_model");
            if (vm != null && !vm.isEmpty()) {
                Map<String, Object> v = Json.parseStrictObject(vm);
                if (v != null) noteVideoModel(v, vid);
            }
        }
    }
}
