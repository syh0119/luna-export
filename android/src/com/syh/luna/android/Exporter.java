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
        public final String fileName;
        public final String md5;
        public final boolean fromDownload;
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
        public long mtimeMs;
        public volatile boolean selected;

        Item(File f, String md5) {
            this(f, "music/" + (f == null ? (md5 + ".mdl") : f.getName()), md5,
                    f == null ? 0L : f.length(), f == null ? 0L : f.lastModified());
        }

        Item(File f, String fileName, String md5, long size, long mtimeMs) {
            this.file = f;
            this.fileName = fileName;
            this.md5 = md5;
            this.fromDownload = fileName != null && fileName.startsWith("download/");
            this.size = size;
            this.mtimeMs = mtimeMs;
        }

        public boolean exportable() {
            if (!complete) return false;
            if ("plain".equals(kind) || "mp3".equals(kind)) return true;
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
            if (fromDownload) sb.append("离线下载");
            if (artist != null && !artist.isEmpty()) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(artist);
            }
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
            if ("mp3".equals(kind)) return "MP3";
            if ("plain".equals(kind)) return "M4A";
            if ("video".equals(kind)) return "MV";
            return "?";
        }

        public String state() {
            if ("video".equals(kind) || "junk".equals(kind)) return "跳过";
            if (!complete) return "残片";
            if (!"plain".equals(kind) && !"mp3".equals(kind) && !hasKey) return "缺密钥";
            if (exported && !changed) return "已导出";
            if (!exported && !changed) return "待导出";
            return "可导出";
        }

        public String sizeText() {
            return formatSize(size);
        }

        public static String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
            return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
        }

        public String timeText() {
            if (mtimeMs <= 0) return "";
            return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(mtimeMs));
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
        /** 拿回 Context：导出结束要清暂存区，二次导出还要能重新暂存 */
        public Context ctx;
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

        public List<Item> selectedAll() {
            List<Item> out = new ArrayList<Item>();
            for (Item it : items) if (it.selected) out.add(it);
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
        return sigOf(f.length(), f.lastModified());
    }

    static String sigOf(long size, long mtimeMs) {
        return size + ":" + ((mtimeMs / 1000L) * 1000L);
    }

    private static String md5OfName(String n) {
        return n.toLowerCase(Locale.ROOT).endsWith(".mdl")
                ? n.substring(0, n.length() - 4) : n;
    }

    static String cacheKeyForRel(String rel) {
        if (rel == null) return "";
        if (rel.startsWith("music/")) {
            return md5OfName(rel.substring(6));
        }
        if (rel.startsWith("download/")) {
            return "dl:" + rel.substring(9);
        }
        return md5OfName(rel);
    }

    private static List<File> listDownloadFiles(File dlRoot) {
        List<File> out = new ArrayList<File>();
        File[] uids = dlRoot == null ? null : dlRoot.listFiles();
        if (uids == null) return out;
        for (File u : uids) {
            if (!u.isDirectory()) continue;
            String un = u.getName();
            if (un.isEmpty() || !un.matches("[0-9]+")) continue;
            File[] fs = u.listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                if (f.isFile() && f.getName().matches("[0-9A-Za-z_-]+")) out.add(f);
            }
        }
        return out;
    }

    private static List<Item> deduplicateItems(List<Item> scanned) {
        Map<String, Item> best = new LinkedHashMap<String, Item>();
        for (Item it : scanned) {
            if (!it.complete || it.kid == null || it.kid.isEmpty()) continue;
            String k = it.kid + ":" + it.size;
            Item prev = best.get(k);
            if (prev == null) {
                best.put(k, it);
            } else if (!prev.fromDownload && it.fromDownload) {
                it.exported = it.exported || prev.exported;
                best.put(k, it);
            } else {
                prev.exported = prev.exported || it.exported;
            }
        }
        java.util.Set<Item> keep = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Item, Boolean>());
        keep.addAll(best.values());
        List<Item> out = new ArrayList<Item>(scanned.size());
        for (Item it : scanned) {
            if (!it.complete || it.kid == null || it.kid.isEmpty() || keep.contains(it)) {
                out.add(it);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 准备
    public static Session prepare(Context ctx, boolean rootMode, Log log, Progress prog) {
        long t0 = System.currentTimeMillis();
        Session s = new Session();
        s.ctx = ctx;

        List<Dirs.MdlEntry> stagedEntries = null;
        List<File> files = null;
        List<String> localRels = null;
        if (rootMode) {
            if (prog != null) prog.on(0, 0, "检查 root 权限 …");
            if (!Dirs.hasRoot()) {
                s.error = "未获得 root 授权（su 不可用）";
                if (log != null) log.line("✗ " + s.error);
                return s;
            }
            if (log != null) log.line("su 暂存宿主元数据 …");
            if (prog != null) prog.on(0, 0, "读取宿主元数据（不搬运音频本体）…");
            try {
                s.root = Dirs.stage(ctx);
            } catch (Exception e) {
                s.error = "su 暂存失败: " + e.getMessage();
                if (log != null) {
                    log.line("✗ " + s.error);
                    if (Dirs.lastTarError != null && Dirs.lastTarError.length() > 0)
                        log.line("  tar 路径: " + Dirs.lastTarError);
                    if (Dirs.lastSuOutput != null && Dirs.lastSuOutput.length() > 0)
                        log.line("  su 输出: " + Dirs.lastSuOutput.replace("\n", " | "));
                    if (Dirs.lastBase != null && Dirs.lastBase.length() > 0)
                        log.line("  数据源: " + Dirs.lastBase
                                + "  .mdl 数量: " + Dirs.lastCount);
                    if (Dirs.lastStageList != null && Dirs.lastStageList.length() > 0)
                        log.line("  打包清单: " + Dirs.lastStageList);
                    if (Dirs.lastTarFiles > 0)
                        log.line("  已解包: " + Dirs.lastTarFiles + " 个文件 / "
                                + Dirs.lastTarBytes + " 字节");
                }
                return s;
            }
            s.staged = true;
            s.base = Dirs.lastBase;
            stagedEntries = new ArrayList<Dirs.MdlEntry>(Dirs.lastMdlList);
            if (stagedEntries.isEmpty()) {
                Dirs.cleanup(s.root);
                s.error = "没找到缓存或离线下载曲目（root 模式请确认 su 已授权）";
                if (log != null) log.line("✗ " + s.error);
                return s;
            }
        } else {
            s.root = Dirs.inProcess(ctx);
            s.staged = false;
            files = new ArrayList<File>();
            localRels = new ArrayList<String>();
            for (File f : Pipeline.listMdl(IndexBuilder.sub(s.root, "files/music", false))) {
                files.add(f);
                localRels.add("music/" + f.getName());
            }
            for (File f : listDownloadFiles(IndexBuilder.sub(s.root, "files/download", false))) {
                files.add(f);
                localRels.add("download/" + f.getParentFile().getName() + "/" + f.getName());
            }
            if (files.isEmpty()) {
                s.error = "没找到 .mdl（LSPosed 模式请确认作用域已勾选并重启过宿主）";
                if (log != null) log.line("✗ " + s.error);
                return s;
            }
        }

        int totalCount = rootMode ? stagedEntries.size() : files.size();
        s.stateFile = stateFileFor(ctx, rootMode, s.root);
        s.state = State.load(s.stateFile);
        s.wantLrc = lrcSidecar(ctx);        // 默认 false：歌词已内嵌，不额外丢 .lrc

        if (prog != null) prog.on(0, 0, "解析曲目与密钥索引（" + totalCount + " 个文件）…");
        Index idx = IndexBuilder.build(s.root, s.staged);
        File vault = rootMode ? KeyVault.inStage(s.root) : KeyVault.inHost(s.root);
        s.vaultKeys = KeyVault.apply(idx, vault);
        s.idx = idx;
        s.indexInfo = "密钥 " + idx.kid2spade.size()
                + " · 曲目库 " + idx.vidMeta.size()
                + " · md5映射 " + idx.md5toVid.size();
        // 索引建完立即清理 ~38 MB 临时元数据目录，不留宿主机密副本
        if (s.staged) {
            Dirs.cleanup(s.root);
        }

        s.covers = new CoverStore(ctx);
        int i = 0, probed = 0;

        if (rootMode) {
            List<String> needHeads = new ArrayList<String>();
            for (Dirs.MdlEntry e : stagedEntries) {
                String md5 = cacheKeyForRel(e.name);
                String sig = sigOf(e.size, e.mtimeSec * 1000L);
                FileRec cached = s.state.files.get(md5);
                if (cached == null || !sig.equals(cached.sig)) {
                    needHeads.add(e.name);
                }
            }
            if (!needHeads.isEmpty() && prog != null) {
                prog.on(0, 0, "读取 " + needHeads.size() + " 首新曲目头部 …");
            }
            Map<String, byte[]> heads = needHeads.isEmpty()
                    ? java.util.Collections.<String, byte[]>emptyMap()
                    : Dirs.readHostMdlHeads(s.base, needHeads, StageCmd.HEAD_BYTES);

            for (Dirs.MdlEntry e : stagedEntries) {
                String md5 = cacheKeyForRel(e.name);
                Item it = new Item(null, e.name, md5, e.size, e.mtimeSec * 1000L);
                String sig = sigOf(e.size, e.mtimeSec * 1000L);
                FileRec cached = s.state.files.get(md5);
                if (cached != null && sig.equals(cached.sig)) {
                    applyCachedRec(it, cached, idx);
                } else {
                    it.changed = true;
                    probed++;
                    try {
                        byte[] head = heads.get(e.name);
                        if (head == null && e.size > 0) {
                            head = Dirs.readHostMdl(s.base, e.name, e.size);
                        } else if (head != null && !Pipeline.hasCompleteMp4Head(head) && e.size > head.length) {
                            head = Dirs.readHostMdl(s.base, e.name, e.size);
                        }
                        Pipeline.Result r = Pipeline.processBytes(md5, head, e.size,
                                null, idx, s.covers, true, true, true);
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
                    saveItemRec(s.state, it, sig);
                }
                s.items.add(it);
                i++;
                if (prog != null) prog.on(i, totalCount, "扫描 " + i + "/" + totalCount);
            }
        } else {
            for (int fi = 0; fi < files.size(); fi++) {
                File f = files.get(fi);
                String rel = localRels.get(fi);
                String md5 = cacheKeyForRel(rel);
                Item it = new Item(f, rel, md5, f.length(), f.lastModified());
                String sig = sigOf(f);
                FileRec cached = s.state.files.get(md5);
                if (cached != null && sig.equals(cached.sig)) {
                    applyCachedRec(it, cached, idx);
                } else {
                    it.changed = true;
                    probed++;
                    try {
                        byte[] raw = Index.readBytes(f);
                        Pipeline.Result r = Pipeline.processBytes(md5, raw, raw.length,
                                null, idx, s.covers, true, true, true);
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
                    saveItemRec(s.state, it, sig);
                }
                s.items.add(it);
                i++;
                if (prog != null) prog.on(i, totalCount, "扫描 " + i + "/" + totalCount);
            }
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

        List<Item> deduped = deduplicateItems(s.items);
        s.items.clear();
        s.items.addAll(deduped);

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

    private static void applyCachedRec(Item it, FileRec cached, Index idx) {
        it.kind = cached.kind == null ? "" : cached.kind;
        it.title = cached.title;
        it.artist = cached.artist;
        it.album = cached.album;
        it.complete = cached.complete;
        it.exported = cached.exported;
        it.changed = false;
        it.kid = cached.kid;
        if (it.kid != null && !it.kid.isEmpty()) {
            boolean now = idx.kid2spade.containsKey(it.kid);
            if (now != cached.hasKey) cached.hasKey = now;
            it.hasKey = now;
        } else {
            it.hasKey = cached.hasKey;
        }
        if (it.title == null || it.title.isEmpty()) {
            Index.MetaHit mh = idx.fullMeta(it.md5, null);
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
    }

    private static void saveItemRec(State state, Item it, String sig) {
        FileRec rec = state.files.get(it.md5);
        if (rec == null) { rec = new FileRec(); state.files.put(it.md5, rec); }
        rec.sig = sig;
        rec.kind = it.kind;
        rec.title = it.title;
        rec.artist = it.artist;
        rec.album = it.album;
        rec.kid = it.kid;
        rec.complete = it.complete;
        rec.hasKey = it.hasKey;
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

        if (s.staged && (s.base == null || s.base.isEmpty())) {
            st.failed = chosen.size();
            if (log != null) log.line("✗ 宿主数据源路径无效，请先重新扫描");
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
                Pipeline.Result r;
                if (s.staged) {
                    byte[] raw = Dirs.readHostMdl(s.base, it.fileName, it.size);
                    r = Pipeline.processBytes(it.md5, raw, it.size > 0 ? it.size : raw.length,
                            s.sink, s.idx, s.covers, false, true, s.wantLrc);
                } else {
                    byte[] raw = Index.readBytes(it.file);
                    r = Pipeline.processBytes(it.md5, raw, raw.length,
                            s.sink, s.idx, s.covers, false, true, s.wantLrc);
                }
                if (!first) report.append(",\n");
                first = false;
                report.append(r.toJson());
                FileRec rec = s.state.files.get(it.md5);
                if (rec == null) { rec = new FileRec(); s.state.files.put(it.md5, rec); }
                rec.sig = sigOf(it.size, it.mtimeMs);
                rec.kind = it.kind;
                rec.title = it.title;
                rec.artist = it.artist;
                rec.album = it.album;
                rec.kid = it.kid;
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
                rec.sig = sigOf(it.size, it.mtimeMs);
                rec.kind = it.kind;
                rec.title = it.title;
                rec.artist = it.artist;
                rec.album = it.album;
                rec.kid = it.kid;
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

        // ---- 收尾：清掉暂存区
        // 暂存区是宿主私有数据的**完整副本**（.mdl / sqlite / keva / 缓存 / 密钥仓），
        // 即使用完了也不该留在设备上。暂存在本 App 私有目录里、属主已 chown 回本 App，
        // 所以纯 Java 递归删即可，不需要 root。
        if (s.staged) {
            Dirs.cleanup(s.root);
            if (log != null) log.line("已清理暂存区（宿主私有数据副本不留存）");
        }
        return st;
    }

    // ------------------------------------------------------------------ 删除
    public static final class DeleteResult {
        public int requested, cacheOk, cacheFail, exportedDeleted;
        public long freedBytes;
    }

    public static DeleteResult deleteSelected(Session s, List<Item> targets,
                                              boolean alsoDeleteExported, Log log, Progress prog) {
        DeleteResult dr = new DeleteResult();
        if (s == null || targets == null || targets.isEmpty()) return dr;
        dr.requested = targets.size();

        java.util.Set<String> deletedNames = new java.util.LinkedHashSet<String>();
        if (s.staged) {
            if (s.base == null || s.base.isEmpty()) {
                dr.cacheFail = targets.size();
                if (log != null) log.line("✗ 宿主数据源路径无效，无法删除");
                return dr;
            }
            List<String> names = new ArrayList<String>(targets.size());
            for (Item it : targets) names.add(it.fileName);
            if (prog != null) prog.on(0, 0, "正在删除 " + names.size() + " 个缓存文件 …");
            deletedNames.addAll(Dirs.deleteHostMdls(s.base, names));
        } else {
            int idx = 0;
            for (Item it : targets) {
                idx++;
                if (prog != null) prog.on(idx, targets.size(), "删除 " + it.display());
                boolean ok = false;
                if (it.file != null) {
                    ok = !it.file.exists() || it.file.delete();
                    String fn = it.file.getName();
                    if (fn.toLowerCase(Locale.ROOT).endsWith(".mdl")) {
                        File conf = new File(it.file.getParentFile(),
                                fn.substring(0, fn.length() - 4) + ".mdlnodeconf");
                        if (conf.exists()) conf.delete();
                    }
                }
                if (ok) deletedNames.add(it.fileName);
            }
        }

        List<Item> removed = new ArrayList<Item>();
        for (Item it : targets) {
            if (deletedNames.contains(it.fileName)) {
                dr.cacheOk++;
                dr.freedBytes += it.size;
                removed.add(it);
                if (s.state != null) s.state.files.remove(it.md5);
                if (alsoDeleteExported && s.ctx != null) {
                    if (deleteExportedOutput(s.ctx, it)) dr.exportedDeleted++;
                }
                if (log != null) log.line("🗑 已删除: " + it.display() + " (" + it.sizeText() + ")");
            } else {
                dr.cacheFail++;
                if (log != null) log.line("✗ 删除失败: " + it.display());
            }
        }
        if (!removed.isEmpty()) {
            s.items.removeAll(removed);
            if (s.stateFile != null && s.state != null) s.state.save(s.stateFile);
        }
        if (log != null) {
            log.line("删除完成：成功 " + dr.cacheOk + " · 失败 " + dr.cacheFail
                    + " · 释放 " + Item.formatSize(dr.freedBytes)
                    + (alsoDeleteExported ? " · 同时删已导出 " + dr.exportedDeleted + " 首" : ""));
        }
        return dr;
    }

    private static boolean deleteExportedOutput(Context ctx, Item it) {
        String outName = Pipeline.outName(it.artist, it.title, it.md5, it.kind);
        boolean any = false;
        try {
            File f = new File("/sdcard/" + REL, outName);
            if (f.exists() && f.delete()) any = true;
            int dot = outName.lastIndexOf('.');
            if (dot > 0) {
                String lrcName = outName.substring(0, dot) + ".lrc";
                File lf = new File("/sdcard/" + REL, lrcName);
                if (lf.exists()) lf.delete();
                File dlLrc = new File("/sdcard/Download/" + MediaStoreSink.leaf(REL), lrcName);
                if (dlLrc.exists()) dlLrc.delete();
            }
        } catch (Throwable ignore) { }
        try {
            int rows = ctx.getContentResolver().delete(
                    android.provider.MediaStore.Audio.Media.getContentUri(
                            android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                            + android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{REL + "%", outName});
            if (rows > 0) any = true;
        } catch (Throwable ignore) { }
        return any;
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
    public static final int SORT_ALPHA = 0, SORT_SIZE = 1, SORT_TIME = 2;

    public static int sortMode(Context ctx) {
        try {
            int m = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("sort_mode", SORT_ALPHA);
            return (m >= SORT_ALPHA && m <= SORT_TIME) ? m : SORT_ALPHA;
        } catch (Throwable t) {
            return SORT_ALPHA;
        }
    }

    public static void setSortMode(Context ctx, int mode) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putInt("sort_mode", mode).apply();
        } catch (Throwable ignore) { }
    }

    public static boolean sortAsc(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("sort_asc", true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setSortAsc(Context ctx, boolean asc) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putBoolean("sort_asc", asc).apply();
        } catch (Throwable ignore) { }
    }

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
