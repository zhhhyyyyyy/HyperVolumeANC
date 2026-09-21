package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.util.Log;

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
    private static final String RINGER_LAYOUT =
            "com.android.systemui.miui.volume.MiuiRingerModeLayout";
    private static final String VOLUME_DIALOG_RES =
            "com.android.systemui.miui.volume.MiuiVolumeDialogRes";
    private static final String SHOW_HIDE_ANIMATOR =
            "com.android.systemui.miui.volume.VolumeShowHideAnimator";
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
        Log.i(TAG, "module loaded process=" + param.getProcessName() + " api=" + getApiVersion());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (BLUETOOTH_EXTENSION.equals(param.getPackageName())) {
            OppoPodsBridge.install(this);
            HookHeartbeat.install(this, BLUETOOTH_EXTENSION);
            installAirPodsProviderHooks(param.getClassLoader());
            return;
        }
        if (!SYSTEM_UI.equals(param.getPackageName()) || loaderHookInstalled) {
            return;
        }
        HookHeartbeat.install(this, SYSTEM_UI);
        loaderHookInstalled = true;
        installClassLoaderHook();
        tryInstallLayoutHook(param.getClassLoader());
        Log.i(TAG, "waiting for HyperOS volume plugin class");
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
            Log.i(TAG, "AirPods repository access enabled for SystemUI");
        } catch (Throwable error) {
            Log.e(TAG, "failed to enable AirPods repository access", error);
        }
    }

    private void installClassLoaderHook() {
        try {
            Method loadClass = ClassLoader.class.getDeclaredMethod("loadClass", String.class, boolean.class);
            loaderHookHandle = hook(loadClass)
                    .setId("hypervolumeanc.v1:classloader:miui-ringer-layout")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
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
            Log.e(TAG, "failed to hook ClassLoader.loadClass", error);
        }
    }

    private void tryInstallLayoutHook(ClassLoader classLoader) {
        try {
            if (installLayoutHook(Class.forName(RINGER_LAYOUT, false, classLoader))) {
                stopClassLoaderHook();
            }
        } catch (ClassNotFoundException ignored) {
            // The volume implementation is loaded later by SystemUI's plugin loader.
        } catch (Throwable error) {
            Log.e(TAG, "failed to inspect existing volume layout", error);
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
                        VolumeButtonInjector.inject(chain.getThisObject());
                        return result;
                    });
            installExpandedHook(layoutClass);
            installStyleHook(layoutClass, "updateResources");
            installStyleHook(layoutClass, "onMaterialModeChanged");
            installCollapsedPreparationHook(layoutClass);
            installExpandedHeightHook(layoutClass.getClassLoader());
            installNativeAnimationHooks(layoutClass.getClassLoader());
            Log.i(TAG, "volume layout hook installed loader=" + layoutClass.getClassLoader());
            return true;
        } catch (Throwable error) {
            hookedLayouts.remove(layoutClass);
            Log.e(TAG, "failed to hook MiuiRingerModeLayout", error);
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
                                Log.e(TAG, "failed to extend native ringer animation delay", error);
                            }
                        }
                        return result;
                    });
            Log.i(TAG, "native show/hide animation hook installed");
        } catch (Throwable error) {
            Log.e(TAG, "failed to hook native show/hide animation", error);
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
            Log.i(TAG, "native expand/collapse animation hook installed");
        } catch (Throwable error) {
            Log.e(TAG, "failed to hook native expand/collapse animation", error);
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
                        if (result instanceof Integer height
                                && Boolean.TRUE.equals(chain.getArg(1))
                                && Boolean.TRUE.equals(chain.getArg(2))) {
                            return height + VolumeButtonInjector.expandedHeightExtra();
                        }
                        return result;
                    });
            Log.i(TAG, "expanded volume background height hook installed");
        } catch (Throwable error) {
            Log.e(TAG, "failed to hook expanded volume background height", error);
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
            Log.e(TAG, "failed to hook native volume expansion", error);
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
                        return result;
                    });
        } catch (Throwable error) {
            Log.e(TAG, "failed to hook native style refresh: " + methodName, error);
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
            Log.e(TAG, "failed to hook collapsed blur preparation", error);
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
            Log.i(TAG, "volume plugin found; ClassLoader hook removed");
        }
    }
}
