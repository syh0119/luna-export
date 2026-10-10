package com.syh.luna.android;

import android.content.Context;

import com.syh.luna.core.Index;
import com.syh.luna.core.Json;
import com.syh.luna.core.Pipeline;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 导出编排。
 *
 *   prepare()  —— 定位数据、建索引、扫描 .mdl 形成可勾选列表
 *   export()   —— 对选中条目录盘（解密 + 打标签 + 写封面 + 出报告）
 *   autoRun()  —— 自动导出：带状态缓存，**没有新文件就一行目录都不读**
 *
 * 状态缓存（state.json）以 (size:mtime) 为指纹，缓存每首的标题/歌手/格式/完整性。
 * 文件没变就连探针读都省掉，所以宿主每次启动的自动检查开销 ≈ 一次目录列举。
 */
public final class Exporter {

    /** MediaStore 相对路径 */
    public static final String REL = "Music/汽水导出";
    /** 自动导出最小间隔（毫秒）——状态缓存之外的第二道闸 */
    public static final long AUTO_INTERVAL_MS = 3 * 3600 * 1000L;

    public interface Log { void line(String s); }

    public interface Progress { void on(int done, int total, String label); }

    // ------------------------------------------------------------------ 状态缓存
    public static final class FileRec {
        public String sig = "";
        public String kind = "";
        public String title, artist, album;
        /**
         * 这个文件里 `tenc` 的 KID。
         *
         * 存它是为了让**缓存命中的路径也能重算 hasKey** —— 密钥库是会涨的
         * （LSPosed 侧一直在被动抓 `encrypt_info`），而 KID 就写在文件里、不会变。
         * 只存一个布尔值的话，第一次没 key 的歌会被永久钉死成「不可导出」。
         */
        public String kid;
        public boolean complete, hasKey, exported;
    }

    public static final class State {
        public final Map<String, FileRec> files = new LinkedHashMap<String, FileRec>();

        public static State load(File f) {
            State s = new State();
            if (f == null || !f.isFile()) return s;
            try {
                Map<String, Object> root = Json.parseObject(Index.readAll(f));
                Map<String, Object> m = root == null ? null : Json.asMap(root.get("files"));
                if (m == null) return s;
                for (Map.Entry<String, Object> e : m.entrySet()) {
                    Map<String, Object> r = Json.asMap(e.getValue());
                    if (r == null) continue;
                    FileRec fr = new FileRec();
                    fr.sig = Json.str(r, "sig");
                    fr.kind = Json.str(r, "kind");
                    fr.title = Json.str(r, "title");
                    fr.artist = Json.str(r, "artist");
                    fr.album = Json.str(r, "album");
                    fr.kid = Json.str(r, "kid");
                    fr.complete = "true".equals(String.valueOf(r.get("complete")));
                    fr.hasKey = "true".equals(String.valueOf(r.get("hasKey")));
                    fr.exported = "true".equals(String.valueOf(r.get("exported")));
                    s.files.put(e.getKey(), fr);
                }
            } catch (Throwable ignore) { }
            return s;
        }

        public void save(File f) {
            StringBuilder sb = new StringBuilder(1 << 16);
            sb.append("{\n \"files\": {");
            boolean first = true;
            for (Map.Entry<String, FileRec> e : files.entrySet()) {
                FileRec r = e.getValue();
                if (!first) sb.append(",");
                first = false;
                sb.append("\n  ").append(Json.escape(e.getKey())).append(": {");
                sb.append("\"sig\":").append(Json.escape(r.sig));
                sb.append(",\"kind\":").append(Json.escape(r.kind));
                sb.append(",\"title\":").append(Json.escape(r.title));
                sb.append(",\"artist\":").append(Json.escape(r.artist));
                sb.append(",\"album\":").append(Json.escape(r.album));
                sb.append(",\"kid\":").append(Json.escape(r.kid));
                sb.append(",\"complete\":").append(r.complete);
                sb.append(",\"hasKey\":").append(r.hasKey);
                sb.append(",\"exported\":").append(r.exported).append("}");
            }
            sb.append("\n }\n}");
            try { Index.writeAll(f, sb.toString()); } catch (Throwable ignore) { }
        }
    }

    // ------------------------------------------------------------------ 数据
    public static final class Item {
        public final File file;
        public final String md5;
        public String kind = "";
        public String title;
        public String artist;
        public String album;
        /** 文件里 `tenc` 的 KID —— 用来在缓存命中的路径上重算 hasKey（见 FileRec.kid） */
        public String kid;
        public boolean complete;
        public boolean hasKey;
        public boolean exported;      // 上次已成功导出
        public boolean changed;       // 文件自上次导出后有变化（或从未扫过）
        public long size;
        public volatile boolean selected;

        Item(File f, String md5) { this.file = f; this.md5 = md5; }

        public boolean exportable() {
            if (!complete) return false;
            if ("plain".equals(kind)) return true;
            if (!("flac".equals(kind) || "aac".equals(kind))) return false;
            return hasKey;
        }

        /** 自动导出只碰「有变化或没导出过」的 */
        public boolean needsAutoExport() {
            return exportable() && (!exported || changed);
        }

        public String display() {
            if (title != null && !title.isEmpty()) return title;
            if ("video".equals(kind)) return "MV · " + shortMd5();
            if ("junk".equals(kind)) return "无效文件";
            // 别再裸奔一串 32 位 md5 了 —— 那看起来就跟乱码一样
            return "未知曲目 · " + shortMd5();
        }

        private String shortMd5() {
            return md5.length() > 8 ? md5.substring(0, 8) : md5;
        }

        public String subtitle() {
            if ("video".equals(kind)) return "MV 视频 · 已忽略";
            if ("junk".equals(kind)) return "无效文件";
            StringBuilder sb = new StringBuilder();
            if (artist != null && !artist.isEmpty()) sb.append(artist);
            if (album != null && !album.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(album);
            }
            if (sb.length() == 0) sb.append(md5);
            return sb.toString();
        }

        public String badge() {
            if ("flac".equals(kind)) return "FLAC";
            if ("aac".equals(kind)) return "AAC";
            if ("plain".equals(kind)) return "M4A";
            if ("video".equals(kind)) return "MV";
            return "?";
        }

        public String state() {
            if ("video".equals(kind) || "junk".equals(kind)) return "跳过";
            if (!complete) return "残片";
            if (!"plain".equals(kind) && !hasKey) return "缺密钥";
            if (exported && !changed) return "已导出";
            if (!exported && !changed) return "待导出";
            return "可导出";
        }
    }

    public static final class Session {
        public File root;
        public boolean staged;
        public String base = "";
        public Index idx;
        public String indexInfo = "";
        public int vaultKeys;
        public final List<Item> items = new ArrayList<Item>();
        public CoverStore covers;
        public MediaStoreSink sink;
        public File stateFile;
        public State state;
        public long preparedMs;
        public int changedCount, newCount;
        public String error;
        /**
         * 是否额外输出侧车 `.lrc` 文件。
         *
         * **默认关**：歌词本来就已经写进音频标签了（FLAC 的 `LYRICS` / M4A 的 `©lyr`），
         * 再丢一个同名 `.lrc` 在歌曲旁边只会让目录里多一倍文件。
         * 只有播放器读不了内嵌标签时才需要打开。
         */
        public boolean wantLrc;

        public int countExportable() {
            int n = 0;
            for (Item it : items) if (it.exportable()) n++;
            return n;
        }

        public List<Item> selectedExportable() {
            List<Item> out = new ArrayList<Item>();
            for (Item it : items) if (it.selected && it.exportable()) out.add(it);
            return out;
        }

        public List<Item> autoTargets() {
            List<Item> out = new ArrayList<Item>();
            for (Item it : items) if (it.needsAutoExport()) out.add(it);
            return out;
        }
    }

    public static final class Stats {
        public int requested, ok, skip, failed, tagged, withCover;
        public long ms;
        public String sinkInfo = "";
        public String coverInfo = "";

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("请求 ").append(requested)
              .append(" · 成功 ").append(ok)
              .append(" · 失败 ").append(failed)
              .append(" · 带标签 ").append(tagged)
              .append(" · 带封面 ").append(withCover).append('\n');
            sb.append("产物: ").append(sinkInfo).append('\n');
            sb.append("封面: ").append(coverInfo).append('\n');
            sb.append("耗时 ").append(ms / 1000.0).append(" 秒");
            return sb.toString();
        }
    }

    private Exporter() { }

    // ------------------------------------------------------------------ 状态文件位置
    /** LSPosed 模式放宿主私有目录；独立模式放自己私有目录（暂存目录是临时的，不能用） */
    public static File stateFileFor(Context ctx, boolean rootMode, File root) {
        if (rootMode) return new File(ctx.getFilesDir(), "export_state.json");
        return new File(new File(root, KeyVault.DIR), "state.json");
    }

    static String sigOf(File f) {
        return f.length() + ":" + f.lastModified();
    }

    // ------------------------------------------------------------------ 准备
    public static Session prepare(Context ctx, boolean rootMode, Log log, Progress prog) {
        long t0 = System.currentTimeMillis();
        Session s = new Session();

        if (rootMode) {
            if (!Dirs.hasRoot()) {
                s.error = "未获得 root 授权（su 不可用）";
                if (log != null) log.line("✗ " + s.error);
                return s;
            }
            if (log != null) log.line("su 暂存宿主私有目录 …");
            try {
                s.root = Dirs.stage();
            } catch (Exception e) {
                s.error = "su 暂存失败: " + e.getMessage();
                if (log != null) log.line("✗ " + s.error);
                return s;
            }
            s.staged = true;
            s.base = Dirs.lastBase;
        } else {
            s.root = Dirs.inProcess(ctx);
            s.staged = false;
        }

        File mdlDir = IndexBuilder.sub(s.root, "files/music", s.staged);
        List<File> files = Pipeline.listMdl(mdlDir);
        if (files.isEmpty()) {
            s.error = "没找到 .mdl（root 模式请确认 su 已授权；LSPosed 模式请确认作用域已勾选并重启过宿主）";
            if (log != null) log.line("✗ " + s.error);
            return s;
        }

        s.stateFile = stateFileFor(ctx, rootMode, s.root);
        s.state = State.load(s.stateFile);
        s.wantLrc = lrcSidecar(ctx);        // 默认 false：歌词已内嵌，不额外丢 .lrc

        if (prog != null) prog.on(0, files.size(), "建立索引 …");
        Index idx = IndexBuilder.build(s.root, s.staged);
        File vault = rootMode ? KeyVault.inStage(s.root) : KeyVault.inHost(s.root);
        s.vaultKeys = KeyVault.apply(idx, vault);
        s.idx = idx;
        s.indexInfo = "密钥 " + idx.kid2spade.size()
                + " · 曲目库 " + idx.vidMeta.size()
                + " · md5映射 " + idx.md5toVid.size();

        s.covers = new CoverStore(ctx);
        int i = 0, probed = 0;
        for (File f : files) {
            String n = f.getName();
            String md5 = n.toLowerCase(Locale.ROOT).endsWith(".mdl")
                    ? n.substring(0, n.length() - 4) : n;
            Item it = new Item(f, md5);
            it.size = f.length();
            String sig = sigOf(f);
            FileRec cached = s.state.files.get(md5);
            if (cached != null && sig.equals(cached.sig)) {
                // 命中缓存：完全不读文件
                it.kind = cached.kind == null ? "" : cached.kind;
                it.title = cached.title;
                it.artist = cached.artist;
                it.album = cached.album;
                it.complete = cached.complete;
                it.exported = cached.exported;
                it.changed = false;
                // hasKey 用**当前索引**按 KID 重算，不信缓存里的布尔值。
                // 密钥库是会长大的（LSPosed 侧一直在被动抓 encrypt_info），
                // KID 又写在文件里不会变 —— 只存布尔值的话，第一次没 key 的歌
                // 会被永久钉死成「不可导出」，密钥补上了也还是灰的。
                it.kid = cached.kid;
                if (it.kid != null && !it.kid.isEmpty()) {
                    boolean now = idx.kid2spade.containsKey(it.kid);
                    if (now != cached.hasKey) cached.hasKey = now;
                    it.hasKey = now;
                } else {
                    it.hasKey = cached.hasKey;
                }
                // 上次没解析出标题的，从**索引**里再查一次（零读盘）。
                // 索引是会变的：LSPosed 侧被动抓取一直在往 blobs.bin 里攒，
                // 之前查不到的歌后来可能就有了 —— 否则空标题会被缓存永久固化。
                if (it.title == null || it.title.isEmpty()) {
                    Index.MetaHit mh = idx.fullMeta(md5, null);
                    String t = Json.str(mh.meta, "title");
                    if (t != null && !t.isEmpty()) {
                        it.title = t;
                        it.artist = Json.str(mh.meta, "artist");
                        it.album = Json.str(mh.meta, "album");
                        cached.title = it.title;
                        cached.artist = it.artist;
                        cached.album = it.album;
                    }
                }
            } else {
                it.changed = true;
                probed++;
                try {
                    Pipeline.Result r = Pipeline.process(f, null, idx, s.covers, true, true, true);
                    it.kind = r.kind;
                    it.title = r.title;
                    it.artist = r.artist;
                    it.album = r.album;
                    it.complete = r.complete;
                    it.hasKey = isKey(r);
                    it.kid = r.kid;
                } catch (Throwable t) {
                    it.kind = "junk";
                }
                if (cached == null) s.newCount++;
                // 把探测结果写回缓存 —— 否则下次扫描 sig 对不上还要重读一遍，
                // 而且 kid / hasKey 永远存不下来（缓存路径就永远重算不了）
                FileRec rec = s.state.files.get(md5);
                if (rec == null) { rec = new FileRec(); s.state.files.put(md5, rec); }
                rec.sig = sig;
                rec.kind = it.kind;
                rec.title = it.title;
                rec.artist = it.artist;
                rec.album = it.album;
                rec.kid = it.kid;
                rec.complete = it.complete;
                rec.hasKey = it.hasKey;
            }
            s.items.add(it);
            i++;
            if (prog != null) prog.on(i, files.size(), "扫描 " + i + "/" + files.size());
        }
        s.changedCount = probed;

        // ---- 「已导出」以输出目录里的**真实文件**为准
        // state.json 只是「我曾经导出过」的记忆；用户删文件/清存储/换机之后它就是错的。
        java.util.Set<String> existing = listExported(ctx);
        if (existing != null) {
            for (Item it : s.items) {
                boolean can = it.complete && !"video".equals(it.kind) && !"junk".equals(it.kind);
                it.exported = can && existing.contains(
                        Pipeline.outName(it.artist, it.title, it.md5, it.kind));
                FileRec rec = s.state.files.get(it.md5);
                if (rec != null) rec.exported = it.exported;   // 对齐缓存，免得下次列不出目录时又用错
            }
        }

        java.util.Collections.sort(s.items, new java.util.Comparator<Item>() {
            public int compare(Item a, Item b) {
                int ra = rank(a), rb = rank(b);
                if (ra != rb) return ra - rb;
                String ka = (a.artist == null ? "" : a.artist) + a.display();
                String kb = (b.artist == null ? "" : b.artist) + b.display();
                return ka.compareToIgnoreCase(kb);
            }
            private int rank(Item it) {
                // 排序目标：能点的排前面，看得见但点不了的排后面
                if (it.exportable() && !it.exported) return 0;   // 还没导出过 → 最该处理
                if (it.exportable()) return 1;                   // 已导出（可重复导出）
                String st = it.state();
                if ("缺密钥".equals(st)) return 2;
                if ("残片".equals(st)) return 3;
                return 4;                                        // MV / 无效
            }
        });

        s.sink = new MediaStoreSink(ctx, REL);
        s.preparedMs = System.currentTimeMillis() - t0;
        // 扫描结果也落盘：kid / hasKey 这些只有写回去，下次缓存命中时才重算得出来
        if (s.stateFile != null) s.state.save(s.stateFile);
        if (log != null) {
            log.line("索引: " + s.indexInfo
                    + (s.vaultKeys > 0 ? "（密钥仓补入 " + s.vaultKeys + "）" : ""));
            log.line("扫描 " + s.items.size() + " 个 .mdl：新增 " + s.newCount
                    + " · 有变化 " + s.changedCount + " · 可导出 " + s.countExportable()
                    + "（读盘 " + s.changedCount + " 个，其余走缓存，"
                    + s.preparedMs / 1000.0 + "s）");
        }
        return s;
    }

    static boolean isKey(Pipeline.Result r) {
        String c = r.keySrc;
        return c != null && c.indexOf("bad") < 0 && c.indexOf("no sample") < 0;
    }

    // ------------------------------------------------------------------ 「已导出」的真实判据
    /**
     * 列出输出目录里**实际存在**的文件名。
     *
     * @return null = 三条件通路全失败，压根列不出来（调用方退回 state.json 的记忆值）
     *         非 null = 列成功（空集也代表「确实一个都没有」）
     *
     * 为什么要这么做：state.json 只记得「我曾经导出过」。用户在文件管理器里删掉、
     * 清理存储、换机恢复之后，那份记录就是错的 —— 列表会继续显示「已导出」，
     * 而文件其实早没了，用户再点导出也以为已经导过。
     */
    public static java.util.Set<String> listExported(Context ctx) {
        java.util.Set<String> names = new java.util.HashSet<String>();
        boolean any = false;

        // ① 直接列目录（root 模式 / 有全盘权限时可用）
        try {
            File[] fs = new File("/sdcard/" + REL).listFiles();
            if (fs != null) { any = true; for (File f : fs) names.add(f.getName()); }
        } catch (Throwable ignore) { }

        // ② Download/<leaf>/ —— .lrc 与报告的落点
        try {
            File[] fs = new File("/sdcard/Download/" + MediaStoreSink.leaf(REL)).listFiles();
            if (fs != null) { any = true; for (File f : fs) names.add(f.getName()); }
        } catch (Throwable ignore) { }

        // ③ MediaStore（LSPosed 模式跑在宿主 uid 下，文件系统通常不可读，靠这条）
        try {
            any |= queryNames(ctx,
                    android.provider.MediaStore.Audio.Media.getContentUri(
                            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{REL + "%"}, names);
            any |= queryNames(ctx,
                    android.provider.MediaStore.Downloads.getContentUri(
                            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{"Download/" + MediaStoreSink.leaf(REL) + "%"}, names);
        } catch (Throwable ignore) { }

        return any ? names : null;
    }

    private static boolean queryNames(Context ctx, android.net.Uri uri, String sel,
                                      String[] args, java.util.Set<String> into) {
        android.database.Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{android.provider.MediaStore.MediaColumns.DISPLAY_NAME}, sel, args, null);
            if (c == null) return false;
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null) into.add(n);
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) { try { c.close(); } catch (Throwable ignore) { } }
        }
    }

    // ------------------------------------------------------------------ 导出
    public static Stats export(Session s, List<Item> chosen, Log log, Progress prog) {
        return export(s, chosen, log, prog, false);
    }

    /**
     * @param gentle true = 后台自动导出：低线程优先级 + 每首之间让出 CPU，尽量不干扰宿主播放
     */
    public static Stats export(Session s, List<Item> chosen, Log log, Progress prog, boolean gentle) {
        long t0 = System.currentTimeMillis();
        Stats st = new Stats();
        if (s == null || s.error != null) {
            st.failed = 1;
            st.ms = System.currentTimeMillis() - t0;
            return st;
        }
        if (gentle) {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignore) { }
        }
        if (chosen == null) chosen = s.autoTargets();
        st.requested = chosen.size();
        if (chosen.isEmpty()) {
            if (log != null) log.line("没有需要导出的曲目");
            st.ms = System.currentTimeMillis() - t0;
            return st;
        }

        StringBuilder report = new StringBuilder("[\n");
        boolean first = true;
        int done = 0;
        for (Item it : chosen) {
            done++;
            // 先报进度再干活 —— 否则第一首要跑完（读盘+解密+打标签+写库）才有一行日志，
            // 用户会以为点了没反应
            if (log != null) log.line("▶ " + done + "/" + chosen.size() + "  " + it.display());
            if (prog != null) prog.on(done, chosen.size(), it.display());
            try {
                Pipeline.Result r = Pipeline.process(it.file, s.sink, s.idx, s.covers, false, true, s.wantLrc);
                if (!first) report.append(",\n");
                first = false;
                report.append(r.toJson());
                FileRec rec = s.state.files.get(it.md5);
                if (rec == null) { rec = new FileRec(); s.state.files.put(it.md5, rec); }
                rec.sig = sigOf(it.file);
                rec.kind = it.kind;
                rec.title = it.title;
                rec.artist = it.artist;
                rec.album = it.album;
                rec.complete = it.complete;
                rec.hasKey = it.hasKey;
                if (r.out != null) {
                    rec.exported = true;
                    it.exported = true;
                    it.changed = false;
                    st.ok++;
                    if (r.tagged) st.tagged++;
                    if (r.hasCover) st.withCover++;
                    if (log != null) log.line("✓ " + it.display() + "  →  " + baseName(r.out)
                            + (r.lrc != null ? "  +lrc" : ""));
                } else {
                    st.failed++;
                    if (log != null) log.line("✗ " + it.display() + "（" + r.keySrc + "）");
                }
            } catch (Throwable t) {
                st.failed++;
                if (log != null) log.line("✗ " + it.display() + " 异常: " + t);
            }
            if (gentle) {
                try { Thread.sleep(25); } catch (InterruptedException ignore) { }
            }
            // 宿主是 32 位进程，一首无损曲目在「读入 + 解密 + 打标签」里的峰值是文件大小的
            // 2~3 倍。每首之间主动回收一次，避免上一首的几十 MB 一直挂着把宿主推向 OOM。
            System.gc();
        }
        report.append("\n]");

        // 未选中的条目的元数据也要进缓存，下次扫描就能免读
        for (Item it : s.items) {
            if (!s.state.files.containsKey(it.md5)) {
                FileRec rec = new FileRec();
                rec.sig = sigOf(it.file);
                rec.kind = it.kind;
                rec.title = it.title;
                rec.artist = it.artist;
                rec.album = it.album;
                rec.complete = it.complete;
                rec.hasKey = it.hasKey;
                rec.exported = false;
                s.state.files.put(it.md5, rec);
            }
        }
        if (s.stateFile != null) s.state.save(s.stateFile);

        s.sink.put("_导出报告.json",
                report.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder si = new StringBuilder();
        si.append("音频 ").append(s.sink.viaAudio).append(" 首已入媒体库");
        si.append(" · 歌词同目录 ").append(s.sink.viaDirect).append(" 个");
        if (s.sink.viaDownloads > 0)
            si.append(" · 报告/日志在 Download/").append(MediaStoreSink.leaf(REL)).append("/");
        if (s.sink.viaFallback > 0) si.append(" · 退回私有目录 ").append(s.sink.viaFallback);
        if (s.sink.failed > 0) si.append(" · 失败 ").append(s.sink.failed);
        st.sinkInfo = si.toString();
        st.coverInfo = "新下载 " + s.covers.downloaded + " · 缓存命中 " + s.covers.cached
                + " · 失败 " + s.covers.failed;
        st.ms = System.currentTimeMillis() - t0;

        if (log != null) {
            log.line("");
            log.line("输出: " + st.sinkInfo);
            log.line(st.summary());
        }
        StringBuilder lg = new StringBuilder();
        lg.append("# 汽水导出日志  ").append(now()).append('\n');
        lg.append("数据前缀: ").append(s.base).append('\n');
        lg.append("索引: ").append(s.indexInfo).append('\n');
        lg.append(st.summary()).append('\n');
        s.sink.put("_导出日志.txt", lg.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return st;
    }

    /** 手动：准备 + 导出全部可导出（保留 API 语义） */
    public static Stats run(Context ctx, boolean rootMode, Log log) {
        Session s = prepare(ctx, rootMode, log, null);
        return export(s, null, log, null, false);
    }

    /**
     * 自动导出（LSPosed 宿主启动时调用）。
     * 开销要点：
     *   1) 先看状态缓存：所有 .mdl 的 (size:mtime) 都没变 → 连一个文件都不读，直接返回
     *   2) 有变化才只导出「新增/变更」的那几首，不动已导出的
     *   3) 后台线程优先级 + 每首之间 sleep，尽量不影响宿主
     */
    public static Stats autoRun(Context ctx, Log log) {
        long t0 = System.currentTimeMillis();
        Stats st = new Stats();
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable ignore) { }

        Session s = prepare(ctx, false, log, null);
        if (s.error != null) {
            st.failed = 1;
            st.ms = System.currentTimeMillis() - t0;
            return st;
        }
        if (s.changedCount == 0 && s.newCount == 0) {
            if (log != null) log.line("自动检查: " + s.items.size()
                    + " 个 .mdl 全部未变更，无需导出（仅列举目录，未读文件内容）");
            st.ms = System.currentTimeMillis() - t0;
            return st;
        }
        List<Item> targets = s.autoTargets();
        if (targets.isEmpty()) {
            if (log != null) log.line("自动检查: 有变化但无可导出项，跳过");
            st.ms = System.currentTimeMillis() - t0;
            return st;
        }
        if (log != null) log.line("自动导出: 需处理 " + targets.size() + " 首（新增/变更）");
        return export(s, targets, log, null, true);
    }

    public static Stats autoRun(Context ctx, Log log, int unused) {
        return autoRun(ctx, log);
    }

    /** 距上次自动检查不足 AUTO_INTERVAL_MS 则返回 false */
    public static boolean dueForAuto(Context ctx, File hostDataDir) {
        File f = new File(new File(hostDataDir, KeyVault.DIR), "last.json");
        long last = 0;
        try {
            if (f.isFile()) last = Long.parseLong(Index.readAll(f).trim().replaceAll("[^0-9]", ""));
        } catch (Exception ignore) { }
        long now = System.currentTimeMillis();
        if (now - last < AUTO_INTERVAL_MS) return false;
        try { Index.writeAll(f, String.valueOf(now)); } catch (Exception ignore) { }
        return true;
    }

    // ------------------------------------------------------------------ 独立模式的偏好设置
    private static final String PREF = "luna";

    /**
     * 「启动时自动增量导出」开关。
     *
     * ⚠️ 默认 **关闭**：自动导出会在你只是正常听歌/浏览时也把曲目落盘到 /sdcard，白占空间。
     * 只有用户主动点「导出选中」才产出文件。想要自动化的人可以自己打开。
     */
    public static boolean autoOnLaunch(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean("auto_on_launch", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 是否额外输出侧车 `.lrc` 文件。**默认关** —— 歌词已经内嵌在音频标签里
     * （FLAC `LYRICS` / M4A `©lyr`），再写一份同名 `.lrc` 只会让目录多一倍文件。
     */
    public static boolean lrcSidecar(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getBoolean("lrc_sidecar", false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setLrcSidecar(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean("lrc_sidecar", on).apply();
        } catch (Throwable ignore) { }
    }

    public static void setAutoOnLaunch(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean("auto_on_launch", on).apply();
        } catch (Throwable ignore) { }
    }

    /** 独立模式的节流：距上次不足 AUTO_INTERVAL_MS 返回 false */
    public static boolean dueForAutoPrefs(Context ctx) {
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            long last = p.getLong("last_auto", 0L);
            long now = System.currentTimeMillis();
            if (now - last < AUTO_INTERVAL_MS) return false;
            p.edit().putLong("last_auto", now).apply();
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 距下次自动检查还有多久（给界面显示） */
    public static long nextAutoIn(Context ctx) {
        try {
            long last = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .getLong("last_auto", 0L);
            long left = AUTO_INTERVAL_MS - (System.currentTimeMillis() - last);
            return left > 0 ? left : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void resetAutoThrottle(Context ctx) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putLong("last_auto", 0L).apply();
        } catch (Throwable ignore) { }
    }

    /** 重置节流（用户手动点过导出后，自动检查不必再等） */
    public static void touchLastRun(File hostDataDir) {
        try {
            Index.writeAll(new File(new File(hostDataDir, KeyVault.DIR), "last.json"),
                    String.valueOf(System.currentTimeMillis()));
        } catch (Exception ignore) { }
    }

    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    public static String baseName(String path) {
        if (path == null) return "-";
        int i = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return i >= 0 ? path.substring(i + 1) : path;
    }

    public static Set<String> md5Set(List<Item> items) {
        java.util.HashSet<String> s = new java.util.HashSet<String>();
        for (Item it : items) s.add(it.md5);
        return s;
    }
}
