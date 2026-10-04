package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行诊断：把「功能到底有没有生效、没生效是因为什么」记录下来，供设置页查看。
 *
 * 这里刻意不照搬参考模块那种「每个混淆类/方法一行」的清单：
 * 不同 ROM、不同版本、是否装了第三方耳机模块，都会让具体类和方法的形状变化，
 * 列一堆方法名既看不懂也容易过期。所以只记录我们自己关心的结果：
 * 作用域有没有进来、插件有没有定位到、钩子有没有挂上、运行时的判定结论与原因。
 */
final class HookDiagnostics {
    enum State {
        /** 已经就绪。 */
        OK,
        /** 还没到触发的时机（例如插件未加载、没有连接耳机）。 */
        WAITING,
        /** 当前环境不适用（例如这个 ROM 没有对应入口，或功能被设置关闭）。 */
        MISSING,
        /** 该挂上但失败了。 */
        FAILED
    }

    private static final class Item {
        final String id;
        String name = "";
        String detail = "";
        State state = State.WAITING;
        int count;

        Item(String id) {
            this.id = id;
        }
    }

    private static final Map<String, Item> ITEMS = new LinkedHashMap<>();
    private static volatile String versionName = "";
    private static volatile long versionCode;

    private HookDiagnostics() {
    }

    static synchronized void attachContext(Context context) {
        if (context == null || !versionName.isEmpty()) {
            return;
        }
        try {
            Context application = context.getApplicationContext();
            Context source = application == null ? context : application;
            PackageInfo info = source.getPackageManager()
                    .getPackageInfo(source.getPackageName(), 0);
            versionName = info.versionName == null ? "" : info.versionName;
            versionCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode()
                    : info.versionCode;
        } catch (Throwable error) {
            HookLog.w("failed to read the module version for diagnostics", error);
        }
    }

    static void ok(String id, String name, String detail) {
        record(id, name, State.OK, detail);
    }

    static void waiting(String id, String name, String detail) {
        record(id, name, State.WAITING, detail);
    }

    static void missing(String id, String name, String detail) {
        record(id, name, State.MISSING, detail);
    }

    static void failed(String id, String name, Throwable error) {
        String detail = error == null
                ? "挂载失败"
                : error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : ": " + error.getMessage());
        record(id, name, State.FAILED, detail);
    }

    static synchronized void record(String id, String name, State state, String detail) {
        Item item = ITEMS.get(id);
        if (item == null) {
            item = new Item(id);
            ITEMS.put(id, item);
        }
        if (name != null && !name.isEmpty()) {
            item.name = name;
        }
        item.state = state;
        if (detail != null) {
            item.detail = detail;
        }
    }

    /** 命中一次：累加次数并刷新最新结论（不改变就绪状态）。 */
    static synchronized void hit(String id, String name, String detail) {
        Item item = ITEMS.get(id);
        if (item == null) {
            item = new Item(id);
            item.name = name == null ? id : name;
            item.state = State.OK;
            ITEMS.put(id, item);
        }
        item.count++;
        if (detail != null) {
            item.detail = detail;
        }
    }

    static synchronized String reportJson(String processName) {
        try {
            JSONObject root = new JSONObject();
            root.put("process", processName == null ? "" : processName);
            root.put("versionName", versionName);
            root.put("versionCode", versionCode);
            root.put("pid", Process.myPid());
            root.put("reportTime", System.currentTimeMillis());
            JSONArray array = new JSONArray();
            for (Item item : new ArrayList<>(ITEMS.values())) {
                JSONObject json = new JSONObject();
                json.put("id", item.id);
                json.put("name", item.name);
                json.put("state", item.state.name());
                json.put("detail", item.detail);
                json.put("count", item.count);
                array.put(json);
            }
            root.put("items", array);
            return root.toString();
        } catch (Throwable error) {
            HookLog.w("failed to build the diagnostics report", error);
            return "";
        }
    }
}
