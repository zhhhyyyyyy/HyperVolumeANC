package io.github.hypervolumeanc;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 关于页：渐变作为页面背景铺满整页（在内容下面），卡片与图标在滚动内容里，
 * 因此滚动时不会出现背景悬浮在内容上的割裂感，做法与 HyperCeiler 关于页一致。
 */
final class AboutPage {
    private static final String DEVELOPER_NAME = "zhhhyyyyyy";
    private static final String DEVELOPER_URL = "https://github.com/zhhhyyyyyy";
    private static final String TELEGRAM_URL = "https://t.me/+yCcx0sOHbMQyNTI1";
    private static final String MIUIX_URL = "https://compose-miuix-ui.github.io/miuix/";

    private AboutPage() {
    }

    static View create(Activity activity, int topPadding,
                       View.OnScrollChangeListener externalListener) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(activity.getColor(R.color.window_background));
        root.addView(new GradientBackgroundView(activity), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = Ui.column(activity);
        int side = Ui.dp(activity, 16);
        int baseTop = topPadding + Ui.dp(activity, 12);
        content.setPadding(side, baseTop, side, Ui.dp(activity, 96));
        View hero = hero(activity);
        content.addView(hero);
        content.addView(cards(activity));

        ScrollView scrollView = new ScrollView(activity);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 标题区随滚动位移、缩放与淡出（与 HyperCeiler 关于页一致）。
        scrollView.setOnScrollChangeListener((view, scrollX, scrollY, oldX, oldY) -> {
            float progress = Math.min(1f, scrollY / (float) Ui.dp(activity, 170));
            hero.setAlpha(1f - progress * 0.88f);
            hero.setScaleX(1f - progress * 0.28f);
            hero.setScaleY(1f - progress * 0.28f);
            hero.setTranslationY(scrollY * 0.34f);
            if (externalListener != null) {
                externalListener.onScrollChange(view, scrollX, scrollY, oldX, oldY);
            }
        });
        root.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            android.graphics.Insets bars = windowInsets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            content.setPadding(side, baseTop + bars.top, side, Ui.dp(activity, 96) + bars.bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
        return root;
    }

    private static View hero(Activity activity) {
        LinearLayout hero = Ui.column(activity);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(0, Ui.dp(activity, 22), 0, Ui.dp(activity, 22));

        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setBackground(activity.getDrawable(R.drawable.bg_app_icon_plate));
        icon.setClipToOutline(true);
        icon.setElevation(Ui.dp(activity, 6));
        icon.setPadding(Ui.dp(activity, 8), Ui.dp(activity, 8),
                Ui.dp(activity, 8), Ui.dp(activity, 8));
        hero.addView(icon, new LinearLayout.LayoutParams(
                Ui.dp(activity, 96), Ui.dp(activity, 96)));

        TextView name = Ui.text(activity, activity.getString(R.string.app_name), 24,
                R.color.text_primary, true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = Ui.dp(activity, 14);
        hero.addView(name, nameParams);

        TextView tagline = Ui.text(activity, activity.getString(R.string.about_tagline,
                        versionName(activity)), 13, R.color.text_secondary, false);
        LinearLayout.LayoutParams taglineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        taglineParams.topMargin = Ui.dp(activity, 4);
        hero.addView(tagline, taglineParams);
        return hero;
    }

    private static View cards(Activity activity) {
        LinearLayout wrapper = Ui.column(activity);

        LinearLayout feature = Ui.card(activity);
        feature.addView(Ui.row(activity, activity.getString(R.string.about_feature_title),
                activity.getString(R.string.about_feature_body), null, false));
        wrapper.addView(feature, matchParams(activity, 0));

        LinearLayout tip = Ui.card(activity);
        TextView tipTitle = Ui.text(activity, activity.getString(R.string.about_developer_tip_title),
                16, R.color.text_primary, false);
        tipTitle.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 14),
                Ui.dp(activity, 16), 0);
        tip.addView(tipTitle);
        TextView tipBody = Ui.text(activity, activity.getString(R.string.about_developer_tip_body),
                14, R.color.text_secondary, false);
        tipBody.setLineSpacing(Ui.dp(activity, 3), 1.15f);
        tipBody.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8),
                Ui.dp(activity, 16), Ui.dp(activity, 16));
        tip.addView(tipBody);
        wrapper.addView(tip, matchParams(activity, 12));

        LinearLayout developer = Ui.card(activity);
        developer.addView(developerRow(activity));
        wrapper.addView(developer, matchParams(activity, 12));

        LinearLayout links = Ui.card(activity);
        links.addView(replayRow(activity));
        Ui.addDivider(links);
        links.addView(projectRow(activity));
        Ui.addDivider(links);
        links.addView(linkRow(activity, R.drawable.ic_brand_telegram,
                activity.getString(R.string.about_telegram),
                activity.getString(R.string.about_telegram_summary), TELEGRAM_URL));
        Ui.addDivider(links);
        links.addView(linkRow(activity, R.drawable.ic_brand_github,
                activity.getString(R.string.about_framework),
                activity.getString(R.string.about_framework_summary),
                "https://github.com/LSPosed/LSPosed"));
        Ui.addDivider(links);
        links.addView(linkRow(activity, 0,
                activity.getString(R.string.about_miuix),
                activity.getString(R.string.about_miuix_summary), MIUIX_URL));
        wrapper.addView(links, matchParams(activity, 12));

        LinearLayout appInfo = Ui.card(activity);
        View licenses = Ui.row(activity, activity.getString(R.string.settings_licenses),
                null, Ui.chevron(activity), true);
        licenses.setOnClickListener(view ->
                InfoActivity.start(activity, InfoActivity.PAGE_LICENSES));
        appInfo.addView(licenses);
        Ui.addDivider(appInfo);
        View contributors = Ui.row(activity, activity.getString(R.string.settings_contributors),
                null, Ui.chevron(activity), true);
        contributors.setOnClickListener(view ->
                InfoActivity.start(activity, InfoActivity.PAGE_CONTRIBUTORS));
        appInfo.addView(contributors);
        wrapper.addView(appInfo, matchParams(activity, 12));

        LinearLayout details = Ui.card(activity);
        details.addView(Ui.row(activity, activity.getString(R.string.about_package),
                activity.getPackageName(), null, false));
        Ui.addDivider(details);
        details.addView(Ui.row(activity, activity.getString(R.string.about_scope),
                activity.getString(R.string.about_scopes_value), null, false));
        wrapper.addView(details, matchParams(activity, 12));

        TextView copyright = Ui.text(activity,
                activity.getString(R.string.about_copyright, DEVELOPER_NAME),
                12, R.color.text_tertiary, false);
        copyright.setPadding(Ui.dp(activity, 22), Ui.dp(activity, 18), Ui.dp(activity, 22), 0);
        wrapper.addView(copyright);
        return wrapper;
    }

    private static LinearLayout.LayoutParams matchParams(Activity activity, float topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(activity, topMargin);
        return params;
    }

    private static View developerRow(Activity activity) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 14),
                Ui.dp(activity, 16), Ui.dp(activity, 14));
        row.setBackground(activity.getDrawable(R.drawable.bg_row));
        row.setOnClickListener(view -> Ui.openUrl(activity, DEVELOPER_URL));

        ImageView avatar = Ui.avatar(activity, R.drawable.developer_avatar, 48);
        row.addView(avatar, new LinearLayout.LayoutParams(
                Ui.dp(activity, 48), Ui.dp(activity, 48)));

        LinearLayout labels = Ui.column(activity);
        labels.setPadding(Ui.dp(activity, 14), 0, 0, 0);
        labels.addView(Ui.text(activity, activity.getString(R.string.about_developer_label),
                13, R.color.text_secondary, false));
        TextView name = Ui.text(activity, DEVELOPER_NAME, 16, R.color.text_primary, true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = Ui.dp(activity, 2);
        labels.addView(name, nameParams);
        labels.addView(Ui.text(activity, DEVELOPER_URL, 13, R.color.text_secondary, false));
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        row.addView(Ui.chevron(activity));
        return row;
    }

    private static View replayRow(Activity activity) {
        View row = Ui.row(activity, activity.getString(R.string.about_replay_oobe),
                activity.getString(R.string.about_replay_oobe_summary),
                Ui.chevron(activity), true);
        row.setOnClickListener(view -> {
            Settings.setOobeDone(activity, false);
            activity.startActivity(new Intent(activity, OobeActivity.class));
            activity.finish();
        });
        return row;
    }

    private static View projectRow(Activity activity) {
        String url = activity.getString(R.string.project_url).trim();
        boolean available = !url.isEmpty();
        View row = Ui.linkRow(activity, R.drawable.ic_brand_github,
                activity.getString(R.string.about_project_link),
                available ? url : activity.getString(R.string.about_project_placeholder),
                available ? Ui.chevron(activity) : null, available);
        if (available) {
            row.setOnClickListener(view -> Ui.openUrl(activity, url));
        }
        return row;
    }

    private static View linkRow(Activity activity, int iconRes, String title,
                                String summary, String url) {
        View row = iconRes == 0
                ? Ui.row(activity, title, summary, Ui.chevron(activity), true)
                : Ui.linkRow(activity, iconRes, title, summary, Ui.chevron(activity), true);
        row.setOnClickListener(view -> Ui.openUrl(activity, url));
        return row;
    }

    static String versionName(Activity activity) {
        try {
            android.content.pm.PackageInfo info =
                    activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Throwable error) {
            return "";
        }
    }

}
