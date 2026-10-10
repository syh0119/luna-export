package com.syh.luna.android;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.syh.luna.core.Index;
import com.syh.luna.core.Scanner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Android 侧的数据采集：把宿主私有目录变成「文本 blob 列表」，再交给 core 的 Scanner。
 * 抽取逻辑本身在 core.Scanner（纯 Java，桌面可验证）。
 */
public final class IndexBuilder {

    private IndexBuilder() { }

    public static Index build(File root, boolean staged) {
        return Scanner.scan(collect(root, staged)).index();
    }

    public static File sub(File root, String s, boolean staged) {
        return staged ? new File(root, s.replace('/', '_')) : new File(root, s);
    }

    public static List<String> collect(File root, boolean staged) {
        List<String> blobs = new ArrayList<String>();
        for (String s : Dirs.SUB) {
            if ("files/music".equals(s)) continue;      // .mdl 本体不参与索引
            walk(sub(root, s, staged), blobs);
        }
        // 自己在 hook 里攒下的响应体。
        // 宿主的元数据只在「最近浏览/播放过」的歌上留缓存，且 sqlite 的 WAL 会失效；
        // 我们这份是长期累积、不会过期的补充，专门补那些查不到歌名的曲目。
        blobs.addAll(BlobStore.load(BlobStore.file(root)));
        return blobs;
    }

    private static void walk(File f, List<String> blobs) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) walk(c, blobs);
            return;
        }
        byte[] d;
        try {
            d = Index.readBytes(f);
        } catch (Exception e) {
            return;
        }
        if (isSqlite(d)) dumpSqlite(f, blobs);
        else blobs.add(new String(d, StandardCharsets.ISO_8859_1));
    }

    public static boolean isSqlite(byte[] d) {
        byte[] sig = "SQLite format 3\u0000".getBytes(StandardCharsets.ISO_8859_1);
        if (d.length < sig.length) return false;
        for (int i = 0; i < sig.length; i++) if (d[i] != sig[i]) return false;
        return true;
    }

    /** 抓出所有表的所有 TEXT 列（>20 字符），等价 python sqlite3 那段 */
    private static void dumpSqlite(File f, List<String> blobs) {
        SQLiteDatabase db = null;
        try {
            // 读写模式打开：WAL 里的新数据才会被读出来（与 python 行为一致）
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        } catch (Throwable t) {
            try {
                db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            } catch (Throwable t2) {
                return;
            }
        }
        try {
            List<String> tables = new ArrayList<String>();
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
                            if (c.getType(i) != Cursor.FIELD_TYPE_STRING) continue;
                            String v = c.getString(i);
                            if (v != null && v.length() > 20) blobs.add(v);
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
