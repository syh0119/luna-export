package com.syh.luna.android;

import com.syh.luna.core.Index;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 自建「响应体仓库」。
 *
 * 为什么需要它：宿主只在**最近浏览/播放过**的歌上留元数据缓存，且 sqlite 的 WAL
 * 会不断失效（实测 weak_net 里的 md5→vid 映射是几小时前的死帧，读不出来）。
 * 于是有些歌名永远是空的 —— 列表里只能显示 md5。
 *
 * 解法跟「被动抓密钥」是同一套：我们本来就在宿主进程里、已经挂了 OkHttp 的文本出口，
 * 顺手把**含曲目信息的响应体**追加到这个仓库；下次建索引时把它当额外 blob 一起扫。
 * 用户正常浏览/播放，元数据就自己补上了。
 *
 * 存储格式与 `luna_decrypt --blobs` 一致，便于直接复用：
 *   「8 位十六进制长度 \n <原始 UTF-8 字节> \n」 重复。
 */
public final class BlobStore {

    private BlobStore() { }

    /** 仓库上限：超过就把老的丢掉（按整体截断，保留尾部一半） */
    private static final long MAX = 32L << 20;
    /** 单条响应体的上限（超大响应没意义，还拖慢扫描） */
    private static final int MAX_ONE = 2 << 20;

    public static File file(File hostRoot) {
        return new File(new File(hostRoot, KeyVault.DIR), "blobs.bin");
    }

    /** 追加一条。只接受可能含曲目/映射信息的文本，避免把整个仓库塞满垃圾 */
    public static synchronized boolean append(File f, String s) {
        if (f == null || s == null || s.length() < 64) return false;
        try {
            byte[] b = s.getBytes("UTF-8");
            if (b.length > MAX_ONE) return false;
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            if (f.length() > MAX) trim(f);
            FileOutputStream o = new FileOutputStream(f, true);
            try {
                o.write(String.format("%08x", b.length).getBytes("US-ASCII"));
                o.write('\n');
                o.write(b);
                o.write('\n');
            } finally {
                o.close();
            }
            return true;
        } catch (Throwable ignore) {
            return false;
        }
    }

    /** 只保留尾部一半，给新数据腾地方 */
    private static void trim(File f) {
        try {
            byte[] all = Index.readBytes(f);
            int keep = all.length / 2;
            // 从切点往后找第一个换行，避免切断半条记录
            int p = all.length - keep;
            while (p < all.length && all[p] != '\n') p++;
            if (p >= all.length - 16) return;
            FileOutputStream o = new FileOutputStream(f, false);
            try { o.write(all, p + 1, all.length - p - 1); } finally { o.close(); }
        } catch (Throwable ignore) { }
    }

    /** 读出全部条目（坏条目直接跳过，不让一条烂数据毁掉整个索引） */
    public static List<String> load(File f) {
        List<String> out = new ArrayList<String>();
        if (f == null || !f.isFile()) return out;
        byte[] d;
        try {
            d = Index.readBytes(f);
        } catch (IOException e) {
            return out;
        }
        int p = 0;
        while (p + 9 <= d.length) {
            int nl = -1;
            for (int i = p; i < d.length && i < p + 12; i++) {
                if (d[i] == '\n') { nl = i; break; }
            }
            if (nl < 0) break;
            long len;
            try {
                len = Long.parseLong(new String(d, p, nl - p, "US-ASCII"), 16);
            } catch (Exception e) {          // NumberFormatException / UnsupportedEncodingException
                break;
            }
            int st = nl + 1;
            long en = st + len;
            if (len < 0 || en > d.length) break;
            try {
                out.add(new String(d, st, (int) len, "UTF-8"));
            } catch (Throwable ignore) { }
            p = (int) en + 1;
        }
        return out;
    }
}
