package com.syh.luna.lsp;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.util.Log;

import com.syh.luna.android.BlobStore;
import com.syh.luna.android.KeyVault;
import com.syh.luna.android.Logx;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 汽水导出 · LSPosed 入口（**只做被动抓取**）。
 *
 * 设计原则：**用户不主动点导出，绝不落盘任何歌曲。**
 *
 * 本模块在宿主进程里只做一件事：
 *   被动抓密钥 —— 只读网络响应里出现的 encrypt_info.spade_a（连同曲目元数据），
 *   写进几十 KB 的 keys.json / blobs.bin。**不产生任何歌曲文件，也不加任何界面。**
 *
 * 它唯一的价值：汽水音乐只在「播放过 / 完整下载过」的歌上缓存 key；
 * 被动把这些收下来，本地没缓存到的歌也能解开。
 *
 * 导出本身完全由「汽水导出」App 自己完成（root 模式读私有目录 → 离线解密）。
 *
 * 试过但已撤掉的方案（记在这里，免得以后重复踩）：
 *   ✗ 宿主内浮窗 / 宿主内导出 —— 宿主是 32 位、常驻 1.1 GB，扛不住建索引与解密，
 *     实测把它撑到 RSS 1.4 GB 后它的图片管线 OOM 崩掉，导出死在写盘中间
 *   ✗ 宿主启动时唤起 App 的浮窗服务 —— 被 HMA-OSS 在系统层拦成
 *     `Unable to start service ... not found`（而且 startService 不抛异常，静默失败）
 *   ✗ 宿主启动自动导出 —— 用户明确要求：没主动点导出，绝不落盘
 */
public class MainHook implements IXposedHookLoadPackage {

    static final String TAG = "LunaExport";
    static final String PKG = "com.luna.music";

    static volatile boolean started = false;
    static volatile Context APP = null;
    static volatile File VAULT = null;
    /** 自建「响应体仓库」——用来补宿主缓存里查不到的曲目元数据 */
    static volatile File BLOBS = null;
    static final AtomicInteger HITS = new AtomicInteger();
    static final AtomicInteger BLOB_SAVED = new AtomicInteger();
    static final Map<String, String> PEND_K = new HashMap<String, String>();
    static final Map<String, String> PEND_V = new HashMap<String, String>();

    // ------------------------------------------------------------------ 入口
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!PKG.equals(lp.packageName)) return;
        if (started) return;
        started = true;
        log("注入 " + PKG + "（不做任何自动导出）");

        try {
            installCapture(lp.classLoader);
        } catch (Throwable t) {
            log("抓密钥 hook 未装: " + t);
        }
        try {
            hookAppCreate(lp.classLoader);
        } catch (Throwable t) {
            log("Application hook 未装: " + t);
        }
    }

    /** 尽早拿到宿主 Context */
    private void hookAppCreate(ClassLoader cl) {
        XposedHelpers.findAndHookMethod(Instrumentation.class, "callApplicationOnCreate",
                Application.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Application app = (Application) param.args[0];
                            if (app != null) onApp(app);
                        } catch (Throwable t) {
                            log("callApplicationOnCreate: " + t);
                        }
                    }
                });
    }

    static synchronized void onApp(Context ctx) {
        if (APP != null) return;
        APP = ctx;
        // android 层统一走 Logx；在宿主进程里把它也接到 LSPosed 日志
        Logx.extra = new Logx.Sink() {
            public void log(String s) { MainHook.log(s); }
        };
        VAULT = new File(new File(ctx.getDataDir(), KeyVault.DIR), KeyVault.FILE);
        BLOBS = BlobStore.file(ctx.getDataDir());
        log("宿主 Context 就绪: " + ctx.getPackageName() + "  vault=" + VAULT);
        log("元数据仓库: " + BLOBS + "（当前 " + BLOBS.length() + " 字节）");
        // ==============================================================
        // 宿主侧**只做这一件事**：被动抓密钥与曲目元数据（每次几十 KB）。
        //
        // 不做的事（都在 v1.0.1~v1.1.0 试过，已按用户要求撤掉）：
        //   ✗ 不挂浮窗 —— 宿主是 32 位、常驻 1.1 GB，扛不住建索引/解密
        //     （实测撑到 RSS 1.4 GB 后它的图片管线 OOM 崩掉，导出死在写盘中间）
        //   ✗ 不注册「手动导出」广播 —— 同样跑在宿主进程里，会重演上面的崩溃
        //   ✗ 不在宿主启动时唤起 App 的浮窗服务 —— 实测被 HMA-OSS 拦成 not found
        //
        // 现在就是纯 App：你自己打开「汽水导出」→ 扫描 → 勾选 → 导出。
        // 这个 hook 的价值只有一条：让那些「没播放过、本地没缓存 key」的歌也能解开。
        // ==============================================================
    }

    // ------------------------------------------------------------------ 抓密钥
    private void installCapture(ClassLoader cl) {
        try {
            Class<?> rb = XposedHelpers.findClass("okhttp3.ResponseBody", cl);
            XposedBridge.hookAllMethods(rb, "string", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object r = p.getResult();
                        if (r instanceof String) onText((String) r);
                    } catch (Throwable ignore) { }
                }
            });
            log("已挂 okhttp3.ResponseBody.string");
        } catch (Throwable t) {
            log("okhttp3.ResponseBody 未找到（宿主可能换 HTTP 栈）");
        }

        // 兜底：okio 的文本出口（只读返回值）
        try {
            Class<?> buf = XposedHelpers.findClass("okio.Buffer", cl);
            XposedBridge.hookAllMethods(buf, "readUtf8", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object r = p.getResult();
                        if (r instanceof String) onText((String) r);
                    } catch (Throwable ignore) { }
                }
            });
            log("已挂 okio.Buffer.readUtf8（兜底）");
        } catch (Throwable t) {
            log("okio.Buffer 未找到（跳过兜底）");
        }
    }

    /** 响应体处理：① 顺路把曲目元数据存进自建仓库 ② 含 spade_a 的抽密钥 */
    static void onText(String s) {
        try {
            if (s == null || s.length() < 40) return;

            // ---- ① 元数据仓库
            // 宿主的缓存会失效（sqlite WAL 翻页后老映射就读不出来了），
            // 于是有些歌永远解析不出歌名。我们这份长期累积、不会过期。
            File bf = BLOBS;
            if (bf != null && BLOB_SAVED.get() < 4000
                    && (s.indexOf("\"track\"") >= 0 || s.indexOf("cache_load_records") >= 0)) {
                if (BlobStore.append(bf, s)) {
                    int n = BLOB_SAVED.incrementAndGet();
                    if (n % 50 == 0) log("元数据仓库已累积 " + n + " 条响应");
                }
            }

            if (s.indexOf("spade_a") < 0) return;
            Map<String, String> kid = new HashMap<String, String>();
            Map<String, String> vid = new HashMap<String, String>();
            if (!KeyVault.harvest(s, kid, vid)) return;
            synchronized (PEND_K) {
                PEND_K.putAll(kid);
                PEND_V.putAll(vid);
                if (PEND_K.size() + PEND_V.size() < 8) return;   // 攒够一批再落盘
                File v = VAULT;
                if (v == null) return;
                KeyVault.merge(v, PEND_K, PEND_V);
                int n = PEND_K.size() + PEND_V.size();
                PEND_K.clear();
                PEND_V.clear();
                log("抓到密钥 " + n + " 条（累计 " + HITS.addAndGet(n) + "）");
            }
        } catch (Throwable ignore) { }
    }

    static void log(String msg) {
        try {
            Log.i(TAG, msg);
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignore) { }
    }
}
