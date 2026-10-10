package com.syh.luna.android;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.syh.luna.core.Index;
import com.syh.luna.core.Scanner;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
 */
public final class IndexBuilder {

    private IndexBuilder() { }

    /** 单条 blob 的上限：超过这个大小的文本文件对索引没价值，只占内存 */
    private static final int MAX_BLOB = 8 << 20;

    private static final byte[] SQLITE_SIG =
            "SQLite format 3\u0000".getBytes(StandardCharsets.ISO_8859_1);

    public static Index build(File root, boolean staged) {
        Scanner s = new Scanner();
        for (String sub : Dirs.SUB) {
            if ("files/music".equals(sub)) continue;      // .mdl 本体不参与索引
            walkInto(sub(root, sub, staged), s);
        }
        // 自己在 hook 里攒下的响应体。
        // 宿主的元数据只在「最近浏览/播放过」的歌上留缓存，且 sqlite 的 WAL 会失效；
        // 我们这份是长期累积、不会过期的补充，专门补那些查不到歌名的曲目。
        for (String b : BlobStore.load(BlobStore.file(root))) s.feed(b);
        return s.finish();
    }

    public static File sub(File root, String s, boolean staged) {
        return staged ? new File(root, s.replace('/', '_')) : new File(root, s);
    }

    // ------------------------------------------------------------------ 遍历

    private static void walkInto(File f, Scanner s) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) walkInto(c, s);
            return;
        }
        if (isSqliteFile(f)) {
            dumpSqliteInto(f, s);
            return;
        }
        byte[] d;
        try {
            d = Index.readBytes(f);
        } catch (Exception e) {
            return;
        }
        if (d.length == 0 || d.length > MAX_BLOB) return;
        s.feed(new String(d, StandardCharsets.ISO_8859_1));
        // d 到此即可回收，峰值 = 单个文件
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
     * 抓出所有表的所有 TEXT 列（>20 字符），逐条喂给 Scanner。
     *
     * ⚠️ 读写模式打开：WAL 里的新数据才会被读出来（与 python 行为一致）。
     */
    private static void dumpSqliteInto(File f, Scanner s) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
        } catch (Throwable t) {
            try {
                db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                        SQLiteDatabase.OPEN_READONLY);
            } catch (Throwable t2) {
                return;
            }
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
