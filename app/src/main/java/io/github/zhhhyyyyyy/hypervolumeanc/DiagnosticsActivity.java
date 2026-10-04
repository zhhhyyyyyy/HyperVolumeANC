package io.github.zhhhyyyyyy.hypervolumeanc;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行诊断：显示各作用域上报的实时状态。
 *
 * 这里展示的是「功能有没有生效、没生效是因为什么」，不是罗列一堆混淆类名——
 * 不同 ROM、不同版本、是否装了第三方耳机模块都会改变具体实现，只有结论和原因对用户有意义。
 */
public final class DiagnosticsActivity extends Activity {
    private static final long PROBE_TIMEOUT_MS = 1400L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout content;
    private final Map<String, String> scopes = new LinkedHashMap<>();

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && HookStatus.ACTION_PONG.equals(intent.getAction())) {
                HookStatus.acceptAnswer(DiagnosticsActivity.this, intent);
            }
            render();
        }
    };

    static void start(Context context) {
        context.startActivity(new Intent(context, DiagnosticsActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeHelper.applyStored(this);
        LocaleHelper.applyStored(this);
        scopes.put(HookStatus.SYSTEM_UI, getString(R.string.settings_scope_systemui));
        scopes.put(HookStatus.BLUETOOTH_EXTENSION, getString(R.string.settings_scope_bluetooth));
        scopes.put(HookStatus.MISOUND, getString(R.string.settings_scope_misound));

        LinearLayout root = Ui.screen(this);
        ImageView refresh = Ui.iconButton(this, R.drawable.ic_restart,
                R.string.diagnostics_refresh, view -> probe());
        LinearLayout bar = Ui.topBar(this, getString(R.string.diagnostics_title), refresh);
        ImageView back = Ui.iconButton(this, R.drawable.ic_back, R.string.action_back,
                view -> finish());
        bar.addView(back, 0, new LinearLayout.LayoutParams(
                Ui.dp(this, 44), Ui.dp(this, 44)));
        root.addView(bar);

        content = Ui.column(this);
        int side = Ui.dp(this, 16);
        int bottom = Ui.dp(this, 32);
        content.setPadding(side, Ui.dp(this, 4), side, bottom);
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        Ui.applySystemBarInsets(root, bar, content, bar.getPaddingTop(), bottom);
        setContentView(root);
        probe();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(receiver);
        } catch (Throwable ignored) {
            // 未注册过。
        }
        super.onDestroy();
    }

    private void probe() {
        try {
            registerReceiver(receiver, new IntentFilter(HookStatus.ACTION_PONG),
                    Context.RECEIVER_EXPORTED);
        } catch (Throwable ignored) {
            // 已注册。
        }
        HookStatus.beginProbe(this);
        render();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::render, PROBE_TIMEOUT_MS);
    }

    private void render() {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        LinearLayout intro = Ui.card(this);
        intro.addView(Ui.row(this, getString(R.string.diagnostics_title),
                getString(R.string.diagnostics_summary), null, false));
        intro.addView(Ui.hint(this, getString(R.string.diagnostics_hint)));
        Ui.addCard(content, intro, 0);

        for (Map.Entry<String, String> scope : scopes.entrySet()) {
            content.addView(Ui.groupTitle(this, scope.getValue()));
            Ui.addCard(content, buildScopeCard(scope.getKey()), 0);
        }

        LinearLayout actions = Ui.card(this);
        actions.addView(Ui.row(this, getString(R.string.diagnostics_copy),
                getString(R.string.diagnostics_copy_summary), Ui.chevron(this), true));
        actions.getChildAt(0).setOnClickListener(view -> copyReport());
        Ui.addCard(content, actions, 12);
    }

    private LinearLayout buildScopeCard(String scope) {
        LinearLayout card = Ui.card(this);
        View dot = Ui.statusDot(this);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 10));
        LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10));
        dotParams.rightMargin = Ui.dp(this, 12);
        header.addView(dot, dotParams);
        boolean alive = HookStatus.isAlive(scope);
        Ui.tintStatusDot(dot, alive);
        LinearLayout labels = Ui.column(this);
        labels.addView(Ui.text(this, scopes.get(scope), 16, R.color.text_primary, false));
        String reportJson = HookStatus.report(this, scope);
        TextView meta = Ui.text(this, describeMeta(scope, reportJson), 12,
                R.color.text_secondary, false);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.topMargin = Ui.dp(this, 3);
        labels.addView(meta, metaParams);
        header.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        card.addView(header);

        if (reportJson == null || reportJson.isEmpty()) {
            card.addView(Ui.hint(this, getString(R.string.diagnostics_empty)));
            return card;
        }
        try {
            JSONObject root = new JSONObject(reportJson);
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) {
                card.addView(Ui.hint(this, getString(R.string.diagnostics_empty)));
                return card;
            }
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                Ui.addDivider(card);
                card.addView(buildItemRow(item));
            }
        } catch (Throwable error) {
            card.addView(Ui.hint(this, getString(R.string.diagnostics_empty)));
        }
        return card;
    }

    private View buildItemRow(JSONObject item) {
        String name = item.optString("name", "");
        String detail = item.optString("detail", "");
        int count = item.optInt("count", 0);
        String state = item.optString("state", "WAITING");
        if (count > 0) {
            detail = detail.isEmpty()
                    ? getString(R.string.diagnostics_count, count)
                    : detail + " · " + getString(R.string.diagnostics_count, count);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        View dot = Ui.statusDot(this);
        LinearLayout.LayoutParams dotParams =
                new LinearLayout.LayoutParams(Ui.dp(this, 8), Ui.dp(this, 8));
        dotParams.rightMargin = Ui.dp(this, 12);
        row.addView(dot, dotParams);
        Ui.tintStatusDot(dot, "OK".equals(state));
        LinearLayout labels = Ui.column(this);
        labels.addView(Ui.text(this, name, 15, R.color.text_primary, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 3);
        labels.addView(Ui.text(this, stateLabel(state) + " · " + detail, 13,
                R.color.text_secondary, false), params);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        return row;
    }

    private String describeMeta(String scope, String reportJson) {
        if (!HookStatus.isAlive(scope)) {
            return getString(R.string.status_disconnected);
        }
        if (reportJson == null || reportJson.isEmpty()) {
            return getString(R.string.diagnostics_refresh);
        }
        try {
            JSONObject root = new JSONObject(reportJson);
            return getString(R.string.diagnostics_report_meta,
                    root.optString("versionName", "-"),
                    root.optInt("pid", 0));
        } catch (Throwable error) {
            return getString(R.string.status_connected);
        }
    }

    private String stateLabel(String state) {
        return switch (state) {
            case "OK" -> getString(R.string.diagnostics_state_ok);
            case "MISSING" -> getString(R.string.diagnostics_state_missing);
            case "FAILED" -> getString(R.string.diagnostics_state_failed);
            default -> getString(R.string.diagnostics_state_waiting);
        };
    }

    /** 把当前诊断整理成纯文本，方便贴到群里或 Issue 里。 */
    private String buildReportText() {
        StringBuilder builder = new StringBuilder();
        builder.append(getString(R.string.app_name)).append(' ')
                .append(AboutPage.versionName(this)).append('\n');
        java.text.DateFormat format = java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.SHORT, java.text.DateFormat.MEDIUM);
        builder.append(format.format(new java.util.Date())).append('\n');
        for (Map.Entry<String, String> scope : scopes.entrySet()) {
            String key = scope.getKey();
            builder.append('\n')
                    .append(scope.getValue())
                    .append(" (")
                    .append(key)
                    .append(") · ")
                    .append(getString(HookStatus.isAlive(key)
                            ? R.string.status_connected
                            : R.string.status_disconnected))
                    .append('\n');
            String reportJson = HookStatus.report(this, key);
            if (reportJson == null || reportJson.isEmpty()) {
                builder.append("  ").append(getString(R.string.diagnostics_empty)).append('\n');
                continue;
            }
            try {
                JSONObject root = new JSONObject(reportJson);
                builder.append("  ").append(getString(R.string.diagnostics_report_meta,
                        root.optString("versionName", "-"),
                        root.optInt("pid", 0))).append('\n');
                JSONArray items = root.optJSONArray("items");
                if (items == null) {
                    continue;
                }
                for (int index = 0; index < items.length(); index++) {
                    JSONObject item = items.optJSONObject(index);
                    if (item == null) {
                        continue;
                    }
                    builder.append("  · ").append(stateLabel(item.optString("state", "")))
                            .append(" | ").append(item.optString("name", ""))
                            .append(" | ").append(item.optString("detail", ""));
                    int count = item.optInt("count", 0);
                    if (count > 0) {
                        builder.append(" | ").append(getString(R.string.diagnostics_count, count));
                    }
                    builder.append('\n');
                }
            } catch (Throwable error) {
                builder.append("  ").append(getString(R.string.diagnostics_empty)).append('\n');
            }
        }
        return builder.toString();
    }

    private void copyReport() {
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "HyperVolumeANC diagnostics", buildReportText()));
                android.widget.Toast.makeText(this, R.string.diagnostics_copy_done,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable error) {
            android.widget.Toast.makeText(this, R.string.diagnostics_copy_failed,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
