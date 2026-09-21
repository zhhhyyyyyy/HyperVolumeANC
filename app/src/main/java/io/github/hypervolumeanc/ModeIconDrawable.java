package io.github.hypervolumeanc;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;

/**
 * The three listening-mode glyphs shown by the volume panel: noise cancelling (ring),
 * transparency (dots) and off (the adaptive glyph).
 */
final class ModeIconDrawable extends Drawable {
    static final int MODE_OFF = 0;
    static final int MODE_NOISE_CANCELLING = 1;
    static final int MODE_TRANSPARENCY = 2;

    private static final float VIEWPORT = 90.0f;
    private static final float DOT_RADIUS = 2.448f;
    private static final float ADAPTIVE_MAJOR_X = 73.98f;
    private static final float ADAPTIVE_MAJOR_Y = 21.48f;
    private static final float ADAPTIVE_MAJOR_RADIUS = 14.41f;
    private static final float ADAPTIVE_MINOR_X = 61.25f;
    private static final float ADAPTIVE_MINOR_Y = 8.75f;
    private static final float ADAPTIVE_MINOR_RADIUS = 6.96f;
    private static final float[][] TRANSPARENCY_DOTS = {
            {44.4364f, 15.9479f}, {64.2642f, 35.7760f}, {24.6076f, 35.7760f},
            {30.4168f, 49.7972f}, {58.4586f, 21.7552f}, {30.4158f, 21.7565f},
            {58.4584f, 49.7976f}, {51.9802f, 17.4389f}, {36.6549f, 17.2886f},
            {62.7735f, 43.3180f}, {26.1005f, 28.4005f}, {62.6795f, 27.9977f},
            {26.1986f, 43.5588f}
    };

    private final int mode;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorStateList tint = ColorStateList.valueOf(Color.BLACK);
    private ColorFilter colorFilter;
    private int alpha = 255;

    ModeIconDrawable(int mode) {
        this.mode = mode;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        float width = getBounds().width();
        float height = getBounds().height();
        if (width <= 0.0f || height <= 0.0f) {
            return;
        }
        float scale = Math.min(width, height) / VIEWPORT;
        float dx = getBounds().left + (width - (VIEWPORT * scale)) / 2.0f;
        float dy = getBounds().top + (height - (VIEWPORT * scale)) / 2.0f;
        int save = canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale);

        paint.setColor(tint.getColorForState(getState(), tint.getDefaultColor()));
        paint.setColorFilter(colorFilter);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(alpha);

        float centerY = mode == MODE_TRANSPARENCY ? 35.5329f : 34.2104f;
        float bodyTop = mode == MODE_TRANSPARENCY ? 55.1158f : 53.7933f;
        float bodyBottom = mode == MODE_TRANSPARENCY ? 76.6575f : 75.3350f;
        canvas.drawCircle(44.4366f, centerY, 12.7291f, paint);
        canvas.drawRoundRect(18.0f, bodyTop, 70.875f, bodyBottom,
                10.7708f, 10.7708f, paint);

        if (mode == MODE_TRANSPARENCY) {
            for (float[] dot : TRANSPARENCY_DOTS) {
                canvas.drawCircle(dot[0], dot[1], DOT_RADIUS, paint);
            }
        } else if (mode == MODE_NOISE_CANCELLING) {
            drawNoiseControlArc(canvas);
        } else {
            drawSparkle(canvas, ADAPTIVE_MAJOR_X, ADAPTIVE_MAJOR_Y, ADAPTIVE_MAJOR_RADIUS);
            drawSparkle(canvas, ADAPTIVE_MINOR_X, ADAPTIVE_MINOR_Y, ADAPTIVE_MINOR_RADIUS);
        }
        canvas.restoreToCount(save);
    }

    private void drawSparkle(Canvas canvas, float centerX, float centerY, float radius) {
        float waist = radius * 0.25f;
        Path sparkle = new Path();
        sparkle.moveTo(centerX, centerY - radius);
        sparkle.cubicTo(centerX + waist, centerY - waist,
                centerX + waist, centerY - waist, centerX + radius, centerY);
        sparkle.cubicTo(centerX + waist, centerY + waist,
                centerX + waist, centerY + waist, centerX, centerY + radius);
        sparkle.cubicTo(centerX - waist, centerY + waist,
                centerX - waist, centerY + waist, centerX - radius, centerY);
        sparkle.cubicTo(centerX - waist, centerY - waist,
                centerX - waist, centerY - waist, centerX, centerY - radius);
        sparkle.close();
        canvas.drawPath(sparkle, paint);
    }

    private void drawNoiseControlArc(Canvas canvas) {
        Path arc = new Path();
        arc.moveTo(29.5553f, 46.9375f);
        arc.cubicTo(26.6246f, 43.5143f, 24.8545f, 39.0680f, 24.8545f, 34.2083f);
        arc.cubicTo(24.8545f, 23.3928f, 33.6222f, 14.6250f, 44.4378f, 14.6250f);
        arc.cubicTo(55.2534f, 14.6250f, 64.0212f, 23.3928f, 64.0212f, 34.2083f);
        arc.cubicTo(64.0212f, 39.0680f, 62.2510f, 43.5143f, 59.3203f, 46.9375f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4.89583f);
        canvas.drawPath(arc, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        this.colorFilter = colorFilter;
        invalidateSelf();
    }

    @Override
    public void setTintList(ColorStateList tint) {
        this.tint = tint == null ? ColorStateList.valueOf(Color.BLACK) : tint;
        invalidateSelf();
    }

    @Override
    public void setTintMode(PorterDuff.Mode tintMode) {
        // The glyph is drawn directly in the tint color.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
