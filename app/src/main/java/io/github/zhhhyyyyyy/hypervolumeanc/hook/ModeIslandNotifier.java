package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;

import org.json.JSONObject;

import java.lang.reflect.Method;

/**
 * Shows the HyperOS device notification (超级岛) when the volume panel switches the
 * headset into noise cancelling or transparency.
 *
 * <p>SystemUI only renders {@code strong_toast_action} requests that carry an
 * {@code island_param} it can parse into its DeviceNotificationModel; without that
 * field the request is dropped silently. The payload below mirrors what the MIUI
 * Bluetooth extension sends: a text on the left and a drawable icon on the right,
 * resolved from {@code package_name}.
 */
final class ModeIslandNotifier {
    private static final String TAG = "HyperVolumeANC";
    private static final String MODULE_PACKAGE = "io.github.zhhhyyyyyy.hypervolumeanc";
    private static final String COMMAND = "strong_toast_action";
    private static final String COMMAND_KEY = "show_custom_strong_toast";
    private static final String CATEGORY_TEXT_BITMAP_INTENT = "text_bitmap_intent";
    private static final String NOTIFY_ID = "hypervolumeanc_mode";
    private static final long DURATION_MS = 2_500L;
    private static final int ICON_TYPE_NORMAL = 0;
    private static final int TEXT_COLOR_DEFAULT = -1;
    private static final int VIEW_FLAG_DEFAULT = 0;

    private ModeIslandNotifier() {
    }

    // "statusbar" is the hidden HyperOS/MIUI service that owns the strong toast channel.
    @SuppressLint("WrongConstant")
    static void show(Context context, int mode, String deviceName) {
        if (mode == AncController.MODE_OFF) {
            return;
        }
        boolean transparency = mode == AncController.MODE_TRANSPARENCY;
        String text = transparency ? "通透开启" : "降噪开启";
        String iconName = transparency ? "mode_transparency" : "mode_noise_cancelling";
        try {
            Bundle bundle = new Bundle();
            bundle.putString("package_name", MODULE_PACKAGE);
            bundle.putString("notifyId", NOTIFY_ID);
            bundle.putString("strong_toast_category", CATEGORY_TEXT_BITMAP_INTENT);
            bundle.putString("status_bar_strong_toast", COMMAND_KEY);
            bundle.putLong("duration", DURATION_MS);
            bundle.putString("island_param", islandParam(text, iconName).toString());
            bundle.putString("param", guideParam(text, iconName).toString());

            Object statusBar = context.getSystemService("statusbar");
            if (statusBar == null) {
                Log.w(TAG, "status bar service unavailable for mode island");
                return;
            }
            Method setStatus = findSetStatus(statusBar.getClass());
            if (setStatus == null) {
                Log.w(TAG, "StatusBarManager.setStatus unavailable");
                return;
            }
            setStatus.invoke(statusBar, 1, COMMAND, bundle);
            Log.i(TAG, "mode island sent text=" + text + " icon=" + iconName
                    + (deviceName == null ? "" : " device=" + deviceName));
        } catch (Throwable error) {
            Log.w(TAG, "failed to show mode island", error);
        }
    }

    /** Island payload: mode icon on the left, text on the right. */
    private static JSONObject islandParam(String text, String iconName) throws Exception {
        return new JSONObject()
                .put("left", new JSONObject().put("iconParams", new JSONObject()
                        .put("iconResName", iconName)
                        .put("iconType", ICON_TYPE_NORMAL)
                        .put("iconFormat", "png")
                        .put("category", "drawable")))
                .put("right", new JSONObject().put("textParams", new JSONObject()
                        .put("text", text)
                        .put("textColor", TEXT_COLOR_DEFAULT)
                        .put("turnAnim", true)));
    }

    /** Guide payload used by older SystemUI builds; keeps the same content. */
    private static JSONObject guideParam(String text, String iconName) throws Exception {
        return new JSONObject()
                .put("left", new JSONObject().put("iconParams", new JSONObject()
                        .put("iconResName", iconName)
                        .put("iconType", ICON_TYPE_NORMAL)
                        .put("iconFormat", "png")
                        .put("type", "drawable")
                        .put("resPackageName", MODULE_PACKAGE)))
                .put("right", new JSONObject().put("textParams", new JSONObject()
                        .put("text", text)
                        .put("textColor", TEXT_COLOR_DEFAULT)
                        .put("viewFlags", VIEW_FLAG_DEFAULT)));
    }

    // "statusbar" is the hidden HyperOS/MIUI status bar service used for strong toasts.
    @SuppressLint("WrongConstant")
    private static Method findSetStatus(Class<?> statusBarClass) {
        try {
            return statusBarClass.getMethod("setStatus", int.class, String.class, Bundle.class);
        } catch (Throwable ignored) {
            // Fall through to the declared-method lookup used by some MIUI builds.
        }
        try {
            Method method = statusBarClass.getDeclaredMethod(
                    "setStatus", int.class, String.class, Bundle.class);
            method.setAccessible(true);
            return method;
        } catch (Throwable error) {
            Log.w(TAG, "setStatus not found on " + statusBarClass.getName(), error);
            return null;
        }
    }
}
