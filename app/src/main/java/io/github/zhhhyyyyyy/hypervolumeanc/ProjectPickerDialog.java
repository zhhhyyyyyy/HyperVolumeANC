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
import android.widget.TextView;

/**
 * 二次选择弹窗：同一件功能有多个第三方项目可选时（例如 OppoPods 的上游作者与分支作者），
 * 用作者头像区分开来，点选后打开对应项目主页。
 */
final class ProjectPickerDialog {
    /** 一个可选项：作者头像、作者名、项目说明与项目地址。 */
    static final class Entry {
        final int avatarRes;
        final CharSequence author;
        final CharSequence summary;
        final String url;

        Entry(int avatarRes, CharSequence author, CharSequence summary, String url) {
            this.avatarRes = avatarRes;
            this.author = author;
            this.summary = summary;
            this.url = url;
        }
    }

    private ProjectPickerDialog() {
    }

    static void show(Activity activity, CharSequence title, CharSequence summary,
                     Entry[] entries) {
        LinearLayout content = Ui.column(activity);
        content.setBackground(activity.getDrawable(R.drawable.bg_dialog));
        content.setPadding(Ui.dp(activity, 20), Ui.dp(activity, 20),
                Ui.dp(activity, 20), Ui.dp(activity, 12));

        content.addView(Ui.text(activity, title, 18, R.color.text_primary, true));
        if (summary != null && summary.length() > 0) {
            TextView summaryView = Ui.text(activity, summary, 13, R.color.text_secondary, false);
            summaryView.setLineSpacing(Ui.dp(activity, 2), 1.15f);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            summaryParams.topMargin = Ui.dp(activity, 8);
            content.addView(summaryView, summaryParams);
        }

        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        for (Entry entry : entries) {
            View row = authorRow(activity, entry);
            row.setOnClickListener(view -> {
                dialog.dismiss();
                Ui.openUrl(activity, entry.url);
            });
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = Ui.dp(activity, 6);
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

    private static View authorRow(Activity activity, Entry entry) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 12),
                Ui.dp(activity, 16), Ui.dp(activity, 12));
        row.setBackground(activity.getDrawable(R.drawable.bg_row));
        row.setClickable(true);
        row.setFocusable(true);

        int avatarSize = Ui.dp(activity, 44);
        ImageView avatar = Ui.avatar(activity, entry.avatarRes, 44);
        row.addView(avatar, new LinearLayout.LayoutParams(avatarSize, avatarSize));

        LinearLayout labels = Ui.column(activity);
        labels.setPadding(Ui.dp(activity, 14), 0, 0, 0);
        labels.addView(Ui.text(activity, entry.author, 16, R.color.text_primary, true));
        TextView summary = Ui.text(activity, entry.summary, 13, R.color.text_secondary, false);
        summary.setLineSpacing(Ui.dp(activity, 2), 1.15f);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = Ui.dp(activity, 2);
        labels.addView(summary, summaryParams);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        row.addView(Ui.chevron(activity));
        return row;
    }

    private static LinearLayout.LayoutParams cancelParams(Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 44));
        params.topMargin = Ui.dp(activity, 14);
        return params;
    }
}
