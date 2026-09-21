package io.github.zhhhyyyyyy.hypervolumeanc;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Animated background used by the about page and the onboarding, following the
 * approach HyperCeiler uses for its about page: a small AGSL shader driven by an
 * animation clock. If runtime shaders are unavailable the view falls back to a
 * Canvas drawn mesh gradient with the same palette.
 */
final class GradientBackgroundView extends View {
    private static final long CYCLE_MS = 24_000L;

    private static final String SHADER = """
            uniform float2 uResolution;
            uniform float uAnimTime;
            uniform float4 uColors[3];
            uniform float4 uBase;
            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / uResolution;
                float t = uAnimTime;
                float2 p0 = float2(0.20 + 0.16 * sin(t * 0.70), 0.26 + 0.14 * cos(t * 0.90));
                float2 p1 = float2(0.82 - 0.15 * sin(t * 0.55 + 1.2), 0.30 + 0.16 * cos(t * 0.75));
                float2 p2 = float2(0.52 + 0.20 * cos(t * 0.45 + 2.0), 0.80 + 0.14 * sin(t * 0.65));
                float d0 = smoothstep(0.72, 0.04, distance(uv, p0));
                float d1 = smoothstep(0.72, 0.04, distance(uv, p1));
                float d2 = smoothstep(0.72, 0.04, distance(uv, p2));
                float3 color = uBase.rgb;
                color = mix(color, uColors[0].rgb, d0 * uColors[0].a);
                color = mix(color, uColors[1].rgb, d1 * uColors[1].a);
                color = mix(color, uColors[2].rgb, d2 * uColors[2].a);
                return half4(half3(color), 1.0);
            }
            """;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] blobColors;
    private final float blobAlpha;
    private RuntimeShader shader;
    private float phase;
    private ValueAnimator animator;
    private boolean shaderReady;

    GradientBackgroundView(Context context) {
        super(context);
        boolean night = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (night) {
            blobColors = new int[]{
                    Color.parseColor("#663FA6A0"),
                    Color.parseColor("#665B7BD8"),
                    Color.parseColor("#667A62C8"),
            };
            blobAlpha = 0.9f;
        } else {
            blobColors = new int[]{
                    Color.parseColor("#807FD1C8"),
                    Color.parseColor("#8099B4F0"),
                    Color.parseColor("#80C0A3EC"),
            };
            blobAlpha = 0.9f;
        }
        try {
            shader = new RuntimeShader(SHADER);
            float[] colors = new float[blobColors.length * 4];
            for (int index = 0; index < blobColors.length; index++) {
                int color = blobColors[index];
                colors[index * 4] = Color.red(color) / 255f;
                colors[index * 4 + 1] = Color.green(color) / 255f;
                colors[index * 4 + 2] = Color.blue(color) / 255f;
                colors[index * 4 + 3] = Color.alpha(color) / 255f;
            }
            shader.setFloatUniform("uColors", colors);
            int base = context.getColor(R.color.gradient_start);
            shader.setFloatUniform("uBase",
                    Color.red(base) / 255f, Color.green(base) / 255f,
                    Color.blue(base) / 255f, 1f);
            shaderReady = true;
        } catch (Throwable ignored) {
            shaderReady = false;
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (shaderReady) {
            shader.setFloatUniform("uResolution", Math.max(1, width), Math.max(1, height));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(CYCLE_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            phase = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (shaderReady) {
            shader.setFloatUniform("uAnimTime", phase * 2f * (float) Math.PI);
            paint.setShader(shader);
            canvas.drawRect(0, 0, width, height, paint);
            return;
        }
        drawFallback(canvas, width, height);
    }

    /** Canvas mesh gradient used when runtime shaders are not available. */
    private void drawFallback(Canvas canvas, int width, int height) {
        double angle = phase * 2 * Math.PI;
        paint.setShader(new LinearGradient(0, 0, width, height,
                getContext().getColor(R.color.gradient_start),
                getContext().getColor(R.color.gradient_end),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);

        float radius = Math.max(width, height) * 0.62f;
        drawBlob(canvas, width, height, radius, blobColors[0],
                (float) (0.20 + 0.16 * Math.sin(angle)),
                (float) (0.26 + 0.14 * Math.cos(angle)));
        drawBlob(canvas, width, height, radius, blobColors[1],
                (float) (0.82 - 0.15 * Math.sin(angle * 0.8)),
                (float) (0.30 + 0.16 * Math.cos(angle * 1.2)));
        drawBlob(canvas, width, height, radius, blobColors[2],
                (float) (0.52 + 0.20 * Math.cos(angle * 0.6)),
                (float) (0.80 + 0.14 * Math.sin(angle * 0.9)));
    }

    private void drawBlob(Canvas canvas, int width, int height, float radius, int color,
                          float centerXRatio, float centerYRatio) {
        blobPaint.setAlpha(Math.round(255 * blobAlpha));
        blobPaint.setShader(new RadialGradient(width * centerXRatio, height * centerYRatio,
                radius, new int[]{color, Color.TRANSPARENT},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, blobPaint);
    }
}
