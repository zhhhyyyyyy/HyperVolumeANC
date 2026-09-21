package io.github.zhhhyyyyyy.hypervolumeanc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers that build the HyperOS styled settings surfaces. */
final class Ui {
    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout screen(Context context) {
        LinearLayout root = column(context);
        root.setBackgroundColor(context.getColor(R.color.window_background));
        return root;
    }

    /** Keeps the header below the status bar and the last row above the navigation bar. */
    static void applySystemBarInsets(View root, View topBar, View scrollContent,
                                     int topBarBasePadding, int contentBaseBottomPadding) {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topBar.setPadding(topBar.getPaddingLeft(), topBarBasePadding + bars.top,
                    topBar.getPaddingRight(), topBar.getPaddingBottom());
            scrollContent.setPadding(scrollContent.getPaddingLeft(),
                    scrollContent.getPaddingTop(), scrollContent.getPaddingRight(),
                    contentBaseBottomPadding + bars.bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    /** Same as {@link #applySystemBarInsets} but the bottom inset goes to a bottom bar. */
    static void applyBottomBarInsets(View root, View topBar, View bottomBar,
                                     int topBarBasePadding, int bottomBarBasePadding) {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topBar.setPadding(topBar.getPaddingLeft(), topBarBasePadding + bars.top,
                    topBar.getPaddingRight(), topBar.getPaddingBottom());
            bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                    bottomBar.getPaddingRight(), bottomBarBasePadding + bars.bottom);
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    static TextView groupTitle(Context context, CharSequence title) {
        TextView view = text(context, title, 13, R.color.text_secondary, false);
        view.setPadding(dp(context, 22), dp(context, 18), dp(context, 22), dp(context, 8));
        return view;
    }

    static TextView hint(Context context, CharSequence value) {
        TextView view = text(context, value, 12, R.color.text_tertiary, false);
        view.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 14));
        view.setLineSpacing(dp(context, 2), 1.15f);
        return view;
    }

    /** Shows the three listening modes and the order used by the volume panel button. */
    static LinearLayout modeStrip(Context context, boolean includeOff) {
        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(context, 16), dp(context, 6), dp(context, 16), dp(context, 14));

        ImageView noiseCancelling = modeGlyph(context, ModeIconDrawable.MODE_NOISE_CANCELLING);
        ImageView transparency = modeGlyph(context, ModeIconDrawable.MODE_TRANSPARENCY);
        ImageView off = modeGlyph(context, ModeIconDrawable.MODE_OFF);

        addModeColumn(strip, noiseCancelling, context.getString(R.string.mode_noise_cancelling));
        strip.addView(arrow(context));
        addModeColumn(strip, transparency, context.getString(R.string.mode_transparency));
        strip.addView(arrow(context));
        addModeColumn(strip, off, context.getString(R.string.mode_off));
        applyModeStripAlpha(strip, true, includeOff);
        return strip;
    }

    /** Updates the strip created by {@link #modeStrip} when the options change. */
    static void updateModeStrip(LinearLayout strip, boolean enabled, boolean includeOff) {
        if (strip.getChildCount() < 5) {
            return;
        }
        applyModeStripAlpha(strip, enabled, includeOff);
    }

    /**
     * Applies the dimming to the glyph drawables and captions instead of the containers:
     * view alpha on a ViewGroup is composited in an offscreen layer that can keep a stale
     * alpha until the screen is rebuilt, which made the icons look washed out.
     */
    private static void applyModeStripAlpha(LinearLayout strip, boolean enabled, boolean includeOff) {
        Context context = strip.getContext();
        int captionColor = context.getColor(R.color.text_secondary);
        int arrowColor = context.getColor(R.color.text_tertiary);
        float active = enabled ? 1.0f : 0.3f;
        float offAlpha = enabled && includeOff ? 1.0f : 0.3f;
        setColumnAlpha(strip, 0, active, captionColor);
        setColumnAlpha(strip, 1, active, arrowColor);
        setColumnAlpha(strip, 2, active, captionColor);
        setColumnAlpha(strip, 3, offAlpha, arrowColor);
        setColumnAlpha(strip, 4, offAlpha, captionColor);
    }

    private static void setColumnAlpha(LinearLayout strip, int index, float alpha, int labelColor) {
        if (index >= strip.getChildCount()) {
            return;
        }
        View child = strip.getChildAt(index);
        if (!(child instanceof LinearLayout column)) {
            // Arrow: fade the text color instead of the view itself.
            if (child instanceof TextView arrow) {
                arrow.setTextColor(withAlpha(labelColor, alpha));
            }
            return;
        }
        View glyph = column.getChildAt(0);
        if (glyph instanceof ImageView image && image.getDrawable() != null) {
            image.getDrawable().setAlpha(Math.round(255 * alpha));
        }
        View caption = column.getChildAt(1);
        if (caption instanceof TextView text) {
            text.setTextColor(withAlpha(labelColor, alpha));
        }
    }

    private static int withAlpha(int color, float alpha) {
        int value = Math.max(0, Math.min(255, Math.round(255 * alpha)));
        return (color & 0x00FFFFFF) | (value << 24);
    }

    static TextView button(Context context, CharSequence label, boolean primary,
                           View.OnClickListener listener) {
        TextView button = text(context, label, 16,
                primary ? R.color.button_primary_text : R.color.text_primary, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(context.getDrawable(
                primary ? R.drawable.bg_button_primary : R.drawable.bg_button_secondary));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(listener);
        return button;
    }

    static View statusDot(Context context) {
        View dot = new View(context);
        dot.setBackground(context.getDrawable(R.drawable.bg_status_dot));
        return dot;
    }

    static void tintStatusDot(View dot, boolean connected) {
        dot.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                dot.getContext().getColor(connected
                        ? R.color.status_connected
                        : R.color.status_disconnected)));
    }

    private static void addModeColumn(LinearLayout strip, ImageView glyph, String label) {
        Context context = strip.getContext();
        LinearLayout column = column(context);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.addView(glyph, new LinearLayout.LayoutParams(dp(context, 34), dp(context, 34)));
        TextView caption = text(context, label, 12, R.color.text_secondary, false);
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = dp(context, 4);
        column.addView(caption, captionParams);
        strip.addView(column, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static ImageView modeGlyph(Context context, int mode) {
        ImageView view = new ImageView(context);
        ModeIconDrawable drawable = new ModeIconDrawable(mode);
        drawable.setTintList(android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.text_primary)));
        view.setImageDrawable(drawable);
        return view;
    }

    private static TextView arrow(Context context) {
        TextView view = text(context, "›", 18, R.color.text_tertiary, false);
        view.setPadding(dp(context, 10), 0, dp(context, 10), dp(context, 14));
        return view;
    }

    /** Circular avatar used by the developer row. */
    static ImageView avatar(Activity activity, int drawableRes, int sizeDp) {
        ImageView view = new ImageView(activity);
        int size = dp(activity, sizeDp);
        Bitmap source = BitmapFactory.decodeResource(activity.getResources(), drawableRes);
        if (source == null) {
            view.setImageResource(drawableRes);
            return view;
        }
        Bitmap round = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(round);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader shader = new BitmapShader(source,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP);
        float scale = Math.max((float) size / source.getWidth(),
                (float) size / source.getHeight());
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate((size - source.getWidth() * scale) / 2.0f,
                (size - source.getHeight() * scale) / 2.0f);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        float radius = size / 2.0f;
        canvas.drawCircle(radius, radius, radius, paint);
        view.setImageBitmap(round);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return view;
    }

    static TextView text(Context context, CharSequence value, float size, int colorRes, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTextColor(context.getColor(colorRes));
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    static LinearLayout topBar(Context context, CharSequence title, View endAction) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(context, 20), dp(context, 8), dp(context, 12), dp(context, 8));

        TextView titleView = text(context, title, 26, R.color.text_primary, true);
        bar.addView(titleView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        if (endAction != null) {
            bar.addView(endAction, new LinearLayout.LayoutParams(
                    dp(context, 44), dp(context, 44)));
        }
        return bar;
    }

    static ImageView iconButton(Context context, int iconRes, int descriptionRes, View.OnClickListener listener) {
        ImageView button = new ImageView(context);
        button.setImageResource(iconRes);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.icon_tint)));
        button.setContentDescription(context.getString(descriptionRes));
        button.setBackground(context.getDrawable(R.drawable.bg_row));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10));
        button.setOnClickListener(listener);
        return button;
    }

    static LinearLayout card(Context context) {
        LinearLayout card = column(context);
        card.setBackground(context.getDrawable(R.drawable.bg_card));
        card.setClipToOutline(true);
        card.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4));
        return card;
    }

    static void addCard(LinearLayout content, LinearLayout card, float topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(content.getContext(), topMargin);
        content.addView(card, params);
    }

    static void addDivider(LinearLayout card) {
        Context context = card.getContext();
        View divider = new View(context);
        divider.setBackgroundColor(context.getColor(R.color.divider_color));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 0.5f)));
        params.leftMargin = dp(context, 16);
        params.rightMargin = dp(context, 16);
        card.addView(divider, params);
    }

    static LinearLayout row(Context context, CharSequence title, CharSequence summary,
                            View endAction, boolean clickable) {
        return row(context, null, 60, title, null, summary, endAction, clickable);
    }

    /**
     * @param iconRes brand mark shown in front of the labels, or 0 for no icon.
     * @param note    small light text appended right after the title, or null.
     */
    static LinearLayout row(Context context, int iconRes, CharSequence title, CharSequence note,
                            CharSequence summary, View endAction, boolean clickable) {
        return row(context, iconRes == 0 ? null : Integer.valueOf(iconRes),
                60, title, note, summary, endAction, clickable);
    }

    /** Compact row with a small leading logo, used by link lists. */
    static LinearLayout linkRow(Context context, int iconRes, CharSequence title,
                                CharSequence summary, View endAction, boolean clickable) {
        return row(context, iconRes == 0 ? null : Integer.valueOf(iconRes),
                34, title, null, summary, endAction, clickable);
    }

    private static LinearLayout row(Context context, Integer iconRes, int iconWidthDp,
                                    CharSequence title, CharSequence note, CharSequence summary,
                                    View endAction, boolean clickable) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));

        if (iconRes != null) {
            row.addView(brandIcon(context, iconRes), brandIconParams(context, iconWidthDp));
        }

        LinearLayout labels = column(context);
        LinearLayout titleLine = new LinearLayout(context);
        titleLine.setOrientation(LinearLayout.HORIZONTAL);
        titleLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView titleView = text(context, title, 16, R.color.text_primary, false);
        titleLine.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (note != null && note.length() > 0) {
            TextView noteView = text(context, note, 12, R.color.text_tertiary, false);
            LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            noteParams.leftMargin = dp(context, 6);
            titleLine.addView(noteView, noteParams);
        }
        labels.addView(titleLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView summaryView = text(context, summary == null ? "" : summary,
                13, R.color.text_secondary, false);
        summaryView.setLineSpacing(dp(context, 1), 1.1f);
        summaryView.setVisibility(summary == null || summary.length() == 0
                ? View.GONE
                : View.VISIBLE);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.topMargin = dp(context, 3);
        labels.addView(summaryView, summaryParams);
        row.addView(labels, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        if (endAction != null) {
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionParams.leftMargin = dp(context, 12);
            row.addView(endAction, actionParams);
        }
        if (clickable) {
            row.setBackground(context.getDrawable(R.drawable.bg_row));
            row.setClickable(true);
            row.setFocusable(true);
        }
        return row;
    }

    /**
     * The icon slot is wide enough for the wordmark logos (Sony, OPPO) so every brand
     * mark carries a similar visual weight while the text stays column aligned.
     */
    private static ImageView brandIcon(Context context, int iconRes) {
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.text_primary)));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        return icon;
    }

    private static LinearLayout.LayoutParams brandIconParams(Context context, int iconWidthDp) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(context, iconWidthDp), dp(context, 32));
        params.rightMargin = dp(context, 14);
        return params;
    }

    static ImageView chevron(Context context) {
        ImageView view = new ImageView(context);
        view.setImageResource(R.drawable.ic_chevron_right);
        view.setImageTintList(android.content.res.ColorStateList.valueOf(
                context.getColor(R.color.text_tertiary)));
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)));
        return view;
    }

    static void openUrl(Context context, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable ignored) {
            // No browser available; keep the settings screen usable.
        }
    }
}
