package com.syh.luna.cli;

import com.syh.luna.core.Cenc;
import com.syh.luna.core.Index;
import com.syh.luna.core.Json;
import com.syh.luna.core.Pipeline;
import com.syh.luna.core.Scanner;
import com.syh.luna.core.Spade;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 桌面 CLI —— 用来验证 Java 移植与 Python 实现逐字节一致。
 *
 *   --spade <spade_a>                     打印派生出的 key（测官方向量）
 *   --report <mdlDir>                     只体检
 *   --batch <mdlDir> <outDir> [选项]      批量解密
 * 选项
 *   --index <luna_index.json>            默认 ./luna_index.json
 *   --cover-map <cover_map.json>         默认 ./cover_map.json
 *   --thumb-dir <dir>                    默认 ./luna/covers_thumb
 *   --lrc / --no-tag / --include-partial
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = new LinkedHashMap<String, String>();
        List<String> pos = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            if (needs(args[i]) && i + 1 < args.length) opt.put(args[i], args[++i]);
            else pos.add(args[i]);
        }

        if (opt.containsKey("--spade")) {
            String a = opt.get("--spade");
            System.out.println("spade_a: " + a);
            System.out.println("key    : " + Spade.fromSpadeA(a));
            return;
        }

        // ---- blob 回放：验证 core.Scanner（索引抽取）与 python 一致
        if (opt.containsKey("--blobs")) {
            List<String> blobs = readBlobs(new File(opt.get("--blobs")));
            Scanner sc = Scanner.scan(blobs);
            Index ix = sc.index();
            System.out.println("blobs=" + sc.blobCount + "  json_parsed=" + sc.jsonCount);
            System.out.println("kid2spade=" + ix.kid2spade.size()
                    + " vid2spade=" + ix.vid2spade.size()
                    + " md5_2_vid=" + ix.md5toVid.size()
                    + " vid_meta=" + ix.vidMeta.size());
            int t = 0, l = 0, c = 0;
            for (Map<String, Object> m : ix.vidMeta.values()) {
                if (Json.str(m, "title") != null) t++;
                if (Json.str(m, "lyric") != null) l++;
                if (m.get("cover") != null) c++;
            }
            System.out.println("title=" + t + " lyric=" + l + " cover=" + c);
            if (opt.containsKey("--out")) {
                Index.writeAll(new File(opt.get("--out")), ix.toJson());
                System.out.println("written: " + opt.get("--out"));
            }
            return;
        }

        File root = new File(".").getAbsoluteFile();
        File idxFile = new File(opt.containsKey("--index") ? opt.get("--index") : "luna_index.json");
        File cmapFile = new File(opt.containsKey("--cover-map") ? opt.get("--cover-map") : "cover_map.json");
        File thumbDir = new File(opt.containsKey("--thumb-dir") ? opt.get("--thumb-dir") : "luna/covers_thumb");

        Index idx = Index.load(idxFile);
        System.out.println("index: kid2spade=" + idx.kid2spade.size()
                + " vid2spade=" + idx.vid2spade.size()
                + " md5_2_vid=" + idx.md5toVid.size()
                + " vid_meta=" + idx.vidMeta.size());
        Index.Covers covers = Index.Covers.load(cmapFile, thumbDir);
        System.out.println("covers: " + covers.map.size());

        boolean tag = !pos.contains("--no-tag") && !opt.containsKey("--no-tag");
        boolean wantLrc = pos.contains("--lrc");
        boolean incPartial = pos.contains("--include-partial");

        if (pos.contains("--report")) {
            File src = new File(pos.get(pos.indexOf("--report") + 1));
            TreeMap<String, Integer> cnt = new TreeMap<String, Integer>();
            int total = 0, withTitle = 0;
            for (File f : Pipeline.listMdl(src)) {
                Pipeline.Result r = Pipeline.process(f, null, idx, covers, true, tag, wantLrc);
                String st = isKey(r) ? "KEY" : "NOKEY";
                if ("video".equals(r.kind) || "junk".equals(r.kind)) st = "NOKEY";
                String key = r.kind + "/" + st + "/" + (r.complete ? "complete" : "partial");
                Integer c = cnt.get(key);
                cnt.put(key, c == null ? 1 : c + 1);
                total++;
                if (r.title != null) withTitle++;
            }
            for (Map.Entry<String, Integer> e : cnt.entrySet())
                System.out.println(String.format("  %-28s %d", e.getKey(), e.getValue()));
            System.out.println("  total " + total + "  with title " + withTitle);
            return;
        }

        int bi = pos.indexOf("--batch");
        if (bi >= 0) {
            File src = new File(pos.get(bi + 1));
            File dst = new File(pos.get(bi + 2));
            dst.mkdirs();
            int ok = 0, skip = 0, part = 0;
            StringBuilder report = new StringBuilder("[\n");
            boolean first = true;
            for (File f : Pipeline.listMdl(src)) {
                Pipeline.Result probe = Pipeline.process(f, null, idx, covers, true, tag, wantLrc);
                if ("junk".equals(probe.kind)) {
                    skip++;
                    System.out.println("JUNK " + probe.md5 + " (size=" + probe.fileSize + ")");
                    continue;
                }
                if ("video".equals(probe.kind)) {
                    part++;
                    System.out.println("VIDEO(skip) " + head(probe.md5) + " MV vide+soun   "
                            + (probe.title == null ? "-" : probe.title));
                    continue;
                }
                if (!probe.complete && !incPartial) {
                    part++;
                    System.out.println("PARTIAL(skip) " + head(probe.md5) + " " + pad(probe.kind, 5)
                            + " " + (probe.title == null ? "-" : probe.title));
                    continue;
                }
                File od = probe.complete ? dst : new File(dst, "_partial");
                od.mkdirs();
                Pipeline.Result r = Pipeline.process(f, new Pipeline.FileSink(od), idx, covers, false, tag, wantLrc);
                if (!first) report.append(",\n");
                first = false;
                report.append(r.toJson());
                if (r.out != null) {
                    ok++;
                    System.out.println("OK   " + head(r.md5) + " " + pad(r.kind, 5) + " "
                            + pad(r.complete ? "complete" : "PARTIAL", 9)
                            + " tag=" + (r.tagged ? "Y" : "-") + " cov=" + (r.hasCover ? "Y" : "-")
                            + " " + new File(r.out).getName());
                } else {
                    skip++;
                    System.out.println("SKIP " + head(r.md5) + " " + pad(r.kind, 5) + " "
                            + pad(r.complete ? "complete" : "PARTIAL", 9)
                            + " key=" + r.keySrc + " kid=" + r.kid);
                }
            }
            report.append("\n]");
            Index.writeAll(new File(dst, "_decrypt_report.json"), report.toString());
            System.out.println("done: ok=" + ok + " skip=" + skip + " partial_skipped=" + part);
            return;
        }

        System.out.println("usage: --spade <a> | --report <dir> | --batch <dir> <outDir> [--lrc] [--no-tag] [--include-partial]");
    }

    /** 需要带一个值的选项（--batch/--report 的后续参数放进 pos，避免歧义） */
    private static boolean needs(String a) {
        return "--spade".equals(a) || "--index".equals(a) || "--blobs".equals(a)
                || "--out".equals(a) || "--cover-map".equals(a) || "--thumb-dir".equals(a);
    }

    /**
     * blob 文件格式：重复的  「8 位十六进制长度 \n <UTF-8 字节> \n」
     * 用 UTF-8 承载「字符串本身」——因为 blob 里混有 latin-1 解码的原文
     * 和 sqlite 的正常 unicode 两类，只有按字符串还原才能两边都对上。
     */
    static List<String> readBlobs(File f) throws Exception {
        byte[] d = Index.readBytes(f);
        List<String> out = new ArrayList<String>();
        int p = 0;
        while (p + 9 <= d.length) {
            int nl = -1;
            for (int i = p; i < d.length && i < p + 12; i++) if (d[i] == '\n') { nl = i; break; }
            if (nl < 0) break;
            long len;
            try { len = Long.parseLong(new String(d, p, nl - p, "US-ASCII"), 16); }
            catch (NumberFormatException e) { break; }
            int st = nl + 1, en = (int) (st + len);
            if (en > d.length) break;
            out.add(new String(d, st, en - st, "UTF-8"));
            p = en + 1;              // 跳过结尾的 \n
        }
        return out;
    }

    static boolean isKey(Pipeline.Result r) {
        String c = r.keySrc;
        return c != null && c.indexOf("bad") < 0 && c.indexOf("no sample") < 0;
    }

    static String head(String s) {
        return s == null ? "--------" : (s.length() > 8 ? s.substring(0, 8) : s);
    }

    static String pad(String s, int n) {
        if (s == null) s = "-";
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }
}
