package io.github.zhhhyyyyyy.hypervolumeanc;

import android.content.Context;
import android.content.Intent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live LSPosed check: the settings screen pings both scopes and only reports a scope
 * as connected when its hooked process answers. Disabling the module therefore stops
 * the answers immediately instead of leaving a stale "connected" state behind.
 */
final class HookStatus {
    static final String ACTION_HOOK_ALIVE = "io.github.zhhhyyyyyy.hypervolumeanc.action.HOOK_ALIVE";
    static final String ACTION_PING = "io.github.zhhhyyyyyy.hypervolumeanc.action.PING";
    static final String ACTION_PONG = "io.github.zhhhyyyyyy.hypervolumeanc.action.PONG";
    static final String EXTRA_PROCESS = "process";
    static final String EXTRA_NONCE = "nonce";
    /** 各作用域上报的运行诊断报告（JSON）。 */
    static final String EXTRA_REPORT_JSON = "report_json";
    static final String SYSTEM_UI = "com.android.systemui";
    static final String BLUETOOTH_EXTENSION = "com.xiaomi.bluetooth";
    /** 分应用音量面板由小米声音提供，所以它是第三个作用域。 */
    static final String MISOUND = "com.miui.misound";
    static final String[] SCOPES = {SYSTEM_UI, BLUETOOTH_EXTENSION, MISOUND};

    private static final Set<String> answered = ConcurrentHashMap.newKeySet();
    private static final java.util.Map<String, String> reports =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final String CACHE_NAME = "hypervolumeanc_hook_reports";
    private static final String CACHE_PREFIX = "report_";
    private static volatile long currentNonce;

    private HookStatus() {
    }

    static void beginProbe(Context context) {
        answered.clear();
        reports.clear();
        long nonce = System.currentTimeMillis();
        currentNonce = nonce;
        for (String scope : SCOPES) {
            try {
                context.sendBroadcast(new Intent(ACTION_PING)
                        .setPackage(scope)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_NONCE, nonce));
            } catch (Throwable ignored) {
                // The scope simply stays unanswered.
            }
        }
    }

    /** @return true when the answer belongs to the current probe round. */
    static boolean acceptAnswer(Intent intent) {
        return acceptAnswer(null, intent);
    }

    /** 记录一次作用域上报，并把报告缓存下来，下次打开诊断页时能立刻看到上次的结果。 */
    static boolean acceptAnswer(Context context, Intent intent) {
        if (intent == null) {
            return false;
        }
        String process = intent.getStringExtra(EXTRA_PROCESS);
        if (process == null || process.isEmpty()) {
            return false;
        }
        String report = intent.getStringExtra(EXTRA_REPORT_JSON);
        if (report != null && !report.isEmpty()) {
            reports.put(process, report);
            if (context != null) {
                try {
                    context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(CACHE_PREFIX + process, report)
                            .apply();
                } catch (Throwable ignored) {
                    // 缓存失败不影响本次显示。
                }
            }
        }
        long nonce = intent.getLongExtra(EXTRA_NONCE, 0L);
        if (nonce != 0L && nonce != currentNonce) {
            return false;
        }
        return answered.add(process);
    }

    static boolean isAlive(String scope) {
        return answered.contains(scope);
    }

    /** 某个作用域最近一次上报的诊断报告，没有则为 null。 */
    static String report(Context context, String scope) {
        String live = reports.get(scope);
        if (live != null && !live.isEmpty()) {
            return live;
        }
        if (context == null) {
            return null;
        }
        try {
            return context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
                    .getString(CACHE_PREFIX + scope, null);
        } catch (Throwable error) {
            return null;
        }
    }

    static boolean anyAlive() {
        for (String scope : SCOPES) {
            if (isAlive(scope)) {
                return true;
            }
        }
        return false;
    }

    static String describe(Context context, String scope) {
        return context.getString(isAlive(scope)
                ? R.string.status_connected
                : R.string.status_disconnected);
    }
}
