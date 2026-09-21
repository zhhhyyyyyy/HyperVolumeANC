package io.github.hypervolumeanc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Restarts the module scopes through root, the same way HyperChanger does. */
final class ScopeRestarter {
    private ScopeRestarter() {
    }

    static void restart(Context context, Map<String, String> targets) {
        Context appContext = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            Result root = su("id");
            if (root.exitCode != 0 || !root.output.contains("uid=0")) {
                toast(appContext, appContext.getString(R.string.restart_no_root));
                return;
            }

            List<String> failures = new ArrayList<>();
            Map<String, String> restarted = new LinkedHashMap<>();
            for (Map.Entry<String, String> target : targets.entrySet()) {
                String packageName = target.getKey();
                String label = target.getValue();

                Result path = su("cmd package path " + packageName);
                if (path.exitCode != 0 || !path.output.contains("package:")) {
                    failures.add(appContext.getString(R.string.restart_not_installed, label));
                    continue;
                }
                Result pids = su("pidof " + packageName);
                String processIds = pids.output.trim();
                if (pids.exitCode != 0 || processIds.isEmpty()) {
                    failures.add(appContext.getString(R.string.restart_not_running, label));
                    continue;
                }
                Result kill = su("kill -15 " + processIds);
                if (kill.exitCode != 0) {
                    failures.add(appContext.getString(R.string.restart_kill_failed, label));
                    continue;
                }
                restarted.put(packageName, label);
            }

            if (failures.isEmpty()) {
                toast(appContext, appContext.getString(R.string.restart_success,
                        String.join(", ", restarted.values())));
            } else if (restarted.isEmpty()) {
                toast(appContext, appContext.getString(R.string.restart_failed,
                        String.join("\n", failures)));
            } else {
                toast(appContext, appContext.getString(R.string.restart_partial,
                        restarted.size(), String.join("\n", failures)));
            }
        }, "hypervolumeanc-scope-restart");
        thread.setDaemon(true);
        thread.start();
    }

    private static Result su(String command) {
        try {
            Process process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            return new Result(process.waitFor(), output.toString());
        } catch (Throwable error) {
            return new Result(-1, "");
        }
    }

    private static void toast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(
                () -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    private static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }
}
