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
    static final String SYSTEM_UI = "com.android.systemui";
    static final String BLUETOOTH_EXTENSION = "com.xiaomi.bluetooth";
    static final String[] SCOPES = {SYSTEM_UI, BLUETOOTH_EXTENSION};

    private static final Set<String> answered = ConcurrentHashMap.newKeySet();
    private static volatile long currentNonce;

    private HookStatus() {
    }

    static void beginProbe(Context context) {
        answered.clear();
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
        if (intent == null) {
            return false;
        }
        String process = intent.getStringExtra(EXTRA_PROCESS);
        if (process == null || process.isEmpty()) {
            return false;
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
