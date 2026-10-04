package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.app.Application;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.TranslateAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 小米声音（com.miui.misound）侧的分应用音量面板。
 *
 * 作用：
 * 1. 接收音量条入口按钮发来的展开广播。
 * 2. 隐藏小米声音自带的悬浮球（入口已经搬到音量条上方）。
 * 3. 去掉面板弹出时的全屏背景模糊与压暗，让桌面保持清晰。
 * 4. 按模块的视觉规范排布卡片：右侧垂直居中、圆角毛玻璃、滑块比例、分页指示器。
 * 5. 面板从右侧滑入 / 滑出，收起时不播放原生缩放动画。
 *
 * 所有反射调用都带兜底：任何一步失败只记录日志，绝不打断小米声音自身的流程。
 */
final class MiSoundAppVolumeHook {
    private static final String TAG = "HyperVolumeANC";
    private static final String MISOUND = "com.miui.misound";

    private static final int STATUS_IDLE = 0;
    private static final int STATUS_EXPANDED = 5000;
    private static final int STATUS_CLOSING = 301;
    private static final int CARD_TAG = 0x7f0a9999;

    private static final String TAG_CARD_INITIALIZED = "hypervolumeanc:card-initialized";

    private static WeakReference<View> capturedFloatingBall = new WeakReference<>(null);
    private static WeakReference<Context> cachedContext = new WeakReference<>(null);
    private static WeakReference<Object> cachedController = new WeakReference<>(null);
    private static volatile boolean receiverRegistered;
    private static volatile ClassLoader appClassLoader;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MiSoundAppVolumeHook() {
    }

    static void install(XposedModule module, ClassLoader classLoader) {
        appClassLoader = classLoader;
        hookApplication(module);
        hookWindowManager(module);
        hookVolumeService(module, classLoader);
        hookController(module, classLoader);
        Context context = currentApplication();
        if (context != null) {
            ensureReceiver(context);
        }
    }

    /** 部分机型的 Application#onCreate 已经执行完，这里直接补一次注册。 */
    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method current = activityThread.getDeclaredMethod("currentApplication");
            current.setAccessible(true);
            Object application = current.invoke(null);
            return application instanceof Context context ? context : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------ 生命周期

    private static void hookApplication(XposedModule module) {
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.hook(onCreate)
                    .setId("hypervolumeanc.v1:misound:application-create")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getThisObject() instanceof Context context) {
                            ensureReceiver(context);
                        }
                        return result;
                    });
        } catch (Throwable error) {
            HookLog.w("misound application hook unavailable", error);
        }
    }

    private static void hookVolumeService(XposedModule module, ClassLoader classLoader) {
        try {
            Class<?> serviceClass = Class.forName(
                    "com.miui.misound.playervolume.VolumeUIService", false, classLoader);
            for (String name : new String[]{"onCreate", "onStartCommand"}) {
                Method method = findMethod(serviceClass, name, name.equals("onCreate") ? 0 : 3);
                if (method == null) {
                    continue;
                }
                module.hook(method)
                        .setId("hypervolumeanc.v1:misound:service:" + name)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (chain.getThisObject() instanceof Service service) {
                                captureControllerFromService(service);
                                ensureReceiver(service.getApplicationContext());
                            }
                            return result;
                        });
            }
            HookLog.i("hooked MiSound volume service");
        } catch (Throwable error) {
            HookLog.w("MiSound volume service is unavailable", error);
        }
    }

    private static void captureControllerFromService(Service service) {
        cachedContext = new WeakReference<>(service.getApplicationContext());
        try {
            for (Field field : service.getClass().getDeclaredFields()) {
                if (!field.getType().getName().startsWith("com.miui.misound.playervolume.")) {
                    continue;
                }
                field.setAccessible(true);
                Object controller = field.get(service);
                if (controller != null) {
                    cachedController = new WeakReference<>(controller);
                    HookLog.i("captured MiSound controller from volume service");
                    break;
                }
            }
        } catch (Throwable error) {
            HookLog.w("failed to capture the MiSound controller", error);
        }
    }

    // ------------------------------------------------------------ 悬浮球

    private static void hookWindowManager(XposedModule module) {
        try {
            Class<?> windowManagerImpl = Class.forName(
                    "android.view.WindowManagerImpl", false, appClassLoader);
            int hooked = 0;
            for (Method method : windowManagerImpl.getDeclaredMethods()) {
                if (!"addView".equals(method.getName())) {
                    continue;
                }
                Class<?>[] types = method.getParameterTypes();
                if (types.length < 2
                        || !View.class.isAssignableFrom(types[0])
                        || !ViewGroup.LayoutParams.class.isAssignableFrom(types[1])) {
                    continue;
                }
                method.setAccessible(true);
                module.hook(method)
                        .setId("hypervolumeanc.v1:misound:window-manager-add-view:"
                                + Integer.toHexString(method.toGenericString().hashCode()))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object first = chain.getArg(0);
                            Object second = chain.getArg(1);
                            if (!(first instanceof View view)
                                    || !(second instanceof ViewGroup.LayoutParams layoutParams)) {
                                return chain.proceed();
                            }
                            String className = view.getClass().getName();
                            if (layoutParams instanceof WindowManager.LayoutParams params) {
                                HookLog.i("MiSound addView " + className + " type=" + params.type
                                        + " size=" + params.width + "x" + params.height);
                                if (isFloatingBall(view, params)) {
                                    // 入口已经移到音量条上方，悬浮球不再显示，但保留实例做点击回退。
                                    captureFloatingBall(view);
                                    return HyperVolumeAncSettings.appVolumeEntryEnabled()
                                            ? null
                                            : chain.proceed();
                                }
                                if (className.contains("MediaVolumePageView")) {
                                    params.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                                    params.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                                    params.dimAmount = 0f;
                                    trySetBlurBehindRadius(params, 0);
                                }
                            } else if (className.contains("MediaVolumePageView")) {
                                HookLog.i("MiSound page view added with "
                                        + layoutParams.getClass().getSimpleName());
                            }
                            return chain.proceed();
                        });
                hooked++;
            }
            HookLog.i("hooked " + hooked + " WindowManagerImpl.addView overload(s) in MiSound");
            HookDiagnostics.ok("misound_page", "面板窗口处理",
                    "已挂 " + hooked + " 个 addView 重载 · 展开时去掉全屏模糊与压暗");
        } catch (Throwable error) {
            HookLog.w("failed to hook WindowManagerImpl.addView in MiSound", error);
            HookDiagnostics.failed("misound_page", "面板窗口处理", error);
        }
    }

    private static void trySetBlurBehindRadius(WindowManager.LayoutParams params, int radius) {
        try {
            Method method = params.getClass().getMethod("setBlurBehindRadius", int.class);
            method.invoke(params, radius);
        } catch (Throwable ignored) {
            // 部分版本没有这个方法。
        }
    }

    /** 悬浮球与展开面板都用 TYPE_APPLICATION_OVERLAY，这里用尺寸与内部控件区分。 */
    private static boolean isFloatingBall(View view, WindowManager.LayoutParams params) {
        if (params.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
            return false;
        }
        Context context = view.getContext();
        if (context == null || !MISOUND.equals(context.getPackageName())) {
            return false;
        }
        if (params.width != ViewGroup.LayoutParams.WRAP_CONTENT
                || params.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            return false;
        }
        int closeId = context.getResources().getIdentifier(
                "miui_volume_close_button", "id", MISOUND);
        if (closeId != 0 && view.findViewById(closeId) != null) {
            return true;
        }
        return hasFloatingActionButton(view);
    }

    private static boolean hasFloatingActionButton(View view) {
        if (view.getClass().getName().contains("FloatingActionButton")) {
            return true;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                if (hasFloatingActionButton(group.getChildAt(index))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void captureFloatingBall(View view) {
        cachedContext = new WeakReference<>(view.getContext().getApplicationContext());
        View target = findFloatingActionButton(view);
        capturedFloatingBall = new WeakReference<>(target != null ? target : view);
        ensureReceiver(view.getContext());
        HookLog.i("MiSound floating ball captured and hidden: " + view.getClass().getName());
        HookDiagnostics.ok("misound_ball", "原生悬浮球", "已拦截悬浮球窗口并隐藏");
    }

    /**
     * 兜底方案：某些版本不走 WindowManagerImpl.addView，或参数特征与预期不同，
     * 那就从控制器里把悬浮球实例找出来直接隐藏（同时保留实例用于点击回退）。
     */
    private static void hideFloatingBallFromController(Object controller) {
        if (controller == null || !HyperVolumeAncSettings.appVolumeEntryEnabled()) {
            return;
        }
        View ball = capturedFloatingBall.get();
        if (ball == null) {
            for (Field field : controller.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !field.getType().getName().endsWith("FloatingActionButton")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(controller);
                    if (value instanceof View view) {
                        ball = view;
                        break;
                    }
                } catch (Throwable ignored) {
                    // 继续找下一个字段。
                }
            }
            if (ball != null) {
                capturedFloatingBall = new WeakReference<>(ball);
                HookLog.i("captured MiSound floating ball from the controller");
            }
        }
        if (ball != null && ball.getVisibility() != View.GONE) {
            ball.setVisibility(View.GONE);
            HookLog.i("hid the native MiSound floating ball (fallback path)");
            HookDiagnostics.hit("misound_ball", "原生悬浮球",
                    "已隐藏原生悬浮球（入口移到音量条上方）");
        }
    }

    private static View findFloatingActionButton(View view) {
        if (view.getClass().getName().contains("FloatingActionButton")) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findFloatingActionButton(group.getChildAt(index));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 展开广播

    private static synchronized void ensureReceiver(Context context) {
        if (receiverRegistered || context == null) {
            return;
        }
        Context application = context.getApplicationContext();
        if (application == null) {
            application = context;
        }
        cachedContext = new WeakReference<>(application);
        try {
            IntentFilter filter = new IntentFilter(MediaVolumeEntry.ACTION_EXPAND_MEDIA_VOLUME);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null
                            || !MediaVolumeEntry.ACTION_EXPAND_MEDIA_VOLUME.equals(intent.getAction())) {
                        return;
                    }
                    HookLog.i("received expand request from the volume panel entry");
                    HookDiagnostics.hit("misound_expand", "面板展开请求",
                            "收到按钮广播，准备展开面板");
                    expandMediaVolumePanel(receiverContext);
                }
            };
            application.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            receiverRegistered = true;
            HyperVolumeAncSettings.attach(application, MiSoundAppVolumeHook::onOptionsChanged);
            HookLog.i("registered app volume expand receiver");
            HookDiagnostics.ok("misound_receiver", "展开广播监听", "已注册，等待音量面板按钮触发");
        } catch (Throwable error) {
            HookLog.w("failed to register the app volume expand receiver", error);
        }
    }

    /** 入口开关变化时不需要额外动作：下一次面板展示会重新判断悬浮球与入口。 */
    private static void onOptionsChanged() {
        HookLog.i("app volume entry option changed enabled="
                + HyperVolumeAncSettings.appVolumeEntryEnabled());
    }

    private static void expandMediaVolumePanel(Context context) {
        try {
            if (tryExpandViaController(context)) {
                return;
            }
        } catch (Throwable error) {
            HookLog.w("direct expand failed, falling back to the floating ball", error);
        }
        if (!expandViaFloatingBallTap()) {
            HookLog.w("all app volume expand strategies failed");
        }
    }

    private static boolean tryExpandViaController(Context context) {
        Object controller = cachedController.get();
        if (controller == null) {
            controller = findControllerInstance(context);
            if (controller != null) {
                cachedController = new WeakReference<>(controller);
            }
        }
        if (controller == null) {
            return false;
        }
        Method show = findMethod(controller.getClass(), "y", 0);
        if (show == null) {
            // 混淆映射不一致时不做任何猜测性读写，直接走悬浮球点击回退。
            return false;
        }
        try {
            if (getStatus(controller) == STATUS_EXPANDED) {
                return true;
            }
            setStatus(controller, STATUS_IDLE);
            Method refresh = findMethod(controller.getClass(), "u", 0);
            if (refresh != null) {
                refresh.invoke(controller);
            }
            if (isEmpty(getApcList(controller))) {
                List<Object> columns = getColumnsList(controller);
                if (columns != null) {
                    columns.clear();
                }
            }
            List<Object> columns = getColumnsList(controller);
            boolean addedFallback = false;
            if (isEmpty(columns)) {
                Method addMedia = findMethod(controller.getClass(), "f", 0);
                if (addMedia != null) {
                    addMedia.invoke(controller);
                    addedFallback = true;
                }
            }
            View viewPager = getViewPager2(controller);
            if (viewPager != null) {
                Object adapter = callMethod(viewPager, "getAdapter");
                if (adapter == null || addedFallback) {
                    Method initAdapter = findMethod(controller.getClass(), "m", 0);
                    if (initAdapter != null) {
                        initAdapter.invoke(controller);
                    }
                }
                viewPager.setVisibility(View.VISIBLE);
            }
            ViewGroup pageView = getMediaVolumePageView(controller);
            if (pageView != null) {
                setupCardLayout(pageView, controller);
            }
            setStatus(controller, STATUS_IDLE);
            show.invoke(controller);
            HookLog.i("expanded app volume panel status=" + getStatus(controller));
            return true;
        } catch (Throwable error) {
            HookLog.w("failed to expand the app volume panel directly", error);
            return false;
        }
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    private static boolean expandViaFloatingBallTap() {
        View ball = capturedFloatingBall.get();
        if (ball == null) {
            return false;
        }
        Object controller = cachedController.get();
        if (controller != null && getStatus(controller) == STATUS_EXPANDED) {
            return true;
        }
        try {
            if (ball.hasOnClickListeners()) {
                ball.performClick();
            } else {
                long now = android.os.SystemClock.uptimeMillis();
                float x = Math.max(ball.getWidth() / 2f, 1f);
                float y = Math.max(ball.getHeight() / 2f, 1f);
                MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0);
                ball.dispatchTouchEvent(down);
                ball.dispatchTouchEvent(up);
                down.recycle();
                up.recycle();
            }
            HookLog.i("expanded app volume panel through the native floating ball");
            return true;
        } catch (Throwable error) {
            HookLog.w("floating ball tap failed", error);
            return false;
        }
    }

    // ------------------------------------------------------------ 面板

    private static void hookController(XposedModule module, ClassLoader classLoader) {
        Class<?> controllerClass = findControllerClass(classLoader);
        if (controllerClass == null) {
            HookLog.w("MiSound app volume controller was not found");
            HookDiagnostics.missing("misound_controller", "分应用音量面板",
                    "当前小米声音版本没有找到分应用音量控制器");
            return;
        }
        HookLog.i("hooking MiSound app volume controller " + controllerClass.getName());
        HookDiagnostics.waiting("misound_controller", "分应用音量面板", "已找到控制器，正在挂载");

        try {
            for (Constructor<?> constructor : controllerClass.getDeclaredConstructors()) {
                module.hook(constructor)
                        .setId("hypervolumeanc.v1:misound:controller-ctor:"
                                + Integer.toHexString(constructor.toGenericString().hashCode()))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            cachedController = new WeakReference<>(chain.getThisObject());
                            return result;
                        });
            }
        } catch (Throwable error) {
            HookLog.w("failed to hook the controller constructor", error);
        }

        hookControllerMethod(module, controllerClass, "n", 0, true);
        hookControllerMethod(module, controllerClass, "y", 0, false);
        hookControllerMethod(module, controllerClass, "u", 0, false);
        hookControllerDismiss(module, controllerClass);
        hookAdapter(module, controllerClass, classLoader);
        hookPageCallback(module, controllerClass, classLoader);
        hookColumns(module, controllerClass, classLoader);
    }

    private static void hookControllerMethod(
            XposedModule module, Class<?> controllerClass, String name, int params, boolean layout) {
        Method method = findMethod(controllerClass, name, params);
        if (method == null) {
            HookLog.w("controller method " + name + "() not found");
            return;
        }
        try {
            module.hook(method)
                    .setId("hypervolumeanc.v1:misound:controller-" + name)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object controller = chain.getThisObject();
                        cachedController = new WeakReference<>(controller);
                        hideFloatingBallFromController(controller);
                        if (layout) {
                            ViewGroup pageView = getMediaVolumePageView(controller);
                            if (pageView != null) {
                                setupCardLayout(pageView, controller);
                            }
                        } else if ("y".equals(name)) {
                            onPanelShown(controller);
                        } else {
                            // u()：没有活跃音源时原生不会清理旧列表，这里补齐。
                            if (isEmpty(getApcList(controller))) {
                                List<Object> columns = getColumnsList(controller);
                                if (columns != null) {
                                    columns.clear();
                                }
                            }
                        }
                        return result;
                    });
        } catch (Throwable error) {
            HookLog.w("failed to hook controller method " + name, error);
        }
    }

    private static void onPanelShown(Object controller) {
        View viewPager = getViewPager2(controller);
        if (viewPager != null) {
            viewPager.clearAnimation();
        }
        ViewGroup pageView = getMediaVolumePageView(controller);
        if (pageView == null) {
            return;
        }
        setupCardLayout(pageView, controller);
        ViewGroup card = findCardContainer(pageView);
        if (card == null) {
            return;
        }
        card.clearAnimation();
        card.startAnimation(slideAnimation(true));
    }

    private static Animation slideAnimation(boolean enter) {
        AnimationSet set = new AnimationSet(true);
        set.addAnimation(new TranslateAnimation(
                Animation.RELATIVE_TO_SELF, enter ? 1f : 0f,
                Animation.RELATIVE_TO_SELF, enter ? 0f : 1f,
                Animation.RELATIVE_TO_SELF, 0f,
                Animation.RELATIVE_TO_SELF, 0f));
        set.addAnimation(enter ? new AlphaAnimation(0f, 1f) : new AlphaAnimation(1f, 0f));
        set.setDuration(enter ? 220L : 200L);
        set.setInterpolator(enter
                ? new DecelerateInterpolator(1.8f)
                : new AccelerateInterpolator(1.8f));
        set.setFillAfter(!enter);
        return set;
    }

    private static void hookControllerDismiss(XposedModule module, Class<?> controllerClass) {
        Method dismiss = findMethod(controllerClass, "g", 0);
        if (dismiss == null) {
            HookLog.w("controller method g() not found");
            return;
        }
        try {
            module.hook(dismiss)
                    .setId("hypervolumeanc.v1:misound:controller-dismiss")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object controller = chain.getThisObject();
                        if (getStatus(controller) != STATUS_EXPANDED) {
                            return chain.proceed();
                        }
                        setStatus(controller, STATUS_CLOSING);
                        View viewPager = getViewPager2(controller);
                        if (viewPager != null) {
                            viewPager.clearAnimation();
                        }
                        ViewGroup card = findCardContainer(getMediaVolumePageView(controller));
                        if (card != null) {
                            card.clearAnimation();
                            card.startAnimation(slideAnimation(false));
                        }
                        Handler handler = getHandler(controller);
                        if (handler == null) {
                            handler = MAIN;
                        }
                        handler.postDelayed(() -> finishDismiss(controller), 200L);
                        // 原生 g() 会播放缩放动画并重启定时器，这里直接接管。
                        return null;
                    });
        } catch (Throwable error) {
            HookLog.w("failed to hook the controller dismiss method", error);
        }
    }

    private static void finishDismiss(Object controller) {
        try {
            Method cleanup = findMethod(controller.getClass(), "p", 0);
            if (cleanup != null) {
                cleanup.invoke(controller);
            }
        } catch (Throwable error) {
            HookLog.w("controller cleanup during dismiss failed", error);
        }
        try {
            ViewGroup pageView = getMediaVolumePageView(controller);
            if (pageView != null && pageView.isAttachedToWindow()) {
                WindowManager manager = getWindowManager(controller);
                if (manager == null
                        && pageView.getContext() != null) {
                    manager = (WindowManager) pageView.getContext()
                            .getSystemService(Context.WINDOW_SERVICE);
                }
                if (manager != null) {
                    manager.removeViewImmediate(pageView);
                }
            }
        } catch (Throwable error) {
            HookLog.w("removing the app volume page view failed", error);
        }
        setStatus(controller, STATUS_IDLE);
    }

    private static void hookAdapter(
            XposedModule module, Class<?> controllerClass, ClassLoader classLoader) {
        Class<?> adapterClass = null;
        for (Class<?> inner : controllerClass.getDeclaredClasses()) {
            if (isAdapterSubclass(inner)) {
                adapterClass = inner;
                break;
            }
        }
        if (adapterClass == null) {
            adapterClass = findClass(controllerClass.getName() + "$i", classLoader);
        }
        if (adapterClass == null) {
            HookLog.w("MiSound app volume adapter was not found");
            return;
        }
        try {
            for (Constructor<?> constructor : adapterClass.getDeclaredConstructors()) {
                module.hook(constructor)
                        .setId("hypervolumeanc.v1:misound:adapter-ctor:"
                                + Integer.toHexString(constructor.toGenericString().hashCode()))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            List<Object> pages = getPagesList(chain.getThisObject());
                            if (pages != null) {
                                for (Object page : pages) {
                                    if (page instanceof ViewGroup group) {
                                        adjustAllSlidersInView(group);
                                    }
                                }
                            }
                            return result;
                        });
            }
            Method onCreateViewHolder = findMethod(adapterClass, "onCreateViewHolder", 2);
            if (onCreateViewHolder != null) {
                module.hook(onCreateViewHolder)
                        .setId("hypervolumeanc.v1:misound:adapter-create")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            View itemView = getItemView(result);
                            if (itemView != null) {
                                // 原生把整页当作点击收起监听，导致点卡片空白也会关面板。
                                itemView.setOnClickListener(null);
                                itemView.setClickable(false);
                                if (itemView instanceof ViewGroup group) {
                                    group.setClipChildren(false);
                                }
                            }
                            return result;
                        });
            }
            Method onBindViewHolder = findMethod(adapterClass, "onBindViewHolder", 2);
            if (onBindViewHolder != null) {
                module.hook(onBindViewHolder)
                        .setId("hypervolumeanc.v1:misound:adapter-bind")
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            detachBoundPage(chain.getThisObject(), chain.getArg(1));
                            Object result = chain.proceed();
                            Object holder = chain.getArg(0);
                            View itemView = getItemView(holder);
                            if (itemView != null) {
                                adjustAllSlidersInView(itemView);
                            }
                            return result;
                        });
            }
            HookLog.i("hooked MiSound app volume adapter " + adapterClass.getName());
            HookDiagnostics.ok("misound_controller", "分应用音量面板",
                    "控制器 / 适配器 / 翻页回调均已挂载");
        } catch (Throwable error) {
            HookLog.w("failed to hook the app volume adapter", error);
        }
    }

    private static void hookPageCallback(
            XposedModule module, Class<?> controllerClass, ClassLoader classLoader) {
        Class<?> callbackClass = null;
        for (Class<?> inner : controllerClass.getDeclaredClasses()) {
            if (isPageCallbackSubclass(inner)) {
                callbackClass = inner;
                break;
            }
        }
        if (callbackClass == null) {
            callbackClass = findClass(controllerClass.getName() + "$e", classLoader);
        }
        if (callbackClass == null) {
            return;
        }
        Method selected = findMethod(callbackClass, "onPageSelected", 1);
        if (selected == null) {
            return;
        }
        try {
            module.hook(selected)
                    .setId("hypervolumeanc.v1:misound:page-selected")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object position = chain.getArg(0);
                        if (!(position instanceof Integer index)) {
                            return result;
                        }
                        Object controller = surroundingController(chain.getThisObject());
                        if (controller == null) {
                            return result;
                        }
                        View viewPager = getViewPager2(controller);
                        if (viewPager != null) {
                            adjustAllSlidersInView(viewPager);
                        }
                        ViewGroup indicator = getIndicatorViewGroup(controller);
                        if (indicator != null) {
                            for (int i = 0; i < indicator.getChildCount(); i++) {
                                updateIndicatorDotStyle(indicator.getChildAt(i), i == index);
                            }
                        }
                        return result;
                    });
            HookLog.i("hooked MiSound page callback " + callbackClass.getName());
        } catch (Throwable error) {
            HookLog.w("failed to hook the page callback", error);
        }
    }

    private static Object surroundingController(Object callback) {
        try {
            Field field = callback.getClass().getDeclaredField("this$0");
            field.setAccessible(true);
            return field.get(callback);
        } catch (Throwable ignored) {
            return cachedController.get();
        }
    }

    /**
     * ViewPager2 复用页面时，如果上一轮页面还挂在旧的 itemView 上，
     * 绑定会触发「child already has a parent」并中断翻页。
     */
    private static void detachBoundPage(Object adapter, Object positionArg) {
        if (!(positionArg instanceof Integer position)) {
            return;
        }
        List<Object> pages = getPagesList(adapter);
        if (pages == null || position < 0 || position >= pages.size()) {
            return;
        }
        if (pages.get(position) instanceof View page
                && page.getParent() instanceof ViewGroup parent) {
            try {
                parent.removeView(page);
            } catch (Throwable error) {
                HookLog.w("failed to detach the reused app volume page", error);
            }
        }
    }

    private static void hookColumns(
            XposedModule module, Class<?> controllerClass, ClassLoader classLoader) {
        List<Class<?>> columnClasses = findColumnClasses(controllerClass, classLoader);
        for (Class<?> columnClass : columnClasses) {
            Method init = findColumnInitMethod(columnClass);
            if (init == null) {
                continue;
            }
            try {
                module.hook(init)
                        .setId("hypervolumeanc.v1:misound:column-init:" + columnClass.getName())
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            applySliderDimensions(chain.getThisObject());
                            return result;
                        });
            } catch (Throwable error) {
                HookLog.w("failed to hook column init on " + columnClass.getName(), error);
            }
        }
        guardColumnKeyHandling(module, columnClasses);
    }

    /**
     * 音量柱按键处理会在 seekbar 尚未绑定时抛空指针（原生崩溃点），
     * 这里在“确实没有可调 UI”的情况下吞掉这次异常。
     */
    private static void guardColumnKeyHandling(XposedModule module, List<Class<?>> columnClasses) {
        Set<Method> guarded = new HashSet<>();
        for (Class<?> columnClass : columnClasses) {
            Class<?> current = columnClass;
            while (current != null && current != Object.class) {
                for (Method method : current.getDeclaredMethods()) {
                    if (!"f".equals(method.getName()) || !guarded.add(method)) {
                        continue;
                    }
                    method.setAccessible(true);
                    try {
                        module.hook(method)
                                .setId("hypervolumeanc.v1:misound:column-guard:"
                                        + Integer.toHexString(
                                                method.toGenericString().hashCode()))
                                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                .intercept(chain -> {
                                    try {
                                        return chain.proceed();
                                    } catch (Throwable error) {
                                        Throwable cause = error instanceof InvocationTargetException
                                                && error.getCause() != null
                                                ? error.getCause()
                                                : error;
                                        if (cause instanceof NullPointerException
                                                && findSeekBar(chain.getThisObject()) == null) {
                                            HookLog.i("skipped volume column key handling: "
                                                    + "seekbar not bound yet");
                                            return defaultValueOf(chain.getExecutable());
                                        }
                                        throw error;
                                    }
                                });
                    } catch (Throwable error) {
                        HookLog.w("failed to guard " + method, error);
                    }
                }
                current = current.getSuperclass();
            }
        }
    }

    private static Object defaultValueOf(java.lang.reflect.Executable executable) {
        if (!(executable instanceof Method method)) {
            return null;
        }
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return ' ';
        }
        return null;
    }

    // ------------------------------------------------------------ 视觉

    private static void setupCardLayout(ViewGroup pageView, Object controller) {
        try {
            if (pageView instanceof LinearLayout linear) {
                linear.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            }
            pageView.setClipChildren(false);
            pageView.setClipToPadding(false);

            ViewGroup card = findCardContainer(pageView);
            if (card == null) {
                return;
            }
            Context context = card.getContext();
            float density = context.getResources().getDisplayMetrics().density;
            int marginEnd = (int) (16 * density);
            int padding = (int) (16 * density);
            int columnGap = columnGapPx(context);
            int halfGap = columnGap / 2;
            int paddingHorizontal = Math.max(padding - halfGap, 0);
            float cornerRadius = 28f * density;

            View viewPager = findViewPager2(card);
            if (viewPager != null) {
                viewPager.setVisibility(View.VISIBLE);
                Object source = controller != null ? controller : cachedController.get();
                List<Object> columns = source != null ? getColumnsList(source) : null;
                int sliderCount = columns != null ? columns.size() : 1;
                int visible = Math.min(Math.max(sliderCount, 1), 3);
                int width = calculateViewPagerWidth(context, visible);
                int height = sliderHeightPx(context);

                ViewGroup.LayoutParams params = viewPager.getLayoutParams();
                if (params != null) {
                    params.width = width;
                    params.height = height;
                    if (params instanceof ViewGroup.MarginLayoutParams margins) {
                        margins.topMargin = 0;
                        margins.bottomMargin = 0;
                    }
                    viewPager.setLayoutParams(params);
                } else {
                    LinearLayout.LayoutParams fresh = new LinearLayout.LayoutParams(width, height);
                    fresh.topMargin = 0;
                    fresh.bottomMargin = 0;
                    viewPager.setLayoutParams(fresh);
                }
                viewPager.setMinimumHeight(height);

                ViewGroup indicator = null;
                if (source != null) {
                    indicator = getIndicatorViewGroup(source);
                }
                if (indicator == null) {
                    int indicatorId = context.getResources().getIdentifier(
                            "volume_indicator", "id", context.getPackageName());
                    if (indicatorId != 0) {
                        View found = card.findViewById(indicatorId);
                        indicator = found instanceof ViewGroup group ? group : null;
                    }
                }
                List<Object> dots = source != null ? getDotsList(source) : null;
                int totalPages = dots != null ? dots.size() : (sliderCount > 3 ? 2 : 1);
                if (indicator != null && indicator != viewPager
                        && !isInside(indicator, viewPager)) {
                    if (totalPages <= 1) {
                        indicator.setVisibility(View.GONE);
                    } else {
                        indicator.setVisibility(View.VISIBLE);
                        ViewGroup.LayoutParams indicatorParams = indicator.getLayoutParams();
                        if (indicatorParams instanceof ViewGroup.MarginLayoutParams margins) {
                            margins.topMargin = (int) (8 * density);
                            margins.bottomMargin = 0;
                            indicator.setLayoutParams(margins);
                        }
                        int currentItem = 0;
                        Object value = callMethod(viewPager, "getCurrentItem");
                        if (value instanceof Integer item) {
                            currentItem = item;
                        }
                        if (indicator.getChildCount() == 0 && dots != null) {
                            for (int i = 0; i < dots.size(); i++) {
                                if (!(dots.get(i) instanceof View dot)) {
                                    continue;
                                }
                                if (dot.getParent() instanceof ViewGroup parent) {
                                    parent.removeView(dot);
                                }
                                updateIndicatorDotStyle(dot, i == currentItem);
                                indicator.addView(dot);
                            }
                        } else {
                            for (int i = 0; i < indicator.getChildCount(); i++) {
                                updateIndicatorDotStyle(
                                        indicator.getChildAt(i), i == currentItem);
                            }
                        }
                    }
                }
                adjustAllSlidersInView(viewPager);
            }

            LinearLayout.LayoutParams cardParams = card.getLayoutParams()
                    instanceof LinearLayout.LayoutParams existing
                    ? existing
                    : new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            cardParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            cardParams.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            cardParams.setMarginEnd(marginEnd);
            card.setLayoutParams(cardParams);
            card.setPadding(paddingHorizontal, padding, paddingHorizontal, padding);
            card.setClickable(true);
            card.setFocusable(true);
            card.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerRadius);
                }
            });
            card.setClipToOutline(true);
            card.setElevation(16f * density);

            if (card.getTag(CARD_TAG) == null) {
                card.setTag(CARD_TAG, TAG_CARD_INITIALIZED);
                card.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View view) {
                        applyBackdropBlur(view, cornerRadius);
                    }

                    @Override
                    public void onViewDetachedFromWindow(View view) {
                    }
                });
            }
            if (card.isAttachedToWindow()) {
                applyBackdropBlur(card, cornerRadius);
            }
        } catch (Throwable error) {
            HookLog.w("failed to lay out the app volume card", error);
        }
    }

    private static boolean isInside(View view, View ancestor) {
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View current) {
            if (current == ancestor) {
                return true;
            }
            parent = current.getParent();
        }
        return false;
    }

    private static void applySliderDimensions(Object column) {
        try {
            View seekBar = findSeekBar(column);
            if (seekBar == null) {
                return;
            }
            Context context = seekBar.getContext();
            int height = sliderHeightPx(context);
            int width = sliderWidthPx(context);
            int halfGap = columnGapPx(context) / 2;
            ViewGroup.LayoutParams params = seekBar.getLayoutParams();
            if (params != null) {
                params.height = height;
                params.width = width;
                if (params instanceof ViewGroup.MarginLayoutParams margins) {
                    margins.setMarginStart(halfGap);
                    margins.setMarginEnd(halfGap);
                    margins.topMargin = 0;
                    margins.bottomMargin = 0;
                }
                seekBar.setLayoutParams(params);
            }
            View root = findRootColumn(column);
            if (root != null) {
                // 原生给根列留了 15dip 底部内边距，导致上下不对称。
                root.setPadding(0, 0, 0, 0);
                ViewGroup.LayoutParams rootParams = root.getLayoutParams();
                if (rootParams instanceof ViewGroup.MarginLayoutParams margins) {
                    margins.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    margins.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    margins.setMarginStart(0);
                    margins.setMarginEnd(0);
                    margins.topMargin = 0;
                    margins.bottomMargin = 0;
                    root.setLayoutParams(margins);
                }
            }
        } catch (Throwable error) {
            HookLog.w("failed to apply slider dimensions", error);
        }
    }

    private static void adjustAllSlidersInView(View view) {
        String name = view.getClass().getName();
        if (name.contains("MiuiVolumeSeekBar") || name.contains("VerticalSeekBar")
                || view instanceof SeekBar) {
            Context context = view.getContext();
            int height = sliderHeightPx(context);
            int width = sliderWidthPx(context);
            int halfGap = columnGapPx(context) / 2;
            ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                params.height = height;
                params.width = width;
                if (params instanceof ViewGroup.MarginLayoutParams margins) {
                    margins.setMarginStart(halfGap);
                    margins.setMarginEnd(halfGap);
                    margins.topMargin = 0;
                    margins.bottomMargin = 0;
                }
                view.setLayoutParams(params);
            }
            if (view.getParent() instanceof View parent) {
                parent.setPadding(0, 0, 0, 0);
                ViewGroup.LayoutParams parentParams = parent.getLayoutParams();
                if (parentParams instanceof ViewGroup.MarginLayoutParams margins) {
                    margins.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                    margins.setMarginStart(0);
                    margins.setMarginEnd(0);
                    margins.topMargin = 0;
                    margins.bottomMargin = 0;
                    parent.setLayoutParams(margins);
                }
            }
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                adjustAllSlidersInView(group.getChildAt(index));
            }
        }
    }

    private static void updateIndicatorDotStyle(View dot, boolean selected) {
        if (dot == null) {
            return;
        }
        dot.setSelected(selected);
        int color = selected ? Color.WHITE : Color.parseColor("#55FFFFFF");
        android.content.res.ColorStateList tint =
                android.content.res.ColorStateList.valueOf(color);
        if (dot instanceof ImageView image) {
            image.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            image.setImageTintList(tint);
            image.setImageTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
        }
        dot.setBackgroundTintList(tint);
    }

    /**
     * 参考官方的分应用音量卡片比例：滑块高度占屏幕长边的 22.1%，
     * 宽度占短边的 15.8%，列间距占短边的 3.8%。
     */
    private static int sliderWidthPx(Context context) {
        return (int) (referenceWidthPx(context) * 0.158f);
    }

    private static int sliderHeightPx(Context context) {
        return (int) (referenceHeightPx(context) * 0.221f);
    }

    private static int columnGapPx(Context context) {
        return (int) (referenceWidthPx(context) * 0.038f);
    }

    private static int referenceWidthPx(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.min(metrics.widthPixels, metrics.heightPixels);
    }

    private static int referenceHeightPx(Context context) {
        android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        return Math.max(metrics.widthPixels, metrics.heightPixels);
    }

    private static int calculateViewPagerWidth(Context context, int columnCount) {
        int sliderWidth = sliderWidthPx(context);
        int gap = columnGapPx(context);
        int columns = Math.min(Math.max(columnCount, 1), 3);
        return columns * (sliderWidth + gap);
    }

    private static void applyBackdropBlur(View view, float cornerRadius) {
        boolean applied = false;
        try {
            Context context = view.getContext();
            boolean night = isNightMode(context);
            Method getViewRootImpl = View.class.getDeclaredMethod("getViewRootImpl");
            getViewRootImpl.setAccessible(true);
            Object viewRootImpl = getViewRootImpl.invoke(view);
            if (viewRootImpl != null) {
                Method createBlur = viewRootImpl.getClass()
                        .getMethod("createBackgroundBlurDrawable");
                Object blur = createBlur.invoke(viewRootImpl);
                if (blur instanceof Drawable drawable) {
                    drawable.setAlpha(255);
                    invokeBestMatch(drawable, "setBlurRadius", 100f);
                    invokeBestMatch(drawable, "setCornerRadius",
                            cornerRadius, cornerRadius, cornerRadius, cornerRadius);
                    invokeIntMethod(drawable, "setColor",
                            Color.parseColor(night ? "#801E1E22" : "#77626262"));
                    view.setBackground(drawable);
                    view.setWillNotDraw(false);
                    applied = true;
                    HookLog.i("applied a background blur to the app volume card");
                }
            }
        } catch (Throwable error) {
            HookLog.w("background blur unavailable, using a flat card", error);
        }
        if (applied) {
            return;
        }
        try {
            GradientDrawable fallback = new GradientDrawable();
            fallback.setShape(GradientDrawable.RECTANGLE);
            fallback.setColor(Color.parseColor(
                    isNightMode(view.getContext()) ? "#D01E1E22" : "#CC454548"));
            fallback.setCornerRadius(cornerRadius);
            view.setBackground(fallback);
        } catch (Throwable ignored) {
            // 保留原生背景。
        }
    }

    /**
     * BackgroundBlurDrawable 在不同版本上分别是四角版与单值版的 setCornerRadius，
     * 参数类型也可能是 int 或 float，这里按参数个数与类型挑一个能用的重载。
     */
    private static void invokeBestMatch(Object target, String name, float... values)
            throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name)
                    || method.getParameterCount() != values.length) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            Object[] args = new Object[values.length];
            boolean usable = true;
            for (int index = 0; index < types.length; index++) {
                if (types[index] == float.class) {
                    args[index] = values[index];
                } else if (types[index] == int.class) {
                    args[index] = (int) values[index];
                } else {
                    usable = false;
                    break;
                }
            }
            if (usable) {
                method.invoke(target, args);
                return;
            }
        }
        if (values.length == 4) {
            invokeBestMatch(target, name, values[0]);
        }
    }

    /** 颜色值直接按 int 传递，避免经过 float 造成精度损失。 */
    private static void invokeIntMethod(Object target, String name, int value)
            throws Exception {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[0];
            if (type == int.class) {
                method.invoke(target, value);
                return;
            }
            if (type == float.class) {
                method.invoke(target, (float) value);
                return;
            }
        }
    }

    private static boolean isNightMode(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // ------------------------------------------------------------ 反射工具

    private static Class<?> findControllerClass(ClassLoader classLoader) {
        Class<?> direct = findClass("com.miui.misound.playervolume.a", classLoader);
        if (direct != null) {
            return direct;
        }
        Class<?> service = findClass("com.miui.misound.playervolume.VolumeUIService", classLoader);
        if (service == null) {
            return null;
        }
        for (Field field : service.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (!type.getName().startsWith("com.miui.misound.playervolume.")) {
                continue;
            }
            for (Field inner : type.getDeclaredFields()) {
                if (inner.getType().getName().endsWith("MediaVolumePageView")) {
                    return type;
                }
            }
        }
        return null;
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object findControllerInstance(Context context) {
        if (context == null) {
            return null;
        }
        ClassLoader classLoader = context.getClassLoader();
        Class<?> controllerClass = findControllerClass(classLoader);
        if (controllerClass == null) {
            return null;
        }
        try {
            Field instance = controllerClass.getDeclaredField("E");
            instance.setAccessible(true);
            Object value = instance.get(null);
            if (value != null) {
                return value;
            }
        } catch (Throwable ignored) {
        }
        try {
            Method getInstance = controllerClass.getDeclaredMethod("j", Context.class);
            getInstance.setAccessible(true);
            return getInstance.invoke(null, context);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount) {
                method.setAccessible(true);
                return method;
            }
        }
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == parameterCount) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Object callMethod(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> type = target.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 0) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(target);
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Object readField(Object target, String... names) {
        if (target == null) {
            return null;
        }
        for (String name : names) {
            try {
                Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static ViewGroup getMediaVolumePageView(Object controller) {
        Object value = readFieldByTypeSuffix(controller, "MediaVolumePageView");
        if (value instanceof ViewGroup group) {
            return group;
        }
        Object legacy = readField(controller, "o");
        return legacy instanceof ViewGroup group ? group : null;
    }

    private static View getViewPager2(Object controller) {
        Object value = readFieldByTypeSuffix(controller, "ViewPager2");
        if (value instanceof View view) {
            return view;
        }
        Object legacy = readField(controller, "p");
        return legacy instanceof View view ? view : null;
    }

    private static Object readFieldByTypeSuffix(Object target, String suffix) {
        if (target == null) {
            return null;
        }
        for (Field field : target.getClass().getDeclaredFields()) {
            if (!field.getType().getName().endsWith(suffix)) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(target);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static ViewGroup getIndicatorViewGroup(Object controller) {
        if (controller == null) {
            return null;
        }
        for (Field field : controller.getClass().getDeclaredFields()) {
            if (!"q".equals(field.getName())
                    || !ViewGroup.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(controller);
                if (value instanceof ViewGroup group) {
                    return group;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static WindowManager getWindowManager(Object controller) {
        if (controller == null) {
            return null;
        }
        for (Field field : controller.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || !WindowManager.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(controller);
                if (value instanceof WindowManager manager) {
                    return manager;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Handler getHandler(Object controller) {
        if (controller == null) {
            return null;
        }
        Object named = readField(controller, "x");
        if (named instanceof Handler handler) {
            return handler;
        }
        for (Field field : controller.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || !Handler.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(controller);
                if (value instanceof Handler handler) {
                    return handler;
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static int getStatus(Object controller) {
        if (controller == null) {
            return STATUS_IDLE;
        }
        for (Field field : controller.getClass().getDeclaredFields()) {
            if (!"a".equals(field.getName()) || field.getType() != int.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.getInt(controller);
            } catch (Throwable ignored) {
                return STATUS_IDLE;
            }
        }
        return STATUS_IDLE;
    }

    private static void setStatus(Object controller, int value) {
        if (controller == null) {
            return;
        }
        for (Field field : controller.getClass().getDeclaredFields()) {
            if (!"a".equals(field.getName()) || field.getType() != int.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                field.setInt(controller, value);
            } catch (Throwable error) {
                HookLog.w("failed to update the app volume panel status", error);
            }
            return;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getColumnsList(Object controller) {
        Object value = readNamedList(controller, "u");
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return nthList(controller, 2);
    }

    private static List<Object> getApcList(Object controller) {
        Object value = readNamedList(controller, "t");
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> cast = (List<Object>) list;
            return cast;
        }
        return nthList(controller, 1);
    }

    private static List<Object> getDotsList(Object controller) {
        Object value = readNamedList(controller, "r");
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> cast = (List<Object>) list;
            return cast;
        }
        return nthList(controller, 0);
    }

    private static Object readNamedList(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field field = target.getClass().getDeclaredField(name);
            if (!List.class.isAssignableFrom(field.getType())) {
                return null;
            }
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> nthList(Object target, int index) {
        if (target == null) {
            return null;
        }
        List<Field> lists = new ArrayList<>();
        for (Field field : target.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    && List.class.isAssignableFrom(field.getType())) {
                lists.add(field);
            }
        }
        if (index >= lists.size()) {
            return null;
        }
        try {
            Field field = lists.get(index);
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof List<?> list ? (List<Object>) list : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getPagesList(Object adapter) {
        Object value = readNamedList(adapter, "e");
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        return nthList(adapter, 0);
    }

    private static View getItemView(Object holder) {
        if (holder == null) {
            return null;
        }
        Object value = readField(holder, "itemView");
        return value instanceof View view ? view : null;
    }

    private static View findSeekBar(Object column) {
        if (column == null) {
            return null;
        }
        for (Class<?> type = column.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!SeekBar.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(column);
                    if (value instanceof View view) {
                        return view;
                    }
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static View findRootColumn(Object column) {
        if (column == null) {
            return null;
        }
        for (Class<?> type = column.getClass(); type != null && type != Object.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                Class<?> kind = field.getType();
                if (!View.class.isAssignableFrom(kind)
                        || SeekBar.class.isAssignableFrom(kind)
                        || ImageView.class.isAssignableFrom(kind)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(column);
                    if (value instanceof View view) {
                        return view;
                    }
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static ViewGroup findCardContainer(ViewGroup root) {
        if (root == null || root.getChildCount() == 0) {
            return null;
        }
        for (int index = 0; index < root.getChildCount(); index++) {
            View child = root.getChildAt(index);
            if (child instanceof ViewGroup group
                    && !child.getClass().getName().contains("ViewPager2")
                    && hasViewPager2(group)) {
                return group;
            }
        }
        if (root.getChildAt(0) instanceof ViewGroup first
                && !first.getClass().getName().contains("ViewPager2")) {
            return first;
        }
        return root;
    }

    private static boolean hasViewPager2(View view) {
        return findViewPager2(view) != null;
    }

    private static View findViewPager2(View view) {
        if (view == null) {
            return null;
        }
        if (view.getClass().getName().contains("ViewPager2")) {
            return view;
        }
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findViewPager2(group.getChildAt(index));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Method findColumnInitMethod(Class<?> columnClass) {
        Method candidate = findMethod(columnClass, "a", 0);
        if (candidate != null && !Modifier.isAbstract(candidate.getModifiers())) {
            return candidate;
        }
        for (Method method : columnClass.getDeclaredMethods()) {
            if (Modifier.isAbstract(method.getModifiers())
                    || Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 0
                    || method.getReturnType() != void.class) {
                continue;
            }
            if (!"b".equals(method.getName()) && !"c".equals(method.getName())
                    && !"d".equals(method.getName()) && !"e".equals(method.getName())) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static List<Class<?>> findColumnClasses(
            Class<?> controllerClass, ClassLoader classLoader) {
        List<Class<?>> result = new ArrayList<>();
        for (Class<?> inner : controllerClass.getDeclaredClasses()) {
            if (Modifier.isAbstract(inner.getModifiers())) {
                continue;
            }
            if (hasSeekBarField(inner)
                    || (inner.getSuperclass() != null
                            && inner.getSuperclass() != Object.class
                            && hasSeekBarField(inner.getSuperclass()))) {
                result.add(inner);
            }
        }
        if (result.isEmpty()) {
            for (String name : new String[]{
                    controllerClass.getName() + "$j",
                    controllerClass.getName() + "$h",
                    "com.miui.misound.playervolume.a$j",
                    "com.miui.misound.playervolume.a$h"}) {
                Class<?> candidate = findClass(name, classLoader);
                if (candidate != null && !Modifier.isAbstract(candidate.getModifiers())) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    private static boolean hasSeekBarField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (SeekBar.class.isAssignableFrom(field.getType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdapterSubclass(Class<?> type) {
        for (Class<?> current = type.getSuperclass();
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            if (current.getName().endsWith("RecyclerView$Adapter")
                    || current.getName().endsWith("Adapter")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPageCallbackSubclass(Class<?> type) {
        for (Class<?> current = type.getSuperclass();
                current != null && current != Object.class;
                current = current.getSuperclass()) {
            if (current.getName().endsWith("ViewPager2$OnPageChangeCallback")
                    || current.getName().endsWith("OnPageChangeCallback")) {
                return true;
            }
        }
        return false;
    }
}
