package com.syh.luna.android;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 暂存链路的离线自测（不需要设备、不需要 root）。
 *
 * 覆盖四层：
 *   1) **tar 解包器**（Tar）—— 用本机真实 tar 生成的归档验证，
 *      包含：普通文件、嵌套目录、GNU longname、空文件、中文名、
 *      路径穿越拒绝。
 *   2) **tar 命令构造**（StageCmd.buildTar）—— shell 语法、清单、
 *      空清单保护、二进制标记位置。
 *   3) **cp 兜底命令构造**（StageCmd.buildCopy）—— 回执必须在 chown **之前**、
 *      标签提取不能再用 $4、cp 不能用 -a（会带来源标签）。
 *   4) **清单一致性** —— SUB 与复制清单一一对应。
 *
 * 跑法：
 *   javac -d out/classes android/src/com/syh/luna/android/{Tar,StageCmd,DirsSelfTest}.java
 *   java -cp out/classes com.syh.luna.android.DirsSelfTest
 */
public final class DirsSelfTest {

    public static void main(String[] args) {
        int fail = 0;

        // ================= 1) Tar 解包器 =================
        fail += tarTests();

        // ================= 2) tar 命令构造 =================
        String tcmd = StageCmd.buildTar("tar");
        fail += check("tar 命令非空", tcmd.length() > 100);
        fail += check("tar 命令含 tar 流标记", tcmd.contains("LUNA_TAR_BEGIN"));
        fail += check("tar 命令有源选择（比较内容数）", tcmd.contains("BEST"));
        fail += check("tar 命令有源缺失保护", tcmd.contains("LUNA_NO_BASE"));
        fail += check("tar 命令有空清单保护", tcmd.contains("LUNA_NO_LIST"));
        fail += check("tar 命令用 -f - 输出到 stdout", tcmd.contains("-f -"));
        fail += check("tar 命令不硬编码 /data/local/tmp", !tcmd.contains("/data/local/tmp"));
        // ★ 用位置参数（set --）而不是字符串累积 ——
        //   `LIST="$LIST a b"` + `tar ... $LIST` 会被 shell 按 IFS 分词，
        //   路径里有空格就碎了，而且未加引号还会做 glob 展开。
        fail += check("★tar 清单用位置参数 set --（字符串累积会被 shell 分词）",
                tcmd.contains("set --") && tcmd.contains("\"$@\""));
        fail += check("★tar 空清单判据用 $#（字符串判据在 set -- 下无效）",
                tcmd.contains("$# -eq 0"));
        for (String sub : StageCmd.SUB) {
            fail += check("tar 清单含 " + sub, tcmd.contains("set -- \"$@\" " + sub));
        }
        int sc = shellSyntax("tar 命令 sh -n 通过", tcmd);
        if (sc >= 0) fail += sc;

        // ================= 3) cp 兜底命令构造 =================
        String ccmd = cmd();
        fail += check("cp 命令非空", ccmd.length() > 100);
        fail += check("cp 命令含 NO_PROBE 保护", ccmd.contains("NO_PROBE"));
        fail += check("cp 命令含 rm -rf 暂存", ccmd.contains("rm -rf \"$f\""));
        fail += check("cp 命令 chown 回本 App", ccmd.contains("chown -R"));
        fail += check("cp 命令权限 u+rwX", ccmd.contains("u+rwX"));
        fail += check("cp 命令不再 chmod 777", !ccmd.contains("777"));
        fail += check("cp 命令含回执文件", ccmd.contains(".luna_ok"));
        fail += check("cp 命令不硬编码 /data/local/tmp", !ccmd.contains("/data/local/tmp"));
        fail += check("暂存名在私有目录下", ccmd.contains("luna_stage"));

        // ★★★ v1.1.4 修的真机致命 bug —— 这三条是回归测试的核心 ★★★
        int iChown = ccmd.indexOf("chown -R");
        int iOk = ccmd.indexOf(".luna_ok");
        fail += check("★回执写入在 chown 之前（否则属主是 root、mode 600，App 读不到）",
                iOk > 0 && iChown > 0 && iOk < iChown);
        fail += check("★标签提取不再用 awk $4（toybox ls -Z 只有两段，取 $4 必为空）",
                !ccmd.contains("awk '{print $4}'"));
        fail += check("★标签提取改用 u: 前缀 token", ccmd.contains("grep '^u:'"));
        fail += check("★cp 不用 -a（会带来源 SELinux 标签）", !ccmd.contains("cp -a"));

        for (String sub : StageCmd.SUB) {
            if ("files/music".equals(sub)) continue;
            String flat = sub.replace('/', '_');
            fail += check("cp 清单覆盖 " + sub + " → " + flat, ccmd.contains(flat));
        }
        sc = shellSyntax("cp 命令 sh -n 通过", ccmd);
        if (sc >= 0) fail += sc;

        // ================= 4) 清单一致性 =================
        for (String sub : StageCmd.SUB) {
            fail += check("SUB 覆盖 " + sub, tcmd.contains(sub) && ccmd.contains(sub));
        }
        fail += check("SUB 里没有 /data/local/tmp",
                !Arrays.toString(StageCmd.SUB).contains("/data/local/tmp"));

        System.out.println(fail == 0 ? "\n全部通过" : "\n失败 " + fail + " 项");
        if (fail != 0) System.exit(1);
    }

    // ================================================================== tar 解包器测试

    static int tarTests() {
        int fail = 0;
        File tmp = null;
        try {
            tmp = File.createTempFile("luna_tar", "");
            tmp.delete();
            tmp.mkdirs();

            // ---- 造一棵真实的目录树
            File src = new File(tmp, "src");
            File deep = new File(src, "files/music");
            File cache = new File(src, "cache/NetCacheLoader");
            File dbs = new File(src, "databases");
            deep.mkdirs();
            cache.mkdirs();
            dbs.mkdirs();
            write(new File(deep, "a.mdl"), "MDL-A");
            write(new File(deep, "b.mdl"), "MDL-B-with-longer-content");
            write(new File(deep, "empty.dat"), "");
            write(new File(cache, "c.json"), "{\"n\":1}");
            // ⚠️ 单段路径（databases/xxx.db）—— 通用「合并前两段」规则会把它错改成
            //    databases_xxx.db，必须验证精确前缀映射没犯这个错
            write(new File(dbs, "player.db"), "SQLITE-PLAYER");
            // 单个文件名超过 100 字节（ustar name 字段上限）→ 触发 GNU longname 扩展头。
            // 注意整个路径要 > 100 字节，才不会被 tar 改用 pax 扩展。
            StringBuilder longName = new StringBuilder("L");
            for (int i = 0; i < 140; i++) longName.append("z");
            longName.append(".mdl");
            write(new File(deep, longName.toString()), "LONG");
            // 中文名
            write(new File(deep, "中文名.mdl"), "CN");

            // ---- 用本机 tar 打包（优先 GNU tar；找不到就用 busybox）
            File tarFile = new File(tmp, "a.tar");
            String tarExe = findTar();
            if (tarExe == null) {
                System.out.println("  [SKIP] tar 解包测试（本机没找到 tar）");
                return 0;
            }
            int rc = run(tarExe, new String[]{"-cf", tarFile.getAbsolutePath(),
                    "-C", src.getAbsolutePath(), "."}, tmp);
            if (rc != 0) {
                System.out.println("  [FAIL] tar 解包测试（tar 打包失败 rc=" + rc + "）");
                return 1;
            }

            // ---- 解包（传 SUB 清单 → 精确前缀映射，对应真机暂存区的扁平命名）
            File dst = new File(tmp, "dst");
            dst.mkdirs();
            Tar.Result r;
            InputStream in = new FileInputStream(tarFile);
            try {
                r = Tar.extract(in, dst, StageCmd.SUB);
            } finally {
                in.close();
            }
            fail += check("tar 解出文件数 > 0", r.files > 0);
            fail += check("tar 解出字节数 > 0", r.bytes > 0);
            fail += check("tar 无错误", r.error == null || r.error.length() == 0);
            // ⚠️ 扁平化：files/music/x.mdl → files_music/x.mdl（与 IndexBuilder.sub() 一致）
            fail += check("tar 重映射 files/music → files_music",
                    new File(dst, "files_music/a.mdl").isFile());
            fail += check("tar 重映射 content 校验 a.mdl",
                    eq(read(new File(dst, "files_music/a.mdl")), "MDL-A"));
            fail += check("tar 重映射 content 校验 b.mdl",
                    eq(read(new File(dst, "files_music/b.mdl")), "MDL-B-with-longer-content"));
            fail += check("tar 空文件保留", new File(dst, "files_music/empty.dat").isFile());
            fail += check("tar 重映射 cache/NetCacheLoader → cache_NetCacheLoader",
                    new File(dst, "cache_NetCacheLoader/c.json").isFile());
            // ⚠️ 单段路径不能被错改（databases/player.db 不能变成 databases_player.db）
            fail += check("tar 单段路径 databases/player.db 不被错改",
                    new File(dst, "databases/player.db").isFile()
                            && !new File(dst, "databases_player.db").exists());
            fail += check("tar GNU longname 文件", new File(dst, "files_music/" + longName).isFile());
            fail += check("tar 中文名文件", new File(dst, "files_music/中文名.mdl").isFile());

            // ---- 路径穿越必须被拒绝
            File evil = new File(tmp, "evil");
            evil.mkdirs();
            byte[] evilTar = buildEvilTar("../escaped.txt");
            InputStream ein = new ByteArrayInputStream(evilTar);
            Tar.Result er;
            try {
                er = Tar.extract(ein, evil);
            } finally {
                ein.close();
            }
            fail += check("tar 拒绝 ../ 路径穿越", !new File(tmp, "escaped.txt").exists());
            fail += check("tar 路径穿越被计入 skipped", er.skipped > 0);
        } catch (Throwable t) {
            System.out.println("  [FAIL] tar 解包测试异常: " + t);
            fail++;
        } finally {
            if (tmp != null) rmrf(tmp);
        }
        return fail;
    }

    /** 手工构造一个含 ../ 的 tar 头 */
    static byte[] buildEvilTar(String name) throws Exception {
        byte[] h = new byte[512];
        put(h, name, 0, 100);
        put(h, "0000644", 100, 8);
        put(h, "0000000", 108, 8);
        put(h, "0000000", 116, 8);
        put(h, "00000000010", 124, 12);
        put(h, "00000000000", 136, 12);
        h[156] = '0';
        put(h, "ustar  ", 257, 8);
        put(h, "00", 329, 2);
        // checksum
        for (int i = 148; i < 156; i++) h[i] = ' ';
        long sum = 0;
        for (int i = 0; i < 512; i++) sum += h[i] & 0xff;
        put(h, String.format("%06o", sum) + "\0 ", 148, 8);
        byte[] body = "PWNED".getBytes("UTF-8");
        byte[] bodyPad = new byte[512];
        System.arraycopy(body, 0, bodyPad, 0, body.length);
        byte[] out = new byte[512 * 2 + 1024];
        System.arraycopy(h, 0, out, 0, 512);
        System.arraycopy(bodyPad, 0, out, 512, 512);
        return out;
    }

    static void put(byte[] b, String s, int off, int len) {
        byte[] x;
        try {
            x = s.getBytes("UTF-8");
        } catch (Throwable t) {
            x = s.getBytes();
        }
        int n = Math.min(x.length, len - 1);
        System.arraycopy(x, 0, b, off, n);
    }

    static void write(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(s.getBytes("UTF-8"));
        } finally {
            o.close();
        }
    }

    static String read(File f) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
            return new String(b, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    static boolean eq(String a, String b) {
        return b != null && b.equals(a);
    }

    static String q(File dir, File f) {
        return "\"" + f.getAbsolutePath().replace("\\", "/") + "\"";
    }

    /** 找一个 tar：优先 GNU tar，再试 busybox */
    static String findTar() {
        String[] cands = {"tar", "C:/Program Files/Git/usr/bin/tar.exe",
                "C:/Program Files/Git/bin/tar.exe"};
        for (String c : cands) {
            try {
                ProcessBuilder pb = new ProcessBuilder(c, "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                p.getInputStream().read();
                if (p.waitFor() == 0) return c;
            } catch (Throwable ignore) { }
        }
        return null;
    }

    /** 直接传参跑一个可执行文件（不经 shell —— 免得 Windows 路径的引号出问题） */
    static int run(String exe, String[] args, File cwd) {
        try {
            String[] all = new String[args.length + 1];
            all[0] = exe;
            System.arraycopy(args, 0, all, 1, args.length);
            ProcessBuilder pb = new ProcessBuilder(all);
            pb.directory(cwd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] b = new byte[4096];
            while (p.getInputStream().read(b) > 0) { }
            return p.waitFor();
        } catch (Throwable t) {
            return -1;
        }
    }

    static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) rmrf(c);
        f.delete();
    }

    // ================================================================== 通用

    static String cmd() {
        return StageCmd.buildCopy("/data/user/0/com.syh.luna/files",
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
        if (sh == null) {
            System.out.println("  (没找到 sh，跳过语法检查)");
            return -1;
        }
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
                "C:/Program Files/Git (x86)/usr/bin/sh.exe"
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
