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
        if (lower.endsWith(".lrc")) return putFile(fileName, data, "text/plain", true);
        if (lower.endsWith(".flac")) return putAudio(fileName, data, "audio/flac");
        if (lower.endsWith(".m4a")) return putAudio(fileName, data, "audio/mp4");
        // 报告/日志：固定放 Download/<leaf>/（共享存储里非媒体文件的稳定可写位置）
        return putFile(fileName, data, "application/octet-stream", false);
    }

    private String putAudio(String fileName, byte[] data, String mime) {
        Uri collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        v.put(MediaStore.Audio.Media.IS_MUSIC, 1);
        if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        String r = write(collection, v, fileName, data);
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
            String r = write(collection, v, fileName, data);
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

    private String write(Uri collection, ContentValues v, String fileName, byte[] data) {
        // 同名同目录先删掉旧条目，避免重复导出堆积重复项
        try {
            cr.delete(collection,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                            + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?",
                    new String[]{fileName, relPath + "%"});
        } catch (Throwable ignore) { }

        Uri uri = null;
        try {
            uri = cr.insert(collection, v);
            if (uri != null) {
                java.io.OutputStream o = cr.openOutputStream(uri);
                if (o != null) {
                    try { o.write(data); o.flush(); } finally { o.close(); }
                    ContentValues done = new ContentValues();
                    if (Build.VERSION.SDK_INT >= 29) done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    if (done.size() > 0) cr.update(uri, done, null, null);
                    viaMediaStore++;
                    return uri.toString();
                }
                cr.delete(uri, null, null);
                uri = null;
            }
        } catch (Throwable t) {
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
