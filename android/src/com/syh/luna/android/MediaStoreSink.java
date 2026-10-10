package com.syh.luna.android;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.syh.luna.core.Pipeline;

/**
 * 输出到 MediaStore：
 *   音频(.flac/.m4a) -> Music/汽水导出/   （会进系统媒体库）
 *   歌词(.lrc)       -> Music/汽水导出/   （走 Downloads 集合，可在「下载」里看到）
 *
 * 落盘失败时退回应用自己的外部私有目录，保证不丢数据。
 */
public final class MediaStoreSink implements Pipeline.Sink {

    public final String relPath;         // 例如 Music/汽水导出
    private final Context ctx;
    private final ContentResolver cr;
    private final java.io.File fallbackDir;
    public int viaMediaStore, viaFallback, failed, viaDirect, viaDownloads;
    /** 真正作为音频入库的条数（不含报告/日志） */
    public int viaAudio;

    public MediaStoreSink(Context ctx, String relPath) {
        this.ctx = ctx;
        this.cr = ctx.getContentResolver();
        this.relPath = relPath;
        java.io.File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        this.fallbackDir = new java.io.File(base, "out");
        this.fallbackDir.mkdirs();
    }

    public String put(String fileName, byte[] data) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        // ⚠️ .lrc 绝不能用 text/plain：MediaStore 的 ensureFileExtension 会按 MIME 的
        //    标准扩展名补齐，text/plain → .txt，于是 `xx.lrc` 落盘变成 `xx.lrc.txt`。
        //    octet-stream 没有标准扩展名，原样保留。
        if (lower.endsWith(".lrc")) return putFile(fileName, data, "application/octet-stream", true);
        if (lower.endsWith(".flac")) return putAudio(fileName, data, "audio/flac");
        if (lower.endsWith(".m4a")) return putAudio(fileName, data, "audio/mp4");
        // 报告/日志：固定放 Download/<leaf>/（共享存储里非媒体文件的稳定可写位置）
        return putFile(fileName, data, "application/octet-stream", false);
    }

    /** 是否已授予「所有文件访问权限」——有它就能直写 /sdcard/Music/… 并与宿主行为一致 */
    public static boolean hasAllFiles(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return android.os.Environment.isExternalStorageManager();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跳到「所有文件访问权限」设置页 */
    public static void requestAllFiles(Context c) {
        try {
            android.content.Intent i;
            if (Build.VERSION.SDK_INT >= 30) {
                i = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + c.getPackageName()));
            } else {
                i = new android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + c.getPackageName()));
            }
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            Logx.i("请求所有文件访问权限失败: " + t);
        }
    }

    private String putAudio(String fileName, byte[] data, String mime) {
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        v.put(MediaStore.Audio.Media.IS_MUSIC, 1);
        if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        String r = write(collection, v, fileName, data, relPath);
        if (r != null && r.startsWith("content:")) viaAudio++;
        return r;
    }

    /**
     * 非音频文件。
     * Android 11+ 对「共享存储里的非媒体文件」只允许放在 Download/ 之下，
     * 所以 Downloads 集合的 RELATIVE_PATH 必须用 Download/xxx，不能用 Music/xxx。
     *
     * allowDirect=true（.lrc）：先试直接写在歌曲旁边（root / 有全盘权限时可行），
     *                          失败才退到 Download/。
     * allowDirect=false（报告/日志）：直接走 Download/，行为稳定可预期。
     */
    private String putFile(String fileName, byte[] data, String mime, boolean allowDirect) {
        // ① 直接写目标目录
        if (allowDirect) {
            try {
                java.io.File direct = new java.io.File("/sdcard/" + relPath, fileName);
                java.io.File dir = direct.getParentFile();
                if (dir != null && (dir.isDirectory() || dir.mkdirs())) {
                    java.io.OutputStream o = new java.io.FileOutputStream(direct);
                    try { o.write(data); } finally { o.close(); }
                    viaDirect++;
                    return direct.getAbsolutePath();
                }
            } catch (Throwable ignore) { }
        }

        // ② MediaStore.Downloads（RELATIVE_PATH 必须以 Download/ 开头）
        String dlPath = "Download/" + leaf(relPath);
        try {
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, dlPath);
            if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            String r = write(collection, v, fileName, data, dlPath);
            if (r != null && r.startsWith("content:")) { viaDownloads++; return r; }
        } catch (Throwable ignore) { }

        // ③ 应用外部私有目录
        try {
            java.io.File f = new java.io.File(fallbackDir, fileName);
            java.io.OutputStream o = new java.io.FileOutputStream(f);
            try { o.write(data); } finally { o.close(); }
            viaFallback++;
            return f.getAbsolutePath();
        } catch (Throwable t) {
            failed++;
            return null;
        }
    }

    static String leaf(String rel) {
        int i = rel.lastIndexOf('/');
        return i >= 0 ? rel.substring(i + 1) : rel;
    }

    /**
     * 分步打点。**这段是排查「导出卡死」时加的**：先前只看到 MediaProvider 打印了
     * insert + Open with FUSE 就再无下文、pending 文件停在 0 字节，无法判断卡在哪一步。
     * 现在每一步都有耗时，logcat 里按 tag LunaExport 过滤即可。
     */
    private static void step(String s) {
        android.util.Log.i("LunaExport", "SINK " + s);
    }

    private String write(Uri collection, ContentValues v, String fileName, byte[] data,
                         String dedupeRelPath) {
        final long t0 = android.os.SystemClock.elapsedRealtime();
        step("start " + fileName + " (" + (data.length / 1048576) + " MB)");
        // 同名同目录先删掉旧条目，避免重复导出堆积重复项。
        // ⚠️ 必须用**实际写入的那个 relPath**（音频是 Music/…，非媒体文件是 Download/…），
        //    否则 LIKE 匹配不上，旧条目留着，MediaStore 就会给出 "xxx (1).flac"。
        // 另外要连 "xxx (1)" 这种被 MediaStore 自动改名过的旧条目一起清掉，
        //    否则重名冲突时系统会继续累加 (2)、(3)…（跨 uid 的条目需要「所有文件访问」才删得掉）。
        try {
            cr.delete(collection,
                    MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND ("
                            + MediaStore.MediaColumns.DISPLAY_NAME + "=? OR "
                            + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?)",
                    new String[]{dedupeRelPath + "%", fileName, fileName + " (%)"});
        } catch (Throwable ignore) { }

        Uri uri = null;
        try {
            uri = cr.insert(collection, v);
            step("insert -> " + (uri == null ? "null" : "ok") + "  +"
                    + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
            if (uri != null) {
                long t1 = android.os.SystemClock.elapsedRealtime();
                java.io.OutputStream o = cr.openOutputStream(uri);
                step("openOutputStream -> " + (o == null ? "null" : "ok") + "  +"
                        + (android.os.SystemClock.elapsedRealtime() - t1) + "ms");
                if (o != null) {
                    try {
                        long t2 = android.os.SystemClock.elapsedRealtime();
                        o.write(data);
                        step("write done  +" + (android.os.SystemClock.elapsedRealtime() - t2) + "ms");
                        o.flush();
                        step("flush done  +" + (android.os.SystemClock.elapsedRealtime() - t2) + "ms");
                    } finally {
                        long t3 = android.os.SystemClock.elapsedRealtime();
                        o.close();
                        step("close done  +" + (android.os.SystemClock.elapsedRealtime() - t3) + "ms");
                    }
                    ContentValues done = new ContentValues();
                    if (Build.VERSION.SDK_INT >= 29) done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    long t4 = android.os.SystemClock.elapsedRealtime();
                    if (done.size() > 0) cr.update(uri, done, null, null);
                    step("clear-pending done  +" + (android.os.SystemClock.elapsedRealtime() - t4) + "ms");
                    viaMediaStore++;
                    step("OK total +" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms");
                    return uri.toString();
                }
                cr.delete(uri, null, null);
                uri = null;
            }
        } catch (Throwable t) {
            step("!! 失败于 +" + (android.os.SystemClock.elapsedRealtime() - t0) + "ms : "
                    + t.getClass().getSimpleName() + " · " + t.getMessage());
            if (uri != null) { try { cr.delete(uri, null, null); } catch (Throwable ignore) { } }
        }
        // 退路：应用外部私有目录
        try {
            java.io.File f = new java.io.File(fallbackDir, fileName);
            java.io.OutputStream o = new java.io.FileOutputStream(f);
            try { o.write(data); } finally { o.close(); }
            viaFallback++;
            return f.getAbsolutePath();
        } catch (Throwable t) {
            failed++;
            return null;
        }
    }
}
