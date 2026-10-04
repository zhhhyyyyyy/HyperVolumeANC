package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;

/**
 * 探测当前是否有第三方应用正在播放媒体声音。
 *
 * 判定条件与小米声音（com.miui.misound）内部的活跃音源判断保持一致：
 * 1. uid >= 10000（第三方应用）；
 * 2. 排除动态壁纸；
 * 3. 播放状态为 PLAYER_STATE_STARTED（2）；
 * 4. 音频属性 usage == USAGE_MEDIA 或 volumeControlStream == STREAM_MUSIC。
 *
 * 分应用音量入口只在“真的有应用在发声”时才出现在音量条上方，
 * 没有满足条件时不占用音量条空间。
 */
final class MediaPlaybackWatcher {
    private static final String TAG = "HyperVolumeANC";
    private static final String WALLPAPER_PACKAGE = "com.miui.miwallpaper";
    private static final int PLAYER_STATE_STARTED = 2;
    private static final int USAGE_MEDIA = 1;
    private static final int STREAM_MUSIC = 3;

    private static volatile boolean listening;

    private MediaPlaybackWatcher() {
    }

    static boolean hasActiveMediaPlayback(Context context) {
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return false;
            }
            List<AudioPlaybackConfiguration> configurations =
                    audioManager.getActivePlaybackConfigurations();
            if (configurations == null || configurations.isEmpty()) {
                HookLog.i("no active playback configurations reported");
                return false;
            }
            PackageManager packageManager = context.getPackageManager();
            for (AudioPlaybackConfiguration configuration : configurations) {
                if (isActiveMedia(configuration, packageManager)) {
                    return true;
                }
            }
            HookLog.i(configurations.size() + " playback configurations checked, none is media");
            return false;
        } catch (Throwable error) {
            HookLog.w("failed to inspect active playback configurations", error);
            return false;
        }
    }

    private static boolean isActiveMedia(
            AudioPlaybackConfiguration configuration, PackageManager packageManager) {
        try {
            int uid = readInt(configuration, "getClientUid");
            String packageName = packageNameOf(packageManager, uid);
            int playerState = readInt(configuration, "getPlayerState");
            AudioAttributes attributes = configuration.getAudioAttributes();
            int usage = attributes == null ? -1 : attributes.getUsage();
            int stream = attributes == null ? -1 : attributes.getVolumeControlStream();
            boolean media = attributes != null
                    && (usage == USAGE_MEDIA || stream == STREAM_MUSIC);
            HookLog.i("playback uid=" + uid + " pkg=" + packageName + " state=" + playerState
                    + " usage=" + usage + " stream=" + stream + " media=" + media);
            if (uid < 10000) {
                return false;
            }
            if (!TextUtils.isEmpty(packageName) && WALLPAPER_PACKAGE.equals(packageName)) {
                return false;
            }
            if (playerState != PLAYER_STATE_STARTED) {
                return false;
            }
            if (attributes == null) {
                return false;
            }
            return media;
        } catch (Throwable error) {
            HookLog.w("failed to inspect playback configuration", error);
            return false;
        }
    }

    private static String packageNameOf(PackageManager packageManager, int uid) {
        try {
            return packageManager.getNameForUid(uid);
        } catch (Throwable error) {
            return null;
        }
    }

    /** getClientUid() / getPlayerState() 是系统内部接口，需要反射调用。 */
    private static int readInt(AudioPlaybackConfiguration configuration, String method)
            throws Exception {
        try {
            Method declared = configuration.getClass().getDeclaredMethod(method);
            declared.setAccessible(true);
            Object value = declared.invoke(configuration);
            return value instanceof Integer number ? number : 0;
        } catch (NoSuchMethodException error) {
            // 混淆或继承差异时退回公开方法。
            Method inherited = configuration.getClass().getMethod(method);
            inherited.setAccessible(true);
            Object value = inherited.invoke(configuration);
            return value instanceof Integer number ? number : 0;
        }
    }

    /**
     * 面板已经显示、音频才开始/停止播放时也要刷新入口可见性，
     * 否则入口会慢半拍。
     */
    static void listen(Context context, Runnable onChange) {
        if (listening) {
            return;
        }
        listening = true;
        try {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                listening = false;
                return;
            }
            Handler handler = new Handler(Looper.getMainLooper());
            audioManager.registerAudioPlaybackCallback(
                    new AudioManager.AudioPlaybackCallback() {
                        @Override
                        public void onPlaybackConfigChanged(
                                List<AudioPlaybackConfiguration> configurations) {
                            handler.post(onChange);
                        }
                    },
                    handler);
            HookLog.i("watching audio playback changes for the media volume entry");
        } catch (Throwable error) {
            listening = false;
            HookLog.w("failed to watch audio playback changes", error);
        }
    }
}
