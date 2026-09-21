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
 * Runs inside the Bluetooth extension process. The OppoPods module publishes the
 * noise-control state of OPPO headsets to {@code com.xiaomi.bluetooth}, which this
 * bridge forwards to the volume panel running in SystemUI.
 *
 * <p>The upstream Leaf-lsgtky build and the 1812z fork share the package name
 * {@code moe.chenxy.oppopods} and the {@code chen.action.oppopods.*} broadcast interface, so
 * the same receiver serves both. The address carried by the forwarded state is what lets
 * SystemUI accept an OPPO headset without depending on its (renamable) device name.
 */
final class OppoPodsBridge {
    static final String ACTION_OPPO_ANC_CHANGED = "chen.action.oppopods.pods_anc_select";
    static final String ACTION_OPPO_STATE = "io.github.zhhhyyyyyy.hypervolumeanc.action.OPPO_STATE";
    static final String EXTRA_ADDRESS = "address";
    static final String EXTRA_STATUS = "status";
    static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private static final String TAG = "HyperVolumeANC";

    private static volatile boolean installed;
    private static volatile boolean registered;

    private OppoPodsBridge() {
    }

    static void install(XposedModule module) {
        if (installed) {
            return;
        }
        installed = true;
        Context context = currentApplication();
        if (context != null && register(context)) {
            Log.i(TAG, "OppoPods bridge registered with existing application context");
            return;
        }
        hookApplicationAttach(module);
        hookInstrumentation(module);
    }

    private static void hookApplicationAttach(XposedModule module) {
        try {
            Method attach = Application.class.getDeclaredMethod("attach", Context.class);
            module.hook(attach)
                    .setId("hypervolumeanc.v1:oppopods-bridge:application-attach")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (chain.getArg(0) instanceof Context application) {
                            register(application);
                        }
                        return result;
                    });
            Log.i(TAG, "waiting for Bluetooth extension application context (attach)");
        } catch (Throwable error) {
            Log.w(TAG, "application attach hook unavailable", error);
        }
    }

    private static void hookInstrumentation(XposedModule module) {
        try {
            Method callApplicationOnCreate =
                    Instrumentation.class.getDeclaredMethod("callApplicationOnCreate", Application.class);
            module.hook(callApplicationOnCreate)
                    .setId("hypervolumeanc.v1:oppopods-bridge:application-create")
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (chain.getArg(0) instanceof Context application) {
                            register(application);
                        }
                        return chain.proceed();
                    });
            Log.i(TAG, "waiting for Bluetooth extension application context (create)");
        } catch (Throwable error) {
            Log.w(TAG, "instrumentation hook unavailable", error);
        }
    }

    private static synchronized boolean register(Context context) {
        if (registered) {
            return true;
        }
        try {
            IntentFilter filter = new IntentFilter(ACTION_OPPO_ANC_CHANGED);
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            registered = true;
            Log.i(TAG, "OppoPods state bridge registered");
        } catch (Throwable error) {
            Log.e(TAG, "failed to register OppoPods state bridge", error);
        }
        return registered;
    }

    private static final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            if (!ACTION_OPPO_ANC_CHANGED.equals(intent == null ? null : intent.getAction())) {
                return;
            }
            int status = intent.getIntExtra("status", 0);
            int mode = AncController.oppoStatusToMode(status);
            if (mode < AncController.MODE_OFF) {
                return;
            }
            try {
                Intent forward = new Intent(ACTION_OPPO_STATE)
                        .setPackage(SYSTEM_UI_PACKAGE)
                        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        .putExtra(EXTRA_STATUS, status)
                        .putExtra(EXTRA_ADDRESS, intent.getStringExtra("address"));
                receiverContext.sendBroadcast(forward);
                Log.i(TAG, "forwarded OppoPods state status=" + status + " mode=" + mode);
            } catch (Throwable error) {
                Log.w(TAG, "failed to forward OppoPods state", error);
            }
        }
    };

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
