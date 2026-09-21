package io.github.hypervolumeanc;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.compose.ui.platform.ComposeView;

import io.github.hypervolumeanc.nav.NavHost;

import java.util.LinkedHashMap;
import java.util.Map;

/** 主页（模块与支持的耳机）、设置（模块状态与外观）与关于三个标签页。 */
public final class MainActivity extends ComponentActivity {
    private static final int TAB_HOME = 0;
    private static final int TAB_SETTINGS = 1;
    private static final int TAB_ABOUT = 2;

    private static final Map<String, String> SCOPES = new LinkedHashMap<>();

    private static final String HUAWEI_PODS_URL = "https://github.com/Nshpiter/HuaweiPods";
    private static final String SONY_PODS_URL = "https://github.com/Mercury000/SonyPods";
    private static final String OPPO_PODS_URL = "https://github.com/1812z/OppoPods";

    private static final String[] LANGUAGE_VALUES = {
            Settings.LANGUAGE_SYSTEM, Settings.LANGUAGE_ZH, Settings.LANGUAGE_EN};
    private static final String[] THEME_VALUES = {
            Settings.THEME_SYSTEM, Settings.THEME_LIGHT, Settings.THEME_DARK};
    private static final String[] NAV_VALUES = {
            Settings.NAV_HYPER, Settings.NAV_FLOATING, Settings.NAV_GLASS};

    private TextView topTitle;
    private View restartButton;
    private FrameLayout pageHost;
    private final View[] pages = new View[3];
    private final View[] pageContents = new View[3];

    private Switch moduleSwitch;
    private Switch cycleSwitch;
    private TextView cycleSummary;
    private TextView statusSummary;
    private TextView statusHint;
    private TextView languageSummary;
    private TextView themeSummary;
    private TextView navStyleSummary;
    private TextView updateSummary;
    private View statusDot;
    private LinearLayout modeStrip;
    private FrameLayout navWrapper;
    private ComposeView navView;

    private static final String ICON_HOME =
            "M10,20v-6h4v6h5v-8h3L12,3 2,12h3v8z";
    private static final String ICON_SETTINGS =
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58"
                    + "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22"
                    + "l-2.39,0.96c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41"
                    + "h-3.84c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33"
                    + "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58"
                    + "C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61"
                    + "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54"
                    + "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54"
                    + "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32"
                    + "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6"
                    + "s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z";
    private static final String ICON_ABOUT =
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z";

    private boolean binding;
    private int titleCenterShift;
    private int topBarBaseColor = android.graphics.Color.TRANSPARENT;
    private View topBarView;
    private boolean probing;
    private int tab = TAB_HOME;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && HookStatus.ACTION_PONG.equals(intent.getAction())) {
                HookStatus.acceptAnswer(intent);
            }
            refreshStatusCard();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.applyStored(this);
        LocaleHelper.applyStored(this);
        if (!Settings.isOobeDone(this)) {
            startActivity(new Intent(this, OobeActivity.class));
            finish();
            return;
        }
        SCOPES.clear();
        SCOPES.put(HookStatus.SYSTEM_UI, getString(R.string.settings_scope_systemui));
        SCOPES.put(HookStatus.BLUETOOTH_EXTENSION, getString(R.string.settings_scope_bluetooth));
        setContentView(buildContentView());
        selectTab(TAB_HOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Settings.notifyScopes(this);
        registerReceiver(statusReceiver,
                new IntentFilter(HookStatus.ACTION_PONG), Context.RECEIVER_EXPORTED);
        probeStatus();
        refreshState();
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(statusReceiver);
        } catch (Throwable ignored) {
            // Not registered, nothing to release.
        }
        super.onPause();
    }

    private View buildContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.window_background));

        restartButton = Ui.iconButton(this, R.drawable.ic_restart, R.string.restart_dialog_title,
                view -> RestartScopeDialog.show(this, SCOPES,
                        targets -> ScopeRestarter.restart(this, targets)));
        LinearLayout topBar = Ui.topBar(this, getString(R.string.app_name), restartButton);
        topTitle = (TextView) topBar.getChildAt(0);
        topBarView = topBar;
        topBarBaseColor = getColor(R.color.window_background);
        int topBarHeight = Ui.dp(this, 56);

        pageHost = new SwipePageHost(this, buildSwipeDetector());
        View.OnScrollChangeListener collapse = (view, scrollX, scrollY, oldX, oldY) ->
                applyTitleCollapse(scrollY);
        topBar.addOnLayoutChangeListener((view, left, top, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) ->
                titleCenterShift = Math.max(0,
                        (right - left - topTitle.getWidth()) / 2 - topBar.getPaddingLeft()));
        pages[TAB_HOME] = wrapPage(buildHomeContent(topBarHeight));
        pages[TAB_SETTINGS] = wrapPage(buildSettingsContent(topBarHeight));
        pages[TAB_ABOUT] = AboutPage.create(this, topBarHeight, collapse);
        if (pages[TAB_HOME] instanceof ScrollView homeScroll) {
            homeScroll.setOnScrollChangeListener(collapse);
        }
        if (pages[TAB_SETTINGS] instanceof ScrollView settingsScroll) {
            settingsScroll.setOnScrollChangeListener(collapse);
        }
        for (int index = 0; index < pages.length; index++) {
            pages[index].setVisibility(index == TAB_HOME ? View.VISIBLE : View.GONE);
            pageHost.addView(pages[index], new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // 页面铺满整窗，顶栏浮在页面上方，这样关于页的渐变可以从状态栏一直铺下来。
        root.addView(pageHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.TOP;
        root.addView(topBar, barParams);

        // 与 HyperChanger 一致：导航 Compose 宿主铺满整屏，胶囊由它自己按导航栏内边距定位，
        // 内容列作为背板来源，这样悬浮/玻璃样式才能取到真实内容做模糊与折射。
        String style = Settings.getNavStyle(this);
        navWrapper = buildNavHost(style, pageHost);
        root.addView(navWrapper, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        int topBarPadding = topBar.getPaddingTop();
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            android.graphics.Insets bars = windowInsets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            topBar.setPadding(topBar.getPaddingLeft(), topBarPadding + bars.top,
                    topBar.getPaddingRight(), topBar.getPaddingBottom());
            for (int index = 0; index < pageContents.length; index++) {
                View content = pageContents[index];
                if (content != null) {
                    content.setPadding(content.getPaddingLeft(), topBarHeight + bars.top,
                            content.getPaddingRight(), content.getPaddingBottom());
                }
            }
            return windowInsets;
        });
        root.requestApplyInsets();
        return root;
    }

    /** 主页 / 设置 / 关于 之间左右滑动切换。 */
    /**
     * 大标题随滚动从小号左对齐收敛成顶部居中，同时顶栏淡入背景，避免内容压到标题上。
     */
    private void applyTitleCollapse(int scrollY) {
        if (topTitle == null) {
            return;
        }
        float progress = Math.min(1f, scrollY / (float) Ui.dp(this, 110));
        topTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26f - 8f * progress);
        topTitle.setTranslationX(progress * titleCenterShift);
        int alpha = Math.round(255 * Math.min(1f, progress * 1.15f) * 0.94f);
        if (topBarView != null) {
            topBarView.setBackgroundColor((topBarBaseColor & 0x00FFFFFF) | (alpha << 24));
        }
    }

    private GestureDetector buildSwipeDetector() {
        return new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent from, MotionEvent to, float velocityX,
                                   float velocityY) {
                if (from == null || to == null) {
                    return false;
                }
                float dx = to.getX() - from.getX();
                if (Math.abs(dx) < Ui.dp(MainActivity.this, 56)
                        || Math.abs(dx) < Math.abs(to.getY() - from.getY())) {
                    return false;
                }
                boolean forward = dx < 0;
                int target = forward ? Math.min(tab + 1, pages.length - 1)
                        : Math.max(tab - 1, 0);
                if (target == tab) {
                    return false;
                }
                selectTab(target);
                View incoming = pages[target];
                incoming.setAlpha(0f);
                incoming.setTranslationX((forward ? 1 : -1) * Ui.dp(MainActivity.this, 48));
                incoming.animate().alpha(1f).translationX(0f).setDuration(220L).start();
                return true;
            }
        });
    }

    /** 只观察触摸事件、不消费，交给内部 ScrollView 正常滚动。 */
    private static final class SwipePageHost extends FrameLayout {
        private final GestureDetector detector;

        SwipePageHost(android.content.Context context, GestureDetector detector) {
            super(context);
            this.detector = detector;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            detector.onTouchEvent(event);
            return super.dispatchTouchEvent(event);
        }
    }

    private ScrollView wrapPage(View content) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    private LinearLayout buildHomeContent(int topPadding) {
        LinearLayout content = Ui.column(this);
        int side = Ui.dp(this, 16);
        content.setPadding(side, topPadding, side, Ui.dp(this, 104));
        pageContents[TAB_HOME] = content;
        content.addView(Ui.groupTitle(this, getString(R.string.home_group_module)));
        content.addView(buildModuleCard());
        content.addView(Ui.groupTitle(this, getString(R.string.home_group_devices)));
        Ui.addCard(content, buildSupportCard(), 0);
        return content;
    }

    private LinearLayout buildSettingsContent(int topPadding) {
        LinearLayout content = Ui.column(this);
        int side = Ui.dp(this, 16);
        content.setPadding(side, topPadding, side, Ui.dp(this, 104));
        pageContents[TAB_SETTINGS] = content;
        content.addView(Ui.groupTitle(this, getString(R.string.settings_group_status)));
        content.addView(buildStatusCard());
        content.addView(Ui.groupTitle(this, getString(R.string.settings_group_appearance)));
        content.addView(buildAppearanceCard());
        content.addView(Ui.groupTitle(this, getString(R.string.settings_group_update)));
        Ui.addCard(content, buildUpdateCard(), 0);
        return content;
    }

    private LinearLayout buildUpdateCard() {
        LinearLayout card = Ui.card(this);
        View row = Ui.row(this, getString(R.string.update_title), "", Ui.chevron(this), true);
        updateSummary = findSummary(row);
        row.setOnClickListener(view -> UpdateDialog.show(this));
        card.addView(row);
        return card;
    }

    /** 直接复用 HyperChanger 的 Compose 导航栏实现。 */
    private FrameLayout buildNavHost(String style, View backdropSource) {
        navView = new ComposeView(this);
        navWrapper = new FrameLayout(this);
        navWrapper.addView(navView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        NavHost.install(navView, backdropSource, style, Settings.getThemeMode(this), "icon_and_text",
                TAB_HOME,
                java.util.List.of(getString(R.string.nav_home), getString(R.string.nav_settings),
                        getString(R.string.nav_about)),
                java.util.List.of(ICON_HOME, ICON_SETTINGS, ICON_ABOUT),
                index -> selectTab(index));
        return navWrapper;
    }

    private void selectTab(int target) {
        tab = target;
        NavHost.select(target);
        for (int index = 0; index < pages.length; index++) {
            pages[index].setVisibility(index == tab ? View.VISIBLE : View.GONE);
        }
        restartButton.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.INVISIBLE);
        if (topTitle != null) {
            topTitle.setText(switch (tab) {
                case TAB_SETTINGS -> getString(R.string.nav_settings);
                case TAB_ABOUT -> getString(R.string.nav_about);
                default -> getString(R.string.app_name);
            });
        }
    }

    private View buildStatusCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));

        statusDot = Ui.statusDot(this);
        LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dotParams.rightMargin = Ui.dp(this, 12);
        row.addView(statusDot, dotParams);

        LinearLayout labels = Ui.column(this);
        labels.addView(Ui.text(this, getString(R.string.settings_status_title), 16,
                R.color.text_primary, false));
        statusSummary = Ui.text(this, "", 13, R.color.text_secondary, false);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = Ui.dp(this, 3);
        labels.addView(statusSummary, summaryParams);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        card.addView(row);
        statusHint = Ui.hint(this, getString(R.string.settings_status_hint));
        card.addView(statusHint);
        return card;
    }

    private LinearLayout buildAppearanceCard() {
        LinearLayout card = Ui.card(this);

        View languageRow = Ui.row(this, getString(R.string.settings_language),
                getString(R.string.settings_language_summary), Ui.chevron(this), true);
        languageSummary = findSummary(languageRow);
        languageRow.setOnClickListener(view -> ChoiceDialog.show(this,
                getString(R.string.settings_language),
                new String[]{
                        getString(R.string.settings_language_system),
                        getString(R.string.settings_language_zh),
                        getString(R.string.settings_language_en)},
                ChoiceDialog.indexOf(LANGUAGE_VALUES, Settings.getLanguage(this)),
                index -> {
                    Settings.setLanguage(this, LANGUAGE_VALUES[index]);
                    LocaleHelper.applyStored(this);
                    recreate();
                }));
        card.addView(languageRow);
        Ui.addDivider(card);

        View themeRow = Ui.row(this, getString(R.string.settings_theme),
                getString(R.string.settings_theme_summary), Ui.chevron(this), true);
        themeSummary = findSummary(themeRow);
        themeRow.setOnClickListener(view -> ChoiceDialog.show(this,
                getString(R.string.settings_theme),
                new String[]{
                        getString(R.string.settings_theme_system),
                        getString(R.string.settings_theme_light),
                        getString(R.string.settings_theme_dark)},
                ChoiceDialog.indexOf(THEME_VALUES, Settings.getThemeMode(this)),
                index -> {
                    Settings.setThemeMode(this, THEME_VALUES[index]);
                    ThemeHelper.applyStored(this);
                    recreate();
                }));
        card.addView(themeRow);
        Ui.addDivider(card);

        View navRow = Ui.row(this, getString(R.string.settings_nav_style),
                getString(R.string.settings_nav_style_summary), Ui.chevron(this), true);
        navStyleSummary = findSummary(navRow);
        navRow.setOnClickListener(view -> ChoiceDialog.show(this,
                getString(R.string.settings_nav_style),
                new String[]{
                        getString(R.string.settings_nav_style_hyper),
                        getString(R.string.settings_nav_style_floating),
                        getString(R.string.settings_nav_style_glass)},
                ChoiceDialog.indexOf(NAV_VALUES, Settings.getNavStyle(this)),
                index -> {
                    Settings.setNavStyle(this, NAV_VALUES[index]);
                    recreate();
                }));
        card.addView(navRow);
        return card;
    }

    private LinearLayout buildModuleCard() {
        LinearLayout card = Ui.card(this);

        moduleSwitch = new Switch(this);
        moduleSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (binding) {
                return;
            }
            Settings.setModuleEnabled(this, checked);
            refreshState();
        });
        card.addView(Ui.row(this, getString(R.string.home_module_enable_title),
                getString(R.string.home_module_enable_summary), moduleSwitch, false));
        Ui.addDivider(card);

        cycleSwitch = new Switch(this);
        cycleSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (binding) {
                return;
            }
            Settings.setCycleIncludesOff(this, checked);
            refreshState();
        });
        View cycleRow = Ui.row(this, getString(R.string.home_cycle_title), "", cycleSwitch, false);
        cycleSummary = findSummary(cycleRow);
        card.addView(cycleRow);
        Ui.addDivider(card);

        modeStrip = Ui.modeStrip(this, Settings.cycleIncludesOff(this));
        card.addView(modeStrip);
        card.addView(Ui.hint(this, getString(R.string.home_mode_hint)));
        return card;
    }

    private LinearLayout buildSupportCard() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.row(this, R.drawable.ic_brand_xiaomi,
                getString(R.string.device_xiaomi), getString(R.string.device_xiaomi_note),
                getString(R.string.device_xiaomi_summary), null, false));
        Ui.addDivider(card);
        card.addView(Ui.row(this, R.drawable.ic_brand_apple,
                getString(R.string.device_apple), null,
                getString(R.string.device_apple_summary), null, false));
        Ui.addDivider(card);
        card.addView(moduleRow(R.drawable.ic_brand_sony, getString(R.string.device_sony),
                "SonyPods", SONY_PODS_URL));
        Ui.addDivider(card);
        card.addView(moduleRow(R.drawable.ic_brand_huawei, getString(R.string.device_huawei),
                "HuaweiPods", HUAWEI_PODS_URL));
        Ui.addDivider(card);
        card.addView(moduleRow(R.drawable.ic_brand_oppo, getString(R.string.device_oppo),
                "OppoPods", OPPO_PODS_URL));
        return card;
    }

    private View moduleRow(int iconRes, String title, String moduleName, String url) {
        View row = Ui.row(this, iconRes, title, null,
                getString(R.string.device_needs_module, moduleName), Ui.chevron(this), true);
        row.setOnClickListener(view -> Ui.openUrl(this, url));
        return row;
    }

    private TextView findSummary(View row) {
        LinearLayout labels = (LinearLayout) ((LinearLayout) row).getChildAt(0);
        return (TextView) labels.getChildAt(1);
    }

    private void refreshState() {
        binding = true;
        boolean enabled = Settings.moduleEnabled(this);
        boolean includeOff = Settings.cycleIncludesOff(this);
        moduleSwitch.setChecked(enabled);
        cycleSwitch.setChecked(includeOff);
        binding = false;

        cycleSummary.setText(enabled
                ? getString(includeOff
                ? R.string.home_cycle_on_summary
                : R.string.home_cycle_off_summary)
                : getString(R.string.home_cycle_disabled_summary));
        cycleSwitch.setEnabled(enabled);
        cycleSwitch.setAlpha(enabled ? 1.0f : 0.5f);
        Ui.updateModeStrip(modeStrip, enabled, includeOff);

        languageSummary.setText(switch (Settings.getLanguage(this)) {
            case Settings.LANGUAGE_ZH -> getString(R.string.settings_language_zh);
            case Settings.LANGUAGE_EN -> getString(R.string.settings_language_en);
            default -> getString(R.string.settings_language_system);
        });
        themeSummary.setText(switch (Settings.getThemeMode(this)) {
            case Settings.THEME_LIGHT -> getString(R.string.settings_theme_light);
            case Settings.THEME_DARK -> getString(R.string.settings_theme_dark);
            default -> getString(R.string.settings_theme_system);
        });
        navStyleSummary.setText(switch (Settings.getNavStyle(this)) {
            case Settings.NAV_HYPER -> getString(R.string.settings_nav_style_hyper);
            case Settings.NAV_GLASS -> getString(R.string.settings_nav_style_glass);
            default -> getString(R.string.settings_nav_style_floating);
        });
        updateSummary.setText(getString(R.string.update_url).trim().isEmpty()
                ? getString(R.string.update_summary_unset, UpdateDialog.versionName(this))
                : getString(R.string.update_summary, UpdateDialog.versionName(this)));
        refreshStatusCard();
    }

    private void refreshStatusCard() {
        if (statusSummary == null) {
            return;
        }
        boolean connected = HookStatus.anyAlive();
        statusSummary.setText(getString(probing
                ? R.string.status_checking
                : (connected ? R.string.status_connected : R.string.status_disconnected)));
        statusSummary.setTextColor(getColor(probing
                ? R.color.text_secondary
                : (connected ? R.color.status_connected : R.color.status_disconnected)));
        Ui.tintStatusDot(statusDot, connected);
        statusHint.setText(getString(R.string.settings_status_detail,
                HookStatus.describe(this, HookStatus.SYSTEM_UI),
                HookStatus.describe(this, HookStatus.BLUETOOTH_EXTENSION)));
    }

    private void probeStatus() {
        probing = true;
        HookStatus.beginProbe(this);
        refreshStatusCard();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            probing = false;
            refreshStatusCard();
        }, 1200L);
    }

}
