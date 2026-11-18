package com.syh.luna.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;

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

    /**
     * 独立 APK 模式：用 su 把宿主私有目录拷到**本 App 私有目录**下的暂存区。
     *
     * ⚠️ 坑 1（挂载命名空间）：从应用发起的 su 可能落在**应用自己的挂载命名空间**里，
     *    那里 /data 有时是个 stub（root 被换成 erofs 模块视图），
     *    真正的数据只能用 init 的 root 去看：/proc/1/root/data/data/<pkg>。
     *    所以这里先试直接路径，不行就自动切到 /proc/1/root。
     *
     * ⚠️ 坑 2（su 与 App 是否看得到同一个目录）：su 是 App 的子进程，继承 App 的
     *    挂载命名空间，但不同 Root 方案行为不完全一样。所以**不猜路径** ——
     *    App 先在私有目录里写一个带随机标记的探针文件，再让 su 逐个候选路径 grep 这个标记，
     *    能看到的那个就是同一个真实目录。**一个都看不到就直接报错，绝不执行删除**。
     *
     * ⚠️ 坑 3（属主与 SELinux 标签）：拷过来的文件属主是宿主 App 的 uid、SELinux 标签
     *    也是宿主 App 的（本 App 读不了）。所以拷完统一 chown 回本 App 的 uid，
     *    并把 SELinux 上下文改回本 App 自己的（从探针文件上读出来，不猜）。
     */
    public static File stage(Context ctx) throws IOException {
        File dest = stageDir(ctx);
        File filesDir = dest.getParentFile();          // /data/user/0/com.syh.luna/files
        String filesPath = filesDir.getAbsolutePath();

        // ---- 探针：写在**暂存目录的父目录**里（不能写在 dest 里，否则后面的 rm -rf 会把它删掉）
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
        } catch (Throwable ignore) { }

        String cmd = StageCmd.build(filesPath, probe, marker, token,
                android.os.Process.myUid());
        String out = su(cmd);
        lastSuOutput = out;
        try { new File(filesDir, probe).delete(); } catch (Throwable ignore) { }
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

        // ---- 回读校验：App 侧必须真的能看到 su 写出来的东西
        File ok = new File(dest, ".luna_ok");
        boolean seen = false;
        try {
            FileInputStreamHelper.read(ok, token);
            seen = true;
        } catch (Throwable ignore) { }
        if (!seen) {
            throw new IOException("暂存区不可见（su 侧路径 " + lastStagePath
                    + "，上下文 " + lastStageCtx + "）—— su 与本 App 不在同一个 /data 视图上");
        }
        return dest;
    }

    /** 暂存目录是否还在（导出期间可能被清理过，需要重新暂存） */
    public static boolean stageAlive(File root) {
        return root != null && new File(root, "files_music").isDirectory();
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
