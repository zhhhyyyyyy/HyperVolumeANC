package io.github.zhhhyyyyyy.hypervolumeanc.hook;

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
            "io.github.zhhhyyyyyy.hypervolumeanc.action.CONFIG_CHANGED";
    static final String EXTRA_MODULE_ENABLED = "module_enabled";
    static final String EXTRA_CYCLE_INCLUDE_OFF = "cycle_include_off";
    static final String EXTRA_ISLAND_NOTIFICATION = "island_notification";
    static final String EXTRA_APP_VOLUME_ENTRY = "app_volume_entry";

    private static final String TAG = "HyperVolumeANC";
    private static final Uri CONFIG_URI = Uri.parse("content://io.github.zhhhyyyyyy.hypervolumeanc.config");
    private static final String METHOD_GET = "get";

    private static volatile Context appContext;
    private static volatile Runnable changeListener;
    private static volatile boolean observing;
    private static volatile boolean loaded;
    private static volatile boolean moduleEnabled = true;
    private static volatile boolean cycleIncludeOff;
    private static volatile boolean islandNotification = true;
    private static volatile boolean appVolumeEntry = true;

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

    /** 切换降噪 / 通透时是否显示超级岛提示。 */
    static boolean islandNotification() {
        return islandNotification;
    }

    /** 是否在音量条上方显示分应用音量入口（有应用发声时才出现）。 */
    static boolean appVolumeEntryEnabled() {
        return appVolumeEntry;
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
                    islandNotification = intent.getBooleanExtra(
                            EXTRA_ISLAND_NOTIFICATION, islandNotification);
                    appVolumeEntry = intent.getBooleanExtra(
                            EXTRA_APP_VOLUME_ENTRY, appVolumeEntry);
                    loaded = true;
                    HookLog.i("module options changed enabled=" + moduleEnabled
                            + " includeOff=" + cycleIncludeOff
                            + " island=" + islandNotification
                            + " appVolume=" + appVolumeEntry);
                    notifyChanged();
                }
            }, new IntentFilter(ACTION_CONFIG_CHANGED), Context.RECEIVER_EXPORTED);
            observing = true;
        } catch (Throwable error) {
            HookLog.w("failed to observe module options", error);
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
        boolean previousIsland = islandNotification;
        boolean previousAppVolume = appVolumeEntry;
        try {
            Bundle result = context.getContentResolver().call(CONFIG_URI, METHOD_GET, null, null);
            if (result == null) {
                return;
            }
            moduleEnabled = result.getBoolean(EXTRA_MODULE_ENABLED, true);
            cycleIncludeOff = result.getBoolean(EXTRA_CYCLE_INCLUDE_OFF, false);
            islandNotification = result.getBoolean(EXTRA_ISLAND_NOTIFICATION, true);
            appVolumeEntry = result.getBoolean(EXTRA_APP_VOLUME_ENTRY, true);
            loaded = true;
            HookLog.i("module options loaded enabled=" + moduleEnabled
                    + " includeOff=" + cycleIncludeOff
                    + " island=" + islandNotification
                    + " appVolume=" + appVolumeEntry);
        } catch (Throwable error) {
            HookLog.w("failed to read module options", error);
            return;
        }
        if (!hadValues || previousEnabled != moduleEnabled
                || previousIncludeOff != cycleIncludeOff
                || previousIsland != islandNotification
                || previousAppVolume != appVolumeEntry) {
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
