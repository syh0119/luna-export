package com.syh.luna.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.syh.luna.core.Index;
import com.syh.luna.core.Pipeline;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 封面：下载 -> 按最大边缩到 <=1200px 的 JPEG(q88) -> 磁盘缓存。
 * 与桌面版（Pillow）不追求字节一致，但产物等价：同为 <=1200px 的 JPEG。
 */
public final class CoverStore implements Pipeline.CoverProvider {

    public static final int MAX_PX = 1200;
    public static final int QUALITY = 88;

    private final File dir;
    private final Map<String, byte[]> memo = new HashMap<String, byte[]>();
    public int downloaded, cached, failed;

    public CoverStore(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        this.dir = new File(base, "covers");
        this.dir.mkdirs();
    }

    public byte[] get(Map<String, Object> cov) {
        if (cov == null) return null;
        String url = Index.coverUrl(cov, "image.image");
        if (url == null || url.isEmpty()) return null;
        String key = Index.sha1hex(url.getBytes(StandardCharsets.UTF_8));
        if (key == null) return null;
        if (memo.containsKey(key)) return memo.get(key);

        File f = new File(dir, key + ".jpg");
        byte[] out = null;
        if (f.isFile() && f.length() > 1024) {
            try { out = Index.readBytes(f); cached++; } catch (IOException ignore) { }
        }
        if (out == null) {
            byte[] raw = download(url);
            if (raw != null) {
                byte[] norm = normalize(raw);
                if (norm != null) {
                    try {
                        java.io.OutputStream o = new java.io.FileOutputStream(f);
                        try { o.write(norm); } finally { o.close(); }
                    } catch (IOException ignore) { }
                    out = norm;
                    downloaded++;
                }
            }
            if (out == null) failed++;
        }
        memo.put(key, out);
        return out;
    }

    // ------------------------------------------------------------------ 下载
    static byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            int code = c.getResponseCode();
            if (code != 200) return null;
            byte[] d = Index.readBytes(c.getInputStream(), c.getContentLength());
            if (d.length < 1024) return null;
            boolean jpeg = (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8;
            boolean png = d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
            return (jpeg || png) ? d : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ 归一化
    /** 缩到最大边 <=1200px 的 JPEG；失败返回 null。不会放大；重编码更大时保留原图 */
    static byte[] normalize(byte[] raw) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
            int w = o.outWidth, h = o.outHeight;
            if (w <= 0 || h <= 0) return null;
            int maxDim = Math.max(w, h);
            int sample = 1;
            while (maxDim / (sample * 2) >= MAX_PX) sample *= 2;

            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeByteArray(raw, 0, raw.length, d);
            if (bm == null) return null;

            int cw = bm.getWidth(), ch = bm.getHeight();
            if (Math.max(cw, ch) > MAX_PX) {
                float k = (float) MAX_PX / Math.max(cw, ch);
                Bitmap sc = Bitmap.createScaledBitmap(bm,
                        Math.max(1, Math.round(cw * k)), Math.max(1, Math.round(ch * k)), true);
                if (sc != bm) { bm.recycle(); bm = sc; }
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream(raw.length / 2 + 8192);
            bm.compress(Bitmap.CompressFormat.JPEG, QUALITY, bo);
            bm.recycle();
            byte[] out = bo.toByteArray();
            if (out.length == 0) return null;
            // 已经很小且比原图大，就用原图
            if (out.length >= raw.length && Math.max(w, h) <= MAX_PX) return raw;
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 供诊断用：列出缓存目录里的封面数 */
    public int cachedFiles() {
        File[] fs = dir.listFiles();
        return fs == null ? 0 : fs.length;
    }
}
