package io.github.zhhhyyyyyy.hypervolumeanc;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 首次启动引导，版式参考 HyperCeiler 的 provision 页面：
 * 动态渐变铺满整页作为背景，中间是大图标 + 大标题 + 说明，底部是主按钮与下划线次按钮，
 * 左上角是返回箭头，切换步骤时做淡入上移动画。
 */
public final class OobeActivity extends Activity {
    private static final int STEP_WELCOME = 0;
    private static final int STEP_DEVELOPER = 1;
    private static final int STEP_TERMS = 2;
    private static final int STEP_DONE = 3;
    private static final int STEP_COUNT = 4;

    private static final String STATE_STEP = "oobe_step";
    private static final long TRANSITION_MS = 260L;
    private static final TimeInterpolator INTERPOLATOR = new DecelerateInterpolator();

    private FrameLayout contentHost;
    private LinearLayout buttonBar;
    private ImageView backButton;
    private View topBar;
    private LinearLayout column;
    private int step = STEP_WELCOME;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.applyStored(this);
        LocaleHelper.applyStored(this);
        if (savedInstanceState != null) {
            step = savedInstanceState.getInt(STATE_STEP, STEP_WELCOME);
        }
        setContentView(buildContentView());
        showStep(step, false);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_STEP, step);
    }

    @Override
    public void onBackPressed() {
        if (step > STEP_WELCOME) {
            showStep(step - 1, true);
            return;
        }
        super.onBackPressed();
    }

    private View buildContentView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.window_background));
        root.addView(new GradientBackgroundView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column = Ui.column(this);
        topBar = buildTopBar();
        column.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));

        contentHost = new FrameLayout(this);
        column.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        buttonBar = Ui.column(this);
        buttonBar.setPadding(Ui.dp(this, 24), Ui.dp(this, 4), Ui.dp(this, 24), Ui.dp(this, 20));
        column.addView(buttonBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            column.setPadding(0, bars.top, 0, bars.bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
        return root;
    }

    private View buildTopBar() {
        FrameLayout bar = new FrameLayout(this);
        backButton = Ui.iconButton(this, R.drawable.ic_back, R.string.action_back,
                view -> onBackPressed());
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        params.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        params.leftMargin = Ui.dp(this, 12);
        bar.addView(backButton, params);
        return bar;
    }

    private void showStep(int target, boolean animate) {
        step = Math.max(STEP_WELCOME, Math.min(target, STEP_COUNT - 1));
        backButton.setVisibility(step == STEP_WELCOME ? View.INVISIBLE : View.VISIBLE);

        View page = switch (step) {
            case STEP_DEVELOPER -> buildDeveloperStep();
            case STEP_TERMS -> buildTermsStep();
            case STEP_DONE -> buildDoneStep();
            default -> buildWelcomeStep();
        };
        // 条款页自己有内部滚动，其余步骤在小屏上也能滚动查看。
        if (step != STEP_TERMS) {
            ScrollView scrollView = new ScrollView(this);
            scrollView.setFillViewport(true);
            scrollView.setClipToPadding(false);
            scrollView.addView(page, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            page = scrollView;
        }
        contentHost.removeAllViews();
        contentHost.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        buildButtons();

        if (animate) {
            page.setAlpha(0f);
            page.setTranslationY(Ui.dp(this, 16));
            page.animate().alpha(1f).translationY(0f)
                    .setDuration(TRANSITION_MS).setInterpolator(INTERPOLATOR).start();
            buttonBar.setAlpha(0f);
            buttonBar.animate().alpha(1f).setDuration(TRANSITION_MS).start();
        }
    }

    private View page() {
        LinearLayout page = Ui.column(this);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), Ui.dp(this, 12));
        return page;
    }

    private View heroIcon(int drawableRes, int sizeDp) {
        return heroIcon(drawableRes, sizeDp, false);
    }

    /**
     * @param fill 头像这类照片铺满整块底板，应用图标则留出内边距。
     */
    private View heroIcon(int drawableRes, int sizeDp, boolean fill) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(drawableRes);
        icon.setBackground(getDrawable(R.drawable.bg_app_icon_plate));
        icon.setClipToOutline(true);
        icon.setElevation(Ui.dp(this, 6));
        icon.setScaleType(fill ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
        if (!fill) {
            icon.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        }
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(Ui.dp(this, sizeDp), Ui.dp(this, sizeDp));
        icon.setLayoutParams(params);
        return icon;
    }

    private void addTitle(LinearLayout page, CharSequence title, CharSequence subtitle) {
        TextView titleView = Ui.text(this, title, 28, R.color.text_primary, true);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = Ui.dp(this, 22);
        page.addView(titleView, titleParams);
        if (subtitle != null && subtitle.length() > 0) {
            TextView subtitleView = Ui.text(this, subtitle, 15, R.color.text_secondary, false);
            subtitleView.setGravity(Gravity.CENTER);
            subtitleView.setLineSpacing(Ui.dp(this, 3), 1.15f);
            LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subtitleParams.topMargin = Ui.dp(this, 10);
            page.addView(subtitleView, subtitleParams);
        }
    }

    private View buildWelcomeStep() {
        LinearLayout page = (LinearLayout) page();
        page.addView(heroIcon(R.mipmap.ic_launcher, 112));
        addTitle(page, getString(R.string.app_name), getString(R.string.oobe_welcome_subtitle));

        LinearLayout card = Ui.card(this);
        addPoint(card, getString(R.string.oobe_welcome_point_1));
        Ui.addDivider(card);
        addPoint(card, getString(R.string.oobe_welcome_point_2));
        Ui.addDivider(card);
        addPoint(card, getString(R.string.oobe_welcome_point_3));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = Ui.dp(this, 26);
        page.addView(card, cardParams);
        return page;
    }

    private void addPoint(LinearLayout card, String text) {
        TextView view = Ui.text(this, "· " + text, 14, R.color.text_secondary, false);
        view.setLineSpacing(Ui.dp(this, 2), 1.15f);
        view.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        card.addView(view);
    }

    private View buildDeveloperStep() {
        LinearLayout page = (LinearLayout) page();
        page.addView(heroIcon(R.drawable.developer_avatar, 112, true));
        addTitle(page, getString(R.string.oobe_developer_name),
                getString(R.string.about_developer_label));

        LinearLayout links = Ui.card(this);
        links.addView(projectRow());
        Ui.addDivider(links);
        links.addView(telegramRow());
        Ui.addDivider(links);
        links.addView(codexRow());
        LinearLayout.LayoutParams linksParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        linksParams.topMargin = Ui.dp(this, 22);
        page.addView(links, linksParams);
        return page;
    }

    private View projectRow() {
        String url = getString(R.string.project_url).trim();
        boolean available = !url.isEmpty();
        View row = Ui.linkRow(this, R.drawable.ic_brand_github,
                getString(R.string.oobe_developer_project_title),
                available ? url : getString(R.string.oobe_developer_project_placeholder),
                available ? Ui.chevron(this) : null, available);
        if (available) {
            row.setOnClickListener(view -> Ui.openUrl(this, url));
        }
        return row;
    }

    private View telegramRow() {
        View row = Ui.linkRow(this, R.drawable.ic_brand_telegram,
                getString(R.string.oobe_developer_telegram_title),
                getString(R.string.oobe_developer_telegram), Ui.chevron(this), true);
        row.setOnClickListener(view ->
                Ui.openUrl(this, getString(R.string.oobe_developer_telegram)));
        return row;
    }

    /** 彩蛋行：这个模块的界面与代码大多是 Codex 写的，用 OpenAI 图标当梗。 */
    private View codexRow() {
        return Ui.linkRow(this, R.drawable.ic_brand_openai,
                getString(R.string.oobe_developer_codex),
                getString(R.string.oobe_developer_codex_summary), null, false);
    }

    private View buildTermsStep() {
        LinearLayout page = (LinearLayout) page();

        LinearLayout card = Ui.card(this);
        TextView terms = Ui.text(this, getString(R.string.oobe_terms_body), 14,
                R.color.text_secondary, false);
        terms.setLineSpacing(Ui.dp(this, 3), 1.15f);
        terms.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        card.addView(terms);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(card, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        CheckBox agree = new CheckBox(this);
        agree.setText(getString(R.string.oobe_terms_agree));
        agree.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        agree.setTextColor(getColor(R.color.text_primary));
        LinearLayout.LayoutParams agreeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        agreeParams.topMargin = Ui.dp(this, 10);
        page.addView(agree, agreeParams);

        agree.setOnCheckedChangeListener((button, checked) -> {
            if (primaryButton != null) {
                primaryButton.setEnabled(checked);
                primaryButton.setAlpha(checked ? 1.0f : 0.5f);
            }
        });
        agreeBox = agree;
        return page;
    }

    private CheckBox agreeBox;
    private TextView primaryButton;

    private View buildDoneStep() {
        LinearLayout page = (LinearLayout) page();
        page.addView(heroIcon(R.drawable.ic_oobe_check, 108));
        addTitle(page, getString(R.string.oobe_done_title), null);

        LinearLayout card = Ui.card(this);
        card.addView(Ui.row(this, getString(R.string.about_feature_title),
                getString(R.string.oobe_done_summary), null, false));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.topMargin = Ui.dp(this, 22);
        page.addView(card, cardParams);
        return page;
    }

    private void buildButtons() {
        buttonBar.removeAllViews();
        agreeBox = null;
        primaryButton = null;
        switch (step) {
            case STEP_WELCOME -> addPrimary(getString(R.string.action_start),
                    view -> showStep(STEP_DEVELOPER, true));
            case STEP_DEVELOPER -> {
                addPrimary(getString(R.string.action_next),
                        view -> showStep(STEP_TERMS, true));
                addSecondary(getString(R.string.action_previous),
                        view -> showStep(STEP_WELCOME, true));
            }
            case STEP_TERMS -> {
                TextView confirm = addPrimary(getString(R.string.action_agree_enter),
                        view -> showStep(STEP_DONE, true));
                confirm.setEnabled(false);
                confirm.setAlpha(0.5f);
                if (agreeBox != null && agreeBox.isChecked()) {
                    confirm.setEnabled(true);
                    confirm.setAlpha(1.0f);
                }
                addSecondary(getString(R.string.action_previous),
                        view -> showStep(STEP_DEVELOPER, true));
            }
            default -> addPrimary(getString(R.string.action_enter_app), view -> finishOobe());
        }
    }

    private TextView addPrimary(String label, View.OnClickListener listener) {
        primaryButton = Ui.button(this, label, true, listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50));
        buttonBar.addView(primaryButton, params);
        return primaryButton;
    }

    private void addSecondary(String label, View.OnClickListener listener) {
        TextView button = Ui.text(this, label, 14, R.color.text_secondary, false);
        button.setGravity(Gravity.CENTER);
        button.setPaintFlags(button.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        button.setPadding(0, Ui.dp(this, 14), 0, 0);
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(listener);
        buttonBar.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void finishOobe() {
        Settings.setOobeDone(this, true);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
