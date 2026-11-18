package com.syh.luna.android;

/**
 * 生成 su 要执行的「暂存」命令。
 *
 * 抽成独立类有两个理由：
 *   1) **不依赖 Android API** —— 可以在纯 JVM 上跑自测（DirsSelfTest），
 *      验证生成的 shell 语法正确、复制清单完整。
 *   2) 命令构造与执行分离，出问题时能直接把这条命令打出来看。
 *
 * 这条命令做的事：
 *   ① 用探针文件找出「su 看到的」跟本 App 同一个的私有目录
 *   ② 把宿主的私有数据子目录拷进去
 *   ③ 把属主/标签/权限全部收回本 App
 *   ④ 写一个回执文件，让 App 侧确认 su 写的就是自己看得到的地方
 */
public final class StageCmd {

    private StageCmd() { }

    /** 宿主包名（汽水音乐） */
    public static final String HOST_PKG = "com.luna.music";
    /** 本 App 包名 */
    public static final String SELF_PKG = "com.syh.luna";
    /** 暂存目录名（位于本 App 私有目录内） */
    public static final String STAGE_NAME = "luna_stage";

    /**
     * 需要暂存的数据子路径。
     *
     * ⚠️ 这里跟复制命令里的 pair() 列表**必须一一对应** ——
     *   以前 Dirs.SUB 声明了 files/NetCacheLoader，stage() 却没复制它，
     *   root 模式下这一套缓存整个漏掉（标题/歌手查不到）。DirsSelfTest 会校验这一点。
     */
    public static final String[] SUB = {
            "files/music",              // .mdl 本体
            "databases",                // sqlite：player/history/recent_played/download
            "cache/NetCacheLoader",     // 接口原始 JSON blob（cache 版）
            "files/NetCacheLoader",     // 接口原始 JSON blob（files 版）—— 与上面是两套缓存，都要读
            "files/keva",               // key-value 缓存（含 cached_queue / initial_record 等 .sgv）
            "files/luna_export",        // 本模块 hook 抓到的密钥仓
    };

    /**
     * @param filesPath 本 App 私有目录（App 自己看到的路径）
     * @param probe     探针文件名（写在 filesPath 里，不在暂存区内）
     * @param marker    探针文件里的标记内容
     * @param token     回执文件里应写的内容
     * @param myUid     本 App 的 uid（用来 chown）
     */
    public static String build(String filesPath, String probe, String marker, String token, int myUid) {
        String A = "/data/data/" + HOST_PKG;
        String B = "/proc/1/root/data/data/" + HOST_PKG;

        StringBuilder sb = new StringBuilder();
        // ---- ① 找「su 看到的、跟 App 同一个」的私有目录
        sb.append("P=; for C in ");
        sb.append(q(filesPath)).append(" ");
        sb.append(q("/data/user/0/" + SELF_PKG + "/files")).append(" ");
        sb.append(q("/data/data/" + SELF_PKG + "/files")).append(" ");
        sb.append(q("/proc/1/root/data/user/0/" + SELF_PKG + "/files")).append(" ");
        sb.append(q("/proc/1/root/data/data/" + SELF_PKG + "/files")).append("; do ");
        sb.append("if cat \"$C/").append(probe).append("\" 2>/dev/null | grep -q ")
          .append(q(marker)).append("; then P=\"$C\"; break; fi; ");
        sb.append("done; ");
        // ★ 保护：一个都没匹配上就直接退出 —— **绝不在路径没确认时执行 rm -rf**
        sb.append("if [ -z \"$P\" ]; then echo 'NO_PROBE'; exit 9; fi; ");
        sb.append("echo \"STAGE_PARENT=$P\"; ");
        // ---- 学本 App 目录的 SELinux 上下文（探针是 App 自己创建的，标签是对的）
        // ls -Z 的输出是 "<权限> <属主> <属组> <上下文> <路径>"，上下文本身不含空格，
        // 取第 4 个字段最稳（不同 toybox 的 grep -o 输出格式有差异）。
        sb.append("CTX=$(ls -Zd \"$P/").append(probe).append("\" 2>/dev/null | awk '{print $4}'); ");
        sb.append("echo \"STAGE_CTX=$CTX\"; ");
        sb.append("S=\"$P/").append(STAGE_NAME).append("\"; ");
        sb.append("echo \"STAGE_PATH=$S\"; ");
        // ---- ② 到这一步路径已确认，才安全地重置暂存区
        sb.append("rm -rf \"$S\"; mkdir -p \"$S\"; ");
        // ---- 宿主数据源：直接路径优先，不行就用 init 的 root 看
        sb.append("if [ -e ").append(A).append("/files/music ]; then BASE=").append(A)
          .append("; else BASE=").append(B).append("; fi; ");
        sb.append("echo \"BASE=$BASE\"; ");
        // ★ 复制清单直接由 SUB 驱动 —— 不会再出现「声明了却没复制」的漏索引
        for (String sub : SUB) pair(sb, sub);
        // ---- ③ 属主 + 标签 + 权限：全部收回本 App
        // chown 收回属主；restorecon 按策略重打标签（更可靠）；
        // chcon 用探针学到的上下文兜底（restorecon 在部分 ROM 上不存在或被裁剪）。
        sb.append("chown -R ").append(myUid).append(" \"$S\" 2>&1; ");
        sb.append("restorecon -RF \"$S\" 2>/dev/null; ");
        sb.append("if [ -n \"$CTX\" ]; then chcon -R \"$CTX\" \"$S\" 2>&1 || true; fi; ");
        sb.append("chmod -R 700 \"$S\" 2>&1; ");
        // ---- ④ 回执：App 侧读得到这个文件，才说明 su 写的就是 App 看得到的地方
        sb.append("echo ").append(q(token)).append(" > \"$S/.luna_ok\" 2>&1; ");
        sb.append("chmod 600 \"$S/.luna_ok\" 2>/dev/null; ");
        sb.append("echo '---LS---'; ls \"$S\"; ");
        sb.append("echo '---COUNT---'; ls \"$S/files_music\" 2>/dev/null | wc -l");
        return sb.toString();
    }

    /** shell 单引号转义（路径里带空格/单引号时不能裸拼） */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** 拷贝一个子目录到暂存区（扁平命名：files/keva → files_keva） */
    static void pair(StringBuilder sb, String sub) {
        String flat = sub.replace('/', '_');
        sb.append("if [ -e \"$BASE/").append(sub).append("\" ]; then ")
          .append("cp -a \"$BASE/").append(sub).append("\" \"$S/").append(flat).append("\" 2>&1 ")
          .append("|| cp -r \"$BASE/").append(sub).append("\" \"$S/").append(flat).append("\" 2>&1; ")
          .append("fi; ");
    }
}
