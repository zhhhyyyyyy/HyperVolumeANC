package io.github.zhhhyyyyyy.hypervolumeanc;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.pm.PackageManager;

/**
 * Module options. The values live in this app and are published to the scoped
 * processes through {@link ModuleConfigProvider} and a change broadcast.
 */
final class Settings {
    static final String PREFS_NAME = "hypervolumeanc_settings";
    static final String KEY_MODULE_ENABLED = "module_enabled";
    static final String KEY_CYCLE_INCLUDE_OFF = "cycle_include_off";
    static final String KEY_ISLAND_NOTIFICATION = "island_notification";
    static final String KEY_OOBE_DONE = "oobe_done";
    static final String KEY_LANGUAGE = "app_language";
    static final String KEY_THEME_MODE = "theme_mode";
    static final String KEY_NAV_STYLE = "nav_style";

    /** 桌面图标的启动入口，隐藏开关只切换这个别名，模块设置入口仍在 SplashActivity 上。 */
    private static final String LAUNCHER_ALIAS = ".LauncherActivity";

    static final String LANGUAGE_SYSTEM = "system";
    static final String LANGUAGE_ZH = "zh";
    static final String LANGUAGE_EN = "en";

    static final String THEME_SYSTEM = "system";
    static final String THEME_LIGHT = "light";
    static final String THEME_DARK = "dark";

    // 与 HyperChanger 的 NavigationStyle.preferenceValue 保持一致，否则会回退成 HyperOS 底栏。
    static final String NAV_HYPER = "hyper_os";
    static final String NAV_FLOATING = "hyper_os_floating";
    static final String NAV_GLASS = "liquid_glass";

    private static final String[] TARGETS = {
            "com.android.systemui",
            "com.xiaomi.bluetooth",
    };

    private Settings() {
    }

    static boolean moduleEnabled(Context context) {
        return preferences(context).getBoolean(KEY_MODULE_ENABLED, true);
    }

    static boolean cycleIncludesOff(Context context) {
        return preferences(context).getBoolean(KEY_CYCLE_INCLUDE_OFF, false);
    }

    /** 切到降噪 / 通透时是否用超级岛提示，默认开启。 */
    static boolean islandNotification(Context context) {
        return preferences(context).getBoolean(KEY_ISLAND_NOTIFICATION, true);
    }

    static void setModuleEnabled(Context context, boolean value) {
        write(context, KEY_MODULE_ENABLED, value);
    }

    static void setCycleIncludesOff(Context context, boolean value) {
        write(context, KEY_CYCLE_INCLUDE_OFF, value);
    }

    static void setIslandNotification(Context context, boolean value) {
        write(context, KEY_ISLAND_NOTIFICATION, value);
    }

    /**
     * 桌面图标是否已隐藏。状态由系统包管理器保管，所以这里直接读组件状态而不是偏好设置，
     * 清除应用数据或从模块管理器进来时都能拿到真实值。
     */
    static boolean launcherIconHidden(Context context) {
        try {
            int state = context.getPackageManager().getComponentEnabledSetting(
                    launcherAlias(context));
            return state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable error) {
            return false;
        }
    }

    static void setLauncherIconHidden(Context context, boolean hidden) {
        try {
            context.getPackageManager().setComponentEnabledSetting(
                    launcherAlias(context),
                    hidden
                            ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Throwable ignored) {
            // 没有权限或被系统拒绝时保持原样，开关会在下次刷新时回到真实状态。
        }
    }

    private static ComponentName launcherAlias(Context context) {
        return new ComponentName(context.getPackageName(),
                context.getPackageName() + LAUNCHER_ALIAS);
    }

    /** First-run guide state: shown until the user finishes (or re-opens) the OOBE. */
    static boolean isOobeDone(Context context) {
        return preferences(context).getBoolean(KEY_OOBE_DONE, false);
    }

    static void setOobeDone(Context context, boolean done) {
        preferences(context).edit().putBoolean(KEY_OOBE_DONE, done).apply();
    }

    static String getLanguage(Context context) {
        return preferences(context).getString(KEY_LANGUAGE, LANGUAGE_SYSTEM);
    }

    static void setLanguage(Context context, String value) {
        preferences(context).edit().putString(KEY_LANGUAGE, value).apply();
    }

    static String getThemeMode(Context context) {
        return preferences(context).getString(KEY_THEME_MODE, THEME_SYSTEM);
    }

    static void setThemeMode(Context context, String value) {
        preferences(context).edit().putString(KEY_THEME_MODE, value).apply();
    }

    static String getNavStyle(Context context) {
        String value = preferences(context).getString(KEY_NAV_STYLE, NAV_FLOATING);
        // 兼容 1.5.1 及更早版本写入的短名称。
        return switch (value == null ? "" : value) {
            case "hyper" -> NAV_HYPER;
            case "floating" -> NAV_FLOATING;
            case "glass" -> NAV_GLASS;
            default -> value;
        };
    }

    static void setNavStyle(Context context, String value) {
        preferences(context).edit().putString(KEY_NAV_STYLE, value).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void write(Context context, String key, boolean value) {
        preferences(context).edit().putBoolean(key, value).apply();
        notifyScopes(context);
    }

    /** Tells the hooked processes to pick the new options up without a restart. */
    static void notifyScopes(Context context) {
        Context appContext = context.getApplicationContext();
        for (String target : TARGETS) {
            try {
                appContext.sendBroadcast(new Intent(ModuleConfigProvider.ACTION_CONFIG_CHANGED)
                        .setPackage(target)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(ModuleConfigProvider.EXTRA_MODULE_ENABLED,
                                moduleEnabled(appContext))
                        .putExtra(ModuleConfigProvider.EXTRA_CYCLE_INCLUDE_OFF,
                                cycleIncludesOff(appContext))
                        .putExtra(ModuleConfigProvider.EXTRA_ISLAND_NOTIFICATION,
                                islandNotification(appContext)));
            } catch (Throwable ignored) {
                // The target may not be running; it reads the provider on start instead.
            }
        }
    }
}
