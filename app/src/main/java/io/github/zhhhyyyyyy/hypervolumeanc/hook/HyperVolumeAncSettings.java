package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Module options published by the settings app.
 *
 * <p>The provider is the source of truth, but it is only reachable while the settings app
 * package is running. A freshly installed or force-stopped package makes
 * {@code ContentResolver.call()} fail with {@code IllegalArgumentException: Unknown authority},
 * and the previous behaviour was to silently fall back to the compile-time defaults. That reset
 * {@code cycle_include_off} to {@code false}, which is why the "off" entry disappeared from the
 * noise cancelling / transparency cycle until the user opened the app and toggled the switch
 * again. Options are now cached inside the hooked process, restored synchronously on attach,
 * and the provider read is retried with backoff until it succeeds.
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

    private static final String CACHE_PREFS = "hypervolumeanc_module_config";
    private static final String CACHE_MODULE_ENABLED = "module_enabled";
    private static final String CACHE_CYCLE_INCLUDE_OFF = "cycle_include_off";
    private static final String CACHE_ISLAND_NOTIFICATION = "island_notification";
    private static final String CACHE_APP_VOLUME_ENTRY = "app_volume_entry";

    /** Backoff used while the settings app package is not readable yet. */
    private static final long[] RETRY_DELAYS_MS = {2_000L, 8_000L, 30_000L, 60_000L, 120_000L};
    private static final long RELOAD_DEBOUNCE_MS = 2_000L;

    private static volatile Context appContext;
    private static volatile Runnable changeListener;
    private static volatile boolean observing;
    private static volatile boolean loaded;
    private static volatile boolean moduleEnabled = true;
    private static volatile boolean cycleIncludeOff;
    private static volatile boolean islandNotification = true;
    private static volatile boolean appVolumeEntry = true;
    private static volatile int retryIndex;
    private static volatile long lastReloadAt;

    private static final ScheduledExecutorService WORKER =
            new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, "hypervolumeanc-options");
                thread.setDaemon(true);
                return thread;
            });

    private HyperVolumeAncSettings() {
    }

    static void attach(Context context, Runnable onChanged) {
        Context application = context.getApplicationContext();
        appContext = application == null ? context : application;
        changeListener = onChanged;
        registerReceiver();
        restoreFromCache();
        WORKER.execute(HyperVolumeAncSettings::loadWithRetry);
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

    /**
     * Re-reads the provider in the background. Called when the volume panel is shown so a stale
     * cache heals itself as soon as the settings app becomes reachable again.
     */
    static void reloadAsync() {
        long now = SystemClock.uptimeMillis();
        if (now - lastReloadAt < RELOAD_DEBOUNCE_MS) {
            return;
        }
        lastReloadAt = now;
        WORKER.execute(HyperVolumeAncSettings::load);
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
                    retryIndex = 0;
                    persistToCache();
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

    /** Last known options survive a SystemUI restart even when the provider is unreachable. */
    private static void restoreFromCache() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            SharedPreferences prefs = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE);
            if (!prefs.contains(CACHE_CYCLE_INCLUDE_OFF)) {
                return;
            }
            moduleEnabled = prefs.getBoolean(CACHE_MODULE_ENABLED, true);
            cycleIncludeOff = prefs.getBoolean(CACHE_CYCLE_INCLUDE_OFF, false);
            islandNotification = prefs.getBoolean(CACHE_ISLAND_NOTIFICATION, true);
            appVolumeEntry = prefs.getBoolean(CACHE_APP_VOLUME_ENTRY, true);
            loaded = true;
            HookLog.i("module options restored from cache enabled=" + moduleEnabled
                    + " includeOff=" + cycleIncludeOff
                    + " island=" + islandNotification
                    + " appVolume=" + appVolumeEntry);
        } catch (Throwable error) {
            HookLog.w("failed to restore cached module options", error);
        }
    }

    private static void persistToCache() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        try {
            context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(CACHE_MODULE_ENABLED, moduleEnabled)
                    .putBoolean(CACHE_CYCLE_INCLUDE_OFF, cycleIncludeOff)
                    .putBoolean(CACHE_ISLAND_NOTIFICATION, islandNotification)
                    .putBoolean(CACHE_APP_VOLUME_ENTRY, appVolumeEntry)
                    .apply();
        } catch (Throwable error) {
            HookLog.w("failed to cache module options", error);
        }
    }

    private static void loadWithRetry() {
        if (load()) {
            retryIndex = 0;
            return;
        }
        int index = retryIndex;
        if (index >= RETRY_DELAYS_MS.length) {
            HookLog.w("gave up reading module options, keeping cached values");
            return;
        }
        retryIndex = index + 1;
        WORKER.schedule(HyperVolumeAncSettings::loadWithRetry, RETRY_DELAYS_MS[index],
                TimeUnit.MILLISECONDS);
    }

    private static boolean load() {
        Context context = appContext;
        if (context == null) {
            return false;
        }
        boolean hadValues = loaded;
        boolean previousEnabled = moduleEnabled;
        boolean previousIncludeOff = cycleIncludeOff;
        boolean previousIsland = islandNotification;
        boolean previousAppVolume = appVolumeEntry;
        try {
            Bundle result = context.getContentResolver().call(CONFIG_URI, METHOD_GET, null, null);
            if (result == null) {
                HookLog.w("module options provider returned no bundle");
                return false;
            }
            moduleEnabled = result.getBoolean(EXTRA_MODULE_ENABLED, true);
            cycleIncludeOff = result.getBoolean(EXTRA_CYCLE_INCLUDE_OFF, false);
            islandNotification = result.getBoolean(EXTRA_ISLAND_NOTIFICATION, true);
            appVolumeEntry = result.getBoolean(EXTRA_APP_VOLUME_ENTRY, true);
            loaded = true;
            persistToCache();
            HookLog.i("module options loaded enabled=" + moduleEnabled
                    + " includeOff=" + cycleIncludeOff
                    + " island=" + islandNotification
                    + " appVolume=" + appVolumeEntry);
        } catch (Throwable error) {
            HookLog.w("failed to read module options, keeping "
                    + (hadValues ? "cached" : "default") + " values", error);
            return false;
        }
        if (!hadValues || previousEnabled != moduleEnabled
                || previousIncludeOff != cycleIncludeOff
                || previousIsland != islandNotification
                || previousAppVolume != appVolumeEntry) {
            notifyChanged();
        }
        return true;
    }

    private static void notifyChanged() {
        Runnable listener = changeListener;
        if (listener != null) {
            listener.run();
        }
    }
}
