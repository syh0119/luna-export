package com.syh.luna.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单文件流水线 —— 逐字节等价于 Python luna_decrypt.process()。
 *
 * 平台差异通过两个接口隔离：
 *   Sink          —— 输出（桌面 = 文件；Android = MediaStore）
 *   CoverProvider —— 取封面（桌面 = 本地已归一化的缩略图；Android = 下载 + Bitmap 缩放）
 */
public final class Pipeline {

    private Pipeline() { }

    // ------------------------------------------------------------------ 抽象
    public interface Sink {
        /** 写一个文件；成功返回其展示路径（绝对路径或 content uri），失败返回 null */
        String put(String fileName, byte[] data);
    }

    public interface CoverProvider {
        /** 返回已归一化（<=1200px JPEG）的封面字节；无则 null */
        byte[] get(Map<String, Object> cov);
    }

    /** 桌面用：写进目录 */
    public static final class FileSink implements Sink {
        public final File dir;

        public FileSink(File dir) { this.dir = dir; }

        public String put(String fileName, byte[] data) {
            File out = new File(dir, fileName);
            try {
                dir.mkdirs();
                java.io.OutputStream o = new java.io.FileOutputStream(out);
                try { o.write(data); } finally { o.close(); }
                return out.getAbsolutePath();
            } catch (IOException e) {
                return null;
            }
        }
    }

    public static final class Result {
        public String md5 = "";
        public String kind = "";
        public int samples;
        public int ivs;
        public long fileSize;
        public long dataSize;
        public boolean complete;
        public boolean tagged;
        public boolean hasCover;
        public String out;
        public String lrc;
        public int decryptedBytes;
        public String keySrc;
        public String kid;
        public String vid;
        public String title;
        public String artist;
        public String album;

        public String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append(" \"md5\": ").append(Json.escape(md5)).append(",\n");
            sb.append(" \"kind\": ").append(Json.escape(kind)).append(",\n");
            sb.append(" \"samples\": ").append(samples).append(",\n");
            sb.append(" \"ivs\": ").append(ivs).append(",\n");
            sb.append(" \"file_size\": ").append(fileSize).append(",\n");
            sb.append(" \"data_size\": ").append(dataSize).append(",\n");
            sb.append(" \"complete\": ").append(complete).append(",\n");
            sb.append(" \"tagged\": ").append(tagged).append(",\n");
            sb.append(" \"has_cover\": ").append(hasCover).append(",\n");
            sb.append(" \"out\": ").append(Json.escape(out)).append(",\n");
            sb.append(" \"lrc\": ").append(Json.escape(lrc)).append(",\n");
            sb.append(" \"decrypted_bytes\": ").append(decryptedBytes).append(",\n");
            sb.append(" \"key_src\": ").append(Json.escape(keySrc)).append(",\n");
            sb.append(" \"kid\": ").append(Json.escape(kid)).append(",\n");
            sb.append(" \"vid\": ").append(Json.escape(vid)).append(",\n");
            sb.append(" \"title\": ").append(Json.escape(title)).append(",\n");
            sb.append(" \"artist\": ").append(Json.escape(artist)).append(",\n");
            sb.append(" \"album\": ").append(Json.escape(album)).append("\n");
            sb.append("}");
            return sb.toString();
        }
    }

    static Result blank(String md5) {
        Result r = new Result();
        r.md5 = md5;
        return r;
    }

    private static boolean isAsciiAlnum(byte b) {
        return (b >= '0' && b <= '9') || (b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z');
    }

    /** dry=true 只体检；sink=null 也不落盘 */
    public static Result process(File src, Sink sink, Index idx, CoverProvider covers,
                                 boolean dry, boolean tag, boolean wantLrc) {
        String name = src.getName();
        String md5 = name.toLowerCase().endsWith(".mdl")
                ? name.substring(0, name.length() - 4) : name;
        byte[] raw;
        try {
            raw = Index.readBytes(src);
        } catch (IOException e) {
            Result r = blank(md5);
            r.kind = "junk";
            return r;
        }
        Result info = blank(md5);
        info.fileSize = raw.length;
        if (raw.length < 1024 || !(isAsciiAlnum(raw[4]) && isAsciiAlnum(raw[5])
                && isAsciiAlnum(raw[6]) && isAsciiAlnum(raw[7]))) {
            info.kind = "junk";
            return info;
        }

        // raw 是刚从文件读出来的私有数组，没有任何人再引用它 —— 直接就地使用。
        // （原来是 raw.clone()，在 32 位宿主里等于白占一整份文件大小的内存。）
        byte[] b = raw;
        String kind = Cenc.classify(b);
        List<Cenc.Sample> table = Cenc.parseTrack(b);
        List<byte[]> ivs = Cenc.parseIvs(b);
        int n = Math.min(table.size(), ivs.size());
        long sizeSum = 0, maxOff = 0;
        for (Cenc.Sample s : table) {
            sizeSum += s.size;
            if (s.off + s.size > maxOff) maxOff = s.off + s.size;
        }
        boolean complete;
        if ("plain".equals(kind) || "video".equals(kind)) {
            complete = !table.isEmpty() && sizeSum <= b.length && maxOff <= b.length;
        } else {
            complete = !table.isEmpty() && sizeSum <= b.length && maxOff <= b.length
                    && n == table.size();
        }

        info.kind = kind;
        info.samples = table.size();
        info.ivs = ivs.size();
        info.dataSize = sizeSum;
        info.complete = complete;

        if ("video".equals(kind)) return info;      // MV：按需求忽略

        // ---- 未加密：不查密钥（与 python 顺序一致，plain 分支先返回）
        if ("plain".equals(kind)) {
            Index.MetaHit pm = idx.fullMeta(md5, null);
            Map<String, Object> pmeta = pm.meta;
            info.vid = pm.vid;
            info.title = Json.str(pmeta, "title");
            info.artist = Json.str(pmeta, "artist");
            info.album = Json.str(pmeta, "album");
            String plrc = Tags.krcToLrc(Json.str(pmeta, "lyric"));
            Map<String, String> ptags = tagsOf(info, plrc);
            if (!dry && sink != null) {
                byte[] data = b;
                if (tag) {
                    byte[] cov = covers == null ? null : covers.get(Json.asMap(pmeta.get("cover")));
                    data = Tags.m4aEmbed(data, ptags, cov);
                    info.tagged = nonEmpty(ptags.get("TITLE")) || nonEmpty(ptags.get("ARTIST"));
                    info.hasCover = cov != null && cov.length > 0;
                }
                emit(info, sink, data, ".m4a", plrc, wantLrc);
            }
            return info;
        }

        Index.KeyHit kh = idx.lookupKey(b, md5);
        info.keySrc = kh.src;
        info.kid = kh.kid;
        info.vid = kh.vid;
        Index.MetaHit mh = idx.fullMeta(md5, kh.vid);
        Map<String, Object> meta = mh.meta;
        info.vid = kh.vid != null ? kh.vid : mh.vid;
        info.title = Json.str(meta, "title");
        info.artist = Json.str(meta, "artist");
        info.album = Json.str(meta, "album");

        String lrc = Tags.krcToLrc(Json.str(meta, "lyric"));
        Map<String, String> tags = tagsOf(info, lrc);

        if (kh.spade == null) return info;
        if (table.isEmpty()) {
            info.keySrc = (kh.src == null ? "null" : kh.src) + " (no sample table)";
            return info;
        }
        String hexKey = Spade.fromSpadeA(kh.spade);
        if (hexKey == null || hexKey.length() != 32) {
            info.keySrc = (kh.src == null ? "null" : kh.src) + " (bad spade)";
            return info;
        }
        byte[] key = hex(hexKey);
        int eff = n;
        if (!complete) {
            eff = 0;
            for (int i = 0; i < n; i++) {
                Cenc.Sample s = table.get(i);
                if (s.off + s.size <= b.length) eff++;
            }
        }
        if (dry || sink == null) return info;

        byte[] data;
        String ext;
        if ("flac".equals(kind)) {
            data = Cenc.decryptFlac(b, key, table, ivs, eff);
            ext = ".flac";
        } else {
            // decryptAac 是**原地**改 fourcc + 原地解样本，返回的就是传进去的数组；
            // 原来这里传 b.clone()，等于白多一份拷贝
            data = Cenc.decryptAac(b, key, table, ivs, eff);
            ext = ".m4a";
        }
        b = null;                       // 让 GC 尽早回收输入缓冲（下面写标签时峰值会翻倍）

        // 只有完整曲目才写标签（残片的 moov 声明与真实数据不符）
        if (tag && complete) {
            byte[] cov = covers == null ? null : covers.get(Json.asMap(meta.get("cover")));
            data = ".flac".equals(ext) ? Tags.flacEmbed(data, tags, cov) : Tags.m4aEmbed(data, tags, cov);
            info.tagged = nonEmpty(tags.get("TITLE")) || nonEmpty(tags.get("ARTIST"));
            info.hasCover = cov != null && cov.length > 0;
        }
        emit(info, sink, data, ext, lrc, wantLrc);
        return info;
    }

    private static Map<String, String> tagsOf(Result info, String lrc) {
        Map<String, String> tags = new LinkedHashMap<String, String>();
        tags.put("TITLE", info.title == null ? "" : info.title);
        tags.put("ARTIST", info.artist == null ? "" : info.artist);
        tags.put("ALBUM", info.album == null ? "" : info.album);
        tags.put("LYRICS", lrc);
        return tags;
    }

    private static boolean nonEmpty(String s) { return s != null && !s.isEmpty(); }

    /** 目录内所有 .mdl，按名排序 */
    public static List<File> listMdl(File dir) {
        List<File> out = new ArrayList<File>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".mdl")) out.add(f);
        }
        java.util.Collections.sort(out, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
        });
        return out;
    }

    /**
     * 输出文件名（含扩展名）。
     *
     * ⚠️ 这个名字是「是否已导出」的唯一纽带：Exporter 会拿它去输出目录里找文件，
     *    所以 emit() 和判断逻辑必须走同一个函数，不允许各写一份。
     */
    public static String outName(String artist, String title, String md5, String kind) {
        String base = (title != null && !title.isEmpty())
                ? Tags.safe(dash(artist, title), md5) : md5;
        return base + ("flac".equals(kind) ? ".flac" : ".m4a");
    }

    private static void emit(Result info, Sink sink, byte[] data, String ext,
                             String lrc, boolean wantLrc) {
        String base = info.title != null && !info.title.isEmpty()
                ? Tags.safe(dash(info.artist, info.title), info.md5) : info.md5;
        String p = sink.put(base + ext, data);
        if (p == null) return;
        info.out = p;
        info.decryptedBytes = data.length;
        if (wantLrc && lrc != null && !lrc.isEmpty()) {
            String lp = sink.put(base + ".lrc", lrc.getBytes(StandardCharsets.UTF_8));
            if (lp != null) info.lrc = lp;
        }
    }

    private static String dash(String artist, String title) {
        return (artist == null ? "None" : artist) + " - " + (title == null ? "None" : title);
    }

    public static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
