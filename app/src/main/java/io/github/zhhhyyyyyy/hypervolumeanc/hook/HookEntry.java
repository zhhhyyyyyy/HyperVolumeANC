package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

public final class HookEntry extends XposedModule {
    private static final String TAG = "HyperVolumeANC";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String BLUETOOTH_EXTENSION = "com.xiaomi.bluetooth";
    private static final String MISOUND = "com.miui.misound";
    private static final String RINGER_LAYOUT =
            "com.android.systemui.miui.volume.MiuiRingerModeLayout";
    private static final String VOLUME_DIALOG_VIEW =
            "com.android.systemui.miui.volume.MiuiVolumeDialogView";
    private static final String PANEL_CONTROLLER =
            "com.android.systemui.miui.volume.VolumePanelViewController";
    private static final String VOLUME_DIALOG_RES =
            "com.android.systemui.miui.volume.MiuiVolumeDialogRes";
    private static final String SHOW_HIDE_ANIMATOR =
            "com.android.systemui.miui.volume.VolumeShowHideAnimator";
    private static final String SLIDE_CONTAINER_ANIM =
            "com.android.systemui.miui.volume.SlideContainerAnim";
    private static final String EXPAND_COLLAPSED_ANIMATOR =
            "com.android.systemui.miui.volume.VolumeExpandCollapsedAnimator";
    private static final String BLUETOOTH_CONTENT_PROVIDER =
            "com.android.bluetooth.ble.app.headset.miuibluetoothprovider.MiuiBluetoothContentProvider";
    private static final String BLUETOOTH_PERMISSION_CHECKER =
            "com.android.bluetooth.ble.app.permission.PermissionChecker";

    private final Set<Class<?>> hookedLayouts =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private volatile boolean loaderHookInstalled;
    private volatile boolean airPodsProviderHooksInstalled;
    private volatile XposedInterface.HookHandle loaderHookHandle;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        HookLog.attach(this);
        HookDiagnostics.ok("core", "模块入口", "api=" + getApiVersion() + " · " + param.getProcessName());
        HookLog.i("module loaded process=" + param.getProcessName() + " api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (BLUETOOTH_EXTENSION.equals(param.getPackageName())) {
            HookDiagnostics.ok("scope_bluetooth", "蓝牙扩展作用域", "已进入进程");
            OppoPodsBridge.install(this);
            HookHeartbeat.install(this, BLUETOOTH_EXTENSION);
            installAirPodsProviderHooks(param.getClassLoader());
            return;
        }
        if (MISOUND.equals(param.getPackageName())) {
            HookDiagnostics.ok("scope_misound", "音质音效作用域", "已进入进程");
            HookHeartbeat.install(this, MISOUND);
            MiSoundAppVolumeHook.install(this, param.getClassLoader());
            return;
        }
        if (!SYSTEM_UI.equals(param.getPackageName()) || loaderHookInstalled) {
            return;
        }
        HookHeartbeat.install(this, SYSTEM_UI);
        HookDiagnostics.ok("scope_systemui", "系统界面作用域", "已进入进程");
        loaderHookInstalled = true;
        installPluginDiscovery(param.getClassLoader());
    }

    private static final String PLUGIN_INSTANCE =
            "com.android.systemui.shared.plugins.PluginInstance";
    private static final String PLUGIN_ACTION_MANAGER =
            "com.android.systemui.shared.plugins.PluginActionManager";
    private static final String TARGET_PLUGIN_PACKAGE = "miui.systemui.plugin";
    private static final String PLUGIN_INJECTOR =
            "com.miui.systemui.plugin.PluginInstanceInjector";
    private static final long PLUGIN_DISCOVERY_INTERVAL_MS = 500L;
    private static final int PLUGIN_DISCOVERY_ATTEMPTS = 24;

    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private volatile boolean layoutHookInstalled;
    private volatile boolean pluginDiscoveryStarted;
    private final ThreadLocal<Boolean> handlingPluginInstance = new ThreadLocal<>();

    /**
     * 找到音量插件的 ClassLoader 并安装钩子。
     *
     * 优先走插件注入器 / 插件实例这条路（小米音量插件由 miui.systemui.plugin 承载），
     * 只有这些途径都拿不到时才退回监视 ClassLoader.loadClass。后者在多模块同时监视时
     * 会互相放大调用链，SystemUI 启动阶段尤其容易超时（failed to complete startup ANR）。
     */
    private void installPluginDiscovery(ClassLoader systemUiLoader) {
        synchronized (this) {
            if (pluginDiscoveryStarted) {
                return;
            }
            pluginDiscoveryStarted = true;
        }
        if (installLayoutHookFrom(getPluginClassLoaderFromInjector(systemUiLoader))) {
            return;
        }
        HookDiagnostics.waiting("sysui_plugin", "音量插件定位", "等待音量插件加载");
        hookPluginInstance(systemUiLoader);
        hookPluginActionManager(systemUiLoader);
        retryPluginDiscovery(systemUiLoader, 0);
        HookLog.i("waiting for HyperOS volume plugin class");
    }

    private void retryPluginDiscovery(ClassLoader systemUiLoader, int attempt) {
        if (layoutHookInstalled) {
            return;
        }
        if (attempt > PLUGIN_DISCOVERY_ATTEMPTS) {
            HookLog.i("plugin class loader not found through plugin hooks; "
                    + "falling back to ClassLoader monitoring");
            HookDiagnostics.waiting("sysui_plugin", "音量插件定位", "常规途径未命中，改用 ClassLoader 监视");
            installClassLoaderHook();
            return;
        }
        MAIN.postDelayed(() -> {
            if (layoutHookInstalled) {
                return;
            }
            if (installLayoutHookFrom(getPluginClassLoaderFromInjector(systemUiLoader))) {
                return;
            }
            retryPluginDiscovery(systemUiLoader, attempt + 1);
        }, PLUGIN_DISCOVERY_INTERVAL_MS);
    }

    /** HyperOS 4 的插件注入器里缓存了各插件包名对应的 ClassLoader。 */
    private ClassLoader getPluginClassLoaderFromInjector(ClassLoader classLoader) {
        if (classLoader == null) {
            return null;
        }
        try {
            Class<?> injector = Class.forName(PLUGIN_INJECTOR, false, classLoader);
            Field field = injector.getDeclaredField("sClassLoaders");
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof java.util.Map<?, ?> map) {
                Object loader = map.get(TARGET_PLUGIN_PACKAGE);
                if (loader instanceof ClassLoader pluginLoader) {
                    return pluginLoader;
                }
            }
        } catch (Throwable error) {
            // 注入器不存在或结构不同，继续用其它途径探测。
        }
        return null;
    }

    private boolean installLayoutHookFrom(ClassLoader pluginLoader) {
        if (pluginLoader == null || layoutHookInstalled) {
            return false;
        }
        try {
            if (installLayoutHook(Class.forName(RINGER_LAYOUT, false, pluginLoader))) {
                HookLog.i("volume layout hook installed loader=" + pluginLoader);
                HookDiagnostics.ok("sysui_plugin", "音量插件定位", shortenLoader(pluginLoader));
                return true;
            }
        } catch (ClassNotFoundException error) {
            // 插件还没把音量类加载进来，等待下一次重试。
        } catch (Throwable error) {
            HookLog.w("failed to install volume layout hooks", error);
        }
        return false;
    }

    /** ClassLoader.toString() 太长，只取插件路径的最后一段用于诊断展示。 */
    private static String shortenLoader(ClassLoader loader) {
        String text = String.valueOf(loader);
        int marker = text.indexOf("zip file \"");
        if (marker >= 0) {
            int start = marker + "zip file \"".length();
            int end = text.indexOf('"', start);
            if (end > start) {
                String path = text.substring(start, end);
                int slash = path.lastIndexOf('/');
                return slash >= 0 ? path.substring(slash + 1) : path;
            }
        }
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    private void hookPluginInstance(ClassLoader classLoader) {
        try {
            Class<?> instanceClass = Class.forName(PLUGIN_INSTANCE, false, classLoader);
            for (Method method : instanceClass.getDeclaredMethods()) {
                if (!"loadPlugin".equals(method.getName())) {
                    continue;
                }
                method.setAccessible(true);
                hook(method)
                        .setId("hypervolumeanc.v1:plugin-instance:load-plugin:"
                                + Integer.toHexString(method.toGenericString().hashCode()))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            handlePluginInstance(chain.getThisObject());
                            return result;
                        });
            }
            HookLog.i("watching PluginInstance lifecycle for the volume plugin");
        } catch (Throwable error) {
            HookLog.w("PluginInstance is unavailable; using the retry path", error);
        }
    }

    private void hookPluginActionManager(ClassLoader classLoader) {
        try {
            Class<?> managerClass = Class.forName(PLUGIN_ACTION_MANAGER, false, classLoader);
            for (Method method : managerClass.getDeclaredMethods()) {
                if (!"onPluginConnected".equals(method.getName())
                        || method.getParameterCount() < 1) {
                    continue;
                }
                method.setAccessible(true);
                hook(method)
                        .setId("hypervolumeanc.v1:plugin-action-manager:connected:"
                                + Integer.toHexString(method.toGenericString().hashCode()))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            handlePluginInstance(chain.getArg(0));
                            return result;
                        });
            }
        } catch (Throwable error) {
            HookLog.w("PluginActionManager is unavailable", error);
        }
    }

    /** 插件实例里取出 ClassLoader（字段名随版本不同，逐个尝试）。 */
    private void handlePluginInstance(Object instance) {
        if (instance == null || layoutHookInstalled || Boolean.TRUE.equals(
                handlingPluginInstance.get())) {
            return;
        }
        try {
            handlingPluginInstance.set(Boolean.TRUE);
            ClassLoader pluginLoader = extractPluginClassLoader(instance);
            if (pluginLoader != null) {
                installLayoutHookFrom(pluginLoader);
            }
        } catch (Throwable error) {
            HookLog.w("failed to inspect the plugin instance", error);
        } finally {
            handlingPluginInstance.set(Boolean.FALSE);
        }
    }

    private ClassLoader extractPluginClassLoader(Object instance) {
        for (String name : new String[]{"pluginData", "mPlugin", "plugin", "mPluginContext",
                "pluginContext", "pluginFactory", "mPluginFactory"}) {
            ClassLoader loader = classLoaderFromValue(readField(instance, name));
            if (loader != null && canLoadPluginClass(loader)) {
                return loader;
            }
        }
        return null;
    }

    private ClassLoader classLoaderFromValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof ClassLoader loader) {
            return loader;
        }
        if (value instanceof android.content.Context context) {
            return context.getClassLoader();
        }
        for (String name : new String[]{"plugin", "context", "hostContext",
                "pluginContext", "mPlugin", "mPluginContext"}) {
            ClassLoader loader = classLoaderFromValue(readField(value, name));
            if (loader != null) {
                return loader;
            }
        }
        return null;
    }

    private boolean canLoadPluginClass(ClassLoader loader) {
        try {
            Class.forName(RINGER_LAYOUT, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void installAirPodsProviderHooks(ClassLoader classLoader) {
        if (airPodsProviderHooksInstalled) {
            return;
        }
        try {
            Class<?> provider = Class.forName(BLUETOOTH_CONTENT_PROVIDER, false, classLoader);
            Method checkCallerPermission = provider.getDeclaredMethod(
                    "checkCallerPermission", android.content.Context.class, String.class);
            hook(checkCallerPermission)
                    .setId("hypervolumeanc.v1:airpods-provider:caller")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> SYSTEM_UI.equals(chain.getArg(1))
                            ? true
                            : chain.proceed());

            Class<?> permissionChecker = Class.forName(
                    BLUETOOTH_PERMISSION_CHECKER, false, classLoader);
            Method checkPackageAndSignature = permissionChecker.getDeclaredMethod(
                    "b",
                    android.content.Context.class,
                    String.class,
                    String[].class,
                    String[].class);
            hook(checkPackageAndSignature)
                    .setId("hypervolumeanc.v1:airpods-provider:repository")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> SYSTEM_UI.equals(chain.getArg(1))
                            ? true
                            : chain.proceed());

            airPodsProviderHooksInstalled = true;
            HookLog.i("AirPods repository access enabled for SystemUI");
            HookDiagnostics.ok("bt_airpods", "AirPods 仓库放行", "已允许系统界面读取耳机状态");
        } catch (Throwable error) {
            HookLog.e("failed to enable AirPods repository access", error);
            HookDiagnostics.failed("bt_airpods", "AirPods 仓库放行", error);
        }
    }

    private void installClassLoaderHook() {
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
            loaderHookHandle = hook(loadClass)
                    .setId("hypervolumeanc.v1:classloader:miui-ringer-layout")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        // 只对音量插件那个类做判断，其它类加载原样放行：多个模块同时监视
                        // ClassLoader 时，任何额外工作都会被放大到整条加载链上。
                        if (!RINGER_LAYOUT.equals(chain.getArg(0))) {
                            return chain.proceed();
                        }
                        Object result = chain.proceed();
                        if (result instanceof Class<?> loaded
                                && RINGER_LAYOUT.equals(loaded.getName())) {
                            if (installLayoutHook(loaded)) {
                                stopClassLoaderHook();
                            }
                        }
                        return result;
                    });
        } catch (Throwable error) {
            HookLog.e("failed to hook ClassLoader.loadClass", error);
        }
    }

    private boolean installLayoutHook(Class<?> layoutClass) {
        synchronized (hookedLayouts) {
            if (!hookedLayouts.add(layoutClass)) {
                return true;
            }
        }
        try {
            Method onFinishInflate = layoutClass.getDeclaredMethod("onFinishInflate");
            hook(onFinishInflate)
                    .setId("hypervolumeanc.v1:miui-ringer-layout:onFinishInflate:"
                            + Integer.toHexString(System.identityHashCode(layoutClass.getClassLoader())))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object layout = chain.getThisObject();
                        VolumeButtonInjector.inject(layout);
                        MediaVolumeEntry.inject(layout instanceof android.view.View view ? view : null);
                        return result;
                    });
            installExpandedHook(layoutClass);
            installStyleHook(layoutClass, "updateResources");
            installStyleHook(layoutClass, "onMaterialModeChanged");
            installCollapsedPreparationHook(layoutClass);
            layoutHookInstalled = true;
            stopClassLoaderHook();
            HookDiagnostics.ok("sysui_layout", "音量面板布局钩子", "已挂载");
            // 下面这些钩子需要再加载若干个插件类。放在主线程队列里执行，
            // 让当前这次类加载尽快返回，避免拖长插件加载路径。
            ClassLoader pluginLoader = layoutClass.getClassLoader();
            MAIN.post(() -> {
                try {
                    installExpandedHeightHook(pluginLoader);
                    installNativeAnimationHooks(pluginLoader);
                    installDialogHooks(pluginLoader);
                    HookDiagnostics.ok("sysui_anim", "音量面板动画钩子", "已挂载");
                } catch (Throwable error) {
                    HookLog.e("failed to install deferred volume hooks", error);
                    HookDiagnostics.failed("sysui_anim", "音量面板动画钩子", error);
                }
            });
            HookLog.i("volume layout hook installed loader=" + layoutClass.getClassLoader());
            return true;
        } catch (Throwable error) {
            hookedLayouts.remove(layoutClass);
            HookLog.e("failed to hook MiuiRingerModeLayout", error);
            return false;
        }
    }

    private void installNativeAnimationHooks(ClassLoader classLoader) {
        installShowHideAnimationHook(classLoader);
        installExpandCollapsedAnimationHook(classLoader);
    }

    private void installShowHideAnimationHook(ClassLoader classLoader) {
        try {
            Class<?> animatorClass = Class.forName(SHOW_HIDE_ANIMATOR, false, classLoader);
            Method initView = animatorClass.getDeclaredMethod(
                    "initView",
                    android.view.View.class,
                    android.view.View.class,
                    android.view.View.class);
            hook(initView)
                    .setId("hypervolumeanc.v1:show-hide-animator:initView:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        VolumeButtonInjector.attachToShowHideAnimator(
                                chain.getThisObject(), (android.view.View) chain.getArg(0));
                        installAnimatorListenerHook(chain.getThisObject(), classLoader);
                        return result;
                    });
            Method setViewX = animatorClass.getDeclaredMethod("setViewX");
            hook(setViewX)
                    .setId("hypervolumeanc.v1:show-hide-animator:set-view-x:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        MediaVolumeEntry.syncAnimation();
                        MediaVolumeEntry.tick();
                        return result;
                    });
            Method onAnimComplete = animatorClass.getDeclaredMethod("onAnimComplete");
            hook(onAnimComplete)
                    .setId("hypervolumeanc.v1:show-hide-animator:complete:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (isAnimatorCollapsed(chain.getThisObject())) {
                            MediaVolumeEntry.onHideComplete();
                        } else {
                            MediaVolumeEntry.syncAnimation();
                        }
                        return result;
                    });
            Method cancel = animatorClass.getDeclaredMethod("cancel");
            hook(cancel)
                    .setId("hypervolumeanc.v1:show-hide-animator:cancel:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (isAnimatorCollapsed(chain.getThisObject())) {
                            MediaVolumeEntry.onHideComplete();
                        }
                        return result;
                    });
            Class<?> animatorKtClass = Class.forName(
                    SHOW_HIDE_ANIMATOR + "Kt", false, classLoader);
            Method createRingerButtonArgs = animatorKtClass.getDeclaredMethod(
                    "createRingerButtonArgs", boolean.class, int.class, float.class);
            hook(createRingerButtonArgs)
                    .setId("hypervolumeanc.v1:show-hide-animator:ringer-delay:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (Boolean.TRUE.equals(chain.getArg(0))
                                && ((Integer) chain.getArg(1)) >= 2
                                && result != null) {
                            try {
                                Method getDelayX = result.getClass().getDeclaredMethod("getDelayX");
                                Method setDelayX = result.getClass().getDeclaredMethod(
                                        "setDelayX", long.class);
                                getDelayX.setAccessible(true);
                                setDelayX.setAccessible(true);
                                if (((Long) getDelayX.invoke(result)) > 0L) {
                                    setDelayX.invoke(result, 70L);
                                }
                            } catch (Throwable error) {
                                HookLog.e("failed to extend native ringer animation delay", error);
                            }
                        }
                        return result;
                    });
            HookLog.i("native show/hide animation hook installed");
        } catch (Throwable error) {
            HookLog.e("failed to hook native show/hide animation", error);
        }
    }

    private volatile boolean animatorListenerHookInstalled;

    /** 官方 show/hide 动画的 mExpanded：false 表示正在收起（收起结束才复位入口）。 */
    private static boolean isAnimatorCollapsed(Object animator) {
        try {
            Field field = animator.getClass().getDeclaredField("mExpanded");
            field.setAccessible(true);
            return field.get(animator) instanceof Boolean expanded && !expanded;
        } catch (Throwable error) {
            return false;
        }
    }

    /** 官方动画每帧回调 listener.onUpdate，入口在这里跟随勿扰行同步缩放与位移。 */
    private void installAnimatorListenerHook(Object animator, ClassLoader classLoader) {
        if (animatorListenerHookInstalled || animator == null) {
            return;
        }
        Object listener = null;
        try {
            Field field = animator.getClass().getDeclaredField("listener");
            field.setAccessible(true);
            listener = field.get(animator);
        } catch (Throwable error) {
            HookLog.w("volume animator listener is unavailable", error);
        }
        if (listener == null) {
            return;
        }
        Class<?> listenerClass = listener.getClass();
        for (Method method : listenerClass.getDeclaredMethods()) {
            if (!"onUpdate".equals(method.getName()) || method.getParameterCount() != 2) {
                continue;
            }
            method.setAccessible(true);
            try {
                hook(method)
                        .setId("hypervolumeanc.v1:show-hide-listener:update:"
                                + Integer.toHexString(System.identityHashCode(classLoader)))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            MediaVolumeEntry.syncAnimation();
                            return result;
                        });
                animatorListenerHookInstalled = true;
                HookLog.i("volume animator listener hooked for the app volume entry");
            } catch (Throwable error) {
                HookLog.w("failed to hook the volume animator listener", error);
            }
            return;
        }
    }

    private void installExpandCollapsedAnimationHook(ClassLoader classLoader) {
        try {
            Class<?> animatorClass = Class.forName(
                    EXPAND_COLLAPSED_ANIMATOR, false, classLoader);
            Method frameCallback = animatorClass.getDeclaredMethod(
                    "frameCallback$lambda$9", animatorClass, long.class);
            hook(frameCallback)
                    .setId("hypervolumeanc.v1:expand-collapsed-animator:frame:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        VolumeButtonInjector.syncExpandCollapsedFrame(chain.getArg(0));
                        return result;
                    });
            HookLog.i("native expand/collapse animation hook installed");
        } catch (Throwable error) {
            HookLog.e("failed to hook native expand/collapse animation", error);
        }
    }

    /**
     * 分应用音量入口相关的钩子：入口的显隐跟随音量条的展示 / 收起与二级菜单状态，
     * 并让入口在原生 show/hide 动画里跟着静音 / 勿扰按钮一起缩放位移。
     */
    private void installDialogHooks(ClassLoader classLoader) {
        installVolumeDialogViewHooks(classLoader);
        installPanelControllerHook(classLoader);
        installEntryMarginHook(classLoader);
        installSlideAnimHook(classLoader);
    }

    private void installVolumeDialogViewHooks(ClassLoader classLoader) {
        try {
            Class<?> dialogClass = Class.forName(VOLUME_DIALOG_VIEW, false, classLoader);
            Method showH = dialogClass.getDeclaredMethod(
                    "showH", Runnable.class);
            hook(showH)
                    .setId("hypervolumeanc.v1:volume-dialog:show:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (chain.getThisObject() instanceof android.view.View view) {
                            MediaVolumeEntry.prepareShow(view);
                        }
                        return chain.proceed();
                    });
            Method dismissH = dialogClass.getDeclaredMethod(
                    "dismissH", boolean.class, Runnable.class);
            hook(dismissH)
                    .setId("hypervolumeanc.v1:volume-dialog:dismiss:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getThisObject() instanceof android.view.View view) {
                            MediaVolumeEntry.onDismissStarted(
                                    view, Boolean.TRUE.equals(chain.getArg(0)));
                        }
                        return result;
                    });
            Method expandState = dialogClass.getDeclaredMethod(
                    "onExpandStateUpdated", boolean.class);
            hook(expandState)
                    .setId("hypervolumeanc.v1:volume-dialog:expand:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getThisObject() instanceof android.view.View view
                                && chain.getArg(0) instanceof Boolean expanded) {
                            MediaVolumeEntry.onExpandedChanged(view, expanded);
                        }
                        return result;
                    });
            installDialogLayoutHook(dialogClass, classLoader);
            installDialogLifecycleHooks(dialogClass, classLoader);
            HookLog.i("volume dialog hooks installed");
            HookDiagnostics.ok("sysui_dialog", "音量面板生命周期钩子", "已挂载");
        } catch (Throwable error) {
            HookLog.e("failed to hook the volume dialog view", error);
            HookDiagnostics.failed("sysui_dialog", "音量面板生命周期钩子", error);
        }
    }

    /**
     * 额外两个触发点：面板挂到窗口时、页脚显隐变化时都重新判断入口可见性。
     * 不同 ROM 版本对 showH / onExpandStateUpdated 的调用时机略有差异，
     * 多挂几个触发点可以保证入口不会因为某一个钩子没命中而一直不出现。
     */
    private void installDialogLifecycleHooks(Class<?> dialogClass, ClassLoader classLoader) {
        try {
            Method onAttached = dialogClass.getDeclaredMethod("onAttachedToWindow");
            hook(onAttached)
                    .setId("hypervolumeanc.v1:volume-dialog:attached:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getThisObject() instanceof android.view.View view) {
                            MediaVolumeEntry.prepareShow(view);
                        }
                        return result;
                    });
        } catch (Throwable error) {
            HookLog.w("volume dialog attach hook unavailable", error);
        }
        try {
            Method footer = dialogClass.getDeclaredMethod("updateFooterVisibility", boolean.class);
            hook(footer)
                    .setId("hypervolumeanc.v1:volume-dialog:footer:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        MediaVolumeEntry.refreshVisibility();
                        return result;
                    });
        } catch (Throwable error) {
            HookLog.w("volume dialog footer hook unavailable", error);
        }
        try {
            Method onDetached = dialogClass.getDeclaredMethod("onDetachedFromWindow");
            hook(onDetached)
                    .setId("hypervolumeanc.v1:volume-dialog:detached:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        MediaVolumeEntry.onHideComplete();
                        return result;
                    });
        } catch (Throwable error) {
            HookLog.w("volume dialog detach hook unavailable", error);
        }
    }

    private void installDialogLayoutHook(Class<?> dialogClass, ClassLoader classLoader) {
        try {
            Method updateLayout = dialogClass.getDeclaredMethod("updateDialogViewLP");
            hook(updateLayout)
                    .setId("hypervolumeanc.v1:volume-dialog:layout:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        MediaVolumeEntry.refreshVisibility();
                        return result;
                    });
        } catch (Throwable ignored) {
            // 部分版本没有这个方法，入口会在下一次展示时重新定位。
        }
    }

    /** 缓存音量控制器，供入口点击后收起音量条使用。 */
    private void installPanelControllerHook(ClassLoader classLoader) {
        try {
            Class<?> controllerClass = Class.forName(PANEL_CONTROLLER, false, classLoader);
            Method showInt = controllerClass.getDeclaredMethod("showH", int.class);
            hook(showInt)
                    .setId("hypervolumeanc.v1:panel-controller:show:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        MediaVolumeEntry.setPanelController(chain.getThisObject());
                        MediaVolumeEntry.prepareShow(null);
                        return chain.proceed();
                    });
        } catch (Throwable error) {
            HookLog.w("panel controller hook unavailable", error);
        }
    }

    /**
     * 入口占据音量条上方一行时，面板 topMargin 同步上移，
     * 保证滑块、静音与勿扰按钮的绝对位置和横屏表现都与原生一致。
     */
    private void installEntryMarginHook(ClassLoader classLoader) {
        try {
            Class<?> resourceClass = Class.forName(VOLUME_DIALOG_RES, false, classLoader);
            Method marginTop = resourceClass.getDeclaredMethod("getMarginTop",
                    android.content.Context.class, boolean.class, boolean.class, int.class, int.class);
            hook(marginTop)
                    .setId("hypervolumeanc.v1:volume-dialog-res:margin-top:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!(result instanceof Integer margin)) {
                            return result;
                        }
                        int offset = MediaVolumeEntry.marginOffset(
                                (android.content.Context) chain.getArg(0),
                                Boolean.TRUE.equals(chain.getArg(1)),
                                Boolean.TRUE.equals(chain.getArg(2)));
                        return offset == 0 ? margin : margin - offset;
                    });
        } catch (Throwable error) {
            HookLog.w("app volume entry margin hook unavailable", error);
        }
    }

    /**
     * 触底 / 拖拽拉伸 / 按键调节时的弹性位移：官方按等差序列驱动静音与勿扰行，
     * 这里把位于它们上下两侧的新增按钮外推一步，保持整列一起伸缩。
     */
    private void installSlideAnimHook(ClassLoader classLoader) {
        try {
            Class<?> slideClass = Class.forName(SLIDE_CONTAINER_ANIM, false, classLoader);
            // AnimListener 是接口，抽象方法没法直接挂钩子；先拿到实现类实例再挂它的实现。
            for (java.lang.reflect.Constructor<?> constructor : slideClass.getDeclaredConstructors()) {
                constructor.setAccessible(true);
                hook(constructor)
                        .setId("hypervolumeanc.v1:slide-anim:ctor:"
                                + Integer.toHexString(constructor.toGenericString().hashCode()))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            captureSlideListener(chain.getThisObject());
                            return result;
                        });
            }
            for (Method method : slideClass.getDeclaredMethods()) {
                String name = method.getName();
                if (!name.startsWith("anim") && !"initView".equals(name)) {
                    continue;
                }
                method.setAccessible(true);
                hook(method)
                        .setId("hypervolumeanc.v1:slide-anim:" + name + ":"
                                + Integer.toHexString(method.toGenericString().hashCode()))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            captureSlideListener(chain.getThisObject());
                            return result;
                        });
            }
            HookLog.i("slide animation hooks installed");
            HookDiagnostics.ok("sysui_slide", "触底 / 拖拽位移钩子", "已挂载");
        } catch (Throwable error) {
            HookLog.w("slide animation hook unavailable", error);
            HookDiagnostics.missing("sysui_slide", "触底 / 拖拽位移钩子", "当前版本没有对应类");
        }
    }

    private volatile boolean slideListenerHooked;

    /** 从 SlideContainerAnim 里取出实现 AnimListener 的实例，挂它自己的 setRingerY/setDndY。 */
    private void captureSlideListener(Object anim) {
        if (anim == null || slideListenerHooked) {
            return;
        }
        Object listener = null;
        try {
            Field field = anim.getClass().getDeclaredField("mAnimListener");
            field.setAccessible(true);
            listener = field.get(anim);
        } catch (Throwable error) {
            HookLog.w("slide anim listener is unavailable", error);
        }
        if (listener == null) {
            return;
        }
        Class<?> listenerClass = listener.getClass();
        for (Method method : listenerClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(method.getModifiers())) {
                continue;
            }
            String name = method.getName();
            boolean ringer = "setRingerY".equals(name) && method.getParameterCount() == 2;
            boolean dnd = "setDndY".equals(name) && method.getParameterCount() == 2;
            boolean reset = "resetView".equals(name) && method.getParameterCount() == 0;
            if (!ringer && !dnd && !reset) {
                continue;
            }
            method.setAccessible(true);
            try {
                hook(method)
                        .setId("hypervolumeanc.v1:slide-listener:" + name + ":"
                                + Integer.toHexString(method.toGenericString().hashCode()))
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            if (ringer && chain.getArg(1) instanceof Float value) {
                                MediaVolumeEntry.onSlideRinger(value);
                            } else if (dnd && chain.getArg(1) instanceof Float value) {
                                MediaVolumeEntry.onSlideDnd(value);
                            } else if (reset) {
                                MediaVolumeEntry.onSlideReset();
                            }
                            return result;
                        });
                slideListenerHooked = true;
            } catch (Throwable error) {
                HookLog.w("failed to hook slide listener " + name, error);
            }
        }
        if (slideListenerHooked) {
            HookLog.i("slide listener hooked: " + listenerClass.getName());
        }
    }

    private void installExpandedHeightHook(ClassLoader classLoader) {
        try {
            Class<?> resourceClass = Class.forName(VOLUME_DIALOG_RES, false, classLoader);
            Method method = resourceClass.getDeclaredMethod(
                    "getHeight", android.content.Context.class, boolean.class, boolean.class);
            hook(method)
                    .setId("hypervolumeanc.v1:volume-dialog-res:getHeight:"
                            + Integer.toHexString(System.identityHashCode(classLoader)))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        // 只看是否展开：普通音量条和控制中心面板都要为我们多出来的那一行加高背景。
                        if (result instanceof Integer height
                                && Boolean.TRUE.equals(chain.getArg(2))) {
                            return height + VolumeButtonInjector.expandedHeightExtra();
                        }
                        return result;
                    });
            HookLog.i("expanded volume background height hook installed");
        } catch (Throwable error) {
            HookLog.e("failed to hook expanded volume background height", error);
        }
    }

    private void installExpandedHook(Class<?> layoutClass) {
        try {
            Method method = layoutClass.getDeclaredMethod(
                    "updateExpandedH", boolean.class, boolean.class);
            hook(method)
                    .setId(hookId(layoutClass, "updateExpandedH"))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        VolumeButtonInjector.onExpanded(
                                chain.getThisObject(),
                                (Boolean) chain.getArg(0),
                                (Boolean) chain.getArg(1));
                        return result;
                    });
        } catch (Throwable error) {
            HookLog.e("failed to hook native volume expansion", error);
        }
    }

    private void installStyleHook(Class<?> layoutClass, String methodName) {
        try {
            Method method = layoutClass.getDeclaredMethod(methodName);
            hook(method)
                    .setId(hookId(layoutClass, methodName))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        VolumeButtonInjector.refreshStyle(chain.getThisObject());
                        MediaVolumeEntry.refreshStyle();
                        return result;
                    });
        } catch (Throwable error) {
            HookLog.e("failed to hook native style refresh: " + methodName, error);
        }
    }

    private void installCollapsedPreparationHook(Class<?> layoutClass) {
        try {
            Method method = layoutClass.getDeclaredMethod(
                    "prepareCollapsedBlurForShowAnimation");
            hook(method)
                    .setId(hookId(layoutClass, "prepareCollapsedBlurForShowAnimation"))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        VolumeButtonInjector.onExpanded(chain.getThisObject(), false, true);
                        return result;
                    });
        } catch (Throwable error) {
            HookLog.e("failed to hook collapsed blur preparation", error);
        }
    }

    private String hookId(Class<?> layoutClass, String methodName) {
        return "hypervolumeanc.v1:miui-ringer-layout:" + methodName + ":"
                + Integer.toHexString(System.identityHashCode(layoutClass.getClassLoader()));
    }

    private void stopClassLoaderHook() {
        XposedInterface.HookHandle handle = loaderHookHandle;
        if (handle != null) {
            loaderHookHandle = null;
            handle.unhook();
            HookLog.i("volume plugin found; ClassLoader hook removed");
        }
    }
}
