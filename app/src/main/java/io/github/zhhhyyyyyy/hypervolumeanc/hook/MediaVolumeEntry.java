package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 音量条上方的「分应用音量」入口按钮。
 *
 * 设计要点（与 AppVolumeBarHook 的行为对齐，但改到音量条上方）：
 * 1. 只有真的有应用在播放媒体时才出现，没有满足条件时不占用音量条的空间；
 * 2. 位于音量条（滑块）上方、面板顶部之外的空隙里：面板的 topMargin 随之上移一行，
 *    因此滑块、静音与勿扰按钮的绝对位置完全不变，横屏时也不会把按钮挤出屏幕；
 * 3. 与降噪按钮一样复用原生 miui_ringer_mode_layout + RingerButtonHelper，
 *    外观、毛玻璃、按压反馈都是原生的；
 * 4. 展开二级菜单时隐藏（与上游一致，入口是折叠态快捷入口），
 *    展开期间仍跟随面板的缩放动画做同样的位移补偿。
 */
final class MediaVolumeEntry {
    private static final String TAG = "HyperVolumeANC";
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";
    private static final String MISOUND_PACKAGE = "com.miui.misound";

    /** 自己注入的入口按钮 tag。 */
    private static final String ENTRY_TAG = "hypervolumeanc:app-volume-entry";
    /** 上游 AppVolumeBarHook 注入按钮的 tag：它还在时不再叠加第二个入口。 */
    private static final String UPSTREAM_ENTRY_TAG = "tag_app_volume_entry_root";

    static final String ACTION_EXPAND_MEDIA_VOLUME =
            "io.github.zhhhyyyyyy.hypervolumeanc.action.EXPAND_MEDIA_VOLUME";

    private static final int FALLBACK_BUTTON_WIDTH_DP = 58;
    private static final int FALLBACK_BUTTON_HEIGHT_DP = 40;
    private static final int FALLBACK_GAP_DP = 10;
    /** onFinishInflate 时视图还没挂到父节点上，等父节点就绪再注入；最多等这么多轮。 */
    private static final int MAX_INJECT_ATTEMPTS = 8;

    private static WeakReference<ViewGroup> entryRow = new WeakReference<>(null);
    private static WeakReference<Object> entryHelper = new WeakReference<>(null);
    private static WeakReference<ViewGroup> dialogView = new WeakReference<>(null);
    private static WeakReference<View> dndRow = new WeakReference<>(null);
    private static WeakReference<View> volumeContainer = new WeakReference<>(null);
    private static WeakReference<Object> panelController = new WeakReference<>(null);

    /** 本次展示中入口是否占用了音量条上方的空间（同时决定面板 topMargin 的补偿）。 */
    private static volatile boolean entryShown;
    private static volatile boolean panelExpanded;
    private static volatile boolean panelVisible;
    private static volatile float slideRingerY;
    private static volatile float slideDndY;
    /** 自愈式刷新节流：动画每帧只允许按固定间隔重算一次可见性。 */
    private static final long TICK_INTERVAL_MS = 250L;
    private static volatile long lastTickAt;

    private MediaVolumeEntry() {
    }

    // ---------------------------------------------------------------- 注入

    /** 原生音量面板 inflate 完成后调用，把入口按钮插到音量条上方。 */
    static void inject(View ringerLayout) {
        if (!(ringerLayout instanceof ViewGroup host)) {
            return;
        }
        // MiuiRingerModeLayout#onFinishInflate 触发时它还没有父节点（LayoutInflater 之后才
        // 把它加进音量对话框），这里等父节点出现再注入。
        injectWhenAttached(host, 0);
    }

    private static void injectWhenAttached(ViewGroup host, int attempt) {
        if (attempt > MAX_INJECT_ATTEMPTS) {
            HookLog.w("volume dialog view never appeared for the media volume entry");
            return;
        }
        host.post(() -> {
            if (host.getParent() instanceof ViewGroup dialog) {
                injectInto(host, dialog);
            } else {
                injectWhenAttached(host, attempt + 1);
            }
        });
    }

    private static void injectInto(ViewGroup host, ViewGroup dialog) {
        try {
            if (dialog.findViewWithTag(ENTRY_TAG) != null) {
                return;
            }
            if (dialog.findViewWithTag(UPSTREAM_ENTRY_TAG) != null) {
                HookLog.i("AppVolumeBarHook entry detected; keeping the upstream button only");
                return;
            }
            // 入口是给按音量键弹出的普通音量面板用的；控制中心里的音量面板保持原生外观。
            Object needShowDialog = readField(host, "mNeedShowDialog");
            if (needShowDialog instanceof Boolean showDialog && !showDialog) {
                HookLog.i("control center volume panel detected; skipping the app volume entry");
                return;
            }

            Context context = dialog.getContext();
            int layoutId = resource(context, "layout", "miui_ringer_mode_layout");
            if (layoutId == 0) {
                HookLog.e("miui_ringer_mode_layout is unavailable");
                return;
            }

            ViewGroup row = (ViewGroup) LayoutInflater.from(context).inflate(layoutId, dialog, false);
            row.setTag(ENTRY_TAG);
            View blur = required(row, "bg_blur");
            View standard = required(row, "miui_standard_btn");
            View iconView = required(row, "icon");
            if (blur == null || standard == null || !(iconView instanceof ImageView icon)) {
                HookLog.e("official ringer template is incomplete");
                return;
            }
            View timer = find(row, "timer_layout");
            if (timer != null) {
                timer.setVisibility(View.GONE);
            }

            // 原生按钮的毛玻璃底、材质、按压动画都由 RingerButtonHelper 负责，
            // 与静音 / 勿扰 / 降噪按钮使用同一套实例样式。
            Object helper = createRingerHelper(host, row, false, false);
            entryHelper = new WeakReference<>(helper);
            applyEntryStyle(helper, icon);
            blur.setContentDescription("分应用音量，点击打开应用独立音量面板");
            blur.setOnClickListener(MediaVolumeEntry::onEntryClick);
            blur.setClickable(true);

            Context entryContext = context;
            int rowHeight = buttonHeight(entryContext) + gap(entryContext);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight);
            row.setVisibility(View.GONE);
            dialog.addView(row, 0, params);

            entryRow = new WeakReference<>(row);
            dialogView = new WeakReference<>(dialog);
            dndRow = new WeakReference<>(find(host, "dnd_layout"));
            volumeContainer = new WeakReference<>(find(dialog, "volume_dialog_container"));
            MediaPlaybackWatcher.listen(context, MediaVolumeEntry::refreshVisibility);
            HookLog.i("media volume entry injected above the volume bar host="
                    + host.getClass().getSimpleName() + " dialog=" + dialog.getClass().getSimpleName()
                    + " row=" + row.getLayoutParams().width + "x" + row.getLayoutParams().height
                    + " standardBackground=" + (standard.getBackground() != null));
            HookDiagnostics.ok("sysui_entry", "分应用音量入口行",
                    "已注入 · 背景=" + (standard.getBackground() != null)
                            + " · 行高=" + row.getLayoutParams().height + "px");
            HookDiagnostics.hit("sysui_entry_state", "入口显示判定", "等待判定");
        } catch (Throwable error) {
            HookLog.e("failed to inject the media volume entry", error);
            HookDiagnostics.failed("sysui_entry", "分应用音量入口行", error);
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /** 音量条即将展示：在原生套用折叠态布局之前决定入口是否出现。 */
    static void prepareShow(View dialog) {
        ViewGroup group = dialog instanceof ViewGroup candidate ? candidate : dialogView.get();
        if (group == null) {
            return;
        }
        if (dialogView.get() != group) {
            // 展示的是另一个音量面板（控制中心面板或重建后的面板），把入口搬过去。
            adoptPanel(group);
        }
        Object needShowDialog = readField(group, "mNeedShowDialog");
        if (needShowDialog instanceof Boolean need && !need) {
            // 控制中心里的音量面板保持原生外观：不显示入口、也不做位移补偿。
            panelVisible = false;
            panelExpanded = true;
            setEntryShown(group, false, true);
            return;
        }
        panelVisible = true;
        panelExpanded = isExpanded(group);
        applyVisibility(group, true);
    }

    /** 在给定音量面板里找到原生 ringer 布局并按需注入入口（幂等）。 */
    private static void adoptPanel(ViewGroup dialog) {
        View host = find(dialog, "miui_volume_ringer_layout");
        if (host instanceof ViewGroup ringer) {
            injectInto(ringer, dialog);
        }
    }

    /** 二级菜单展开 / 收起后同步入口。 */
    static void onExpandedChanged(View dialog, boolean expanded) {
        if (!(dialog instanceof ViewGroup group)) {
            return;
        }
        panelExpanded = expanded;
        // 展开时原生刚写入的是「展开态」的 topMargin，本身不含补偿，不能再加减一次；
        // 收起时原生写入的是不含补偿的折叠态 topMargin，需要我们自己补上。
        applyVisibility(group, !expanded);
    }

    /**
     * 音量条开始收起：带动画时不立刻复位，入口要跟着面板一起滑出屏外，
     * 收起动画结束后再复位（否则面板滑出途中会因为 topMargin 变化跳一下）。
     */
    static void onDismissStarted(View dialog, boolean withAnimation) {
        if (!withAnimation) {
            onHideComplete();
        }
    }

    /** 收起动画结束：复位入口与面板 topMargin，下一次展示重新判断条件。 */
    static void onHideComplete() {
        ViewGroup group = dialogView.get();
        if (group != null) {
            setEntryShown(group, false, true);
        }
        panelVisible = false;
        resetAnimation();
    }

    /** 设置里的开关变化后重新判断。 */
    static void refreshVisibility() {
        ViewGroup group = dialogView.get();
        // 面板没显示时不做改动：这里只影响可见状态，避免把收起状态的布局算乱。
        if (group != null && panelVisible) {
            applyVisibility(group, true);
        }
    }

    /**
     * 自愈式刷新：官方动画每帧都会回调这里，只要面板在动就按固定间隔重算一次可见性。
     * 这样即使某个生命周期钩子在当前 ROM 上没命中，入口依然会在下一次音量面板动画时出现。
     */
    static void tick() {
        ViewGroup group = dialogView.get();
        if (group == null || !group.isAttachedToWindow()) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTickAt < TICK_INTERVAL_MS) {
            return;
        }
        lastTickAt = now;
        applyVisibility(group, true);
    }

    /**
     * 触底 / 拖拽拉伸时官方按等差序列驱动静音与勿扰行，入口位于静音上方一行，
     * 因此位移是静音位移再外推一步。
     */
    static void onSlideRinger(float ringerY) {
        slideRingerY = ringerY;
        applySlideTranslation();
    }

    static void onSlideDnd(float dndY) {
        slideDndY = dndY;
        applySlideTranslation();
    }

    private static void applySlideTranslation() {
        ViewGroup entry = entryRow.get();
        if (entry != null && entry.getVisibility() == View.VISIBLE) {
            entry.setTranslationY(2f * slideRingerY - slideDndY);
        }
        VolumeButtonInjector.setExtraRowTranslationY(2f * slideDndY - slideRingerY);
    }

    static void onSlideReset() {
        slideRingerY = 0f;
        slideDndY = 0f;
        ViewGroup entry = entryRow.get();
        if (entry != null) {
            entry.setTranslationY(0f);
        }
        VolumeButtonInjector.setExtraRowTranslationY(0f);
    }

    /**
     * 音量面板 topMargin 的补偿量：入口占用音量条上方一行时，面板整体上移，
     * 让滑块保持在原位。原生每次套用折叠态布局都会经过这里，
     * 因此横竖屏切换、展开收起后都保持一致。
     */
    static int marginOffset(Context context, boolean needShowDialog, boolean expanded) {
        if (!entryShown || expanded || !needShowDialog) {
            return 0;
        }
        return buttonHeight(context) + gap(context);
    }

    static void setPanelController(Object controller) {
        panelController = new WeakReference<>(controller);
    }

    /**
     * 应用实例按钮的材质与毛玻璃底。
     *
     * 注意：RingerButtonHelper.updateState() 在「展开态与开关状态都没变」时会直接 return，
     * 而毛玻璃底（bg_blur 的模糊注册）与前景材质都只在那里面应用。复制出来的按钮初始
     * 状态与官方按钮一致，直接调用会被这个提前返回挡掉，按钮就没有背景。
     * 这里用一次状态来回切换强制它真正走一遍材质应用，最后停在「未激活」的外观上。
     */
    private static void applyEntryStyle(Object helper, ImageView icon) {
        invoke(helper, "setRingerMode", new Class<?>[]{boolean.class}, true);
        invoke(helper, "updateState", new Class<?>[0]);
        invoke(helper, "setRingerMode", new Class<?>[]{boolean.class}, false);
        invoke(helper, "updateState", new Class<?>[0]);
        // 折叠态尺寸与毛玻璃底；onExpanded 不会覆盖前景材质。
        invoke(helper, "onExpanded",
                new Class<?>[]{boolean.class, boolean.class}, false, true);
        if (icon != null) {
            icon.setImageDrawable(new EqualizerDrawable());
            icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
            icon.setVisibility(View.VISIBLE);
        }
    }

    /** 材质模式变化 / 资源刷新时重新套一遍样式（updateState 会连带把图标换成官方图标，这里再换回来）。 */
    static void refreshStyle() {
        ViewGroup row = entryRow.get();
        Object helper = entryHelper.get();
        if (row == null || helper == null) {
            return;
        }
        View iconView = find(row, "icon");
        applyEntryStyle(helper, iconView instanceof ImageView icon ? icon : null);
        View standard = find(row, "miui_standard_btn");
        HookLog.i("media volume entry style refreshed standardBackground="
                + (standard != null && standard.getBackground() != null));
    }

    // ---------------------------------------------------------------- 动画

    /**
     * 官方 show/hide 动画每帧驱动音量柱与静音/勿扰行；入口跟着勿扰行做同样的
     * 缩放与纵向位移补偿，因此看起来和原生按钮在同一个整体里缩放。
     */
    static void syncAnimation() {
        ViewGroup entry = entryRow.get();
        if (entry == null || entry.getVisibility() != View.VISIBLE) {
            return;
        }
        View dnd = dndRow.get();
        float scale = dnd != null ? dnd.getScaleX() : 1f;
        entry.setScaleX(scale);
        entry.setScaleY(scale);
        if (dnd != null) {
            entry.setAlpha(dnd.getAlpha());
        }
        View container = volumeContainer.get();
        if (container != null) {
            float entryCenter = entry.getTop() + entry.getHeight() / 2f;
            float containerCenter = container.getTop() + container.getHeight() / 2f;
            entry.setTranslationY((scale - 1f) * (entryCenter - containerCenter));
        }
    }

    private static void resetAnimation() {
        ViewGroup entry = entryRow.get();
        if (entry == null) {
            return;
        }
        entry.setScaleX(1f);
        entry.setScaleY(1f);
        entry.setAlpha(1f);
        entry.setTranslationY(0f);
    }

    // ---------------------------------------------------------------- 内部实现

    private static void applyVisibility(ViewGroup dialog, boolean allowMarginFix) {
        boolean enabled = HyperVolumeAncSettings.appVolumeEntryEnabled();
        boolean expanded = panelExpanded;
        boolean playing = MediaPlaybackWatcher.hasActiveMediaPlayback(dialog.getContext());
        boolean visible = enabled && !expanded && playing;
        HookLog.i("entry visibility option=" + enabled + " expanded=" + expanded
                + " playing=" + playing + " -> " + (visible ? "VISIBLE" : "GONE"));
        HookDiagnostics.hit("sysui_entry_state", "入口显示判定",
                "开关=" + (enabled ? "开" : "关")
                        + " · 二级菜单=" + (expanded ? "展开" : "收起")
                        + " · 有应用在播放=" + (playing ? "是" : "否")
                        + " → " + (visible ? "显示" : "隐藏"));
        setEntryShown(dialog, visible, allowMarginFix);
    }

    private static void setEntryShown(ViewGroup dialog, boolean visible, boolean adjustMargin) {
        ViewGroup entry = entryRow.get();
        if (entry == null || dialogView.get() != dialog) {
            return;
        }
        boolean changed = entryShown != visible;
        entryShown = visible;
        if (entry.getVisibility() != (visible ? View.VISIBLE : View.GONE)) {
            entry.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (changed) {
            HookLog.i("entry " + (visible ? "shown" : "hidden")
                    + " marginFix=" + adjustMargin + " visibility=" + entry.getVisibility());
        }
        if (visible) {
            syncAnimation();
        } else {
            resetAnimation();
        }
        if (changed && adjustMargin) {
            shiftPanel(dialog, visible ? -1 : 1);
        }
    }

    private static void shiftPanel(ViewGroup dialog, int direction) {
        ViewGroup.LayoutParams params = dialog.getLayoutParams();
        if (!(params instanceof ViewGroup.MarginLayoutParams margins)) {
            return;
        }
        int shift = buttonHeight(dialog.getContext()) + gap(dialog.getContext());
        int target = margins.topMargin + direction * shift;
        if (target < 0) {
            HookLog.w("skip panel margin shift, topMargin would become " + target);
            return;
        }
        margins.topMargin = target;
        dialog.setLayoutParams(margins);
        HookLog.i("panel topMargin " + (direction < 0 ? "reduced" : "restored")
                + " by " + shift + " -> " + target);
    }

    private static boolean isExpanded(View dialog) {
        try {
            Method method = dialog.getClass().getMethod("isExpanded");
            Object value = method.invoke(dialog);
            if (value instanceof Boolean expanded) {
                return expanded;
            }
        } catch (Throwable ignored) {
            // 部分版本的内置音量视图没有 isExpanded()，退回记录值。
        }
        return panelExpanded;
    }

    private static void onEntryClick(View view) {
        Context context = view.getContext();
        HookLog.i("media volume entry clicked");
        HookDiagnostics.hit("sysui_entry_click", "入口点击", "已请求小米声音展开分应用音量面板");
        try {
            Intent intent = new Intent(ACTION_EXPAND_MEDIA_VOLUME)
                    .setPackage(MISOUND_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            HookLog.e("failed to ask MiSound to expand the app volume panel", error);
        }
        dismissVolumeDialog();
    }

    /** 先收起 SystemUI 自己的音量条，随后由小米声音弹出分应用音量面板。 */
    private static void dismissVolumeDialog() {
        Object controller = panelController.get();
        if (controller != null) {
            try {
                Method dismiss = controller.getClass().getDeclaredMethod("dismissH", int.class);
                dismiss.setAccessible(true);
                dismiss.invoke(controller, 8);
                return;
            } catch (Throwable error) {
                HookLog.w("failed to dismiss the volume dialog through its controller", error);
            }
        }
        View dialog = dialogView.get();
        if (dialog != null) {
            try {
                Method dismiss = dialog.getClass().getDeclaredMethod(
                        "dismissH", boolean.class, Runnable.class);
                dismiss.setAccessible(true);
                dismiss.invoke(dialog, true, null);
                return;
            } catch (Throwable error) {
                HookLog.w("failed to dismiss the volume dialog view", error);
            }
        }
    }

    private static Object createRingerHelper(
            ViewGroup host, ViewGroup row, boolean isZen, boolean state) throws Exception {
        Class<?> helperClass = null;
        for (Class<?> candidate : host.getClass().getDeclaredClasses()) {
            if ("RingerButtonHelper".equals(candidate.getSimpleName())) {
                helperClass = candidate;
                break;
            }
        }
        if (helperClass == null) {
            throw new ClassNotFoundException(host.getClass().getName() + "$RingerButtonHelper");
        }
        Constructor<?> constructor = helperClass.getDeclaredConstructor(
                host.getClass(), View.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(host, row, isZen, state);
    }

    private static void invoke(Object target, String name, Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            method.invoke(target, arguments);
        } catch (Throwable error) {
            HookLog.e("native ringer helper call failed: " + name, error);
        }
    }

    private static View find(View root, String name) {
        if (root == null) {
            return null;
        }
        int id = resource(root.getContext(), "id", name);
        return id == 0 ? null : root.findViewById(id);
    }

    private static View required(View root, String name) {
        return find(root, name);
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                // 继续在父类里找。
            }
        }
        return null;
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
        return id == 0
                ? Math.round(fallbackDp * context.getResources().getDisplayMetrics().density)
                : context.getResources().getDimensionPixelSize(id);
    }

    /** 实例按钮本身的宽度：折叠态与静音 / 勿扰一致（58dp）。 */
    private static int buttonWidth(Context context) {
        return dimension(context, "o3_miui_ringer_btn_width", FALLBACK_BUTTON_WIDTH_DP);
    }

    /** 实例按钮本身的高度：折叠态 40dp。 */
    private static int buttonHeight(Context context) {
        return dimension(context, "o3_miui_ringer_btn_height", FALLBACK_BUTTON_HEIGHT_DP);
    }

    /** 与原生两个按钮之间的间距一致（折叠态用 miui_volume_footer_margin_top）。 */
    private static int gap(Context context) {
        return dimension(context, "miui_volume_footer_margin_top", FALLBACK_GAP_DP);
    }

    /**
     * 分应用音量入口图标：三轨均衡器，与官方「应用独立音量」图标一致。
     * 纯代码绘制，不依赖插件资源，保证任何版本都能显示。
     */
    private static final class EqualizerDrawable extends Drawable {
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ColorStateList tintList;

        EqualizerDrawable() {
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setStrokeCap(Paint.Cap.ROUND);
            linePaint.setColor(Color.WHITE);
            knobPaint.setStyle(Paint.Style.FILL);
            knobPaint.setColor(Color.WHITE);
        }

        @Override
        public void draw(Canvas canvas) {
            RectF bounds = new RectF(getBounds());
            float width = bounds.width();
            float height = bounds.height();
            if (width <= 0f || height <= 0f) {
                return;
            }
            int color = tintList != null
                    ? tintList.getColorForState(getState(), Color.WHITE)
                    : Color.WHITE;
            linePaint.setColor(color);
            knobPaint.setColor(color);

            float scaleX = width / 24f;
            float scaleY = height / 24f;
            linePaint.setStrokeWidth(2f * scaleX);

            drawTrack(canvas, bounds, scaleX, scaleY, 5.5f, 13f);
            drawTrack(canvas, bounds, scaleX, scaleY, 12f, 8f);
            drawTrack(canvas, bounds, scaleX, scaleY, 18.5f, 15f);
        }

        private void drawTrack(Canvas canvas, RectF bounds, float scaleX, float scaleY,
                float trackX, float knobY) {
            float centerX = bounds.left + trackX * scaleX;
            canvas.drawLine(centerX, bounds.top + 4.5f * scaleY,
                    centerX, bounds.top + 19.5f * scaleY, linePaint);
            float knobWidth = 4.2f * scaleX;
            float knobHeight = 5.6f * scaleY;
            float centerY = bounds.top + knobY * scaleY;
            RectF knob = new RectF(centerX - knobWidth / 2f, centerY - knobHeight / 2f,
                    centerX + knobWidth / 2f, centerY + knobHeight / 2f);
            float radius = 1.8f * scaleX;
            canvas.drawRoundRect(knob, radius, radius, knobPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            linePaint.setAlpha(alpha);
            knobPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            linePaint.setColorFilter(colorFilter);
            knobPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public void setTintList(ColorStateList tint) {
            tintList = tint;
            invalidateSelf();
        }

        @Override
        public boolean isStateful() {
            return tintList != null && tintList.isStateful();
        }

        @Override
        protected boolean onStateChange(int[] state) {
            invalidateSelf();
            return true;
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
