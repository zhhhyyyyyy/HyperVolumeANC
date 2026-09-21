package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

final class VolumeButtonInjector {
    private static final String TAG = "HyperVolumeANC";
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";
    private static volatile boolean nativeRowAvailable;
    private static volatile int nativeExpandedExtraHeight;

    private VolumeButtonInjector() {
    }

    static void inject(Object target) {
        if (!(target instanceof ViewGroup host)) {
            Log.w(TAG, "ringer layout has unexpected type: " + target);
            return;
        }

        ViewGroup buttonLayout = asViewGroup(find(host, "miui_ringer_btn_layout"));
        ViewGroup stateLayout = asViewGroup(find(host, "miui_ringer_state_layout"));
        View dndRow = find(host, "dnd_layout");
        View nativeDivider = find(host, "miui_volume_ringer_divider");
        if (buttonLayout == null || stateLayout == null || dndRow == null
                || nativeDivider == null) {
            Log.e(TAG, "native ringer hierarchy is incomplete");
            return;
        }
        if (findNativeButton(buttonLayout) != null) {
            return;
        }

        View ancRow = null;
        View divider = null;
        try {
            Context context = host.getContext();
            int rowLayoutId = resource(context, "layout", "miui_ringer_mode_layout");
            if (rowLayoutId == 0) {
                throw new Resources.NotFoundException("miui_ringer_mode_layout");
            }

            ancRow = LayoutInflater.from(context).inflate(rowLayoutId, buttonLayout, false);
            ancRow.setId(View.generateViewId());
            divider = new View(context);
            divider.setId(View.generateViewId());
            divider.setLayoutParams(copyLayoutParams(nativeDivider.getLayoutParams()));

            int insertAt = buttonLayout.indexOfChild(dndRow) + 1;
            buttonLayout.addView(divider, insertAt);
            buttonLayout.addView(ancRow, insertAt + 1);
            setWrapContentHeight(stateLayout);

            NativeButton button = new NativeButton(
                    host, stateLayout, buttonLayout, dndRow, nativeDivider,
                    divider, ancRow, rowLayoutId);
            ancRow.setTag(button);

            AncController controller = AncController.get(context);
            button.bind(controller);
            controller.attach(button);
            host.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    controller.refresh();
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    // SystemUI reuses the same panel instance after detaching it.
                }
            });
            Log.i(TAG, "native ANC ringer row injected");
        } catch (Throwable error) {
            if (ancRow != null && ancRow.getParent() == buttonLayout) {
                buttonLayout.removeView(ancRow);
            }
            if (divider != null && divider.getParent() == buttonLayout) {
                buttonLayout.removeView(divider);
            }
            Log.e(TAG, "failed to inject native ANC ringer row", error);
        }
    }

    static void onExpanded(Object target, boolean expanded, boolean force) {
        NativeButton button = findNativeButton(target);
        if (button != null) {
            button.updateExpanded(expanded, force);
        }
    }

    static void refreshStyle(Object target) {
        NativeButton button = findNativeButton(target);
        if (button != null) {
            button.refreshStyle();
        }
    }

    static int expandedHeightExtra() {
        return nativeRowAvailable ? nativeExpandedExtraHeight : 0;
    }

    static void attachToShowHideAnimator(Object animator, View volumeView) {
        attachToShowHideAnimator(animator, volumeView, true);
    }

    private static void attachToShowHideAnimator(
            Object animator, View volumeView, boolean allowRetry) {
        NativeButton button = findNativeButton(volumeView);
        if (button == null) {
            if (allowRetry) {
                volumeView.post(() -> attachToShowHideAnimator(animator, volumeView, false));
            } else {
                Log.w(TAG, "ANC row unavailable when show/hide animator initialized");
            }
            return;
        }
        try {
            Field rowsField = animator.getClass().getDeclaredField("mRingerBtnLayouts");
            rowsField.setAccessible(true);
            View[] rows = (View[]) rowsField.get(animator);
            if (rows == null) {
                return;
            }
            for (View row : rows) {
                if (row == button.ancRow) {
                    return;
                }
            }

            View[] extendedRows = Arrays.copyOf(rows, rows.length + 1);
            extendedRows[rows.length] = button.ancRow;
            rowsField.set(animator, extendedRows);

            Field positionsField = animator.getClass().getDeclaredField("ringerBtnLayoutsX");
            positionsField.setAccessible(true);
            Float[] positions = (Float[]) positionsField.get(animator);
            Float[] extendedPositions = positions == null
                    ? new Float[extendedRows.length]
                    : Arrays.copyOf(positions, extendedRows.length);
            for (int index = 0; index < extendedPositions.length; index++) {
                if (extendedPositions[index] == null) {
                    extendedPositions[index] = 0.0f;
                }
            }
            extendedPositions[extendedPositions.length - 1] =
                    button.ancRow.getX() + volumeView.getX();
            positionsField.set(animator, extendedPositions);
            Log.i(TAG, "ANC row joined native show/hide animation sequence");
        } catch (Throwable error) {
            Log.e(TAG, "failed to join native show/hide animation", error);
        }
    }

    static void syncExpandCollapsedFrame(Object animator) {
        try {
            Field layoutField = animator.getClass().getDeclaredField("mRingerModeLayout");
            layoutField.setAccessible(true);
            NativeButton button = findNativeButton(layoutField.get(animator));
            if (button != null) {
                button.syncExpandCollapsedFrame();
            }
        } catch (Throwable error) {
            Log.e(TAG, "failed to synchronize ANC expansion frame", error);
        }
    }

    private static NativeButton findNativeButton(Object target) {
        if (!(target instanceof View root)) {
            return null;
        }
        ViewGroup layout = asViewGroup(find(root, "miui_ringer_btn_layout"));
        if (layout == null) {
            return null;
        }
        for (int index = 0; index < layout.getChildCount(); index++) {
            Object tag = layout.getChildAt(index).getTag();
            if (tag instanceof NativeButton button) {
                return button;
            }
        }
        return null;
    }

    private static View find(View root, String name) {
        int id = resource(root.getContext(), "id", name);
        return id == 0 ? null : root.findViewById(id);
    }

    private static int resource(Context context, String type, String name) {
        Resources resources = context.getResources();
        int id = resources.getIdentifier(name, type, PLUGIN_PACKAGE);
        if (id == 0) {
            id = resources.getIdentifier(name, type, context.getPackageName());
        }
        return id;
    }

    private static int dimension(Context context, String name, int fallbackDp) {
        int id = resource(context, "dimen", name);
        return id == 0 ? dp(context, fallbackDp)
                : context.getResources().getDimensionPixelSize(id);
    }

    private static int color(Context context, String name, int fallback) {
        int id = resource(context, "color", name);
        return id == 0 ? fallback : context.getColor(id);
    }

    private static ViewGroup asViewGroup(View view) {
        return view instanceof ViewGroup group ? group : null;
    }

    private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams source) {
        if (source instanceof LinearLayout.LayoutParams linear) {
            return new LinearLayout.LayoutParams(linear);
        }
        return new LinearLayout.LayoutParams(source);
    }

    private static void setWrapContentHeight(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            view.setLayoutParams(params);
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static final class NativeButton {
        private final ViewGroup host;
        private final ViewGroup stateLayout;
        private final ViewGroup buttonLayout;
        private final View dndRow;
        private final View nativeDivider;
        private final View divider;
        private final View ancRow;
        private final View ancBlur;
        private final View ancStandard;
        private final ImageView ancIcon;
        private final View timerLayout;
        private final View actionRow;
        private final View actionBlur;
        private final View actionStandard;
        private final ImageView actionIcon;
        private final TextView actionText;
        private final Object ancHelper;
        private final Object actionHelper;
        private final int actionWidth;
        private final int expandedRowHeight;
        private final int dividerHeight;
        private final int expandedPanelHeight;
        private final int shadowPaddingTop;
        private final int shadowPaddingBottom;

        private boolean expanded;
        private boolean available;
        private int mode = AncController.MODE_OFF;
        private boolean includeOffMode;

        NativeButton(
                ViewGroup host,
                ViewGroup stateLayout,
                ViewGroup buttonLayout,
                View dndRow,
                View nativeDivider,
                View divider,
                View ancRow,
                int rowLayoutId) throws Exception {
            this.host = host;
            this.stateLayout = stateLayout;
            this.buttonLayout = buttonLayout;
            this.dndRow = dndRow;
            this.nativeDivider = nativeDivider;
            this.divider = divider;
            this.ancRow = ancRow;

            Context context = host.getContext();
            this.ancBlur = required(ancRow, "bg_blur");
            this.ancStandard = required(ancRow, "miui_standard_btn");
            this.ancIcon = (ImageView) required(ancRow, "icon");
            this.timerLayout = required(ancRow, "timer_layout");
            this.ancHelper = createRingerHelper(host, ancRow, false, false);

            this.actionRow = LayoutInflater.from(context).inflate(rowLayoutId, null, false);
            this.actionRow.setId(View.generateViewId());
            this.actionBlur = required(actionRow, "bg_blur");
            this.actionStandard = required(actionRow, "miui_standard_btn");
            this.actionIcon = (ImageView) required(actionRow, "icon");
            this.actionHelper = createRingerHelper(host, actionRow, false, false);
            View nestedTimer = find(actionRow, "timer_layout");
            if (nestedTimer != null) {
                nestedTimer.setVisibility(View.GONE);
            }

            ViewGroup timer = (ViewGroup) timerLayout;
            timer.removeAllViews();
            int actionMargin = dimension(context, "miui_volume_timer_margin_left", 12);
            this.actionWidth = dimension(context, "miui_volume_timer_seekbar_width", 152);
            this.expandedRowHeight = dimension(context, "o3_miui_ringer_btn_height_expended", 56);
            this.dividerHeight = dimension(context, "o3_miui_volume_ringer_divider_height", 14);
            nativeExpandedExtraHeight = expandedRowHeight + dividerHeight;
            this.expandedPanelHeight = dimension(
                    context, "o3_miui_volume_background_height_expanded", 344);
            this.shadowPaddingTop = dimension(
                    context, "miui_volume_shadow_padding_top_expanded", 42);
            this.shadowPaddingBottom = dimension(
                    context, "miui_volume_shadow_padding_bottom_expanded", 168);
            FrameLayout.LayoutParams actionParams = new FrameLayout.LayoutParams(
                    actionWidth, expandedRowHeight);
            actionParams.setMarginStart(actionMargin);
            timer.addView(actionRow, actionParams);

            this.actionIcon.setVisibility(View.GONE);
            this.actionText = new TextView(context);
            actionText.setGravity(Gravity.CENTER);
            actionText.setText("断开连接");
            actionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            actionText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            ((ViewGroup) actionStandard).addView(actionText,
                    new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));

            ancStandard.setAccessibilityDelegate(null);
            ancBlur.setContentDescription("切换蓝牙耳机降噪和通透模式");
            actionStandard.setAccessibilityDelegate(null);
            actionBlur.setContentDescription("断开蓝牙耳机连接");
            ancRow.setVisibility(View.GONE);
            divider.setVisibility(View.GONE);
            timerLayout.setVisibility(View.GONE);
        }

        void bind(AncController controller) {
            ancBlur.setOnClickListener(view -> {
                Log.i(TAG, "ANC button click received");
                controller.toggle();
            });
            actionBlur.setOnClickListener(view -> {
                Log.i(TAG, "disconnect button click received");
                controller.disconnect();
            });
        }

        boolean isAlive() {
            return ancRow.getParent() == buttonLayout;
        }

        void render(int mode, boolean available, boolean includeOff) {
            this.mode = mode;
            this.available = available;
            this.includeOffMode = includeOff;
            nativeRowAvailable = available;
            ancRow.setVisibility(available ? View.VISIBLE : View.GONE);
            divider.setVisibility(available ? View.VISIBLE : View.GONE);
            if (available) {
                updateAncState();
            }
            timerLayout.setVisibility(available && expanded ? View.VISIBLE : View.GONE);
            host.post(this::refreshPanelGeometry);
        }

        void updateExpanded(boolean expanded, boolean force) {
            this.expanded = expanded;
            invoke(ancHelper, "onExpanded", new Class<?>[]{boolean.class, boolean.class},
                    expanded, force);
            invoke(actionHelper, "onExpanded", new Class<?>[]{boolean.class, boolean.class},
                    true, force);
            updateAncState();
            updateActionStyle();
            timerLayout.setVisibility(available && expanded ? View.VISIBLE : View.GONE);
            host.post(this::applyNativeLayout);
        }

        void refreshStyle() {
            updateExpanded(expanded, true);
        }

        private void updateAncState() {
            boolean active = mode != AncController.MODE_OFF;
            invoke(ancHelper, "setRingerMode", new Class<?>[]{boolean.class}, active);
            invoke(ancHelper, "updateState", new Class<?>[0]);
            ancIcon.setImageDrawable(new AirPodsModeDrawable(mode));
            int tint;
            if (mode == AncController.MODE_TRANSPARENCY) {
                tint = color(host.getContext(), "vp_o3_dnd_on", 0xFF7767F9);
                ancBlur.setContentDescription(includeOffMode
                        ? "通透模式，点击切换到关闭"
                        : "通透模式，点击切换降噪模式");
            } else if (mode == AncController.MODE_NOISE_CANCELLING) {
                tint = color(host.getContext(), "vp_o3_silent_on", 0xFFFF4F3F);
                ancBlur.setContentDescription("降噪模式，点击切换通透模式");
            } else {
                tint = color(host.getContext(), "vp_o3_silent_off", 0xFFFFFFFF);
                ancBlur.setContentDescription("降噪已关闭，点击开启降噪模式");
            }
            ancIcon.setImageTintList(ColorStateList.valueOf(tint));
        }

        private void syncExpandCollapsedFrame() {
            if (!available || !isAlive() || dndRow.getWidth() == 0 || dndRow.getHeight() == 0) {
                return;
            }

            int gap = nativeDivider.getHeight();
            if (gap <= 0) {
                gap = dividerHeight;
            }
            int left = dndRow.getLeft();
            int top = dndRow.getBottom() + gap;
            int right = dndRow.getRight();
            int bottom = top + dndRow.getHeight();
            divider.setLeftTopRightBottom(left, dndRow.getBottom(), right, top);
            ancRow.setLeftTopRightBottom(left, top, right, bottom);

            View sourceBlur = find(dndRow, "bg_blur");
            View sourceStandard = find(dndRow, "miui_standard_btn");
            View sourceIcon = find(dndRow, "icon");
            View sourceTimer = find(dndRow, "timer_layout");
            copyBounds(sourceBlur, ancBlur);
            copyBounds(sourceStandard, ancStandard);
            copyBounds(sourceIcon, ancIcon);
            copyAnimatedProperties(sourceTimer, timerLayout);
        }

        private void copyBounds(View source, View target) {
            if (source == null || target == null) {
                return;
            }
            target.setLeftTopRightBottom(
                    source.getLeft(), source.getTop(), source.getRight(), source.getBottom());
        }

        private void copyAnimatedProperties(View source, View target) {
            if (source == null || target == null || target.getVisibility() != View.VISIBLE) {
                return;
            }
            copyBounds(source, target);
            target.setAlpha(source.getAlpha());
            target.setPivotX(source.getPivotX());
            target.setPivotY(source.getPivotY());
            target.setScaleX(source.getScaleX());
            target.setScaleY(source.getScaleY());
        }

        private void updateActionStyle() {
            invoke(actionHelper, "setRingerMode", new Class<?>[]{boolean.class}, false);
            invoke(actionHelper, "updateState", new Class<?>[0]);
            actionIcon.setVisibility(View.GONE);
            actionText.setTextColor(color(host.getContext(), "vp_o3_silent_off", 0xFFFFFFFF));
            setSize(actionStandard, actionWidth, expandedRowHeight);
            setSize(actionBlur, actionWidth, expandedRowHeight);
            setSize(actionRow, actionWidth, expandedRowHeight);
        }

        private void applyNativeLayout() {
            if (!isAlive()) {
                return;
            }
            setWrapContentHeight(host);
            setWrapContentHeight(stateLayout);
            syncDividerSize();
            updateActionStyle();
            updatePanelHeight();
            host.requestLayout();
        }

        private void refreshPanelGeometry() {
            if (!isAlive()) {
                return;
            }
            View parent = host.getParent() instanceof View view ? view : null;
            if (parent != null) {
                try {
                    Method method = parent.getClass().getDeclaredMethod("updateVolumePanelSize");
                    method.setAccessible(true);
                    method.invoke(parent);
                } catch (NoSuchMethodException ignored) {
                    Log.w(TAG, "MiuiVolumeDialogView.updateVolumePanelSize unavailable");
                } catch (Throwable error) {
                    Log.e(TAG, "failed to refresh native volume panel geometry", error);
                }
            }
            applyNativeLayout();
        }

        private void syncDividerSize() {
            ViewGroup.LayoutParams source = nativeDivider.getLayoutParams();
            ViewGroup.LayoutParams target = divider.getLayoutParams();
            if (source != null && target != null
                    && (target.width != source.width || target.height != source.height)) {
                target.width = source.width;
                target.height = source.height;
                divider.setLayoutParams(target);
            }
        }

        private void updatePanelHeight() {
            if (!expanded) {
                return;
            }
            View root = host.getRootView();
            View background = find(root, "blur_frame");
            View shadow = find(root, "shadow");
            int extra = available ? expandedRowHeight + dividerHeight : 0;
            int backgroundHeight = expandedPanelHeight + extra;
            setHeight(background, backgroundHeight);
            setHeight(shadow, backgroundHeight + shadowPaddingTop + shadowPaddingBottom);
        }

        private View required(View root, String name) throws Resources.NotFoundException {
            View result = find(root, name);
            if (result == null) {
                throw new Resources.NotFoundException(name);
            }
            return result;
        }

        private Object createRingerHelper(
                Object outer, View row, boolean isZen, boolean state) throws Exception {
            Class<?> helperClass = null;
            for (Class<?> candidate : outer.getClass().getDeclaredClasses()) {
                if ("RingerButtonHelper".equals(candidate.getSimpleName())) {
                    helperClass = candidate;
                    break;
                }
            }
            if (helperClass == null) {
                throw new ClassNotFoundException(outer.getClass().getName()
                        + "$RingerButtonHelper");
            }
            Constructor<?> constructor = helperClass.getDeclaredConstructor(
                    outer.getClass(), View.class, boolean.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(outer, row, isZen, state);
        }

        private void invoke(Object target, String name, Class<?>[] parameterTypes,
                Object... arguments) {
            try {
                Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                method.invoke(target, arguments);
            } catch (Throwable error) {
                Log.e(TAG, "native helper call failed: " + name, error);
            }
        }

        private void setSize(View view, int width, int height) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null && (params.width != width || params.height != height)) {
                params.width = width;
                params.height = height;
                view.setLayoutParams(params);
            }
        }

        private void setHeight(View view, int height) {
            if (view == null) {
                return;
            }
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null && params.height != height) {
                params.height = height;
                view.setLayoutParams(params);
            }
        }
    }
}
