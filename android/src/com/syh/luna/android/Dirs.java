package com.syh.luna.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 定位 / 暂存宿主私有数据目录。
 *
 * 两种运行模式：
 *   LSPosed 模式  —— 代码跑在 com.luna.music 进程内，直接读它自己的私有目录（无需 su）
 *   独立 APK 模式 —— 普通 App + KernelSU/Magisk 的 su，把私有目录 cp 到 /data/local/tmp 暂存后读
 */
public final class Dirs {

    private Dirs() { }

    public static final String PKG = "com.luna.music";
    public static final String STAGE = "/data/local/tmp/luna_stage";

    /** 需要的数据子路径 */
    public static final String[] SUB = {
            "files/music",              // .mdl 本体
            "databases",                // sqlite：player/history/recent_played/download
            "cache/NetCacheLoader",     // 接口原始 JSON blob
            "files/keva",               // key-value 缓存
            "files/luna_export",        // 本模块 hook 抓到的密钥仓
    };

    /** LSPosed 模式：进程内目录 = /data/user/0/com.luna.music */
    public static File inProcess(Context ctx) {
        return ctx.getDataDir();
    }

    /** 最近一次 su 的原始输出（供诊断） */
    public static volatile String lastSuOutput = "";
    /** 最近一次 su 的退出码 */
    public static volatile int lastSuCode = -1;
    /** 最近一次实际用到的宿主数据目录前缀 */
    public static volatile String lastBase = "";

    /**
     * 独立 APK 模式：用 su 把私有目录拷到 /data/local/tmp 暂存。
     *
     * ⚠️ 坑：从应用发起的 su 可能落在**应用自己的挂载命名空间**里，那里
     *    /data/data 是个空 stub（root 被换成 erofs 模块视图），
     *    真正的数据只能用 init 的 root 去看：/proc/1/root/data/data/<pkg>。
     *    所以这里先试直接路径，不行就自动切到 /proc/1/root。
     */
    public static File stage() throws IOException {
        String S = STAGE;
        String A = "/data/data/" + PKG;
        String B = "/proc/1/root/data/data/" + PKG;
        StringBuilder sb = new StringBuilder();
        sb.append("S=").append(S).append("; rm -rf \"$S\"; mkdir -p \"$S\"; ");
        sb.append("if [ -e ").append(A).append("/files/music ]; then BASE=").append(A)
          .append("; else BASE=").append(B).append("; fi; ");
        sb.append("echo \"BASE=$BASE\"; ");
        pair(sb, "files/music");
        pair(sb, "databases");
        pair(sb, "cache/NetCacheLoader");
        pair(sb, "files/keva");
        pair(sb, "files/luna_export");
        sb.append("chmod -R 777 \"$S\" 2>&1; ");
        sb.append("echo '---LS---'; ls \"$S\"; ");
        sb.append("echo '---COUNT---'; ls \"$S/files_music\" 2>/dev/null | wc -l");
        String out = su(sb.toString());
        lastSuOutput = out;
        if (out == null) throw new IOException("su 无法执行（未安装 su？）");
        int i = out.indexOf("BASE=");
        if (i >= 0) {
            int j = out.indexOf('\n', i);
            lastBase = j > i ? out.substring(i + 5, j).trim() : "";
        }
        return new File(S);
    }

    private static void pair(StringBuilder sb, String sub) {
        String flat = sub.replace('/', '_');
        sb.append("if [ -e \"$BASE/").append(sub).append("\" ]; then ")
          .append("cp -a \"$BASE/").append(sub).append("\" \"$S/").append(flat).append("\" 2>&1 ")
          .append("|| cp -r \"$BASE/").append(sub).append("\" \"$S/").append(flat).append("\" 2>&1; ")
          .append("fi; ");
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
    public static String diagnose() {
        String A = "/data/data/" + PKG;
        String B = "/proc/1/root/data/data/" + PKG;
        String cmd =
                "echo '[id]'; id 2>&1; "
              + "echo '[ns]'; readlink /proc/self/ns/mnt; readlink /proc/1/ns/mnt; "
              + "echo '[A direct]'; ls -ld " + A + " 2>&1; "
              + "echo '[B proc1 ]'; ls -ld " + B + " 2>&1; "
              + "echo '[B music ]'; ls " + B + "/files/music 2>&1 | wc -l; "
              + "echo '[B db    ]'; ls " + B + "/databases 2>&1 | head -8; "
              + "echo '[B net   ]'; ls " + B + "/cache/NetCacheLoader 2>&1 | head -3; "
              + "echo '[B keva  ]'; ls " + B + "/files/keva 2>&1 | head -3";
        String out = su(cmd);
        return "su 退出码 " + lastSuCode + "\n" + (out == null ? "(su 无法执行)" : out);
    }
}
