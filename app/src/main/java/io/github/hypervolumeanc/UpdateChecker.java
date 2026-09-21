package io.github.hypervolumeanc;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 检查更新：沿用 HyperChanger 的 update.json 字段（VersionCode / VersionName /
 * ReleaseNoteURL / APKURL / APKSize），发布后把地址填到 strings.xml 的 update_url 即可。
 */
final class UpdateChecker {
    static final class Info {
        final long versionCode;
        final String versionName;
        final String releaseNoteUrl;
        final String apkUrl;

        Info(long versionCode, String versionName, String releaseNoteUrl, String apkUrl) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.releaseNoteUrl = releaseNoteUrl;
            this.apkUrl = apkUrl;
        }
    }

    interface Callback {
        void onResult(Info info, String error);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UpdateChecker() {
    }

    static void check(String url, Callback callback) {
        Thread thread = new Thread(() -> {
            Info info = null;
            String error = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(15_000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("User-Agent", "HyperVolumeANC");
                try {
                    int code = connection.getResponseCode();
                    if (code < 200 || code > 299) {
                        throw new IllegalStateException("HTTP " + code);
                    }
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(connection.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            body.append(line);
                        }
                    }
                    JSONObject json = new JSONObject(body.toString());
                    long versionCode = 0L;
                    Object raw = json.opt("VersionCode");
                    if (raw instanceof Number number) {
                        versionCode = number.longValue();
                    } else if (raw != null) {
                        versionCode = Long.parseLong(String.valueOf(raw).trim());
                    }
                    info = new Info(versionCode,
                            json.optString("VersionName", ""),
                            json.optString("ReleaseNoteURL", ""),
                            json.optString("APKURL", ""));
                } finally {
                    connection.disconnect();
                }
            } catch (Throwable throwable) {
                error = throwable.getMessage() == null
                        ? throwable.getClass().getSimpleName()
                        : throwable.getMessage();
            }
            Info result = info;
            String failure = error;
            MAIN.post(() -> callback.onResult(result, failure));
        }, "HyperVolumeANC-update");
        thread.setDaemon(true);
        thread.start();
    }
}
