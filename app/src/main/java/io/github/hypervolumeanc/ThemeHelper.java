package io.github.hypervolumeanc;

import android.app.UiModeManager;
import android.content.Context;

/** Per-app light / dark / system theme, applied through {@link UiModeManager}. */
final class ThemeHelper {
    private ThemeHelper() {
    }

    static void applyStored(Context context) {
        UiModeManager manager = context.getSystemService(UiModeManager.class);
        if (manager == null) {
            return;
        }
        int mode = switch (Settings.getThemeMode(context)) {
            case Settings.THEME_LIGHT -> UiModeManager.MODE_NIGHT_NO;
            case Settings.THEME_DARK -> UiModeManager.MODE_NIGHT_YES;
            default -> UiModeManager.MODE_NIGHT_AUTO;
        };
        try {
            manager.setApplicationNightMode(mode);
        } catch (Throwable ignored) {
            // Keep the system theme when the device does not expose per-app night mode.
        }
    }
}
