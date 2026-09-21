package io.github.hypervolumeanc;

import android.app.LocaleManager;
import android.content.Context;
import android.os.LocaleList;

/**
 * Per-app language, applied through the platform API (Android 13+) so the system
 * recreates the activities with the chosen locale.
 */
final class LocaleHelper {
    private LocaleHelper() {
    }

    static void applyStored(Context context) {
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        if (manager == null) {
            return;
        }
        LocaleList target = switch (Settings.getLanguage(context)) {
            case Settings.LANGUAGE_ZH -> LocaleList.forLanguageTags("zh-CN");
            case Settings.LANGUAGE_EN -> LocaleList.forLanguageTags("en");
            default -> LocaleList.getEmptyLocaleList();
        };
        try {
            if (!target.equals(manager.getApplicationLocales())) {
                manager.setApplicationLocales(target);
            }
        } catch (Throwable ignored) {
            // Older builds without per-app language support simply keep the system locale.
        }
    }
}
