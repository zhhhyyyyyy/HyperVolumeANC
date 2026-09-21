package io.github.zhhhyyyyyy.hypervolumeanc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Receives the "module loaded" reports sent by the scoped processes. */
public final class HookStatusReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (HookStatus.ACTION_PONG.equals(intent.getAction())) {
            HookStatus.acceptAnswer(intent);
        }
    }
}
