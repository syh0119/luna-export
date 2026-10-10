package com.syh.luna.android;

/**
 * 日志桥。
 *
 * 浮层代码现在**两个进程都会用到**：
 *   · LSPosed 模式 → 跑在 com.luna.music 进程里
 *   · 独立模式     → 跑在 com.syh.luna 自己的前台 Service 里
 * 后者没有 Xposed 环境，直接调 XposedBridge 会 NoClassDefFoundError。
 * 所以统一走这里：一定写 logcat，另外可选地再转发给模块（XposedBridge）。
 */
public final class Logx {

    public interface Sink { void log(String s); }

    /** 由模块在注入时设置，用来把日志同时投递到 LSPosed 的日志 */
    public static volatile Sink extra;

    public static final String TAG = "LunaExport";

    private Logx() { }

    public static void i(String s) {
        try {
            android.util.Log.i(TAG, s == null ? "null" : s);
        } catch (Throwable ignore) { }
        Sink e = extra;
        if (e != null) {
            try { e.log(s); } catch (Throwable ignore) { }
        }
    }
}
