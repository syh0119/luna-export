package com.syh.luna.android;

/**
 * 生成 su 要执行的「暂存」命令。
 *
 * 抽成独立类有两个理由：
 *   1) **不依赖 Android API** —— 可以在纯 JVM 上跑自测（DirsSelfTest），
 *      验证生成的 shell 语法正确、复制清单完整。
 *   2) 命令构造与执行分离，出问题时能直接把这条命令打出来看。
 *
 * ─────────────────────────────────────────────────────────────────────
 * v1.1.4 重大改造：**su 只读不写**
 *
 * 原设计是让 su 把宿主私有数据 `cp` 到本 App 的私有目录里，
 * 再靠 chown / chcon / restorecon 把属主和 SELinux 标签「掰」回本 App。
 * 这条路在真机上踩了三个坑（都是 100% 必现，不是设备差异）：
 *
 *   ① 回执文件写在 chown 之后 → 属主 root、mode 600
 *      → App 连自己写好的回执都读不到 → 误报「暂存区不可见」。
 *   ② SELinux 标签用 `ls -Zd | awk '{print $4}'` 提取 ——
 *      toybox 的 ls -Z 输出是「上下文 路径」两段，取 $4 必然是空串
 *      → chcon 被跳过 → 宿主 App 的 MCS 类别带过来 → 一个字节都读不到。
 *   ③ 依赖 chcon / restorecon，而这两个命令在部分 ROM / Root 方案里
 *      不存在或被裁剪 —— 一旦缺失就没有任何兜底。
 *
 * 新设计：**su 只把宿主数据打成 tar 流吐到 stdout，App 自己解包进自己的目录**。
 *
 *   · 每一个文件都是 **App 自己创建的** → 属主天然正确；
 *   · 内核按**创建者的域**做 type_transition，App 在自己的 app_data_file
 *     目录里建文件，MCS 类别自动继承本 App 那一套 → SELinux 标签天然正确；
 *   · 完全不需要 chown / chcon / restorecon；
 *   · 完全不依赖 su 与 App 是否共享同一个挂载命名空间 ——
 *     su 只需要能**读**到宿主数据（读不到就换 /proc/1/root），
 *     写不写得到本 App 的目录根本无关紧要。
 *
 * 代价是需要设备上有 tar（toybox tar 或 busybox tar）。
 * 运行时探测（{@link #detectTar()}），没有 tar 时退回 {@link #buildCopy()} 兜底。
 */
public final class StageCmd {

    private StageCmd() { }

    /** 宿主包名（汽水音乐） */
    public static final String HOST_PKG = "com.luna.music";
    /** 本 App 包名 */
    public static final String SELF_PKG = "com.syh.luna";
    /** 暂存目录名（位于本 App 私有目录内） */
    public static final String STAGE_NAME = "luna_stage";

    /** tar 流与文本诊断的分隔标记（App 侧按这个切分 stdout） */
    public static final String TAR_BEGIN = "LUNA_TAR_BEGIN\n";
    /** 批量读 .mdl 头部流的分隔标记 */
    public static final String HEAD_BEGIN = "LUNA_HEAD_BEGIN\n";
    /** 单文件流读取的分隔标记 */
    public static final String FILE_BEGIN = "LUNA_FILE_BEGIN\n";
    /** 扫描体检读取的单个 .mdl 头部上限（128 KB，覆盖 99%+ 真机 MP4 moov，不足时按需回退全文件） */
    public static final int HEAD_BYTES = 128 * 1024;
    private static volatile String cachedTarStyle;

    static void resetTarCache() {
        cachedTarStyle = null;
    }

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

    /** 轻量化暂存子路径：只搬索引所需的元数据目录，不搬动辄数 GB 的 files/music */
    public static final String[] META_SUB = {
            "databases",
            "cache/NetCacheLoader",
            "files/NetCacheLoader",
            "files/keva",
            "files/luna_export",
    };

    // ================================================================== 方案 A：tar 流（首选）

    /**
     * 探测设备上有没有可用的 tar。
     *
     * @return "tar" / "busybox tar" / ""（没有）
     */
    public static String detectTar() {
        String cached = cachedTarStyle;
        if (cached != null) return cached;
        String out = run("if command -v tar >/dev/null 2>&1; then echo HAS_TAR; fi; "
                + "if command -v busybox >/dev/null 2>&1; then "
                + "if busybox 2>&1 | tr ' ' '\\n' | grep -qx tar; then echo HAS_BUSYBOX_TAR; fi; fi;");
        if (out == null) return "";
        if (out.contains("HAS_TAR")) {
            cachedTarStyle = "tar";
            return "tar";
        }
        if (out.contains("HAS_BUSYBOX_TAR")) {
            cachedTarStyle = "busybox tar";
            return "busybox tar";
        }
        return "";
    }

    /**
     * 生成「把宿主数据打成 tar 流」的命令。
     *
     * 输出格式：
     *   <若干行文本诊断>
     *   LUNA_TAR_BEGIN\n
     *   <二进制 tar 流>            ← App 从这里开始交给 Tar.extract
     *   \nLUNA_TAR_END\n
     *
     * ⚠️ 文本诊断必须在标记**之前**全部打印完 —— 标记之后就是二进制，
     *    再混进任何一行文本都会污染 tar 流。
     *
     * @param tarStyle "tar" 或 "busybox tar"
     */
    public static String buildTar(String tarStyle) {
        String A = "/data/data/" + HOST_PKG;
        String B = "/proc/1/root/data/data/" + HOST_PKG;

        StringBuilder sb = new StringBuilder();
        // ---- ① 选数据源：两个候选里挑「files/music 内容更多」的那个
        // 挂载命名空间里 /data 有时是 stub（有目录但没内容），
        // 所以不能只判断存不存在，要比内容数量。
        sb.append("BASE=; BEST=-1; ");
        sb.append("for c in ").append(q(A)).append(" ").append(q(B)).append("; do ");
        sb.append("if [ -d \"$c/files\" ]; then n=$(ls -A \"$c/files/music\" 2>/dev/null | wc -l); ");
        sb.append("if [ \"$n\" -gt \"$BEST\" ]; then BEST=$n; BASE=\"$c\"; fi; fi; ");
        sb.append("done; ");
        sb.append("if [ -z \"$BASE\" ]; then echo 'LUNA_NO_BASE'; exit 7; fi; ");
        sb.append("echo \"LUNA_BASE=$BASE\"; ");
        sb.append("echo \"LUNA_COUNT=$BEST\"; ");
        // ---- 输出 files/music/*.mdl 与 files/download/<uid>/* 元清单（size:mtime:relPath），免去搬运数 GB 本体
        appendEnumerateMdl(sb);
        // ---- ② 清单：仅打包元数据子目录（META_SUB），绝不打包数 GB 的 files/music
        sb.append("set --; ");
        for (String sub : META_SUB) {
            sb.append("if [ -e \"$BASE/").append(sub).append("\" ]; then set -- \"$@\" ")
              .append(sub).append("; fi; ");
        }
        // 清单内容（诊断用，打印在二进制标记之前）
        sb.append("echo \"LUNA_LIST=$*\"; ");
        // 空清单必须挡住 —— 否则 `tar -c -f -`（无操作数）会把**整个 $BASE** 打包
        sb.append("if [ $# -eq 0 ]; then echo 'LUNA_NO_LIST'; exit 6; fi; ");
        // ---- ③ 分隔标记：App 从这行之后开始按二进制读
        sb.append("printf '").append(TAR_BEGIN.replace("\n", "\\n")).append("'; ");
        // ---- ④ 打包
        sb.append("cd \"$BASE\" 2>/dev/null || exit 8; ");
        sb.append(tarStyle).append(" -c -f - \"$@\"; ");
        sb.append("printf '\\nLUNA_TAR_END\\n'; ");
        return sb.toString();
    }

    // ================================================================== 方案 B：cp 兜底（设备没 tar 时）

    /**
     * 备选方案：让 su 直接把数据拷进本 App 的私有目录。
     *
     * ⚠️ 这条路对属主与 SELinux 标签要求很严，v1.1.4 修了两个 100% 必现的 bug：
     *
     *   A) **回执属主错** —— 原来 chown 在前、写回执在后，回执由 root 创建、
     *      属主 root、权限 600，App 读自己的回执都被 DAC 拒绝 → 报「暂存区不可见」。
     *      修法：回执在 chown **之前**写，chown/chmod 统一覆盖它。
     *
     *   B) **标签取错字段** —— 原来用 {@code ls -Zd | awk '{print $4}'}，
     *      以为 toybox 会输出 long 格式的「权限 属主 属组 上下文 路径」。
     *      实际 toybox 的 {@code ls -Z} 输出是「上下文 路径」两段，
     *      取 $4 必然是空串 → chcon 被跳过 → 拷过来的文件带着**宿主 App 的
     *      MCS 类别**（app_data_file:s0:cXXX,cYYY，XXX 是宿主 uid），
     *      本 App 一个字节都读不到。
     *      修法：按「以 u: 开头的 token」提取，并配 stat/ls 多路兜底。
     *
     * @param filesPath 本 App 私有目录（App 自己看到的路径）
     * @param probe     探针文件名（写在 filesPath 与 filesPath/luna_stage 里）
     * @param marker    探针文件里的标记内容
     * @param token     回执文件里应写的内容
     * @param myUid     本 App 的 uid（用来 chown）
     */
    public static String buildCopy(String filesPath, String probe, String marker, String token, int myUid) {
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
        // ★ 保护：一个都没匹配上就直接退出 —— **绝不在路径没确认时执行删除**
        sb.append("if [ -z \"$P\" ]; then echo 'NO_PROBE'; exit 9; fi; ");
        sb.append("echo \"STAGE_PARENT=$P\"; ");
        sb.append("S=\"$P/").append(STAGE_NAME).append("\"; ");
        sb.append("echo \"STAGE_PATH=$S\"; ");

        // ---- 学 SELinux 上下文（多路兜底）
        // toybox `ls -Z` 的输出格式是「<上下文> <路径>」，
        // 所以不能按字段序号取，要按「以 u: 开头的 token」取。
        // 优先用暂存目录里的探针（App 自己创建，标签最准），
        // 退而求其次用父目录的探针（同一个 app_data_file + 同一套 MCS 类别）。
        sb.append("CTX=; ");
        sb.append("for F in \"$S/").append(probe).append("\" \"$P/").append(probe).append("\"; do ");
        sb.append("if [ -z \"$CTX\" ]; then ");
        sb.append("CTX=$(stat -c %C \"$F\" 2>/dev/null); ");
        sb.append("if [ -z \"$CTX\" ]; then CTX=$(ls -Zd \"$F\" 2>/dev/null | tr ' ' '\\n' | grep '^u:' | head -1); fi; ");
        sb.append("fi; done; ");
        sb.append("echo \"STAGE_CTX=$CTX\"; ");

        // ---- ② 清空暂存目录的**内容**，但保留目录本身
        // 目录是 App 自己创建的：属主 = App uid，标签 = app_data_file + App 的 MCS 类别。
        // 如果这里 rm -rf 掉再由 root 重建，目录的标签就变成 root 那套，
        // 里面所有文件都会被判给别的类别 —— App 照样读不到。
        sb.append("for f in \"$S\"/* \"$S\"/.[!.]*; do [ -e \"$f\" ] || continue; rm -rf \"$f\"; done; ");
        sb.append("mkdir -p \"$S\"; ");

        // ---- 宿主数据源：直接路径优先，不行就用 init 的 root 看
        sb.append("if [ -e ").append(A).append("/files/music ]; then BASE=").append(A)
          .append("; else BASE=").append(B).append("; fi; ");
        sb.append("echo \"BASE=$BASE\"; ");

        // ★ 复制清单仅包含元数据目录（META_SUB），不搬运 files/music
        for (String sub : META_SUB) pair(sb, sub);

        // ---- ③ 回执：**先写**，后面的 chown/chcon/chmod 会一并覆盖它
        // （原来写在 chown 之后 → 属主 root + 权限 600 → App 永远读不到）
        sb.append("printf '%s' ").append(q(token)).append(" > \"$S/.luna_ok\" 2>&1; ");

        // ---- ④ 属主 + 标签 + 权限：全部收回本 App
        sb.append("chown -R ").append(myUid).append(" \"$S\" 2>&1; ");
        sb.append("if [ -n \"$CTX\" ]; then chcon -R \"$CTX\" \"$S\" 2>&1 || restorecon -RF \"$S\" 2>&1; ");
        sb.append("else restorecon -RF \"$S\" 2>&1; fi; ");
        // u+rwX：目录带执行位、普通文件不带（chmod 700 会给 .mdl / sqlite 加上执行位，不对）
        sb.append("chmod -R u+rwX,go-rwx \"$S\" 2>&1; ");
        // 回执给 644：目录本身是 700，外壳进不来；万一 chown 失败也还能读
        sb.append("chmod 644 \"$S/.luna_ok\" 2>/dev/null; ");

        // ---- 诊断输出（App 侧解析失败时会把这些原样带出来）
        sb.append("echo '---LS---'; ls \"$S\"; ");
        sb.append("echo \"OKLABEL=$(ls -Zd \"$S/.luna_ok\" 2>/dev/null | tr ' ' '\\n' | grep '^u:' | head -1)\"; ");
        sb.append("echo \"DIRLABEL=$(ls -Zd \"$S\" 2>/dev/null | tr ' ' '\\n' | grep '^u:' | head -1)\"; ");
        sb.append("echo \"OWNER=$(stat -c %u \"$S\" 2>/dev/null)\"; ");
        sb.append("echo \"COUNT=$(ls \"$BASE/files/music\" 2>/dev/null | wc -l)\"; ");
        appendEnumerateMdl(sb);
        return sb.toString();
    }

    static void appendEnumerateMdl(StringBuilder sb) {
        sb.append("if cd \"$BASE/files/music\" 2>/dev/null; then ");
        sb.append("find . -maxdepth 1 -type f \\( -name '*.mdl' -o -name '*.MDL' \\) -exec stat -c 'LUNA_MDL=%s:%Y:music/%n' {} + 2>/dev/null | sed 's|:music/\\./|:music/|'; ");
        sb.append("fi; ");
        sb.append("if cd \"$BASE/files/download\" 2>/dev/null; then ");
        sb.append("for d in [0-9]*; do ");
        sb.append("[ -d \"$d\" ] && [ ! -L \"$d\" ] || continue; ");
        sb.append("case \"$d\" in ''|*[!0-9]*) continue;; esac; ");
        sb.append("find \"$d\" -maxdepth 1 -type f -exec stat -c 'LUNA_MDL=%s:%Y:download/%n' {} + 2>/dev/null | grep -E '^LUNA_MDL=[0-9]+:[0-9]+:download/[0-9]+/[0-9A-Za-z_-]+$'; ");
        sb.append("done; fi; ");
    }

    static String normalizeRel(String relOrName) {
        if (relOrName == null) return null;
        return relOrName.indexOf('/') < 0 ? ("music/" + relOrName) : relOrName;
    }

    static boolean safeFileName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (".".equals(name) || "..".equals(name) || name.startsWith(".")) return false;
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0
                || name.indexOf('\0') >= 0) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) return false;
        }
        return true;
    }

    static boolean allowedHostBase(String base) {
        if (base == null) return false;
        return ("/data/data/" + HOST_PKG).equals(base)
                || ("/proc/1/root/data/data/" + HOST_PKG).equals(base)
                || ("/data/user/0/" + HOST_PKG).equals(base);
    }

    static boolean safeMdlName(String name) {
        if (!safeFileName(name)) return false;
        if (name.length() <= 4 || name.indexOf('*') >= 0 || name.indexOf('?') >= 0) return false;
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".mdl");
    }

    static String safeRelPath(String rel) {
        if (rel == null || rel.isEmpty()) throw new IllegalArgumentException("empty relPath");
        if (rel.startsWith("music/")) {
            String name = rel.substring(6);
            if (!safeMdlName(name)) throw new IllegalArgumentException("unsafe music rel: " + rel);
            return rel;
        }
        if (rel.startsWith("download/")) {
            String rest = rel.substring(9);
            int slash = rest.indexOf('/');
            if (slash <= 0 || slash >= rest.length() - 1 || rest.indexOf('/', slash + 1) >= 0) {
                throw new IllegalArgumentException("invalid download rel: " + rel);
            }
            String uid = rest.substring(0, slash);
            String name = rest.substring(slash + 1);
            for (int i = 0; i < uid.length(); i++) {
                char c = uid.charAt(i);
                if (c < '0' || c > '9') throw new IllegalArgumentException("invalid uid: " + rel);
            }
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z')
                        || (c >= 'a' && c <= 'z') || c == '_' || c == '-';
                if (!ok) throw new IllegalArgumentException("unsafe download name: " + rel);
            }
            return rel;
        }
        throw new IllegalArgumentException("unsupported relPath: " + rel);
    }

    /** 仅允许按白名单路径安全删除宿主音频及伴生配置（严禁 rm -rf） */
    public static String buildDeleteMdl(String base, java.util.List<String> names) {
        if (!allowedHostBase(base)) {
            throw new IllegalArgumentException("非法的宿主基路径: " + base);
        }
        StringBuilder sb = new StringBuilder();
        if (names != null) {
            for (String raw : names) {
                String rel;
                try {
                    rel = safeRelPath(normalizeRel(raw));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String full = base + "/files/" + rel;
                sb.append("f=").append(q(full)).append("; ");
                if (rel.startsWith("music/")) {
                    String conf = base + "/files/" + rel.substring(0, rel.length() - 4) + ".mdlnodeconf";
                    sb.append("c=").append(q(conf)).append("; rm -f \"$f\" \"$c\" 2>/dev/null; ");
                } else {
                    sb.append("rm -f \"$f\" 2>/dev/null; ");
                }
                sb.append("if [ ! -e \"$f\" ]; then printf 'DEL_OK=%s\\n' ").append(q(raw))
                  .append("; else printf 'DEL_FAIL=%s\\n' ").append(q(raw)).append("; fi; ");
            }
        }
        return sb.toString();
    }

    /** 按需读取单个宿主文件流（cat 到 stdout） */
    public static String buildReadFile(String base, String name) {
        if (base == null || base.isEmpty()) {
            throw new IllegalArgumentException("base 为空");
        }
        String rel = safeRelPath(normalizeRel(name));
        String full = base + "/files/" + rel;
        return "printf '" + FILE_BEGIN.replace("\n", "\\n") + "'; f=" + q(full)
                + "; if [ -f \"$f\" ] && [ ! -L \"$f\" ]; then cat \"$f\"; fi; ";
    }

    /** 批量读取多个宿主文件的头部（前 maxBytes 字节），供扫描体检使用 */
    public static String buildReadHeadBatch(String base, java.util.List<String> names, int maxBytes) {
        if (base == null || base.isEmpty()) {
            throw new IllegalArgumentException("base 为空");
        }
        int blocks = Math.max(1, (maxBytes + 4095) / 4096);
        int cap = blocks * 4096;
        StringBuilder sb = new StringBuilder();
        sb.append("printf '").append(HEAD_BEGIN.replace("\n", "\\n")).append("'; ");
        if (names != null) {
            for (String raw : names) {
                String rel;
                try {
                    rel = safeRelPath(normalizeRel(raw));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String full = base + "/files/" + rel;
                sb.append("f=").append(q(full)).append("; ");
                sb.append("if [ -f \"$f\" ] && [ ! -L \"$f\" ]; then ");
                sb.append("sz=$(stat -c %s \"$f\" 2>/dev/null || echo 0); ");
                sb.append("if [ \"$sz\" -gt ").append(cap).append(" ]; then n=").append(cap)
                  .append("; else n=$sz; fi; ");
                sb.append("printf 'H %d %s\\n' \"$n\" ").append(q(raw)).append("; ");
                sb.append("[ \"$n\" -gt 0 ] && dd if=\"$f\" bs=4096 count=").append(blocks)
                  .append(" 2>/dev/null; ");
                sb.append("else printf 'H 0 %s\\n' ").append(q(raw)).append("; fi; ");
            }
        }
        return sb.toString();
    }

    /** shell 单引号转义（路径里带空格/单引号时不能裸拼） */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * 拷贝一个子目录到暂存区（扁平命名：files/keva → files_keva）。
     *
     * ⚠️ 用 {@code cp -r} 而不是 {@code cp -a}：
     *   {@code cp -a} 会把**源的 SELinux 标签**一并带过来，
     *   那是宿主 App 的 app_data_file + 宿主 uid 的 MCS 类别，本 App 无权读。
     *   {@code cp -r} 不保留标签，新文件继承暂存目录的标签（= 本 App 的，正确）。
     */
    static void pair(StringBuilder sb, String sub) {
        String flat = sub.replace('/', '_');
        sb.append("if [ -e \"$BASE/").append(sub).append("\" ]; then ")
          .append("cp -r \"$BASE/").append(sub).append("\" \"$S/").append(flat).append("\" 2>&1; ")
          .append("fi; ");
    }

    /** 跑一条 su 命令，返回合并后的输出（供 detectTar 用的小工具） */
    static String run(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.io.InputStream in = p.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] b = new byte[1024];
            int n;
            while ((n = in.read(b)) > 0) sb.append(new String(b, 0, n));
            p.waitFor();
            p.destroy();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
