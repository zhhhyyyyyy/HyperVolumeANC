package io.github.zhhhyyyyyy.hypervolumeanc;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 更新模块弹窗，行为与 HyperChanger 的软件更新页一致：读取 update.json 对比版本号。 */
final class UpdateDialog {
    private UpdateDialog() {
    }

    static void show(Activity activity) {
        LinearLayout content = Ui.column(activity);
        content.setBackground(activity.getDrawable(R.drawable.bg_dialog));
        content.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 20),
                Ui.dp(activity, 20), Ui.dp(activity, 12));

        content.addView(Ui.text(activity, activity.getString(R.string.update_dialog_title), 18,
                R.color.text_primary, true));
        TextView status = Ui.text(activity, "", 14, R.color.text_secondary, false);
        status.setLineSpacing(Ui.dp(activity, 3), 1.15f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = Ui.dp(activity, 10);
        content.addView(status, statusParams);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        TextView download = Ui.button(activity, activity.getString(R.string.update_open_download),
                true, null);
        download.setVisibility(ViewGroup.GONE);
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 44));
        downloadParams.topMargin = Ui.dp(activity, 16);
        content.addView(download, downloadParams);

        TextView recheck = Ui.button(activity, activity.getString(R.string.update_recheck),
                false, null);
        LinearLayout.LayoutParams recheckParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 44));
        recheckParams.topMargin = Ui.dp(activity, 10);
        content.addView(recheck, recheckParams);

        content.addView(Ui.button(activity, activity.getString(R.string.action_cancel), false,
                        view -> dialog.dismiss()),
                cancelParams(activity));

        String updateUrl = activity.getString(R.string.update_url).trim();
        Runnable[] checkTask = new Runnable[1];
        checkTask[0] = () -> {
            if (updateUrl.isEmpty()) {
                status.setText(activity.getString(R.string.update_current,
                        versionName(activity), versionCode(activity)) + "\n"
                        + activity.getString(R.string.update_unset));
                download.setVisibility(ViewGroup.GONE);
                return;
            }
            status.setText(activity.getString(R.string.update_current,
                    versionName(activity), versionCode(activity)) + "\n"
                    + activity.getString(R.string.update_checking));
            download.setVisibility(ViewGroup.GONE);
            UpdateChecker.check(updateUrl, (info, error) -> {
                if (dialog.isShowing()) {
                    if (error != null) {
                        status.setText(activity.getString(R.string.update_error, error));
                    } else if (info == null) {
                        status.setText(activity.getString(R.string.update_error, "unknown"));
                    } else if (info.versionCode > versionCode(activity)) {
                        status.setText(activity.getString(R.string.update_available,
                                info.versionName, info.versionCode));
                        String target = info.apkUrl.isEmpty() ? info.releaseNoteUrl : info.apkUrl;
                        if (!target.isEmpty()) {
                            download.setVisibility(ViewGroup.VISIBLE);
                            download.setOnClickListener(view ->
                                    Ui.openUrl(activity, target));
                        }
                    } else {
                        status.setText(activity.getString(R.string.update_latest));
                    }
                }
            });
        };
        recheck.setOnClickListener(view -> checkTask[0].run());

        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.45f;
            window.setAttributes(attributes);
            DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            int width = Math.min(Ui.dp(activity, 420), (int) (metrics.widthPixels * 0.92f));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.CENTER);
        }
        checkTask[0].run();
    }

    private static LinearLayout.LayoutParams cancelParams(Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 44));
        params.topMargin = Ui.dp(activity, 10);
        return params;
    }

    static String versionName(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Throwable error) {
            return "";
        }
    }

    static long versionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.getLongVersionCode();
        } catch (Throwable error) {
            return 0L;
        }
    }
}
