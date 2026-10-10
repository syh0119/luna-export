package com.syh.luna.lsp;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.util.Log;

import com.syh.luna.android.BlobStore;
import com.syh.luna.android.Exporter;
import com.syh.luna.android.KeyVault;

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
 * 汽水导出 · LSPosed 入口。
 *
 * 设计原则：**用户不主动点导出，绝不落盘任何歌曲。**
 * 所以这里没有「宿主启动自动导出」——那会让你只是正常听歌/浏览时也被导出，白占空间。
 *
 * 本模块只做三件事：
 *   1) 在汽水音乐**自己的界面里挂一个浮层入口**（悬浮球 + 展开卡片），
 *      点一下就地导出 —— 免 su、免切应用。见 {@link Overlay}
 *   2) 被动抓密钥：只读网络响应里出现的 encrypt_info.spade_a，写进几十 KB 的 keys.json
 *      —— 不产生任何歌曲文件
 *   3) 兜底：也响应显式广播，便于 adb / 自动化触发
 *      adb shell am broadcast -a com.syh.luna.action.EXPORT
 *      （可带 --esa md5 a,b,c 只导出指定的几首）
 *
 * 浮层的挂载点用 Application.registerActivityLifecycleCallbacks —— 不需要 hook 任何
 * 混淆类名，宿主改版也不受影响。（moonhook 走的是同一条路。）
 */
public class MainHook implements IXposedHookLoadPackage {

    static final String TAG = "LunaExport";
    static final String PKG = "com.luna.music";
    static final String ACTION_EXPORT = "com.syh.luna.action.EXPORT";

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
        VAULT = new File(new File(ctx.getDataDir(), KeyVault.DIR), KeyVault.FILE);
        BLOBS = BlobStore.file(ctx.getDataDir());
        log("宿主 Context 就绪: " + ctx.getPackageName() + "  vault=" + VAULT);
        log("元数据仓库: " + BLOBS + "（当前 " + BLOBS.length() + " 字节）");
        installManualReceiver(ctx);
        installOverlay(ctx);
    }

    // ------------------------------------------------------------------ 宿主内浮层
    /**
     * 用 ActivityLifecycleCallbacks 找出前台 Activity，往上挂我们的浮层。
     *
     * 为什么不用 hook Activity.onResume：
     *   宿主 Activity 类名混淆过、且会改版；生命周期回调是 Application 的公开 API，
     *   不碰宿主任何类，最稳。
     */
    private static void installOverlay(Context ctx) {
        try {
            if (!(ctx instanceof Application)) {
                log("Context 不是 Application，浮层跳过");
                return;
            }
            ((Application) ctx).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        public void onActivityCreated(android.app.Activity a, android.os.Bundle b) { }

                        public void onActivityStarted(android.app.Activity a) { }

                        public void onActivityResumed(android.app.Activity a) {
                            Overlay.onActivityResumed(a);
                        }

                        public void onActivityPaused(android.app.Activity a) { }

                        public void onActivityStopped(android.app.Activity a) { }

                        public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) { }

                        public void onActivityDestroyed(android.app.Activity a) {
                            Overlay.onActivityDestroyed(a);
                        }
                    });
            log("已注册前台浮层（ActivityLifecycleCallbacks）");
        } catch (Throwable t) {
            log("注册浮层失败: " + t);
        }
    }

    /** 只有收到显式广播才导出；顺带可用 md5 白名单，只导指定的几首 */
    private static void installManualReceiver(final Context ctx) {
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(final Context c, android.content.Intent it) {
                    try {
                        final String[] md5 = it == null ? null : it.getStringArrayExtra("md5");
                        log("收到手动导出请求" + (md5 == null ? "（全部可导出）" : "（" + md5.length + " 首）"));
                        Thread th = new Thread(new Runnable() {
                            public void run() {
                                try {
                                    Exporter.Session s = Exporter.prepare(ctx, false, new Exporter.Log() {
                                        public void line(String x) { log(x); }
                                    }, null);
                                    if (s.error != null) { log("✗ " + s.error); return; }
                                    java.util.List<Exporter.Item> chosen = null;
                                    if (md5 != null) {
                                        java.util.Set<String> want =
                                                new java.util.HashSet<String>(java.util.Arrays.asList(md5));
                                        chosen = new java.util.ArrayList<Exporter.Item>();
                                        for (Exporter.Item i : s.items)
                                            if (want.contains(i.md5) && i.exportable()) chosen.add(i);
                                    }
                                    Exporter.Stats st = Exporter.export(s, chosen, new Exporter.Log() {
                                        public void line(String x) { log(x); }
                                    }, null, true);
                                    log("手动导出完成: 请求 " + st.requested + " / 成功 " + st.ok
                                            + " / 失败 " + st.failed);
                                } catch (Throwable t) {
                                    log("手动导出异常: " + t);
                                }
                            }
                        }, "luna-manual-export");
                        th.setDaemon(true);
                        th.start();
                    } catch (Throwable t) {
                        log("onReceive: " + t);
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter(ACTION_EXPORT);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // 2 = Context.RECEIVER_EXPORTED（写常量值避免低版本运行时找不到符号）
                ctx.registerReceiver(r, f, 2);
            } else {
                ctx.registerReceiver(r, f);
            }
            log("已注册手动导出广播接收器: " + ACTION_EXPORT);
        } catch (Throwable t) {
            log("注册广播失败: " + t);
        }
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
