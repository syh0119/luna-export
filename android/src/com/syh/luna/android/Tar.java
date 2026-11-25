package com.syh.luna.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 纯 Java 的 tar 解包器（只解包，不打包）。
 *
 * ⚠️ 为什么需要它（v1.1.4 的核心改造）：
 *
 *   原来的做法是让 su 直接把宿主私有数据 `cp` 到本 App 的私有目录里，
 *   再靠 chown / chcon / restorecon 把属主和 SELinux 标签「掰」回本 App。
 *   这条路在真机上连续踩了三个坑（都是 100% 必现）：
 *
 *     ① 回执文件写在 chown 之后 → 属主 root、mode 600
 *        → App 连自己写好的回执都读不到 → 误报「暂存区不可见」。
 *     ② SELinux 标签用 `ls -Zd | awk '{print $4}'` 提取 ——
 *        toybox 的 ls -Z 输出是「上下文 路径」两段，取 $4 必然是空串
 *        → chcon 被跳过 → 宿主 App 的 MCS 类别带过来 → 一个字节都读不到。
 *     ③ restorecon 在部分 ROM / Root 方案里不存在或被裁剪，没有兜底。
 *
 *   改成「**su 把 tar 流吐到 stdout，App 自己解包进自己的目录**」之后：
 *
 *     · 每一个文件都是 **App 自己创建的** → 属主天然正确；
 *     · 内核按**创建者的域**做 type_transition，App 在自己的 app_data_file
 *       目录里建文件，MCS 类别自动继承本 App 那一套 → SELinux 标签天然正确；
 *     · 完全不需要 chown / chcon / restorecon；
 *     · 完全不依赖 su 与 App 是否共享同一个挂载命名空间 ——
 *       su 只需要能**读**到宿主数据（读不到就换 /proc/1/root），
 *       写不写得到本 App 的目录根本无关紧要。
 *
 * 只支持 POSIX ustar + GNU longname + pax 头跳过，日常足够。
 */
public final class Tar {

    private Tar() { }

    public static final class Result {
        /** 普通文件数 */
        public int files;
        /** 目录数 */
        public int dirs;
        /** 跳过的条目（符号链接等） */
        public int skipped;
        /** 解出的总字节 */
        public long bytes;
        /** 错误信息（空 = 没错误） */
        public String error = "";
        /** 解出的条目名（前若干个，用于诊断） */
        public final List<String> names = new ArrayList<String>();
    }

    /**
     * 从流里解包到 dest。
     *
     * @param in   tar 字节流（**边读边写，不整包进内存** —— 宿主的 .mdl 可能上百 MB）
     * @param dest 目标目录
     */
    public static Result extract(InputStream in, File dest) throws IOException {
        return extract(in, dest, null);
    }

    /**
     * 从流里解包到 dest，**按给定的前缀清单重映射路径**。
     *
     * <p>为什么需要重映射：tar 归档里是宿主私有目录的相对路径
     * （{@code files/music/x.mdl}），而暂存区用的是**扁平命名**
     * （{@code files_music/x.mdl}，见 {@code IndexBuilder.sub()} 的
     * {@code s.replace('/', '_')}）—— 两边对不上就是「解包了但索引读不到」。
     *
     * <p>⚠️ 重映射**不能**用「把前两段合并」这种通用规则：
     * {@code databases/foo.db} 会被错改成 {@code databases_foo.db}。
     * 所以这里按 {@link StageCmd#SUB} 的清单做**精确前缀匹配**，
     * 最长前缀优先，保证与 cp 兜底路径的扁平规则完全一致。
     *
     * @param subs 源子路径清单（如 {@code files/music}）；null = 不重映射
     */
    public static Result extract(InputStream in, File dest, String[] subs) throws IOException {
        Result r = new Result();
        byte[] h = new byte[512];
        byte[] buf = new byte[64 * 1024];
        String longName = null;
        if (!dest.isDirectory() && !dest.mkdirs() && !dest.isDirectory()) {
            throw new IOException("无法创建解包目录: " + dest);
        }
        // 前缀映射表：源路径 → 扁平名（最长前缀优先）
        String[] srcs = null, flats = null;
        if (subs != null && subs.length > 0) {
            int n = 0;
            for (String s : subs) if (s != null && s.length() > 0) n++;
            srcs = new String[n];
            flats = new String[n];
            int k = 0;
            for (String s : subs) {
                if (s == null || s.length() == 0) continue;
                srcs[k] = s;
                flats[k] = s.replace('/', '_');     // 与 IndexBuilder.sub() 同一规则
                k++;
            }
            // 最长前缀优先
            for (int i = 0; i < srcs.length; i++) {
                for (int j = i + 1; j < srcs.length; j++) {
                    if (srcs[j].length() > srcs[i].length()) {
                        String ts = srcs[i]; srcs[i] = srcs[j]; srcs[j] = ts;
                        String tf = flats[i]; flats[i] = flats[j]; flats[j] = tf;
                    }
                }
            }
        }

        while (true) {
            int n = readFully(in, h, 512);
            if (n < 512) break;                       // EOF
            if (isZero(h)) break;                     // 结束块
            if (!valid(h)) {
                // su 可能在 tar 前面打了 banner —— 逐字节滑动找回同步
                if (!resync(in, h)) {
                    r.error = "tar 头校验失败（流被污染或不是 tar）";
                    throw new IOException(r.error);
                }
                continue;
            }

            String name = cstr(h, 0, 100);
            String prefix = cstr(h, 345, 155);
            long size = num(h, 124, 12);
            char type = (char) (h[156] & 0xff);

            // GNU long name：下一个条目的真实文件名
            if (type == 'L') {
                byte[] data = new byte[(int) size];
                readFully(in, data, (int) size);
                skipPad(in, size);
                int z = 0;
                while (z < data.length && data[z] != 0) z++;
                longName = new String(data, 0, z, "UTF-8");
                continue;
            }
            // pax 扩展头：里面可能有 path= / size= 等记录
            // ⚠️ Android 的 toybox tar 默认就吐 **pax** 格式，
            //    长文件名是通过 pax 的 path= 记录传的 —— 必须真的解析出来，
            //    否则长文件名的路径会被截断（name 字段只有 100 字节）。
            if (type == 'x' || type == 'X') {
                byte[] data = new byte[(int) size];
                readFully(in, data, (int) size);
                skipPad(in, size);
                String p = paxPath(data);
                if (p != null && p.length() > 0) longName = p;
                continue;
            }
            // pax 全局头（type g）：跳过
            if (type == 'g') {
                skipData(in, size);
                continue;
            }
            // ---- 文件名合成
            //
            // ⚠️ 三种来源，优先级：pax/GNU 的长名 > ustar 的 prefix+name > name
            //   · pax / GNU longname 给的是**完整路径**（含 ./ 前缀），
            //     这时绝不能再拼 prefix —— 拼了会变成
            //     "./files/music/./files/music/x.mdl"，映射必然失败。
            //   · 没有长名时，ustar 格式把路径拆成 prefix(155) + name(100)，
            //     这时才需要拼。
            if (longName != null) {
                name = longName;
                longName = null;
            } else if (prefix != null && prefix.length() > 0) {
                name = prefix + "/" + name;
            }
            if (name == null || name.length() == 0) { skipData(in, size); continue; }
            name = name.replace('\\', '/');
            // ⚠️ 安全检查必须在**重映射之前**做 —— 否则 ../evil 会被映射成别的名字绕过检查
            if (name.startsWith("/") || name.contains("../")) {
                skipData(in, size);
                r.skipped++;
                continue;
            }

            // ---- 路径重映射（精确前缀匹配，最长前缀优先）
            //
            // ⚠️ 顺序很重要：GNU LongLink 里存的是**带 ./ 前缀的完整路径**
            //    （./files/music/x.mdl），而普通条目里也可能带 ./。
            //    所以先按「剥掉 ./ 之后的路径」做前缀匹配，
            //    匹配不上再退回用原始串匹配 —— 两种都覆盖到。
            String bare = name;
            while (bare.startsWith("./")) bare = bare.substring(2);
            while (bare.startsWith("/")) bare = bare.substring(1);

            if (srcs != null) {
                String mapped = null;
                for (int i = 0; i < srcs.length; i++) {
                    String s = srcs[i];
                    if (bare.equals(s) || bare.startsWith(s + "/")) {
                        mapped = flats[i] + bare.substring(s.length());
                        break;
                    }
                }
                if (mapped == null) {
                    // 不在清单里的路径（中间层目录、临时文件）→ 跳过。
                    // 父目录由文件写入时自动创建，不需要归档里的目录条目。
                    skipData(in, size);
                    r.skipped++;
                    continue;
                }
                name = mapped;
            } else {
                name = bare;
            }

            File target = new File(dest, name);
            if (type == '5') {                        // 目录
                if (!target.isDirectory()) target.mkdirs();
                r.dirs++;
                skipData(in, size);
                continue;
            }
            if (type == '0' || type == '\0' || type == '7' || type == 0) {   // 普通文件
                File p = target.getParentFile();
                if (p != null && !p.isDirectory()) p.mkdirs();
                OutputStream os = new FileOutputStream(target);
                try {
                    copy(in, os, size, buf);
                } finally {
                    os.close();
                }
                // 权限：属主可读写即可（App 自己就是属主）
                target.setReadable(true, false);
                target.setWritable(true, false);
                r.files++;
                r.bytes += size;
                if (r.names.size() < 40) r.names.add(name);
                skipPad(in, size);
                continue;
            }
            // 符号链接 / 硬链接 / 设备节点：宿主私有目录里不会出现，跳过
            skipData(in, size);
            r.skipped++;
        }
        return r;
    }

    // ------------------------------------------------------------------ tar 头解析

    /**
     * 从 pax 扩展头里取 {@code path=} 记录。
     *
     * <p>pax 扩展头的格式是「<len> <key>=<value>\n」重复，每个记录的总长度
     * **包含自身的十进制长度前缀与结尾换行**。这里只需要 path，
     * 其余记录（size / mtime / uid …）一律忽略 ——
     * size 已经在 ustar 头里有（只有超过 8GB 才需要 pax 覆盖，宿主数据不可能）。
     *
     * @return path 的值；没有则返回 null
     */
    public static String paxPath(byte[] data) {
        int i = 0;
        while (i < data.length) {
            // 记录长度：十进制数字，直到第一个空格
            int sp = i;
            while (sp < data.length && data[sp] != ' ') sp++;
            if (sp >= data.length) return null;
            int len = 0;
            boolean any = false;
            for (int k = i; k < sp; k++) {
                if (data[k] < '0' || data[k] > '9') { any = false; break; }
                len = len * 10 + (data[k] - '0');
                any = true;
            }
            if (!any || len <= 0 || i + len > data.length) return null;
            // 记录体：[sp+1, i+len-1)，末尾是换行
            int bodyStart = sp + 1;
            int bodyEnd = i + len - 1;              // 去掉结尾 '\n'
            if (bodyEnd > data.length) bodyEnd = data.length;
            if (bodyEnd > bodyStart) {
                String rec;
                try {
                    rec = new String(data, bodyStart, bodyEnd - bodyStart, "UTF-8");
                } catch (Throwable t) {
                    rec = new String(data, bodyStart, bodyEnd - bodyStart);
                }
                int eq = rec.indexOf('=');
                if (eq > 0 && "path".equals(rec.substring(0, eq))) {
                    return rec.substring(eq + 1);
                }
            }
            i += len;
        }
        return null;
    }

    /** 校验和：把 chksum 字段当 8 个空格后求和 */
    static boolean valid(byte[] h) {
        long sum = 0;
        for (int i = 0; i < 512; i++) {
            int b = (i >= 148 && i < 156) ? ' ' : (h[i] & 0xff);
            sum += b;
        }
        long want = num(h, 148, 8);
        return want < 0 || want == sum;
    }

    /** 全 0 = 归档结束 */
    static boolean isZero(byte[] h) {
        for (int i = 0; i < 512; i++) if (h[i] != 0) return false;
        return true;
    }

    /** 取 C 字符串（NUL 结尾） */
    static String cstr(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        if (end == off) return "";
        try {
            return new String(h, off, end - off, "UTF-8");
        } catch (Throwable t) {
            return new String(h, off, end - off);
        }
    }

    /** 取八进制数字（兼容 GNU base-256 大数） */
    static long num(byte[] h, int off, int len) {
        if ((h[off] & 0x80) != 0) {
            long v = h[off] & 0x7f;
            for (int i = 1; i < len; i++) v = (v << 8) | (h[off + i] & 0xff);
            return v;
        }
        long v = 0;
        boolean any = false;
        for (int i = 0; i < len; i++) {
            int c = h[off + i] & 0xff;
            if (c == 0 || c == ' ') break;
            if (c < '0' || c > '7') continue;
            v = v * 8 + (c - '0');
            any = true;
        }
        return any ? v : 0;
    }

    // ------------------------------------------------------------------ 流工具

    static int readFully(InputStream in, byte[] b, int len) throws IOException {
        int got = 0;
        while (got < len) {
            int k = in.read(b, got, len - got);
            if (k < 0) break;
            got += k;
        }
        return got;
    }

    static void copy(InputStream in, OutputStream out, long size, byte[] buf) throws IOException {
        long left = size;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int k = in.read(buf, 0, want);
            if (k < 0) throw new IOException("tar 数据提前结束");
            out.write(buf, 0, k);
            left -= k;
        }
    }

    /** 跳过 size 字节数据 + 512 对齐填充 */
    static void skipData(InputStream in, long size) throws IOException {
        long left = size;
        byte[] tmp = new byte[8192];
        while (left > 0) {
            int want = (int) Math.min(tmp.length, left);
            int k = in.read(tmp, 0, want);
            if (k < 0) break;
            left -= k;
        }
        skipPad(in, size);
    }

    /** 跳过 512 对齐填充 */
    static void skipPad(InputStream in, long size) throws IOException {
        int pad = (int) ((512 - (size % 512)) % 512);
        for (int i = 0; i < pad; i++) if (in.read() < 0) return;
    }

    /** 逐字节滑动，重新找回 tar 头同步点 */
    static boolean resync(InputStream in, byte[] h) throws IOException {
        for (int attempt = 0; attempt < 4096; attempt++) {
            if (readFully(in, h, 512) < 512) return false;
            if (valid(h)) return true;
            System.arraycopy(h, 1, h, 0, 511);
            int c = in.read();
            if (c < 0) return false;
            h[511] = (byte) c;
        }
        return false;
    }
}
