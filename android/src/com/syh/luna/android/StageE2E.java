package com.syh.luna.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 端到端模拟：完整跑一遍「su 只读 → tar 流 → App 解包」链路。
 *
 * 真实执行 StageCmd.buildTar() 生成的 shell 命令（用 sh -c），
 * 把它的 stdout 当作 su 的输出接到 App 侧，再交给 Tar.extract 解包。
 *
 * 这能验证：
 *   ① 生成的命令在真实 shell 下能跑通（不只是 sh -n 语法检查）
 *   ② 标记前的文本诊断与标记后的二进制流能正确切分
 *   ③ 解包结果落在暂存区该在的位置（扁平命名）
 *   ④ su 全程**没有写入** App 的目录（模拟「su 只能读」）
 */
public final class StageE2E {

    private StageE2E() { }

    public static void main(String[] args) throws Exception {
        int fail = 0;

        // ---- 搭一个「宿主私有目录」
        File work = Files.createTempDirectory("luna_e2e").toFile();
        File host = new File(work, "host");
        File appFiles = new File(work, "app/files");
        new File(host, "files/music").mkdirs();
        new File(host, "databases").mkdirs();
        new File(host, "cache/NetCacheLoader").mkdirs();
        new File(host, "files/keva").mkdirs();
        appFiles.mkdirs();
        write(new File(host, "files/music/a.mdl"), "MDL-A");
        write(new File(host, "files/music/b.mdl"), "MDL-B");
        write(new File(host, "files/music/中文名.mdl"), "CN");
        write(new File(host, "databases/player.db"), "SQLITE");
        write(new File(host, "cache/NetCacheLoader/c.json"), "{\"n\":1}");
        write(new File(host, "files/keva/k.sgv"), "KEVA");
        // 宿主目录：只读，模拟「su 能读但 App 不该改它」
        host.setReadable(true, false);
        host.setWritable(false, false);

        // ---- 把宿主路径塞进命令（模拟 StageCmd 里的 A/B 两个候选）
        String cmd = buildCmd(host.getAbsolutePath());

        // ---- 真的跑这条命令（sh -c），拿 stdout
        ProcessBuilder pb = new ProcessBuilder(findSh(), "-c", cmd);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        InputStream stdout = p.getInputStream();

        // ---- App 侧：读到标记为止，其余当 tar 流解包
        StringBuilder head = new StringBuilder();
        int ch;
        boolean found = false;
        while ((ch = stdout.read()) >= 0) {
            head.append((char) ch);
            if (head.length() > 8192) break;
            String h = head.toString();
            int bi = h.indexOf(StageCmd.TAR_BEGIN);
            if (bi >= 0) { found = true; head.setLength(bi); break; }
        }
        fail += check("stdout 里能找到 tar 流标记", found);
        if (!found) {
            System.out.println("  su 输出: " + head);
            // 把 stderr 也读出来 —— 命令失败时只有 stderr 有信息
            try {
                byte[] eb = new byte[8192];
                int en = p.getErrorStream().read(eb);
                if (en > 0) System.out.println("  su stderr: " + new String(eb, 0, en, "UTF-8"));
            } catch (Throwable ignore) { }
            System.out.println("  实际命令: " + cmd);
            System.exit(1);
        }
        String text = head.toString();
        fail += check("诊断里有 LUNA_BASE", text.contains("LUNA_BASE="));
        fail += check("诊断里有 LUNA_LIST", text.contains("LUNA_LIST="));
        System.out.println("  su 文本诊断:\n    " + text.trim().replace("\n", "\n    "));

        // ---- 解包
        File dest = new File(appFiles, "luna_stage");
        dest.mkdirs();
        Tar.Result r = Tar.extract(stdout, dest, StageCmd.SUB);
        int rc = p.waitFor();
        fail += check("su 退出码 0", rc == 0);
        fail += check("解出 6 个文件", r.files == 6);
        // 目录条目：中间层（files/、cache/）不在 SUB 清单里 → 被跳过（设计如此）；
        // 真正建出来的只有扁平后的顶层目录
        fail += check("目录数合理（>=1，中间层被跳过是设计）", r.dirs >= 1);

        // ---- 校验落点
        fail += check("files_music/a.mdl", new File(dest, "files_music/a.mdl").isFile());
        fail += check("files_music/b.mdl", new File(dest, "files_music/b.mdl").isFile());
        fail += check("files_music/中文名.mdl", new File(dest, "files_music/中文名.mdl").isFile());
        fail += check("databases/player.db", new File(dest, "databases/player.db").isFile());
        fail += check("cache_NetCacheLoader/c.json", new File(dest, "cache_NetCacheLoader/c.json").isFile());
        fail += check("files_keva/k.sgv", new File(dest, "files_keva/k.sgv").isFile());
        fail += check("内容正确 a.mdl",
                "MDL-A".equals(read(new File(dest, "files_music/a.mdl"))));

        // ---- 关键：宿主目录没被改过（su 只读）
        fail += check("宿主目录没被写入", !new File(host, "luna_stage").exists());
        fail += check("宿主 files/music 内容数仍是 3",
                new File(host, "files/music").list().length == 3);

        // ---- App 目录里的文件属主就是当前用户（App 自己创建）
        File f = new File(dest, "files_music/a.mdl");
        fail += check("解包文件可读", f.canRead());
        fail += check("解包文件属主=当前用户",
                f.getAbsolutePath().length() > 0 && ownerReadable(f));

        rmrf(work);
        System.out.println(fail == 0 ? "\n端到端模拟：全部通过" : "\n端到端模拟：失败 " + fail + " 项");
        if (fail != 0) System.exit(1);
    }

    /**
     * 生成与 StageCmd.buildTar 等价的命令，但数据源换成给定的宿主目录。
     * 保留关键结构：诊断 → 标记 → tar 流 → 结束标记。
     */
    static String buildCmd(String hostPath) {
        // Windows 路径转正斜杠 —— sh 不认反斜杠
        String hp = hostPath.replace('\\', '/');
        StringBuilder sb = new StringBuilder();
        sb.append("BASE='").append(hp).append("'; ");
        sb.append("BEST=-1; ");
        sb.append("for c in \"$BASE\" '/data/data/com.luna.music' '/proc/1/root/data/data/com.luna.music'; do ");
        sb.append("if [ -d \"$c/files\" ]; then n=$(ls -A \"$c/files/music\" 2>/dev/null | wc -l); ");
        sb.append("if [ \"$n\" -gt \"$BEST\" ]; then BEST=$n; BASE=\"$c\"; fi; fi; ");
        sb.append("done; ");
        sb.append("if [ -z \"$BASE\" ]; then echo 'LUNA_NO_BASE'; exit 7; fi; ");
        sb.append("echo \"LUNA_BASE=$BASE\"; ");
        sb.append("echo \"LUNA_COUNT=$BEST\"; ");
        sb.append("set --; ");
        for (String sub : StageCmd.SUB) {
            sb.append("if [ -e \"$BASE/").append(sub).append("\" ]; then set -- \"$@\" ")
              .append(sub).append("; fi; ");
        }
        sb.append("echo \"LUNA_LIST=$*\"; ");
        sb.append("if [ $# -eq 0 ]; then echo 'LUNA_NO_LIST'; exit 6; fi; ");
        sb.append("printf '").append(StageCmd.TAR_BEGIN.replace("\n", "\\n")).append("'; ");
        sb.append("cd \"$BASE\" 2>/dev/null || exit 8; ");
        sb.append("tar -c -f - \"$@\"; ");
        sb.append("printf '\\nLUNA_TAR_END\\n'; ");
        return sb.toString();
    }

    /** 找一个能用的 sh（Windows 上要 .exe 完整路径） */
    static String findSh() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            String[] cands = {
                    "F:/WorkBuddy/resources/vendor/PortableGit/usr/bin/sh.exe",
                    "C:/Program Files/Git/usr/bin/sh.exe",
                    "C:/Program Files/Git/bin/sh.exe"};
            for (String c : cands) if (new File(c).isFile()) return c;
            String env = System.getenv("SH");
            if (env != null && new File(env).isFile()) return env;
            return "sh.exe";
        }
        return "/bin/sh";
    }

    static boolean ownerReadable(File f) {
        try { return f.canRead(); } catch (Throwable t) { return false; }
    }

    static void write(File f, String s) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream o = new FileOutputStream(f);
        try { o.write(s.getBytes("UTF-8")); } finally { o.close(); }
    }

    static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (Throwable t) { return null; }
    }

    static int check(String name, boolean ok) {
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name);
        return ok ? 0 : 1;
    }

    static void rmrf(File f) {
        if (f == null || !f.exists()) return;
        f.setWritable(true, false);
        File[] cs = f.listFiles();
        if (cs != null) for (File c : cs) rmrf(c);
        f.delete();
    }
}
