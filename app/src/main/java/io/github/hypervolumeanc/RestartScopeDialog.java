package io.github.hypervolumeanc;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/** HyperOS styled dialog for restarting the module scopes. */
final class RestartScopeDialog {
    interface OnConfirm {
        void confirm(Map<String, String> targets);
    }

    private RestartScopeDialog() {
    }

    static void show(Activity activity, Map<String, String> scopes, OnConfirm onConfirm) {
        LinearLayout content = Ui.column(activity);
        content.setBackground(activity.getDrawable(R.drawable.bg_dialog));
        content.setPadding(Ui.dp(activity, 24), Ui.dp(activity, 22),
                Ui.dp(activity, 24), Ui.dp(activity, 18));

        content.addView(Ui.text(activity, activity.getString(R.string.restart_dialog_title), 18,
                R.color.text_primary, true));
        TextView summary = Ui.text(activity,
                activity.getString(R.string.restart_dialog_summary),
                13, R.color.text_secondary, false);
        summary.setLineSpacing(Ui.dp(activity, 2), 1.15f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = Ui.dp(activity, 8);
        summaryParams.bottomMargin = Ui.dp(activity, 14);
        content.addView(summary, summaryParams);

        Map<String, Switch> switches = new LinkedHashMap<>();
        for (Map.Entry<String, String> scope : scopes.entrySet()) {
            Switch toggle = new Switch(activity);
            toggle.setChecked(true);
            switches.put(scope.getKey(), toggle);
            View row = Ui.row(activity, scope.getValue(), scope.getKey(), toggle, false);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            content.addView(row, rowParams);
        }

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = Ui.dp(activity, 16);

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        TextView cancel = Ui.button(activity, activity.getString(R.string.action_cancel), false,
                view -> dialog.dismiss());
        buttons.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(activity, 44), 1.0f));

        TextView restart = Ui.button(activity, activity.getString(R.string.action_restart), true, null);
        LinearLayout.LayoutParams restartParams =
                new LinearLayout.LayoutParams(0, Ui.dp(activity, 44), 1.0f);
        restartParams.leftMargin = Ui.dp(activity, 10);
        buttons.addView(restart, restartParams);
        restart.setOnClickListener(view -> {
            Map<String, String> targets = new LinkedHashMap<>();
            for (Map.Entry<String, Switch> entry : switches.entrySet()) {
                if (entry.getValue().isChecked()) {
                    targets.put(entry.getKey(), scopes.get(entry.getKey()));
                }
            }
            dialog.dismiss();
            if (!targets.isEmpty()) {
                onConfirm.confirm(targets);
            }
        });
        content.addView(buttons, buttonsParams);

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
    }

}
