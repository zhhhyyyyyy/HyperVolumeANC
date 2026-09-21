package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Reports "module loaded" to the settings app so it can show whether LSPosed
 * actually injected the module into each scope.
 */
final class HookHeartbeat {
    static final String ACTION_HOOK_ALIVE = "io.github.zhhhyyyyyy.hypervolumeanc.action.HOOK_ALIVE";
    static final String ACTION_PING = "io.github.zhhhyyyyyy.hypervolumeanc.action.PING";
    static final String ACTION_PONG = "io.github.zhhhyyyyyy.hypervolumeanc.action.PONG";
    static final String EXTRA_PROCESS = "process";
    static final String EXTRA_NONCE = "nonce";
    static final String MODULE_PACKAGE = "io.github.zhhhyyyyyy.hypervolumeanc";

    private static final String TAG = "HyperVolumeANC";
    private static volatile boolean reported;
    private static volatile boolean listening;

    private HookHeartbeat() {
    }

    static void install(XposedModule module, String processName) {
        Context context = currentApplication();
        if (context != null) {
            report(context, processName);
            return;
        }
        hookApplicationAttach(module, processName);
        hookInstrumentation(module, processName);
    }

    static void report(Context context, String processName) {
        if (reported) {
            return;
        }
        reported = true;
        send(context, processName);
        listen(context, processName);
    }

    private static void send(Context context, String processName) {
        try {
            context.sendBroadcast(new Intent(ACTION_HOOK_ALIVE)
                    .setPackage(MODULE_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(EXTRA_PROCESS, processName));
            Log.i(TAG, "reported module loaded process=" + processName);
        } catch (Throwable error) {
            Log.w(TAG, "failed to report module state", error);
        }
    }

    /**
     * Answers the settings screen probe, so it can tell whether the module is
     * currently loaded instead of trusting an old report.
     */
    private static void listen(Context context, String processName) {
        if (listening) {
            return;
        }
        listening = true;
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null || !ACTION_PING.equals(intent.getAction())) {
                        return;
                    }
                    long nonce = intent.getLongExtra(EXTRA_NONCE, 0L);
                    try {
                        receiverContext.sendBroadcast(new Intent(ACTION_PONG)
                                .setPackage(MODULE_PACKAGE)
                                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                                .putExtra(EXTRA_PROCESS, processName)
                                .putExtra(EXTRA_NONCE, nonce));
                        Log.i(TAG, "answered status probe process=" + processName);
                    } catch (Throwable error) {
                        Log.w(TAG, "failed to answer status probe", error);
                    }
                }
            }, new IntentFilter(ACTION_PING), Context.RECEIVER_EXPORTED);
        } catch (Throwable error) {
            Log.w(TAG, "failed to listen for status probe", error);
        }
    }

    private static void hookApplicationAttach(XposedModule module, String processName) {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            module.hook(attach)
                    .setId("hypervolumeanc.v1:heartbeat:application-attach")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getArg(0) instanceof Context application) {
                            report(application, processName);
                        }
                        return result;
                    });
        } catch (Throwable error) {
            Log.w(TAG, "heartbeat application attach hook unavailable", error);
        }
    }

    private static void hookInstrumentation(XposedModule module, String processName) {
        try {
            Method callApplicationOnCreate =
                    Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            module.hook(callApplicationOnCreate)
                    .setId("hypervolumeanc.v1:heartbeat:application-create")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (chain.getArg(0) instanceof Context application) {
                            report(application, processName);
                        }
                        return chain.proceed();
                    });
        } catch (Throwable error) {
            Log.w(TAG, "heartbeat instrumentation hook unavailable", error);
        }
    }

    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            return application instanceof Context context ? context : null;
        } catch (Throwable error) {
            return null;
        }
    }
}
