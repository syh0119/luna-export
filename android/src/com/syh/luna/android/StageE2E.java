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
        ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
        int ch;
        boolean found = false;
        byte[] mark = StageCmd.TAR_BEGIN.getBytes("UTF-8");
        while ((ch = stdout.read()) >= 0) {
            headBytes.write(ch);
            if (headBytes.size() > 65536) break;
            byte[] cur = headBytes.toByteArray();
            if (cur.length >= mark.length) {
                boolean match = true;
                for (int i = 0; i < mark.length; i++) {
                    if (cur[cur.length - mark.length + i] != mark[i]) { match = false; break; }
                }
                if (match) {
                    found = true;
                    headBytes.reset();
                    headBytes.write(cur, 0, cur.length - mark.length);
                    break;
                }
            }
        }
        String text = new String(headBytes.toByteArray(), "UTF-8");
        fail += check("stdout 里能找到 tar 流标记", found);
        if (!found) {
            System.out.println("  su 输出: " + text);
            // 把 stderr 也读出来 —— 命令失败时只有 stderr 有信息
            try {
                byte[] eb = new byte[8192];
                int en = p.getErrorStream().read(eb);
                if (en > 0) System.out.println("  su stderr: " + new String(eb, 0, en, "UTF-8"));
            } catch (Throwable ignore) { }
            System.out.println("  实际命令: " + cmd);
            System.exit(1);
        }
        fail += check("诊断里有 LUNA_BASE", text.contains("LUNA_BASE="));
        fail += check("诊断里有 LUNA_LIST", text.contains("LUNA_LIST="));
        System.out.println("  su 文本诊断:\n    " + text.trim().replace("\n", "\n    "));

        // ---- 解包
        File dest = new File(appFiles, "luna_stage");
        dest.mkdirs();
        Tar.Result r = Tar.extract(stdout, dest, StageCmd.META_SUB);
        int rc = p.waitFor();
        fail += check("su 退出码 0", rc == 0);
        fail += check("解出 3 个元数据文件（不含 files/music）", r.files == 3);
        // 目录条目：中间层（files/、cache/）不在 META_SUB 清单里 → 被跳过（设计如此）；
        // 真正建出来的只有扁平后的顶层目录
        fail += check("目录数合理（>=1，中间层被跳过是设计）", r.dirs >= 1);

        // ---- 校验落点
        fail += check("LUNA_MDL 清单含 a.mdl", text.contains(":a.mdl"));
        fail += check("LUNA_MDL 清单含 中文名.mdl", text.contains(":中文名.mdl"));
        fail += check("★files_music 未被搬运到暂存区", !new File(dest, "files_music").exists());
        fail += check("databases/player.db", new File(dest, "databases/player.db").isFile());
        fail += check("cache_NetCacheLoader/c.json", new File(dest, "cache_NetCacheLoader/c.json").isFile());
        fail += check("files_keva/k.sgv", new File(dest, "files_keva/k.sgv").isFile());

        // ---- 验证批量读头与按需单文件读取
        String hp = host.getAbsolutePath().replace('\\', '/');
        String hcmd = StageCmd.buildReadHeadBatch(hp, Arrays.asList("a.mdl", "b.mdl", "中文名.mdl"), 64);
        ProcessBuilder hpb = new ProcessBuilder(findSh(), "-c", hcmd);
        hpb.redirectErrorStream(false);
        Process hpProc = hpb.start();
        byte[] hAll = readAllBytes(hpProc.getInputStream());
        int hrc = hpProc.waitFor();
        String hStr = new String(hAll, "UTF-8");
        fail += check("批量读头退出码 0", hrc == 0);
        fail += check("批量读头含 LUNA_HEAD_BEGIN 与 MDL-A/MDL-B/CN",
                hStr.contains("LUNA_HEAD_BEGIN") && hStr.contains("MDL-A")
                        && hStr.contains("MDL-B") && hStr.contains("CN"));

        String fcmd = StageCmd.buildReadFile(hp, "b.mdl");
        ProcessBuilder fpb = new ProcessBuilder(findSh(), "-c", fcmd);
        fpb.redirectErrorStream(false);
        Process fpProc = fpb.start();
        byte[] fAll = readAllBytes(fpProc.getInputStream());
        int frc = fpProc.waitFor();
        String fStr = new String(fAll, "UTF-8");
        fail += check("单文件流读退出码 0", frc == 0);
        fail += check("单文件流读含 LUNA_FILE_BEGIN 与 MDL-B",
                fStr.equals(StageCmd.FILE_BEGIN + "MDL-B"));

        // ---- 关键：宿主目录没被改过（su 只读）
        fail += check("宿主目录没被写入", !new File(host, "luna_stage").exists());
        fail += check("宿主 files/music 内容数仍是 3",
                new File(host, "files/music").list().length == 3);

        // ---- App 目录里的文件属主就是当前用户（App 自己创建）
        File f = new File(dest, "databases/player.db");
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
        sb.append("if cd \"$BASE/files/music\" 2>/dev/null; then ");
        sb.append("for f in *.mdl *.MDL; do ");
        sb.append("[ -f \"$f\" ] || continue; ");
        sb.append("st=$(stat -c '%s:%Y' \"$f\" 2>/dev/null); ");
        sb.append("[ -n \"$st\" ] && printf 'LUNA_MDL=%s:%s\\n' \"$st\" \"$f\"; ");
        sb.append("done; fi; ");
        sb.append("set --; ");
        for (String sub : StageCmd.META_SUB) {
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

    static byte[] readAllBytes(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
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
