package com.syh.luna.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 定位 / 暂存宿主私有数据目录。
 *
 * 两种运行模式：
 *   LSPosed 模式  —— 代码跑在 com.luna.music 进程内，直接读它自己的私有目录（无需 su）
 *   独立 APK 模式 —— 普通 App + KernelSU/Magisk 的 su，把宿主私有目录 cp 到**本 App 自己的
 *                    私有目录**下暂存后读
 *
 * ⚠️ 暂存位置（2026-11 修正）：
 *   早期版本把暂存放在 `/data/local/tmp/luna_stage` —— 那是全局可写目录，
 *   一来把根目录搞得乱七八糟（跟一堆魔改模块的临时文件混在一起），
 *   二来拷过去的宿主私有数据副本被 `chmod -R 777` 摆在那里，任意应用都能读。
 *   现在改到 `/data/user/0/com.syh.luna/files/luna_stage`：
 *   App 沙箱内、只有本 App 看得见，权限 700，拷过来的文件 chown/chcon 回本 App 的 uid 与 SELinux 标签。
 */
public final class Dirs {

    private Dirs() { }

    public static final String PKG = "com.luna.music";

    /** 暂存目录名（位于本 App 私有目录内，不放根目录） */
    public static final String STAGE_NAME = "luna_stage";

    /**
     * 需要的数据子路径。
     *
     * ⚠️ 单一来源在 {@link StageCmd#SUB} —— 那里同时驱动「复制清单」和「索引扫描清单」，
     *   不会再出现「声明了却没复制」的漏索引。
     */
    public static final String[] SUB = StageCmd.SUB;

    /** LSPosed 模式：进程内目录 = /data/user/0/com.luna.music */
    public static File inProcess(Context ctx) {
        return ctx.getDataDir();
    }

    /** 暂存目录（本 App 视角）：/data/user/0/com.syh.luna/files/luna_stage */
    public static File stageDir(Context ctx) {
        return new File(ctx.getFilesDir(), STAGE_NAME);
    }

    /** 最近一次 su 的原始输出（供诊断） */
    public static volatile String lastSuOutput = "";
    /** 最近一次 su 的退出码 */
    public static volatile int lastSuCode = -1;
    /** 最近一次实际用到的宿主数据目录前缀 */
    public static volatile String lastBase = "";
    /** 最近一次 su 侧实际使用的暂存路径 */
    public static volatile String lastStagePath = "";
    /** 最近一次 su 侧学到的暂存目录 SELinux 上下文 */
    public static volatile String lastStageCtx = "";
    /** 最近一次 su 报告的回执文件标签（诊断用） */
    public static volatile String lastOkLabel = "";
    /** 最近一次 su 报告的暂存目录标签（诊断用） */
    public static volatile String lastDirLabel = "";
    /** 最近一次 su 报告的暂存目录属主 uid（诊断用） */
    public static volatile String lastOwner = "";
    /** 最近一次 su 报告的 .mdl 数量（诊断用） */
    public static volatile String lastCount = "";

    /**
     * 独立 APK 模式：把宿主私有数据暂存到**本 App 私有目录**下。
     *
     * ── v1.1.4：两条路径 ─────────────────────────────────────────────
     *
     * **主路径（tar 流）**：su 只**读**宿主数据、打成 tar 吐到 stdout，
     *   App 自己解包进自己的目录。文件全部由 App 创建 →
     *   属主天然正确、SELinux 标签由内核按创建者域自动继承 → 天然正确。
     *   完全不需要 chown / chcon / restorecon，也不要求 su 与 App
     *   共享同一个挂载命名空间。
     *
     * **兜底路径（cp）**：设备上没有 tar 时才用。让 su 直接把数据拷进
     *   本 App 的目录，靠 chown + chcon/restorecon 掰回属主与标签。
     *
     * ⚠️ 坑 1（挂载命名空间）：从应用发起的 su 可能落在**应用自己的挂载命名空间**里，
     *    那里 /data 有时是个 stub，真正的数据只能用 init 的 root 去看：/proc/1/root/data/data/<pkg>。
     *    所以两个候选都试，且按 **files/music 的内容数量**择优（不是看存不存在）。
     *
     * ⚠️ 坑 2（属主 + SELinux 标签 —— v1.1.4 修的真机致命 bug）：
     *    拷过来的文件默认属主是宿主 App 的 uid、标签是宿主 App 的
     *    （app_data_file:s0:cXXX,cYYY，XXX 是宿主 uid），本 App **一个字节都读不到**。
     *    必须把属主 chown 回本 App、标签 chcon/restorecon 回本 App 的 MCS 类别。
     *    而且**回执文件必须在 chown 之前写**，否则它属主是 root、mode 600，
     *    App 连自己的回执都读不到 → 误报「暂存区不可见」。
     *
     * ⚠️ 坑 3（su 与 App 是否看得到同一个目录）：su 是 App 的子进程，继承 App 的
     *    挂载命名空间，但不同 Root 方案行为不完全一样。所以**不猜路径** ——
     *    App 先在私有目录里写一个带随机标记的探针文件，再让 su 逐个候选路径 grep 这个标记，
     *    能看到的那个就是同一个真实目录。**一个都看不到就直接报错，绝不执行删除**。
     */
    public static File stage(Context ctx) throws IOException {
        // ============ 主路径：tar 流 ============
        String tarStyle = StageCmd.detectTar();
        if (tarStyle != null && tarStyle.length() > 0) {
            try {
                return stageViaTar(ctx, tarStyle);
            } catch (IOException e) {
                lastTarError = String.valueOf(e.getMessage());
                // tar 路径失败不致命 —— 退回 cp 兜底
            }
        } else {
            lastTarError = "设备上没有 tar";
        }

        // ============ 兜底路径：cp ============
        return stageViaCopy(ctx);
    }

    public static final class MdlEntry {
        public final String name;
        public final long size;
        public final long mtimeSec;
        public MdlEntry(String name, long size, long mtimeSec) {
            this.name = name;
            this.size = size;
            this.mtimeSec = mtimeSec;
        }
        public boolean isDownload() {
            return name != null && name.startsWith("download/");
        }
        public String baseName() {
            if (name == null) return "";
            int i = name.lastIndexOf('/');
            return i >= 0 ? name.substring(i + 1) : name;
        }
    }

    /** 最近一次暂存扫描到的宿主 files/music/*.mdl 元清单 */
    public static volatile List<MdlEntry> lastMdlList = Collections.emptyList();
    /** tar 路径失败的原因（诊断用） */
    public static volatile String lastTarError = "";
    /** tar 路径解出的文件数（诊断用） */
    public static volatile int lastTarFiles = 0;
    /** tar 路径解出的总字节（诊断用） */
    public static volatile long lastTarBytes = 0;
    /** tar 路径实际打包的子目录清单（诊断用） */
    public static volatile String lastStageList = "";

    /**
     * tar 流路径：su 只读元数据目录，App 自己解包；.mdl 本体只列元清单。
     */
    static File stageViaTar(Context ctx, String tarStyle) throws IOException {
        File dest = stageDir(ctx);
        if (!dest.isDirectory() && !dest.mkdirs() && !dest.isDirectory()) {
            throw new IOException("无法创建暂存目录: " + dest);
        }
        cleanup(dest);

        String cmd = StageCmd.buildTar(tarStyle);
        Process p = null;
        InputStream in = null;
        StringBuilder errBuf = new StringBuilder();
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start();
            Thread errThread = drainAsync(p.getErrorStream(), errBuf, 8192);
            in = p.getInputStream();

            try {
                lastSuOutput = readHeaderUntil(in, StageCmd.TAR_BEGIN, 2 * 1024 * 1024);
            } catch (IOException e) {
                try { errThread.join(300); } catch (InterruptedException ignore) { }
                String err = errBuf.toString().trim();
                throw new IOException(e.getMessage() + (err.isEmpty() ? "" : " | stderr: " + err));
            }

            lastBase = field(lastSuOutput, "LUNA_BASE=");
            lastCount = field(lastSuOutput, "LUNA_COUNT=");
            lastStageList = field(lastSuOutput, "LUNA_LIST=");
            lastMdlList = parseMdlList(lastSuOutput);
            if (lastSuOutput.contains("LUNA_NO_BASE")) {
                throw new IOException("su 读不到宿主私有目录（两个候选路径都没有内容）");
            }
            if (lastSuOutput.contains("LUNA_NO_LIST")) {
                throw new IOException("宿主私有目录里一个目标子目录都没有（列表为空）");
            }

            Tar.Result tr = Tar.extract(in, dest, StageCmd.META_SUB);
            lastTarFiles = tr.files;
            lastTarBytes = tr.bytes;

            int rc;
            try {
                rc = p.waitFor();
            } catch (InterruptedException ie) {
                rc = -1;
                Thread.currentThread().interrupt();
            }
            try { errThread.join(300); } catch (InterruptedException ignore) { }
            lastSuCode = rc;
            if (lastMdlList.isEmpty() && tr.files == 0) {
                throw new IOException("tar 解包出来 0 个文件（su 退出码 " + rc + "）");
            }
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
            if (p != null) p.destroy();
        }

        return dest;
    }

    /**
     * cp 兜底路径：让 su 直接把数据拷进本 App 的目录。
     */
    static File stageViaCopy(Context ctx) throws IOException {
        File dest = stageDir(ctx);
        File filesDir = dest.getParentFile();          // /data/user/0/com.syh.luna/files
        String filesPath = filesDir.getAbsolutePath();

        // ---- 探针：写在**暂存目录的父目录**与**暂存目录内部**两处
        //   父目录那份用于「su 能不能看到 App 的私有目录」；
        //   目录内那份用于「学 SELinux 标签」（暂存目录的标签就是 App 创建时的正确标签），
        //   并且必须在清空目录**之前**写、且清空时要排除它 —— 见下方命令里的保留逻辑。
        String probe = ".luna_probe_" + Long.toHexString(System.nanoTime());
        String marker = "LUNA_PROBE_" + Long.toHexString(System.nanoTime());
        String token = "LUNA_OK_" + Long.toHexString(System.nanoTime());
        try {
            filesDir.mkdirs();
            File pf = new File(filesDir, probe);
            FileOutputStream o = new FileOutputStream(pf);
            try { o.write(marker.getBytes("UTF-8")); } finally { o.close(); }
            pf.setReadable(true, false);          // 任何 su 域都要读得到，否则探针失灵
            pf.setWritable(true, false);
            // 暂存目录内的探针（标签样本）
            dest.mkdirs();
            File pf2 = new File(dest, probe);
            FileOutputStream o2 = new FileOutputStream(pf2);
            try { o2.write(marker.getBytes("UTF-8")); } finally { o2.close(); }
            pf2.setReadable(true, false);
        } catch (Throwable ignore) { }

        String cmd = StageCmd.buildCopy(filesPath, probe, marker, token,
                android.os.Process.myUid());
        String out = su(cmd);
        lastSuOutput = out;
        try { new File(filesDir, probe).delete(); } catch (Throwable ignore) { }
        try { new File(dest, probe).delete(); } catch (Throwable ignore) { }
        if (out == null) throw new IOException("su 无法执行（未安装 su？）");
        if (out.indexOf("NO_PROBE") >= 0) {
            throw new IOException("su 看不到本 App 的私有目录（探针未命中）—— "
                    + "Root 方案把 su 关进了独立的挂载命名空间");
        }

        int i = out.indexOf("BASE=");
        if (i >= 0) {
            int j = out.indexOf('\n', i);
            lastBase = j > i ? out.substring(i + 5, j).trim() : "";
        }
        i = out.indexOf("STAGE_PATH=");
        if (i >= 0) {
            int j = out.indexOf('\n', i);
            lastStagePath = j > i ? out.substring(i + 11, j).trim() : "";
        }
        i = out.indexOf("STAGE_CTX=");
        if (i >= 0) {
            int j = out.indexOf('\n', i);
            lastStageCtx = j > i ? out.substring(i + 10, j).trim() : "";
        }
        lastOkLabel = field(out, "OKLABEL=");
        lastDirLabel = field(out, "DIRLABEL=");
        lastOwner = field(out, "OWNER=");
        lastCount = field(out, "COUNT=");
        lastMdlList = parseMdlList(out);

        // ---- 回读校验：App 侧必须真的能看到 su 写出来的东西
        File ok = new File(dest, ".luna_ok");
        boolean seen = false;
        String why = "";
        try {
            FileInputStreamHelper.read(ok, token);
            seen = true;
        } catch (Throwable t) {
            why = String.valueOf(t.getMessage());
        }
        if (!seen) {
            throw new IOException("暂存区不可见（su 侧路径 " + lastStagePath
                    + "，上下文 " + lastStageCtx
                    + "，回执标签 " + lastOkLabel
                    + "，目录标签 " + lastDirLabel
                    + "，属主 " + lastOwner
                    + "，文件数 " + lastCount
                    + "）—— " + why);
        }
        return dest;
    }

    /** 从 su 输出里取 KEY=VALUE 的值（取到行尾或下一个已知键前） */
    static String field(String out, String key) {
        if (out == null) return "";
        int i = out.indexOf(key);
        if (i < 0) return "";
        int j = out.indexOf('\n', i);
        return (j > i ? out.substring(i + key.length(), j) : out.substring(i + key.length())).trim();
    }

    /** 暂存目录是否还在（导出期间可能被清理过，需要重新暂存） */
    public static boolean stageAlive(File root) {
        return root != null && root.isDirectory()
                && (new File(root, "databases").isDirectory()
                || new File(root, "files_keva").isDirectory()
                || new File(root, "files_music").isDirectory());
    }

    /** 按需读取单个宿主 .mdl 完整字节流 */
    public static byte[] readHostMdl(String base, String name, long sizeHint) throws IOException {
        String cmd = StageCmd.buildReadFile(base, name);
        Process p = null;
        InputStream in = null;
        StringBuilder errBuf = new StringBuilder();
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start();
            Thread errThread = drainAsync(p.getErrorStream(), errBuf, 4096);
            in = p.getInputStream();
            readHeaderUntil(in, StageCmd.FILE_BEGIN, 8192);
            int hint = sizeHint > 0 && sizeHint <= Integer.MAX_VALUE ? (int) sizeHint : 65536;
            byte[] data = com.syh.luna.core.Index.readBytes(in, hint);
            try { p.waitFor(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            try { errThread.join(200); } catch (InterruptedException ignore) { }
            return data;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
            if (p != null) p.destroy();
        }
    }

    /** 分批批量读取多个宿主 .mdl 的头部（前 maxBytes 字节） */
    public static Map<String, byte[]> readHostMdlHeads(String base, List<String> names, int maxBytes) {
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>();
        if (base == null || base.isEmpty() || names == null || names.isEmpty()) return out;
        final int batchSize = 80;
        for (int i = 0; i < names.size(); i += batchSize) {
            List<String> sub = names.subList(i, Math.min(names.size(), i + batchSize));
            readHeadBatchInto(base, sub, maxBytes, out);
            for (String nm : sub) {
                if (!out.containsKey(nm)) {
                    try {
                        byte[] single = readHostMdlHeadSingle(base, nm, maxBytes);
                        if (single != null) out.put(nm, single);
                    } catch (Throwable ignore) { }
                }
            }
        }
        return out;
    }

    private static void readHeadBatchInto(String base, List<String> batch, int maxBytes, Map<String, byte[]> out) {
        String cmd = StageCmd.buildReadHeadBatch(base, batch, maxBytes);
        Process p = null;
        InputStream in = null;
        StringBuilder errBuf = new StringBuilder();
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start();
            Thread errThread = drainAsync(p.getErrorStream(), errBuf, 4096);
            in = p.getInputStream();
            readHeaderUntil(in, StageCmd.HEAD_BEGIN, 8192);
            while (true) {
                String line = readLineAscii(in);
                if (line == null) break;
                if (!line.startsWith("H ")) continue;
                int sp = line.indexOf(' ', 2);
                if (sp <= 2) continue;
                int n;
                try { n = Integer.parseInt(line.substring(2, sp)); } catch (NumberFormatException e) { break; }
                String fname = line.substring(sp + 1);
                if (n < 0 || n > maxBytes + 4096) break;
                byte[] buf = new byte[n];
                int got = n == 0 ? 0 : Tar.readFully(in, buf, n);
                if (got < n) break;
                out.put(fname, buf);
            }
            try { p.waitFor(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            try { errThread.join(200); } catch (InterruptedException ignore) { }
        } catch (Throwable ignore) {
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignore) { }
            if (p != null) p.destroy();
        }
    }

    private static byte[] readHostMdlHeadSingle(String base, String name, int maxBytes) throws IOException {
        Map<String, byte[]> tmp = new LinkedHashMap<String, byte[]>();
        readHeadBatchInto(base, Collections.singletonList(name), maxBytes, tmp);
        return tmp.get(name);
    }

    private static String readHeaderUntil(InputStream in, String markerStr, int maxBytes) throws IOException {
        byte[] marker = markerStr.getBytes(StandardCharsets.UTF_8);
        int mlen = marker.length;
        byte[] buf = new byte[Math.min(8192, maxBytes)];
        int len = 0;
        int ch;
        while ((ch = in.read()) >= 0) {
            if (len >= buf.length) {
                if (buf.length >= maxBytes) break;
                int ncap = Math.min(maxBytes, buf.length * 2);
                byte[] nb = new byte[ncap];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            buf[len++] = (byte) ch;
            if (len >= mlen && buf[len - 1] == marker[mlen - 1]) {
                boolean ok = true;
                for (int i = 0; i < mlen - 1; i++) {
                    if (buf[len - mlen + i] != marker[i]) { ok = false; break; }
                }
                if (ok) {
                    return new String(buf, 0, len - mlen, StandardCharsets.UTF_8);
                }
            }
            if (len >= maxBytes) break;
        }
        throw new IOException("su 输出里没有标记 " + markerStr.trim()
                + "（su 侧输出：" + new String(buf, 0, len, StandardCharsets.UTF_8).trim() + "）");
    }

    private static String readLineAscii(InputStream in) throws IOException {
        byte[] buf = new byte[512];
        int len = 0;
        int ch;
        while ((ch = in.read()) >= 0) {
            if (ch == '\n') return new String(buf, 0, len, StandardCharsets.UTF_8);
            if (len == buf.length) {
                byte[] nb = new byte[buf.length * 2];
                System.arraycopy(buf, 0, nb, 0, len);
                buf = nb;
            }
            buf[len++] = (byte) ch;
        }
        return len == 0 ? null : new String(buf, 0, len, StandardCharsets.UTF_8);
    }

    private static Thread drainAsync(final InputStream err, final StringBuilder into, final int maxChars) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] b = new byte[2048];
                    int n;
                    while ((n = err.read(b)) > 0) {
                        if (into.length() < maxChars) {
                            into.append(new String(b, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                } catch (Throwable ignore) { }
            }
        }, "su-stderr");
        t.setDaemon(true);
        t.start();
        return t;
    }

    static List<MdlEntry> parseMdlList(String text) {
        List<MdlEntry> list = new ArrayList<MdlEntry>();
        if (text == null || text.isEmpty()) return list;
        String[] lines = text.split("\n");
        for (String line : lines) {
            if (!line.startsWith("LUNA_MDL=")) continue;
            String rest = line.substring(9).trim();
            int c1 = rest.indexOf(':');
            if (c1 <= 0) continue;
            int c2 = rest.indexOf(':', c1 + 1);
            if (c2 <= c1 + 1) continue;
            try {
                long sz = Long.parseLong(rest.substring(0, c1));
                long mt = Long.parseLong(rest.substring(c1 + 1, c2));
                String nm = rest.substring(c2 + 1);
                if (!nm.isEmpty()) list.add(new MdlEntry(nm, sz, mt));
            } catch (NumberFormatException ignore) { }
        }
        Collections.sort(list, new Comparator<MdlEntry>() {
            public int compare(MdlEntry a, MdlEntry b) { return a.name.compareTo(b.name); }
        });
        return list;
    }

    /** 批量删除宿主 files/music 下的 .mdl 及伴生 .mdlnodeconf，返回实际删除成功的文件名集合 */
    public static java.util.Set<String> deleteHostMdls(String base, List<String> names) {
        java.util.Set<String> deleted = new java.util.LinkedHashSet<String>();
        if (!StageCmd.allowedHostBase(base) || names == null || names.isEmpty()) return deleted;
        final int batchSize = 80;
        for (int i = 0; i < names.size(); i += batchSize) {
            List<String> sub = names.subList(i, Math.min(names.size(), i + batchSize));
            String cmd = StageCmd.buildDeleteMdl(base, sub);
            if (cmd.isEmpty()) continue;
            String out = su(cmd);
            if (out == null) continue;
            for (String line : out.split("\n")) {
                if (line.startsWith("DEL_OK=")) {
                    String nm = line.substring(7).trim();
                    if (!nm.isEmpty()) deleted.add(nm);
                }
            }
        }
        return deleted;
    }

    /**
     * 清掉暂存目录。
     *
     * 暂存在 App 自己的私有目录里，文件属主已经 chown 回本 App，
     * 所以**不需要 root**，纯 Java 递归删即可。
     */
    public static void cleanup(File stageRoot) {
        if (stageRoot == null) return;
        deleteTree(stageRoot);
    }

    /** 清掉本 App 私有目录里的暂存区（启动时清上一次崩溃留下的残留） */
    public static void cleanupStale(Context ctx) {
        if (ctx == null) return;
        cleanup(stageDir(ctx));
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        try {
            if (f.isDirectory()) {
                File[] cs = f.listFiles();
                if (cs != null) for (File c : cs) deleteTree(c);
            }
            f.delete();
        } catch (Throwable ignore) { }
    }

    /** 把暂存目录映射成「逻辑根」：STAGE/databases -> 实际路径 */
    public static File logical(File stagedRoot, String sub) {
        return new File(stagedRoot, sub.replace('/', '_'));
    }

    /** 执行 su -c，成功返回 stdout，失败返回 null */
    public static String su(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            lastSuCode = p.waitFor();
            return sb.toString();
        } catch (Exception e) {
            lastSuOutput = "exec 失败: " + e;
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** 是否已 root（su 可用且真是 uid=0） */
    public static boolean hasRoot() {
        String out = su("id");
        return out != null && out.contains("uid=0");
    }

    /** 排障：把 su 环境能看到的都打印出来 */
    public static String diagnose(Context ctx) {
        String A = "/data/data/" + PKG;
        String B = "/proc/1/root/data/data/" + PKG;
        File stage = ctx == null ? null : stageDir(ctx);
        String cmd =
                "echo '[id]'; id 2>&1; "
              + "echo '[ns]'; readlink /proc/self/ns/mnt; readlink /proc/1/ns/mnt; "
              + "echo '[tar]'; command -v tar 2>&1; "
              + "echo '[A direct]'; ls -ld " + A + " 2>&1; "
              + "echo '[B proc1 ]'; ls -ld " + B + " 2>&1; "
              + "echo '[B music ]'; ls " + B + "/files/music 2>&1 | wc -l; "
              + "echo '[B db    ]'; ls " + B + "/databases 2>&1 | head -8; "
              + "echo '[B net   ]'; ls " + B + "/cache/NetCacheLoader 2>&1 | head -3; "
              + "echo '[B net2  ]'; ls " + B + "/files/NetCacheLoader 2>&1 | head -3; "
              + "echo '[B keva  ]'; ls " + B + "/files/keva 2>&1 | head -3; "
              + "echo '[stage  ]'; ls -ld " + (stage == null ? "-" : stage.getAbsolutePath()) + " 2>&1";
        String out = su(cmd);
        return "su 退出码 " + lastSuCode + "\n" + (out == null ? "(su 无法执行)" : out);
    }

    /** 读一个文件并判断内容是否等于期望值（避免额外 import 冲突） */
    static final class FileInputStreamHelper {
        static void read(File f, String expect) throws IOException {
            if (f == null || !f.isFile()) throw new IOException("回执文件不存在: " + f);
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] b = new byte[512];
                int n = in.read(b);
                String s = n > 0 ? new String(b, 0, n, "UTF-8").trim() : "";
                if (!expect.equals(s)) throw new IOException("回执内容不符: " + s);
            } finally {
                in.close();
            }
        }
    }
}
