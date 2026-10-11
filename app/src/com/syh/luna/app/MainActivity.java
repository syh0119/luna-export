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
    static final int WHITE = 0xFFFFFFFF;

    // 当前生效
    private boolean dark;
    private int ACCENT, BG, CARD, TEXT, SUB, LINE, FILL, OK, OKBG, WARN, WARNBG;

    private Exporter.Session session;
    private final List<Exporter.Item> rows = new ArrayList<Exporter.Item>();
    private RowAdapter adapter;

    private TextView chipTotal, chipReady, chipPicked, chipFilter, statMain, statSub, autoSub;
    private TextView logView, progressText, emptyHint;
    private android.widget.EditText search;
    private String query = "";
    private ProgressBar bar;
    private View logBox, exportBtn;
    private ListView list;

    private volatile boolean busy;
    private final StringBuilder buf = new StringBuilder();
    private boolean logOpen;
    /** false = 列表只显示能操作的（可导出/已导出）；true = 全部 .mdl */
    private boolean showAll;

    @Override
    protected void onCreate(Bundle b) {
        dark = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        initPalette();
        super.onCreate(b);
        setContentView(buildUi());
        append("汽水导出 v" + versionName());
        append("输出到 /sdcard/" + Exporter.REL + "/");
        append("⚠️ 不会自动导出：只有你点「导出选中」才会产出文件。");
        append("用法：点「扫描曲目」→ 勾选想要的 → 点底部按钮导出。");
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
            new Thread(new Runnable() { public void run() { append(Dirs.diagnose()); } }, "diag").start();
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

        body.addView(statusCard());
        body.addView(settingsCard());
        body.addView(chips());
        body.addView(actions());
        body.addView(searchBox());
        body.addView(listCard());
        body.addView(progressArea());
        body.addView(exportButton());

        root.addView(logArea());
        return root;
    }

    /** Miuix 大标题区：无卡片、纯背景 + 30sp 粗体大标题 */
    private View header() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(20), dp(10), dp(20), dp(6));

        TextView t = new TextView(this);
        t.setText("汽水导出");
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(-0.02f);
        h.addView(t);

        TextView s = new TextView(this);
        s.setText(".mdl 离线解密 · 无损 FLAC / M4A · 歌词封面内嵌");
        s.setTextColor(SUB);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        s.setPadding(0, dp(2), 0, 0);
        h.addView(s);
        return h;
    }

    private View statusCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(22), dp(2), dp(22), dp(8));
        statMain = new TextView(this);
        statMain.setTextColor(TEXT);
        statMain.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statMain.setText("检测 root 状态 …");
        c.addView(statMain);

        statSub = new TextView(this);
        statSub.setTextColor(SUB);
        statSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        statSub.setText("输出目录 /sdcard/" + Exporter.REL + "/");
        c.addView(statSub);

        // 点一下去授予「所有文件访问权限」——有它才能把 .lrc 直接写在歌曲旁边
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!com.syh.luna.android.MediaStoreSink.hasAllFiles(MainActivity.this)) {
                    append("正在打开「所有文件访问权限」设置页（授予后 .lrc 会与歌曲同目录）…");
                    com.syh.luna.android.MediaStoreSink.requestAllFiles(MainActivity.this);
                }
            }
        });
        return c;
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

    /** 操作区：主操作实心胶囊 + 次级文字胶囊 */
    /** Miuix 开关行：启动时自动增量导出 */
    private View settingsCard() {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(9), dp(16), dp(9));

        final TextView toggle = new TextView(this);
        toggle.setText("▸ 设置 · 自动导出 / 歌词文件");
        toggle.setTextColor(SUB);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toggle.setClickable(true);
        c.addView(toggle);

        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setVisibility(View.GONE);
        box.setPadding(0, dp(6), dp(-6), 0);
        c.addView(box);

        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean open = box.getVisibility() == View.GONE;
                box.setVisibility(open ? View.VISIBLE : View.GONE);
                toggle.setText((open ? "▾" : "▸") + " 设置 · 自动导出 / 歌词文件");
            }
        });

        // ① 启动时自动增量导出
        box.addView(settingRow("启动时自动增量导出", autoSub = new TextView(this),
                Exporter.autoOnLaunch(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                        Exporter.setAutoOnLaunch(MainActivity.this, v);
                        if (v) {
                            append("⚠️ 已开启「启动时自动增量导出」：");
                            append("   以后你在汽水音乐里新听/新下载的歌，会在打开本应用时自动落盘到");
                            append("   /sdcard/" + Exporter.REL + "/，会持续占用空间。");
                            append("   建议只在「集中整理歌单」期间打开。");
                        } else {
                            append("已关闭自动导出 —— 只有你点「导出选中」才会产出文件。");
                        }
                        refreshAutoSub();
                    }
                }));

        // ② 侧车 .lrc（默认关 —— 歌词本来就内嵌在音频标签里）
        TextView lrcSub = new TextView(this);
        lrcSub.setText("歌词已内嵌在音频里（FLAC 的 LYRICS / M4A 的 ©lyr），"
                + "正常播放器都能读到，通常不需要再要 .lrc 文件");
        box.addView(settingRow("额外输出 .lrc 歌词文件", lrcSub, Exporter.lrcSidecar(this),
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                        Exporter.setLrcSidecar(MainActivity.this, v);
                        append(v ? "已开启：导出时会在歌曲旁再放一份同名 .lrc（目录里文件会翻倍）"
                                 : "已关闭：只保留内嵌歌词，不再额外生成 .lrc");
                    }
                }));

        refreshAutoSub();
        return c;
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
            // ⚠️ thumb 必须给**两个状态**的颜色。只给单一色会让「开/关」看起来一样
            //    （Android 的 thumb 位置其实是对的，但视觉上分不出来）。
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
                if (!it.exportable()) { append("「" + it.display() + "」不可导出：" + it.state()); return; }
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        t.setLayoutParams(lp);
        return t;
    }

    private View logArea() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(4), dp(16), dp(10));

        final TextView toggle = new TextView(this);
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
            h.sub.setText(it.subtitle());
            h.badge.setText(it.badge());

            boolean on = it.selected && exp;
            h.check.setText(on ? "✓" : "");
            h.check.setBackground(on ? round(ACCENT, 100) : ring(LINE, dp(1), 100));
            h.check.setTextColor(WHITE);
            h.check.setAlpha(exp ? 1f : 0.4f);

            String st = it.state();
            h.state.setText(st);
            if ("可导出".equals(st)) {
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
            if (!exp) h.sub.setText(it.subtitle());

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
                            if (s.error != null && emptyHint != null) emptyHint.setText(s.error);
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
            hideProgress();
            runOnUiThread(new Runnable() { public void run() { refreshChips(); } });
            refreshStatus();
        }
    }

    private void pickAll(boolean v) {
        for (Exporter.Item it : rows) if (it.exportable()) it.selected = v;
        adapter.notifyDataSetChanged();
        refreshChips();
    }

    private void pickInvert() {
        for (Exporter.Item it : rows) if (it.exportable()) it.selected = !it.selected;
        adapter.notifyDataSetChanged();
        refreshChips();
    }

    // ================================================================ 状态
    private void refreshChips() {
        int ready = 0, picked = 0, all = 0;
        if (session != null) all = session.items.size();
        for (Exporter.Item it : rows) {
            if (it.exportable()) ready++;
            if (it.selected && it.exportable()) picked++;
        }
        chipTotal.setText(showAll ? ("共 " + all) : ("可操作 " + rows.size() + "/" + all));
        chipReady.setText("可导出 " + ready);
        chipPicked.setText("已选 " + picked);
        if (chipFilter != null) {
            chipFilter.setText(showAll ? "显示全部" : "仅可操作");
        }
        if (exportBtn != null) {
            TextView b = (TextView) exportBtn;
            b.setText(picked > 0 ? "导出选中（" + picked + " 首）" : "导出选中");
            b.setBackground(round(picked > 0 ? ACCENT : alphaOf(ACCENT, 0.45f), 100));
        }
    }

    /**
     * 按当前筛选把 session 里的条目填进列表。
     *
     * 默认**只显示能操作的**（可导出 / 已导出）。原因：汽水音乐里大量 `.mdl` 是
     * 分块下载没下完的残片，它们连本地缓存的元数据都没有（显示成「未知曲目」），
     * 而且永远导不出来 —— 全列出来只会把真正能用的那几十首淹掉。
     */
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
        if (adapter != null) adapter.notifyDataSetChanged();
        boolean empty = rows.isEmpty();
        if (list != null) list.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (emptyHint != null) emptyHint.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void refreshStatus() {
        new Thread(new Runnable() {
            public void run() {
                final boolean root = Dirs.hasRoot();
                final boolean allFiles = com.syh.luna.android.MediaStoreSink
                        .hasAllFiles(MainActivity.this);
                runOnUiThread(new Runnable() {
                    public void run() {
                        String t;
                        if (root && allFiles) {
                            t = "✔  root + 所有文件访问：歌词直接写在歌曲旁";
                        } else if (root) {
                            t = "✔  root 已授权 · 点这里授予「所有文件访问」"
                                    + "（否则 .lrc 只能落到 Download/）";
                        } else {
                            t = "✘  未获得 root 授权（KernelSU / Magisk 里授权本应用）";
                        }
                        statMain.setText(t);
                        statMain.setTextColor(root ? TEXT : WARN);
                        String s = "输出目录 /sdcard/" + Exporter.REL + "/";
                        if (session != null && session.indexInfo != null && !session.indexInfo.isEmpty())
                            s = session.indexInfo + "   ·   扫描 " + (session.preparedMs / 1000.0) + "s";
                        statSub.setText(s);
                    }
                });
            }
        }, "status").start();
    }

    private void setProgress(final int d, final int t, final String label) {
        runOnUiThread(new Runnable() {
            public void run() {
                bar.setVisibility(View.VISIBLE);
                progressText.setVisibility(View.VISIBLE);
                bar.setMax(Math.max(1, t));
                bar.setProgress(d);
                progressText.setText(d + " / " + t + "   " + (label == null ? "" : label));
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
