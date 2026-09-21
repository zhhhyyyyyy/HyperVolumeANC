package io.github.hypervolumeanc.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Module options published by the settings app. Values are read once from the
 * module provider and kept in sync through its change broadcast.
 */
final class HyperVolumeAncSettings {
    static final String ACTION_CONFIG_CHANGED =
            "io.github.hypervolumeanc.action.CONFIG_CHANGED";
    static final String EXTRA_MODULE_ENABLED = "module_enabled";
    static final String EXTRA_CYCLE_INCLUDE_OFF = "cycle_include_off";

    private static final String TAG = "HyperVolumeANC";
    private static final Uri CONFIG_URI = Uri.parse("content://io.github.hypervolumeanc.config");
    private static final String METHOD_GET = "get";

    private static volatile Context appContext;
    private static volatile Runnable changeListener;
    private static volatile boolean observing;
    private static volatile boolean loaded;
    private static volatile boolean moduleEnabled = true;
    private static volatile boolean cycleIncludeOff;

    private HyperVolumeAncSettings() {
    }

    static void attach(Context context, Runnable onChanged) {
        Context application = context.getApplicationContext();
        appContext = application == null ? context : application;
        changeListener = onChanged;
        registerReceiver();
        Thread loader = new Thread(HyperVolumeAncSettings::load, "hypervolumeanc-options");
        loader.setDaemon(true);
        loader.start();
    }

    static boolean moduleEnabled() {
        return moduleEnabled;
    }

    static boolean cycleIncludesOff() {
        return cycleIncludeOff;
    }

    private static void registerReceiver() {
        if (observing) {
            return;
        }
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (!ACTION_CONFIG_CHANGED.equals(intent == null ? null : intent.getAction())) {
                        return;
                    }
                    moduleEnabled = intent.getBooleanExtra(EXTRA_MODULE_ENABLED, moduleEnabled);
                    cycleIncludeOff = intent.getBooleanExtra(
                            EXTRA_CYCLE_INCLUDE_OFF, cycleIncludeOff);
                    loaded = true;
                    Log.i(TAG, "module options changed enabled=" + moduleEnabled
                            + " includeOff=" + cycleIncludeOff);
                    notifyChanged();
                }
            }, new IntentFilter(ACTION_CONFIG_CHANGED), Context.RECEIVER_EXPORTED);
            observing = true;
        } catch (Throwable error) {
            Log.w(TAG, "failed to observe module options", error);
        }
    }

    private static void load() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        boolean hadValues = loaded;
        boolean previousEnabled = moduleEnabled;
        boolean previousIncludeOff = cycleIncludeOff;
        try {
            Bundle result = context.getContentResolver().call(CONFIG_URI, METHOD_GET, null, null);
            if (result == null) {
                return;
            }
            moduleEnabled = result.getBoolean(EXTRA_MODULE_ENABLED, true);
            cycleIncludeOff = result.getBoolean(EXTRA_CYCLE_INCLUDE_OFF, false);
            loaded = true;
            Log.i(TAG, "module options loaded enabled=" + moduleEnabled
                    + " includeOff=" + cycleIncludeOff);
        } catch (Throwable error) {
            Log.w(TAG, "failed to read module options", error);
            return;
        }
        if (!hadValues || previousEnabled != moduleEnabled
                || previousIncludeOff != cycleIncludeOff) {
            notifyChanged();
        }
    }

    private static void notifyChanged() {
        Runnable listener = changeListener;
        if (listener != null) {
            listener.run();
        }
    }
}
