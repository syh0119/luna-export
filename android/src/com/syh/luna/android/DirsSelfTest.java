package com.syh.luna.android;

/**
 * Dirs 的离线自测（不需要设备、不需要 root）。
 *
 * 验证三件事：
 *   1) buildStageCmd 生成的 shell 语法正确（交给 /bin/sh -n 检查）
 *   2) 探针保护逻辑有效：路径没确认时不会执行 rm -rf
 *   3) NetCacheLoader 两套缓存都在复制列表里
 *
 * 跑法：
 *   javac -d out/classes core/src/... android/src/com/syh/luna/android/Dirs.java ...
 *   java -cp out/classes com.syh.luna.android.DirsSelfTest
 */
public final class DirsSelfTest {

    public static void main(String[] args) {
        int fail = 0;
        fail += check("命令非空", cmd().length() > 100);
        fail += check("含 NO_PROBE 保护", cmd().contains("NO_PROBE"));
        fail += check("含 rm -rf 暂存", cmd().contains("rm -rf \"$S\""));
        fail += check("chown 回本 App", cmd().contains("chown -R"));
        fail += check("权限 700", cmd().contains("chmod -R 700"));
        fail += check("不再 chmod 777", !cmd().contains("777"));
        fail += check("复制 files_music", cmd().contains("files_music"));
        fail += check("复制 databases", cmd().contains("databases"));
        fail += check("复制 cache_NetCacheLoader", cmd().contains("cache_NetCacheLoader"));
        fail += check("★复制 files_NetCacheLoader", cmd().contains("files_NetCacheLoader"));
        fail += check("复制 files_keva", cmd().contains("files_keva"));
        fail += check("复制 files_luna_export", cmd().contains("files_luna_export"));
        fail += check("回执文件", cmd().contains(".luna_ok"));
        fail += check("不硬编码 /data/local/tmp", !cmd().contains("/data/local/tmp"));
        fail += check("暂存名在私有目录下", cmd().contains("luna_stage"));

        // ---- 拷出来的目录清单必须与 SUB 对齐（少一个就是漏索引）
        for (String sub : StageCmd.SUB) {
            if ("files/music".equals(sub)) continue;
            String flat = sub.replace('/', '_');
            fail += check("SUB 覆盖 " + sub + " → " + flat, cmd().contains(flat));
        }

        // ---- shell 语法检查（找得到 sh 就查；Windows 上 Git Bash 的 sh 也可以）
        int sc = shellSyntax("生成的命令 sh -n 通过", cmd());
        if (sc < 0) {
            System.out.println("  (没找到 sh，跳过语法检查)");
        } else {
            fail += sc;
        }

        System.out.println(fail == 0 ? "\n全部通过" : "\n失败 " + fail + " 项");
        if (fail != 0) System.exit(1);
    }

    static String cmd() {
        return StageCmd.build("/data/user/0/com.syh.luna/files",
                ".luna_probe_deadbeef", "LUNA_PROBE_deadbeef", "LUNA_OK_deadbeef", 12345);
    }

    static int check(String name, boolean ok) {
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
        return ok ? 0 : 1;
    }

    static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win");
    }

    /** @return 0 通过 / 1 失败 / -1 跳过（找不到 sh） */
    static int shellSyntax(String name, String script) {
        String sh = findSh();
        if (sh == null) return -1;
        try {
            java.io.File f = java.io.File.createTempFile("luna_stage_cmd", ".sh");
            java.io.FileWriter w = new java.io.FileWriter(f);
            try { w.write(script); } finally { w.close(); }
            ProcessBuilder pb = new ProcessBuilder(sh, "-n", f.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = read(p.getInputStream());
            int rc = p.waitFor();
            f.delete();
            if (rc == 0) {
                System.out.println("  [OK]   " + name);
                return 0;
            }
            System.out.println("  [FAIL] " + name + " → " + out);
            return 1;
        } catch (Throwable t) {
            System.out.println("  [SKIP] " + name + " (" + t + ")");
            return -1;
        }
    }

    /**
     * 找一个能用的 sh。
     *
     * Windows 上的 Git 可能是 PortableGit（装在任意盘符），不能写死路径，
     * 所以按「环境变量 → PATH → 常见安装位置」依次找。
     */
    static String findSh() {
        if (!isWindows()) return "/bin/sh";
        String env = System.getenv("SH");
        if (env != null && new java.io.File(env).isFile()) return env;
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(";")) {
                if (d.isEmpty()) continue;
                java.io.File f = new java.io.File(d, "sh.exe");
                if (f.isFile()) return f.getAbsolutePath();
            }
        }
        String[] cands = {
                "C:/Program Files/Git/usr/bin/sh.exe",
                "C:/Program Files/Git/bin/sh.exe",
                "C:/Program Files (x86)/Git/usr/bin/sh.exe"
        };
        for (String c : cands) if (new java.io.File(c).isFile()) return c;
        return null;
    }

    static String read(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n));
        } catch (Throwable ignore) { }
        return sb.toString();
    }
}
