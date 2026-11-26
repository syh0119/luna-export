package com.syh.luna.android;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.syh.luna.core.Index;
import com.syh.luna.core.Scanner;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Android 侧的数据采集：把宿主私有目录「流式」喂给 core 的 Scanner。
 * 抽取逻辑本身在 core.Scanner（纯 Java，桌面可验证）。
 *
 * 两条硬约束（都是被真机教训出来的）：
 *   1) **必须流式**。宿主的 databases + NetCacheLoader + keva 有 28 MB+ / 546 个文件，
 *      读成 Java String 是 UTF-16 要翻倍；而 com.luna.music 的 `ABI: 'arm'` 是
 *      **32 位进程**。一次性把所有 blob 留在堆里会把宿主推到 OOM
 *      （实测宿主 RSS 峰值 1.4 GB，最后它自己的图片管线 OOM 崩掉）。
 *   2) **判 sqlite 只读文件头 16 字节**，不要把整个 .db 读进内存再判断 ——
 *      databases 目录本身就有 13.8 MB。
 *
 * v1.1.7 并发与剪枝优化：
 *   · 跳过 -shm / -journal 与主库配套的 -wal（SQLite 打开主库时已自动读取 WAL，原版把 -wal 当文本扫了两遍）
 *   · 跳过已知埋点/特征工程/同步/推送垃圾库，不碰任何曲目/播放/下载库
 *   · 多线程并发解析 + Semaphore(2) 限制同时打开的 SQLite 句柄数，最后合并分片索引
 */
public final class IndexBuilder {

    private IndexBuilder() { }

    /** 单条 blob 的上限：超过这个大小的文本文件对索引没价值，只占内存 */
    private static final int MAX_BLOB = 8 << 20;

    private static final byte[] SQLITE_SIG =
            "SQLite format 3\u0000".getBytes(StandardCharsets.ISO_8859_1);

    private static final String[] DB_BLACKLIST = {
            "tea_agent",
            "feature_engineering",
            "performance",
            "bd_sync",
            "ai_assistant",
            "fangqing",
            "vod_strategy",
            "account_db",
            "applog",
            "slardar",
            "pitaya",
            "gecko",
            "npth",
            "apm",
            "workdb",
            "google_app_measurement",
            "firebase",
            "imsdk",
            "ttnet",
            "push"
    };

    private static final class Candidate {
        final File file;
        final long length;

        Candidate(File file) {
            this.file = file;
            this.length = file.length();
        }
    }

    public static Index build(File root, boolean staged) {
        final List<Candidate> candidates = new ArrayList<Candidate>();
        for (String sub : Dirs.SUB) {
            if ("files/music".equals(sub)) continue;      // .mdl 本体不参与索引
            boolean inDatabases = "databases".equals(sub);
            collectCandidates(sub(root, sub, staged), inDatabases, candidates);
        }

        Collections.sort(candidates, new Comparator<Candidate>() {
            public int compare(Candidate a, Candidate b) {
                return Long.compare(b.length, a.length);
            }
        });

        Index merged = new Index();
        final Semaphore sqlSem = new Semaphore(2);
        final int total = candidates.size();

        if (total == 1) {
            Scanner s = new Scanner();
            scanFile(candidates.get(0).file, s, sqlSem);
            merged.merge(s.finish());
        } else if (total > 1) {
            int threads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors()));
            final AtomicInteger cursor = new AtomicInteger(0);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Index>> futures = new ArrayList<Future<Index>>(threads);
                for (int i = 0; i < threads; i++) {
                    futures.add(pool.submit(new Callable<Index>() {
                        public Index call() {
                            Scanner s = new Scanner();
                            while (!Thread.currentThread().isInterrupted()) {
                                int idx = cursor.getAndIncrement();
                                if (idx >= total) break;
                                scanFile(candidates.get(idx).file, s, sqlSem);
                            }
                            return s.finish();
                        }
                    }));
                }
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        merged.merge(futures.get(i).get());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Throwable ignore) { }
                }
            } finally {
                pool.shutdownNow();
            }
        }

        // 自己在 hook 里攒下的响应体。
        // 宿主的元数据只在「最近浏览/播放过」的歌上留缓存，且 sqlite 的 WAL 会失效；
        // 我们这份是长期累积、不会过期的补充，专门补那些查不到歌名的曲目。
        // ⚠️ 暂存区是扁平命名，仓库位置要用 fileStaged，否则 root 模式读不到。
        File bf = staged ? BlobStore.fileStaged(root) : BlobStore.file(root);
        List<String> blobs = BlobStore.load(bf);
        if (!blobs.isEmpty()) {
            Scanner bs = new Scanner();
            for (String b : blobs) bs.feed(b);
            merged.merge(bs.finish());
        }
        return merged;
    }

    public static File sub(File root, String s, boolean staged) {
        return staged ? new File(root, s.replace('/', '_')) : new File(root, s);
    }

    // ------------------------------------------------------------------ 收集与过滤

    private static void collectCandidates(File f, boolean inDatabases, List<Candidate> out) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) {
                for (File c : cs) collectCandidates(c, inDatabases, out);
            }
            return;
        }
        if (f.isFile() && !shouldSkip(f, inDatabases)) {
            out.add(new Candidate(f));
        }
    }

    private static boolean shouldSkip(File f, boolean inDatabases) {
        String name = f.getName();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith("-shm") || lower.endsWith("-journal")) return true;
        if (lower.endsWith("-wal")) {
            File parent = f.getParentFile();
            String mainName = name.substring(0, name.length() - 4);
            File mainFile = parent != null ? new File(parent, mainName) : new File(mainName);
            if (mainFile.isFile()) return true;
        }
        if (inDatabases) {
            for (int i = 0; i < DB_BLACKLIST.length; i++) {
                if (lower.contains(DB_BLACKLIST[i])) return true;
            }
        }
        return false;
    }

    private static void scanFile(File f, Scanner s, Semaphore sqlSem) {
        if (isSqliteFile(f)) {
            boolean acquired = false;
            try {
                sqlSem.acquire();
                acquired = true;
                dumpSqliteInto(f, s);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Throwable ignore) {
            } finally {
                if (acquired) sqlSem.release();
            }
            return;
        }
        long len = f.length();
        if (len <= 0 || len > MAX_BLOB) return;
        byte[] d;
        try {
            d = Index.readBytes(f);
        } catch (Exception e) {
            return;
        }
        if (d.length == 0 || d.length > MAX_BLOB) return;
        s.feed(new String(d, StandardCharsets.ISO_8859_1));
    }

    /** 只读头 16 字节判断是不是 sqlite —— 别把整个 .db 读进来 */
    private static boolean isSqliteFile(File f) {
        try {
            if (f.length() < SQLITE_SIG.length) return false;
            InputStream in = new FileInputStream(f);
            try {
                byte[] h = new byte[SQLITE_SIG.length];
                int n = 0;
                while (n < h.length) {
                    int r = in.read(h, n, h.length - n);
                    if (r <= 0) break;
                    n += r;
                }
                if (n < h.length) return false;
                for (int i = 0; i < h.length; i++) if (h[i] != SQLITE_SIG[i]) return false;
                return true;
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** 兼容旧签名（给可能的外部调用） */
    public static boolean isSqlite(byte[] d) {
        if (d == null || d.length < SQLITE_SIG.length) return false;
        for (int i = 0; i < SQLITE_SIG.length; i++) if (d[i] != SQLITE_SIG[i]) return false;
        return true;
    }

    /**
     * 抓出所有表的所有 TEXT / BLOB 列（>20 字符），逐条喂给 Scanner。
     *
     * ⚠️ WAL 感知打开：只有配套 -wal 存在且非空时才用读写模式（读取 WAL 帧），
     *    否则直接用无锁只读模式打开，避免多库并发抢锁或生成额外 journal。
     */
    private static void dumpSqliteInto(File f, Scanner s) {
        String path = f.getAbsolutePath();
        File wal = new File(path + "-wal");
        boolean hasWal = wal.isFile() && wal.length() > 0;
        SQLiteDatabase db = null;
        try {
            if (hasWal) {
                try {
                    db = SQLiteDatabase.openDatabase(path, null,
                            SQLiteDatabase.OPEN_READWRITE | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                } catch (Throwable t) {
                    db = SQLiteDatabase.openDatabase(path, null,
                            SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                }
            } else {
                db = SQLiteDatabase.openDatabase(path, null,
                        SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            }
        } catch (Throwable t2) {
            return;
        }
        try {
            java.util.List<String> tables = new java.util.ArrayList<String>();
            Cursor tc = db.rawQuery("select name from sqlite_master where type='table'", null);
            while (tc.moveToNext()) tables.add(tc.getString(0));
            tc.close();
            for (String tb : tables) {
                Cursor c;
                try {
                    c = db.rawQuery("select * from " + tb, null);
                } catch (Throwable t) {
                    continue;
                }
                try {
                    int n = c.getColumnCount();
                    while (c.moveToNext()) {
                        for (int i = 0; i < n; i++) {
                            int ty = c.getType(i);
                            if (ty == Cursor.FIELD_TYPE_STRING) {
                                String v = c.getString(i);
                                if (v != null && v.length() > 20) s.feed(v);
                            } else if (ty == Cursor.FIELD_TYPE_BLOB) {
                                // ⚠️ BLOB 列也必须扫。
                                //    宿主的播放历史 / 下载记录把整段 JSON 存在 BLOB 里
                                //    （grep 这些 .db 时会报 "Binary file matches"），
                                //    只读 TEXT 列的话这些文件里最新鲜的元数据一条都拿不到 ——
                                //    表现就是「有密钥、能导出，但歌名查不出来」。
                                //    按 ISO-8859-1 还原成 String，交给 Scanner 里的
                                //    Json.parsePrefix 逐段抠 JSON（和 keva/sgv 一个套路）。
                                byte[] b = c.getBlob(i);
                                if (b != null && b.length > 20) {
                                    s.feed(new String(b, java.nio.charset.StandardCharsets.ISO_8859_1));
                                }
                            }
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignore) {
        } finally {
            try { db.close(); } catch (Throwable ignore) { }
        }
    }
}
