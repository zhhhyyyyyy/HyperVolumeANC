package io.github.zhhhyyyyyy.hypervolumeanc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** 开源代码声明与贡献者两个信息页；关于页现在是主界面的第三个标签页。 */
public final class InfoActivity extends Activity {
    private static final String EXTRA_PAGE = "page";

    static final String PAGE_LICENSES = "licenses";
    static final String PAGE_CONTRIBUTORS = "contributors";

    private static final String DEVELOPER_URL = "https://github.com/zhhhyyyyyy";
    private static final String MIUIX_URL = "https://compose-miuix-ui.github.io/miuix/";

    static void start(Context context, String page) {
        context.startActivity(new Intent(context, InfoActivity.class)
                .putExtra(EXTRA_PAGE, page));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.applyStored(this);
        LocaleHelper.applyStored(this);
        String page = getIntent() == null ? PAGE_LICENSES : getIntent().getStringExtra(EXTRA_PAGE);
        if (PAGE_CONTRIBUTORS.equals(page)) {
            setContentView(buildPage(getString(R.string.settings_contributors),
                    this::fillContributors));
        } else {
            setContentView(buildPage(getString(R.string.settings_licenses), this::fillLicenses));
        }
    }

    private View buildPage(String title, CardFiller filler) {
        LinearLayout root = Ui.screen(this);
        LinearLayout bar = Ui.topBar(this, title, null);
        ImageView back = Ui.iconButton(this, R.drawable.ic_back, R.string.action_back,
                view -> finish());
        bar.addView(back, 0, new LinearLayout.LayoutParams(
                Ui.dp(this, 44), Ui.dp(this, 44)));
        root.addView(bar);

        LinearLayout content = Ui.column(this);
        int side = Ui.dp(this, 16);
        int baseBottom = Ui.dp(this, 32);
        content.setPadding(side, Ui.dp(this, 4), side, baseBottom);
        filler.fill(content);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        Ui.applySystemBarInsets(root, bar, content, bar.getPaddingTop(), baseBottom);
        return root;
    }

    private void fillLicenses(LinearLayout content) {
        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.row(this, getString(R.string.licenses_intro_title),
                getString(R.string.licenses_intro_summary), null, false));
        Ui.addCard(content, intro, 0);

        LinearLayout card = Ui.card(this);
        addProject(card, true, "LSPosed · libxposed API", "102",
                getString(R.string.license_lsposed_desc), "https://github.com/LSPosed/LSPosed");
        addProject(card, true, "LibrePods", "",
                getString(R.string.license_librepods_desc),
                "https://github.com/kavishdevar/librepods");
        addProject(card, true, "OppoPods", "1.2.3",
                getString(R.string.license_oppopods_desc),
                "https://github.com/Leaf-lsgtky/OppoPods");
        addProject(card, true, "OppoPods · 1812z", "2.1.0",
                getString(R.string.license_oppopods_fork_desc), "https://github.com/1812z/OppoPods");
        addProject(card, true, "HuaweiPods", "",
                getString(R.string.license_huaweipods_desc),
                "https://github.com/Nshpiter/HuaweiPods");
        addProject(card, true, "SonyPods", "",
                getString(R.string.license_sonypods_desc),
                "https://github.com/Mercury000/SonyPods");
        addProject(card, true, "HyperChanger", "",
                getString(R.string.license_hyperchanger_desc),
                "https://github.com/ColdP/HyperChanger");
        addProject(card, true, "HyperCeiler", "",
                getString(R.string.license_hyperceiler_desc),
                "https://github.com/ReChronoRain/HyperCeiler");
        addProject(card, true, "Backdrop / AndroidLiquidGlass", "2.0.0",
                getString(R.string.license_liquidglass_desc),
                "https://github.com/Kyant0/AndroidLiquidGlass");
        addProject(card, false, "MIUIX", "", getString(R.string.license_miuix_desc), MIUIX_URL);
        Ui.addCard(content, card, 12);
    }

    private void fillContributors(LinearLayout content) {
        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.row(this, getString(R.string.contributors_intro), null, null, false));
        Ui.addCard(content, intro, 0);

        LinearLayout card = Ui.card(this);
        addProject(card, true, "zhhhyyyyyy", "",
                getString(R.string.contributor_self_role), DEVELOPER_URL);
        addProject(card, true, "OpenAI Codex", "",
                getString(R.string.contributor_openai_role), "https://openai.com/codex");
        addProject(card, true, "Claude", "",
                getString(R.string.contributor_claude_role), "https://claude.ai");
        addProject(card, true, "Leaf-lsgtky", "",
                getString(R.string.contributor_leaf_role),
                "https://github.com/Leaf-lsgtky");
        addProject(card, true, "1812z", "",
                getString(R.string.contributor_1812z_role), "https://github.com/1812z");
        addProject(card, true, "Nshpiter", "",
                getString(R.string.contributor_nshpiter_role), "https://github.com/Nshpiter");
        addProject(card, true, "Mercury000", "",
                getString(R.string.contributor_mercury_role), "https://github.com/Mercury000");
        addProject(card, true, "kavishdevar", "",
                getString(R.string.contributor_kavish_role), "https://github.com/kavishdevar");
        addProject(card, true, "ColdP", "",
                getString(R.string.contributor_coldp_role), "https://github.com/ColdP");
        addProject(card, true, "ReChronoRain", "",
                getString(R.string.contributor_rechrono_role),
                "https://github.com/ReChronoRain");
        addProject(card, true, "Kyant0", "",
                getString(R.string.contributor_kyant_role), "https://github.com/Kyant0");
        addProject(card, false, "MIUIX", "",
                getString(R.string.contributor_miuix_role),
                "https://github.com/compose-miuix-ui/miuix");
        Ui.addCard(content, card, 12);
    }

    private void addProject(LinearLayout card, boolean divider, String title, String version,
                            String description, String url) {
        String subtitle = version == null || version.isEmpty()
                ? description
                : version + " · " + description;
        View row = Ui.row(this, title, subtitle, Ui.chevron(this), true);
        row.setOnClickListener(view -> Ui.openUrl(this, url));
        card.addView(row);
        if (divider) {
            Ui.addDivider(card);
        }
    }

    private interface CardFiller {
        void fill(LinearLayout content);
    }
}
