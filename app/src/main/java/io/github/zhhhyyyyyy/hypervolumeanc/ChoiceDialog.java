package io.github.zhhhyyyyyy.hypervolumeanc;

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
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.Arrays;

/** HyperOS 风格的单选弹窗，用于语言、主题与底栏样式这类选项。 */
final class ChoiceDialog {
    interface OnPick {
        void pick(int index);
    }

    private ChoiceDialog() {
    }

    static void show(Activity activity, String title, String[] labels, int selected, OnPick onPick) {
        LinearLayout content = Ui.column(activity);
        content.setBackground(activity.getDrawable(R.drawable.bg_dialog));
        content.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 20),
                Ui.dp(activity, 20), Ui.dp(activity, 12));

        content.addView(Ui.text(activity, title, 18, R.color.text_primary, true),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        for (int index = 0; index < labels.length; index++) {
            View row = row(activity, labels[index], index == selected);
            int picked = index;
            row.setOnClickListener(view -> {
                dialog.dismiss();
                onPick.pick(picked);
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = Ui.dp(activity, 4);
            content.addView(row, rowParams);
        }

        content.addView(Ui.button(activity, activity.getString(R.string.action_cancel), false,
                        view -> dialog.dismiss()),
                cancelParams(activity));

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

    private static View row(Activity activity, String label, boolean selected) {
        ImageView check = null;
        if (selected) {
            check = new ImageView(activity);
            check.setImageResource(R.drawable.ic_check);
            check.setImageTintList(android.content.res.ColorStateList.valueOf(
                    activity.getColor(R.color.accent_color)));
            check.setLayoutParams(new LinearLayout.LayoutParams(
                    Ui.dp(activity, 18), Ui.dp(activity, 18)));
        }
        return Ui.row(activity, label, null, check, true);
    }

    private static LinearLayout.LayoutParams cancelParams(Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 44));
        params.topMargin = Ui.dp(activity, 14);
        return params;
    }

    /** 返回选项在给定顺序中的下标，找不到时回退到 0。 */
    static int indexOf(String[] values, String value) {
        int index = Arrays.asList(values).indexOf(value);
        return index < 0 ? 0 : index;
    }
}
