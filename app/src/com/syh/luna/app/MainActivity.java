package com.syh.luna.app;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.syh.luna.android.Dirs;
import com.syh.luna.android.Exporter;

import java.util.ArrayList;
import java.util.List;

/**
 * 汽水导出 —— 界面按 Miuix（HyperOS / 澎湃 OS 设计语言）风格实现：
 *   大标题 · 白色圆角卡片 · #3482FF 主色 · 胶囊按钮 · 圆形勾选 · 内嵌细分隔线 · 支持深色模式
 * 由于本工程走的是无 Gradle 手动构建（不引入 Compose/androidx），
 * 这里用原生 View + 自绘 drawable 复刻 Miuix 的视觉语言，不依赖任何第三方库。
 */
public class MainActivity extends Activity {

    // ================================================================ Miuix 调色板
    static final int ACCENT_L = 0xFF3482FF, ACCENT_D = 0xFF4C8DFF;
    static final int BG_L = 0xFFF7F8FA, BG_D = 0xFF000000;
    static final int CARD_L = 0xFFFFFFFF, CARD_D = 0xFF1A1A1A;
    static final int TEXT_L = 0xFF191919, TEXT_D = 0xFFEDEDED;
    static final int SUB_L = 0xFF8A8F99, SUB_D = 0xFF8A8F99;
    static final int LINE_L = 0x14000000, LINE_D = 0x1FFFFFFF;
    static final int FILL_L = 0xFFF0F1F5, FILL_D = 0xFF262626;   // 次级填充（胶囊/输入）
    static final int OK_L = 0xFF00A65A, OKBG_L = 0xFFE6F6EE;
    static final int OK_D = 0xFF3DD68C, OKBG_D = 0xFF12291E;
    static final int WARN_L = 0xFFD97706, WARNBG_L = 0xFFFDF3E2;
    static final int WARN_D = 0xFFF0B45A, WARNBG_D = 0xFF2E2412;
    static final int DANGER_L = 0xFFE53935, DANGERBG_L = 0xFFFDECEC;
    static final int DANGER_D = 0xFFFF6B6B, DANGERBG_D = 0xFF331818;
    static final int WHITE = 0xFFFFFFFF;

    // 当前生效
    private boolean dark;
    private int ACCENT, BG, CARD, TEXT, SUB, LINE, FILL, OK, OKBG, WARN, WARNBG, DANGER, DANGERBG;

    private Exporter.Session session;
    private final List<Exporter.Item> rows = new ArrayList<Exporter.Item>();
    private RowAdapter adapter;

    private TextView chipTotal, chipReady, chipPicked, chipFilter, autoSub, logToggle;
    private TextView sortAlphaBtn, sortSizeBtn, sortTimeBtn;
    private TextView logView, progressText, emptyHint, scanBtn, deleteBtn;
    private android.widget.EditText search;
    private String query = "";
    private ProgressBar bar;
    private View logBox, exportBtn;
    private ListView list;

    private volatile boolean busy;
    private volatile boolean cachedRootOk;
    private volatile boolean cachedAllFiles;
    private final StringBuilder buf = new StringBuilder();
    private boolean logOpen;
    /** false = 列表只显示能操作的（可导出/已导出）；true = 全部 .mdl */
    private boolean showAll;
    private int sortMode = Exporter.SORT_ALPHA;
    private boolean sortAsc = true;

    @Override
    protected void onCreate(Bundle b) {
        dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        initPalette();
        super.onCreate(b);
        sortMode = Exporter.sortMode(this);
        sortAsc = Exporter.sortAsc(this);
        setContentView(buildUi());
        append("汽水导出 v" + versionName());
        append("输出到 /sdcard/" + Exporter.REL + "/");
        append("⚠️ 不会自动导出：只有你点「导出选中」才会产出文件。");
        append("用法：点「扫描曲目」→ 勾选想要的 → 点底部按钮导出。");
        // 上次崩了/被杀留下的暂存残留，开 App 就清掉（暂存区是宿主私有数据的副本）
        Dirs.cleanupStale(this);
        refreshChips();
        refreshStatus();
        handleIntent();
        maybeAutoOnLaunch();
    }

    private boolean intentHandled;

    /** 独立模式的「启动时自动增量导出」：带 3 小时节流 */
    private void maybeAutoOnLaunch() {
        if (intentHandled) return;
        if (!Exporter.autoOnLaunch(this)) {
            append("「启动时自动增量导出」已关闭");
            return;
        }
        if (!Exporter.dueForAutoPrefs(this)) {
            append("距上次自动检查不足 " + (Exporter.AUTO_INTERVAL_MS / 3600000)
                    + " 小时，本次跳过（点「扫描曲目」可手动检查）");
            refreshAutoSub();
            return;
        }
        append("启动自动增量检查：只处理新增/变更的曲目 …");
        doScan(true);
    }

    private void initPalette() {
        ACCENT = dark ? ACCENT_D : ACCENT_L;
        BG = dark ? BG_D : BG_L;
        CARD = dark ? CARD_D : CARD_L;
        TEXT = dark ? TEXT_D : TEXT_L;
        SUB = dark ? SUB_D : SUB_L;
        LINE = dark ? LINE_D : LINE_L;
        FILL = dark ? FILL_D : FILL_L;
        OK = dark ? OK_D : OK_L;
        OKBG = dark ? OKBG_D : OKBG_L;
        WARN = dark ? WARN_D : WARN_L;
        WARNBG = dark ? WARNBG_D : WARNBG_L;
        DANGER = dark ? DANGER_D : DANGER_L;
        DANGERBG = dark ? DANGERBG_D : DANGERBG_L;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent();
    }

    private void handleIntent() {
        Intent it = getIntent();
        if (it == null) return;
        if (it.getBooleanExtra("diag", false)) {
            append("=== su 诊断 ===");
            new Thread(new Runnable() { public void run() {
                append(Dirs.diagnose(MainActivity.this));
            } }, "diag").start();
            return;
        }
        if (it.getBooleanExtra("auto", false)) {
            it.removeExtra("auto");
            intentHandled = true;
            append("收到 auto：扫描 + 增量导出");
            doScan(true);
        }
    }

    // ================================================================ 布局
    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(header());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), 0, dp(16), 0);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(body);

        body.addView(chips());
        body.addView(actions());
        body.addView(searchBox());
        body.addView(sortBar());
        body.addView(listCard());
        body.addView(progressArea());
        body.addView(exportButton());

        root.addView(logArea());
        return root;
    }

    /** 极简单行顶栏：仅保留大标题 + 设置按钮 */
    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(20), dp(12), dp(20), dp(8));

        TextView t = new TextView(this);
        t.setText("汽水导出");
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(-0.02f);
        h.addView(t, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnSettings = new TextView(this);
        btnSettings.setText("⚙ 设置");
        btnSettings.setTextColor(TEXT);
        btnSettings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btnSettings.setTypeface(Typeface.DEFAULT_BOLD);
        btnSettings.setPadding(dp(12), dp(6), dp(12), dp(6));
        btnSettings.setBackground(round(FILL, 100));
        btnSettings.setClickable(true);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSettingsDialog(); }
        });
        h.addView(btnSettings);
        return h;
    }

    private void openSettingsDialog() {
        refreshStatus();
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(14));
        scroll.addView(root);

        TextView dlgTitle = new TextView(this);
        dlgTitle.setText("设置");
        dlgTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        dlgTitle.setTypeface(Typeface.DEFAULT_BOLD);
        dlgTitle.setTextColor(TEXT);
        dlgTitle.setPadding(0, 0, 0, dp(12));
        root.addView(dlgTitle);

        // a) 权限与状态卡片
        LinearLayout sc = new LinearLayout(this);
        sc.setOrientation(LinearLayout.VERTICAL);
        sc.setBackground(round(CARD, 14));
        sc.setPadding(dp(14), dp(12), dp(14), dp(12));
        root.addView(sc);

        boolean rootOk = cachedRootOk;
        boolean allFilesOk = cachedAllFiles;

        TextView rootTv = new TextView(this);
        rootTv.setText(rootOk ? "✔ root 已授权" : "✘ 未获得 root 授权（请在 KernelSU / Magisk 中授权）");
        rootTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        rootTv.setTypeface(Typeface.DEFAULT_BOLD);
        rootTv.setTextColor(rootOk ? OK : WARN);
        rootTv.setBackground(round(rootOk ? OKBG : WARNBG, 8));
        rootTv.setPadding(dp(10), dp(6), dp(10), dp(6));
        sc.addView(rootTv);

        TextView filesTv = new TextView(this);
        filesTv.setText(allFilesOk ? "✔ 所有文件访问已授权（.lrc 与歌曲同目录）"
                : "✘ 未授权所有文件访问（点此前往授权）");
        filesTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        filesTv.setTypeface(Typeface.DEFAULT_BOLD);
        filesTv.setTextColor(allFilesOk ? OK : WARN);
        filesTv.setBackground(round(allFilesOk ? OKBG : WARNBG, 8));
        filesTv.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(8);
        sc.addView(filesTv, flp);
        if (!allFilesOk) {
            filesTv.setClickable(true);
            filesTv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    append("正在打开「所有文件访问权限」设置页…");
                    com.syh.luna.android.MediaStoreSink.requestAllFiles(MainActivity.this);
                }
            });
        }

        View div = new View(this);
        div.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dlp.topMargin = dp(10);
        dlp.bottomMargin = dp(10);
        sc.addView(div, dlp);

        TextView metaTv = new TextView(this);
        StringBuilder meta = new StringBuilder();
        meta.append("输出目录：/sdcard/").append(Exporter.REL).append("/");
        meta.append("\n版本：v").append(versionName());
        if (session != null && session.indexInfo != null && !session.indexInfo.isEmpty()) {
            meta.append("\n最近扫描：").append(session.indexInfo)
                .append("（").append(session.preparedMs / 1000.0).append("s）");
        }
        metaTv.setText(meta.toString());
        metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        metaTv.setTextColor(SUB);
        metaTv.setLineSpacing(dp(3), 1f);
        sc.addView(metaTv);

        // b) 功能开关卡片
        LinearLayout swCard = new LinearLayout(this);
        swCard.setOrientation(LinearLayout.VERTICAL);
        swCard.setBackground(round(CARD, 14));
        swCard.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.topMargin = dp(12);
        root.addView(swCard, sclp);

        autoSub = new TextView(this);
        swCard.addView(settingRow("启动时自动增量导出", autoSub,
                Exporter.autoOnLaunch(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                        Exporter.setAutoOnLaunch(MainActivity.this, v);
                        append(v ? "⚠️ 已开启「启动时自动增量导出」" : "已关闭自动导出");
                        refreshAutoSub();
                    }
                }));
        refreshAutoSub();

        View rowLine = new View(this);
        rowLine.setBackgroundColor(LINE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        rlp.topMargin = dp(8);
        rlp.bottomMargin = dp(8);
        swCard.addView(rowLine, rlp);

        TextView lrcSub = new TextView(this);
        lrcSub.setText("歌词已内嵌在音频标签里，通常无需额外生成 .lrc");
        swCard.addView(settingRow("额外输出 .lrc 歌词文件", lrcSub, Exporter.lrcSidecar(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                        Exporter.setLrcSidecar(MainActivity.this, v);
                        append(v ? "已开启：导出时同步生成同名 .lrc" : "已关闭：只保留内嵌歌词");
                    }
                }));

        // c) 底部说明 + su 诊断
        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ftlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ftlp.topMargin = dp(12);
        root.addView(footer, ftlp);

        TextView descTv = new TextView(this);
        descTv.setText(".mdl / 离线下载 CENC 离线解密导出");
        descTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        descTv.setTextColor(SUB);
        footer.addView(descTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView diagBtn = new TextView(this);
        diagBtn.setText("su 环境诊断");
        diagBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        diagBtn.setTypeface(Typeface.DEFAULT_BOLD);
        diagBtn.setTextColor(TEXT);
        diagBtn.setBackground(round(FILL, 100));
        diagBtn.setPadding(dp(12), dp(7), dp(12), dp(7));
        diagBtn.setClickable(true);
        footer.addView(diagBtn);

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .create();

        diagBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                diagBtn.setEnabled(false);
                diagBtn.setText("诊断中…");
                new Thread(new Runnable() {
                    public void run() {
                        final String res = Dirs.diagnose(MainActivity.this);
                        runOnUiThread(new Runnable() {
                            public void run() {
                                if (isFinishing()) return;
                                append("=== su 诊断 ===");
                                append(res);
                                logOpen = true;
                                if (logBox != null) logBox.setVisibility(View.VISIBLE);
                                if (logToggle != null) logToggle.setText("▾ 运行日志");
                                if (dlg.isShowing()) dlg.dismiss();
                            }
                        });
                    }
                }, "diag").start();
            }
        });

        dlg.show();
    }

    /** 一行「标题 + 说明 + 开关」 */
    private View settingRow(String title, TextView subOut, boolean checked,
                            android.widget.CompoundButton.OnCheckedChangeListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        row.addView(mid, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        mid.addView(t);

        if (subOut != null) {
            subOut.setTextColor(SUB);
            subOut.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            subOut.setPadding(0, dp(3), 0, 0);
            mid.addView(subOut);
        }

        android.widget.Switch s = new android.widget.Switch(this);
        s.setChecked(checked);
        s.setShowText(false);
        try {
            s.setThumbTintList(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{ACCENT, dark ? 0xFF6E747E : 0xFFBFC4CC}));
            s.setTrackTintList(new android.content.res.ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{alphaOf(ACCENT, 0.45f), dark ? 0xFF3A3A3A : 0xFFDFE2E8}));
        } catch (Throwable ignore) { }
        if (l != null) s.setOnCheckedChangeListener(l);
        row.addView(s);
        return row;
    }

    private View chips() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(2), dp(6), dp(2), dp(6));
        chipTotal = chip(r, "可操作 0/0", SUB, FILL);
        chipReady = chip(r, "可导出 0", OK, OKBG);
        chipPicked = chip(r, "已选 0", ACCENT, alphaOf(ACCENT, 0.14f));
        chipFilter = chip(r, "仅可操作", ACCENT, alphaOf(ACCENT, 0.14f));
        chipFilter.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAll = !showAll;
                applyFilter();
                refreshChips();
            }
        });
        return r;
    }

    private TextView chip(LinearLayout p, String text, int fg, int bg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTextColor(fg);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        t.setBackground(round(bg, 100));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        p.addView(t, lp);
        return t;
    }

    private void refreshAutoSub() {
        if (autoSub == null) return;
        boolean on = Exporter.autoOnLaunch(this);
        if (!on) {
            autoSub.setText("已关闭（推荐）· 只有你主动点「导出选中」才会产出文件");
            return;
        }
        long left = Exporter.nextAutoIn(this);
        if (left <= 0) autoSub.setText("已开启 · 下次打开本应用时自动处理新增/变更的曲目（会占空间）");
        else autoSub.setText("已开启 · 距下次自动检查约 " + (left / 60000 + 1) + " 分钟");
    }

    private View actions() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, 0, 0, dp(8));

        TextView scan = pill("扫描曲目", ACCENT, WHITE, true);
        scanBtn = scan;
        scan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (busy) { append("任务进行中，请稍候 …"); return; }
                doScan(false);
            }
        });
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.35f);
        l1.rightMargin = dp(8);
        r.addView(scan, l1);

        r.addView(secondary("全选", new Runnable() { public void run() { pickAll(true); } }), weight(1f));
        r.addView(secondary("反选", new Runnable() { public void run() { pickInvert(); } }), weight(1f));
        r.addView(secondary("清空", new Runnable() { public void run() { pickAll(false); } }), weight(1f));
        return r;
    }

    private View secondary(String text, final Runnable act) {
        TextView t = pill(text, FILL, TEXT, false);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (busy) { append("任务进行中，请稍候 …"); return; }
                act.run();
            }
        });
        return t;
    }

    private LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, w);
        lp.rightMargin = dp(8);
        return lp;
    }

    /** 搜索框：按曲名 / 歌手即时过滤下面的列表 */
    private View searchBox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackground(round(FILL, 100));
        c.setPadding(dp(14), 0, dp(10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        lp.bottomMargin = dp(8);
        c.setLayoutParams(lp);

        TextView icon = new TextView(this);
        icon.setText("🔍");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        c.addView(icon);

        search = new android.widget.EditText(this);
        search.setHint("搜索曲名 / 歌手");
        search.setHintTextColor(SUB);
        search.setTextColor(TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(dp(8), 0, dp(4), 0);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s == null ? "" : s.toString().trim().toLowerCase();
                applyFilter();
                refreshChips();
            }
            public void afterTextChanged(android.text.Editable s) { }
        });
        c.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        final TextView clear = new TextView(this);
        clear.setText("✕");
        clear.setTextColor(SUB);
        clear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        clear.setPadding(dp(8), dp(4), dp(2), dp(4));
        clear.setClickable(true);
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { search.setText(""); }
        });
        c.addView(clear);
        return c;
    }

    private View sortBar() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(2), 0, dp(2), dp(8));

        TextView label = new TextView(this);
        label.setText("排序");
        label.setTextColor(SUB);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.rightMargin = dp(8);
        r.addView(label, llp);

        sortAlphaBtn = sortPill(r, Exporter.SORT_ALPHA);
        sortSizeBtn = sortPill(r, Exporter.SORT_SIZE);
        sortTimeBtn = sortPill(r, Exporter.SORT_TIME);
        refreshSortBar();
        return r;
    }

    private TextView sortPill(LinearLayout p, final int mode) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        t.setClickable(true);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { onSortClick(mode); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        p.addView(t, lp);
        return t;
    }

    private void onSortClick(int mode) {
        if (sortMode == mode) {
            sortAsc = !sortAsc;
        } else {
            sortMode = mode;
            sortAsc = (mode == Exporter.SORT_ALPHA);
        }
        Exporter.setSortMode(this, sortMode);
        Exporter.setSortAsc(this, sortAsc);
        refreshSortBar();
        applyFilter();
        refreshChips();
    }

    private void refreshSortBar() {
        styleSortPill(sortAlphaBtn, "字母", Exporter.SORT_ALPHA);
        styleSortPill(sortSizeBtn, "大小", Exporter.SORT_SIZE);
        styleSortPill(sortTimeBtn, "时间", Exporter.SORT_TIME);
    }

    private void styleSortPill(TextView t, String name, int mode) {
        if (t == null) return;
        boolean active = (sortMode == mode);
        t.setText(active ? (name + (sortAsc ? " ↑" : " ↓")) : name);
        t.setTextColor(active ? ACCENT : SUB);
        t.setBackground(round(active ? alphaOf(ACCENT, 0.14f) : FILL, 100));
    }

    private View listCard() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, 18));
        box.setElevation(dp(0.5f));
        box.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setBackgroundColor(Color.TRANSPARENT);
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(4));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                Exporter.Item it = rows.get(pos);
                it.selected = !it.selected;
                adapter.notifyDataSetChanged();
                refreshChips();
            }
        });
        list.setVisibility(View.GONE);
        box.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyHint = new TextView(this);
        emptyHint.setText("还没有曲目列表\n点上面的「扫描曲目」读取汽水音乐里的 .mdl");
        emptyHint.setTextColor(SUB);
        emptyHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        emptyHint.setGravity(Gravity.CENTER);
        emptyHint.setPadding(dp(20), dp(40), dp(20), dp(40));
        box.addView(emptyHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return box;
    }

    private View progressArea() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(2), dp(10), dp(2), 0);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressDrawable(progressDrawable());
        try {
            bar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        } catch (Throwable ignore) { }
        bar.setVisibility(View.GONE);
        c.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        progressText = new TextView(this);
        progressText.setTextColor(SUB);
        progressText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        progressText.setPadding(0, dp(5), 0, 0);
        progressText.setVisibility(View.GONE);
        c.addView(progressText);
        return c;
    }

    /** 细圆头进度条（Miuix 风格） */
    private android.graphics.drawable.Drawable progressDrawable() {
        GradientDrawable bg = round(alphaOf(ACCENT, 0.16f), 100);
        GradientDrawable fg = round(ACCENT, 100);
        android.graphics.drawable.ClipDrawable clip =
                new android.graphics.drawable.ClipDrawable(fg, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{bg, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    private View exportButton() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        rlp.bottomMargin = dp(4);
        row.setLayoutParams(rlp);

        TextView del = pill("删除", alphaOf(DANGERBG, 0.5f), alphaOf(DANGER, 0.45f), true);
        del.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        del.setPadding(0, dp(11), 0, dp(11));
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (busy) { append("任务进行中，请稍候 …"); return; }
                confirmDelete();
            }
        });
        deleteBtn = del;
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f);
        dlp.rightMargin = dp(8);
        row.addView(del, dlp);

        TextView t = pill("导出选中", alphaOf(ACCENT, 0.45f), WHITE, true);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        t.setPadding(0, dp(11), 0, dp(11));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (busy) { append("任务进行中，请稍候 …"); return; }
                doExport();
            }
        });
        exportBtn = t;
        row.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private View logArea() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(4), dp(16), dp(10));

        final TextView toggle = new TextView(this);
        logToggle = toggle;
        toggle.setText("▸ 运行日志");
        toggle.setTextColor(SUB);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        toggle.setPadding(dp(4), dp(5), dp(4), dp(5));
        toggle.setClickable(true);

        logBox = wrapLog();
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                logOpen = !logOpen;
                logBox.setVisibility(logOpen ? View.VISIBLE : View.GONE);
                toggle.setText(logOpen ? "▾ 运行日志" : "▸ 运行日志");
            }
        });
        c.addView(toggle);
        c.addView(logBox);
        return c;
    }

    private View wrapLog() {
        ScrollView sv = new ScrollView(this);
        sv.setBackground(round(dark ? 0xFF111111 : 0xFFF2F3F5, 14));
        sv.setPadding(dp(12), dp(10), dp(12), dp(10));
        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(dark ? 0xFFB9BEC7 : 0xFF5A616B);
        logView.setTextIsSelectable(true);
        sv.addView(logView);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        lp.topMargin = dp(2);
        sv.setLayoutParams(lp);
        sv.setVisibility(View.GONE);
        return sv;
    }

    // ================================================================ 行视图
    private class RowAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int p) { return rows.get(p); }
        public long getItemId(int p) { return p; }

        public View getView(int pos, View cv, ViewGroup parent) {
            Holder h;
            if (cv == null) {
                LinearLayout outer = new LinearLayout(MainActivity.this);
                outer.setOrientation(LinearLayout.VERTICAL);

                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(12), dp(16), dp(12));
                outer.addView(row, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                h = new Holder();
                // Miuix 圆形勾选
                h.check = new TextView(MainActivity.this);
                h.check.setGravity(Gravity.CENTER);
                h.check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                h.check.setTypeface(Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(22), dp(22));
                row.addView(h.check, clp);

                LinearLayout mid = new LinearLayout(MainActivity.this);
                mid.setOrientation(LinearLayout.VERTICAL);
                LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                mlp.leftMargin = dp(12);
                mlp.rightMargin = dp(10);
                row.addView(mid, mlp);

                h.title = new TextView(MainActivity.this);
                h.title.setTextColor(TEXT);
                h.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                h.title.setTypeface(Typeface.DEFAULT_BOLD);
                h.title.setSingleLine(true);
                h.title.setEllipsize(TextUtils.TruncateAt.END);
                mid.addView(h.title);

                h.sub = new TextView(MainActivity.this);
                h.sub.setTextColor(SUB);
                h.sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                h.sub.setSingleLine(true);
                h.sub.setEllipsize(TextUtils.TruncateAt.END);
                h.sub.setPadding(0, dp(3), 0, 0);
                mid.addView(h.sub);

                h.badge = new TextView(MainActivity.this);
                h.badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                h.badge.setTypeface(Typeface.DEFAULT_BOLD);
                h.badge.setPadding(dp(9), dp(4), dp(9), dp(4));
                h.badge.setGravity(Gravity.CENTER);

                h.state = new TextView(MainActivity.this);
                h.state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                h.state.setGravity(Gravity.CENTER);
                h.state.setPadding(0, dp(5), 0, 0);

                LinearLayout right = new LinearLayout(MainActivity.this);
                right.setOrientation(LinearLayout.VERTICAL);
                right.setGravity(Gravity.CENTER_HORIZONTAL);
                right.addView(h.badge);
                right.addView(h.state);
                row.addView(right);

                // 内嵌细分隔线（左缩进，Miuix 列表观感）
                View line = new View(MainActivity.this);
                line.setBackgroundColor(LINE);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
                llp.leftMargin = dp(50);
                outer.addView(line, llp);

                outer.setTag(h);
                cv = outer;
            } else {
                h = (Holder) cv.getTag();
            }

            Exporter.Item it = rows.get(pos);
            boolean exp = it.exportable();
            h.title.setText(it.display());
            String subText = it.subtitle();
            if (sortMode == Exporter.SORT_TIME) {
                String tt = it.timeText();
                if (!tt.isEmpty()) subText = subText + " · " + tt;
            }
            h.sub.setText(subText);
            h.badge.setText(it.badge());

            boolean on = it.selected;
            h.check.setText(on ? "✓" : "");
            h.check.setBackground(on ? round(ACCENT, 100) : ring(LINE, dp(1), 100));
            h.check.setTextColor(WHITE);
            h.check.setAlpha((exp || on) ? 1f : 0.4f);

            String st = it.state();
            h.state.setText(it.sizeText() + " · " + st);
            if ("可导出".equals(st) || "待导出".equals(st)) {
                h.badge.setTextColor(OK);
                h.badge.setBackground(round(OKBG, 8));
                h.state.setTextColor(OK);
            } else if ("残片".equals(st)) {
                h.badge.setTextColor(WARN);
                h.badge.setBackground(round(WARNBG, 8));
                h.state.setTextColor(WARN);
            } else {
                h.badge.setTextColor(SUB);
                h.badge.setBackground(round(FILL, 8));
                h.state.setTextColor(SUB);
            }
            float a = exp ? 1f : 0.42f;
            h.title.setAlpha(a);
            h.sub.setAlpha(a);
            h.badge.setAlpha(a);
            h.state.setAlpha(a);

            View content = ((LinearLayout) cv).getChildAt(0);
            content.setBackgroundColor(on ? alphaOf(ACCENT, dark ? 0.13f : 0.07f) : Color.TRANSPARENT);
            return cv;
        }
    }

    static class Holder {
        TextView check, title, sub, badge, state;
    }

    // ================================================================ 动作
    private void doScan(final boolean thenExportAll) {
        if (busy) return;
        busy = true;
        setBusyUi(true, "扫描中…");
        setProgress(0, 0, "正在准备扫描 …");
        runOnUiThread(new Runnable() {
            public void run() {
                if (rows.isEmpty() && emptyHint != null) {
                    emptyHint.setText("正在扫描汽水音乐缓存，请稍候 …");
                }
            }
        });
        new Thread(new Runnable() {
            public void run() {
                try {
                    final Exporter.Session s = Exporter.prepare(MainActivity.this, true,
                            new Exporter.Log() { public void line(String x) { append(x); } },
                            new Exporter.Progress() {
                                public void on(int d, int t, String l) { setProgress(d, t, l); }
                            });
                    runOnUiThread(new Runnable() {
                        public void run() {
                            session = s;
                            if (emptyHint != null) {
                                if (s.error != null) emptyHint.setText(s.error);
                                else emptyHint.setText("没有符合条件的曲目");
                            }
                            applyFilter();
                            refreshChips();
                        }
                    });
                    if (thenExportAll && s.error == null) {
                        int n = s.autoTargets().size();
                        if (n > 0) {
                            for (Exporter.Item it : s.items) if (it.needsAutoExport()) it.selected = true;
                            runOnUiThread(new Runnable() {
                                public void run() { adapter.notifyDataSetChanged(); refreshChips(); }
                            });
                            doExportInternal(s);
                        } else append("没有需要导出的曲目（都已导出且未变更）");
                    }
                } catch (Throwable t) {
                    append("扫描异常: " + t);
                } finally {
                    busy = false;
                    setBusyUi(false, null);
                    hideProgress();
                    refreshStatus();
                    runOnUiThread(new Runnable() {
                        public void run() { refreshAutoSub(); refreshChips(); }
                    });
                }
            }
        }, "scan").start();
    }

    private void doExport() {
        if (busy) return;
        final Exporter.Session s = session;
        if (s == null || s.error != null) {
            append("还没有列表，先扫描");
            doScan(false);
            return;
        }
        List<Exporter.Item> chosen = s.selectedExportable();
        if (chosen.isEmpty()) { append("没有勾选任何可导出的曲目"); return; }
        busy = true;
        setBusyUi(true, "导出中…");
        setProgress(0, 0, "正在准备导出 …");
        new Thread(new Runnable() { public void run() { doExportInternal(s); } }, "export").start();
    }

    /** 注意：session 必须作为参数传进来 —— 字段版本会在主线程赋值前被后台线程读到（竞态） */
    private void doExportInternal(Exporter.Session s) {
        try {
            if (s == null) { append("会话无效，请重新扫描"); return; }
            List<Exporter.Item> chosen = s.selectedExportable();
            if (chosen.isEmpty()) { append("没有可导出项"); return; }
            append("开始导出 " + chosen.size() + " 首 …");
            Exporter.export(s, chosen,
                    new Exporter.Log() { public void line(String x) { append(x); } },
                    new Exporter.Progress() {
                        public void on(int d, int t, String l) { setProgress(d, t, l); }
                    });
        } catch (Throwable t) {
            append("导出异常: " + t);
        } finally {
            busy = false;
            setBusyUi(false, null);
            hideProgress();
            runOnUiThread(new Runnable() { public void run() { refreshChips(); } });
            refreshStatus();
        }
    }

    private void confirmDelete() {
        if (busy) return;
        final Exporter.Session s = session;
        if (s == null || s.error != null) {
            append("还没有列表，请先扫描");
            return;
        }
        final List<Exporter.Item> chosen = s.selectedAll();
        if (chosen.isEmpty()) {
            append("请先勾选要删除的曲目");
            return;
        }
        long totalBytes = 0;
        int expCount = 0;
        int dlCount = 0;
        int cacheCount = 0;
        for (Exporter.Item it : chosen) {
            totalBytes += it.size;
            if (it.exported) expCount++;
            if (it.fromDownload) dlCount++;
            else cacheCount++;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), 0);

        TextView msg = new TextView(this);
        String scopeDesc;
        if (dlCount > 0 && cacheCount > 0) {
            scopeDesc = "将删除汽水音乐本地的 " + chosen.size() + " 首文件（"
                    + dlCount + " 首离线下载、" + cacheCount + " 首播放缓存，约释放 ";
        } else if (dlCount > 0) {
            scopeDesc = "将删除汽水音乐离线下载中的 " + dlCount + " 首文件（约释放 ";
        } else {
            scopeDesc = "将删除汽水音乐本地缓存中的 " + chosen.size() + " 个 .mdl 及伴生配置（约释放 ";
        }
        msg.setText(scopeDesc + Exporter.Item.formatSize(totalBytes) + "），此操作不可恢复。");
        msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(msg);

        final android.widget.CheckBox cb = new android.widget.CheckBox(this);
        if (expCount > 0) {
            cb.setText("同时删除已导出的音频（" + Exporter.REL + "/ 中 " + expCount + " 首）");
            cb.setChecked(false);
            cb.setPadding(0, dp(10), 0, 0);
            box.addView(cb);
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("删除选中的 " + chosen.size() + " 首音乐")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        doDeleteInternal(s, chosen, cb.isChecked());
                    }
                })
                .show();
    }

    private void doDeleteInternal(final Exporter.Session s, final List<Exporter.Item> chosen,
                                  final boolean alsoDeleteExported) {
        if (busy) return;
        busy = true;
        setBusyUi(true, "删除中…");
        setProgress(0, 0, "正在删除 " + chosen.size() + " 首缓存 …");
        new Thread(new Runnable() {
            public void run() {
                try {
                    Exporter.deleteSelected(s, chosen, alsoDeleteExported,
                            new Exporter.Log() { public void line(String x) { append(x); } },
                            new Exporter.Progress() {
                                public void on(int d, int t, String l) { setProgress(d, t, l); }
                            });
                } catch (Throwable t) {
                    append("删除异常: " + t);
                } finally {
                    busy = false;
                    setBusyUi(false, null);
                    hideProgress();
                    runOnUiThread(new Runnable() {
                        public void run() {
                            applyFilter();
                            refreshChips();
                        }
                    });
                    refreshStatus();
                }
            }
        }, "delete").start();
    }

    private void pickAll(boolean v) {
        if (!v) {
            for (Exporter.Item it : rows) it.selected = false;
        } else {
            boolean anyExp = false;
            for (Exporter.Item it : rows) if (it.exportable()) { anyExp = true; break; }
            for (Exporter.Item it : rows) {
                if (!anyExp || it.exportable()) it.selected = true;
            }
        }
        adapter.notifyDataSetChanged();
        refreshChips();
    }

    private void pickInvert() {
        for (Exporter.Item it : rows) {
            if (showAll || it.exportable()) it.selected = !it.selected;
        }
        adapter.notifyDataSetChanged();
        refreshChips();
    }

    // ================================================================ 状态
    private void refreshChips() {
        int ready = 0, picked = 0, pickedAll = 0, all = 0;
        long pickedBytes = 0;
        if (session != null) all = session.items.size();
        for (Exporter.Item it : rows) {
            if (it.exportable()) ready++;
            if (it.selected) {
                pickedAll++;
                if (it.exportable()) {
                    picked++;
                    pickedBytes += it.size;
                }
            }
        }
        chipTotal.setText(showAll ? ("共 " + all) : ("可操作 " + rows.size() + "/" + all));
        chipReady.setText("可导出 " + ready);
        chipPicked.setText("已选 " + pickedAll);
        if (chipFilter != null) {
            chipFilter.setText(showAll ? "显示全部" : "仅可操作");
        }
        if (deleteBtn != null) {
            deleteBtn.setText(pickedAll > 0 ? ("删除 (" + pickedAll + ")") : "删除");
            deleteBtn.setTextColor(pickedAll > 0 ? DANGER : alphaOf(DANGER, 0.45f));
            deleteBtn.setBackground(round(pickedAll > 0 ? DANGERBG : alphaOf(DANGERBG, 0.5f), 100));
        }
        if (exportBtn != null) {
            TextView b = (TextView) exportBtn;
            b.setText(picked > 0
                    ? ("导出选中（" + picked + " 首 · " + Exporter.Item.formatSize(pickedBytes) + "）")
                    : "导出选中");
            b.setBackground(round(picked > 0 ? ACCENT : alphaOf(ACCENT, 0.45f), 100));
        }
    }

    private void applyFilter() {
        if (session == null) return;
        rows.clear();
        for (Exporter.Item it : session.items) {
            if (!(showAll || it.exportable() || it.exported)) continue;
            if (query.length() > 0) {
                String hay = (it.display() + " " + it.subtitle()).toLowerCase();
                if (!hay.contains(query)) continue;
            }
            rows.add(it);
        }
        final java.text.Collator coll = java.text.Collator.getInstance(java.util.Locale.CHINA);
        coll.setStrength(java.text.Collator.PRIMARY);
        final int mode = sortMode;
        final boolean asc = sortAsc;
        java.util.Collections.sort(rows, new java.util.Comparator<Exporter.Item>() {
            public int compare(Exporter.Item a, Exporter.Item b) {
                int c;
                if (mode == Exporter.SORT_SIZE) {
                    c = Long.compare(a.size, b.size);
                    if (c == 0) c = cmpAlpha(a, b);
                } else if (mode == Exporter.SORT_TIME) {
                    c = Long.compare(a.mtimeMs, b.mtimeMs);
                    if (c == 0) c = cmpAlpha(a, b);
                } else {
                    c = cmpAlpha(a, b);
                }
                return asc ? c : -c;
            }
            private int cmpAlpha(Exporter.Item a, Exporter.Item b) {
                int c = coll.compare(a.display(), b.display());
                if (c != 0) return c;
                String aa = a.artist == null ? "" : a.artist;
                String ba = b.artist == null ? "" : b.artist;
                c = coll.compare(aa, ba);
                if (c != 0) return c;
                return a.md5.compareTo(b.md5);
            }
        });
        if (adapter != null) adapter.notifyDataSetChanged();
        boolean empty = rows.isEmpty();
        if (list != null) list.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (emptyHint != null) emptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void refreshStatus() {
        new Thread(new Runnable() {
            public void run() {
                cachedRootOk = Dirs.hasRoot();
                cachedAllFiles = com.syh.luna.android.MediaStoreSink
                        .hasAllFiles(MainActivity.this);
            }
        }, "status").start();
    }

    private void setBusyUi(final boolean isBusy, final String btnLabel) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (scanBtn != null) {
                    scanBtn.setText(isBusy ? (btnLabel == null ? "处理中…" : btnLabel) : "扫描曲目");
                    scanBtn.setBackground(round(isBusy ? alphaOf(ACCENT, 0.55f) : ACCENT, 100));
                }
            }
        });
    }

    private void setProgress(final int d, final int t, final String label) {
        runOnUiThread(new Runnable() {
            public void run() {
                bar.setVisibility(View.VISIBLE);
                progressText.setVisibility(View.VISIBLE);
                if (t <= 0) {
                    bar.setIndeterminate(true);
                    progressText.setText(label == null ? "" : label);
                } else {
                    bar.setIndeterminate(false);
                    bar.setMax(t);
                    bar.setProgress(d);
                    progressText.setText(d + " / " + t + "   " + (label == null ? "" : label));
                }
            }
        });
    }

    private void hideProgress() {
        runOnUiThread(new Runnable() {
            public void run() {
                bar.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
            }
        });
    }

    private void append(final String s) {
        android.util.Log.i("LunaExportApp", s == null ? "null" : s);
        try {
            // 落一份到私有目录，logcat 被冲掉时仍可取证：adb shell run-as com.syh.luna cat files/last_run.log
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(getFilesDir(), "last_run.log"), true);
            try {
                w.write(Exporter.now() + "  " + (s == null ? "" : s) + "\n");
            } finally {
                w.close();
            }
        } catch (Throwable ignore) { }
        runOnUiThread(new Runnable() {
            public void run() {
                buf.append(s == null ? "" : s).append('\n');
                if (buf.length() > 40000) buf.delete(0, buf.length() - 40000);
                logView.setText(buf.toString());
            }
        });
    }

    // ================================================================ 绘图工具
    private int dp(float v) {
        return Math.max(1, Math.round(getResources().getDisplayMetrics().density * v));
    }

    /** 把颜色按比例调成半透明（替换 alpha 通道，不是按位或） */
    private static int alphaOf(int color, float a) {
        int alpha = Math.max(0, Math.min(255, Math.round(255 * a)));
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private GradientDrawable ring(int strokeColor, int strokePx, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.TRANSPARENT);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(strokePx, strokeColor);
        return d;
    }

    /** 胶囊按钮 */
    private TextView pill(String text, int bg, int fg, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(6), dp(10), dp(6), dp(10));
        t.setBackground(round(bg, 100));
        t.setClickable(true);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackground(round(CARD, 18));
        c.setElevation(dp(0.5f));
        return c;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }
}
