package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * 同时写 logcat 与 LSPosed 框架日志的轻量日志器。
 *
 * 音量面板相关的诊断信息走这里，用户只要在 LSPosed 里导出日志就能提供证据，
 * 不用连电脑抓 logcat；release 构建会剥掉 logcat 部分，框架日志仍然保留。
 */
final class HookLog {
    private static final String TAG = "HyperVolumeANC";
    private static volatile XposedModule module;

    private HookLog() {
    }

    static void attach(XposedModule instance) {
        module = instance;
    }

    static void d(String message) {
        write(Log.DEBUG, message, null);
    }

    static void i(String message) {
        write(Log.INFO, message, null);
    }

    static void i(String message, Throwable error) {
        write(Log.INFO, message, error);
    }

    static void w(String message) {
        write(Log.WARN, message, null);
    }

    static void w(String message, Throwable error) {
        write(Log.WARN, message, error);
    }

    static void e(String message) {
        write(Log.ERROR, message, null);
    }

    static void e(String message, Throwable error) {
        write(Log.ERROR, message, error);
    }

    private static void write(int priority, String message, Throwable error) {
        if (error == null) {
            Log.println(priority, TAG, message);
        } else {
            Log.println(priority, TAG, message + "\n" + Log.getStackTraceString(error));
        }
        XposedModule instance = module;
        if (instance == null) {
            return;
        }
        try {
            if (error == null) {
                instance.log(priority, TAG, message);
            } else {
                instance.log(priority, TAG, message, error);
            }
        } catch (Throwable ignored) {
            // 框架日志不可用时只保留 logcat。
        }
    }
}
