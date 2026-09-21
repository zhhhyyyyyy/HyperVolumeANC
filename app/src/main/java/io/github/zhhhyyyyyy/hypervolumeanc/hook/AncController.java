package io.github.zhhhyyyyyy.hypervolumeanc.hook;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.SystemClock;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

final class AncController {
    static final int MODE_OFF = 0;
    static final int MODE_NOISE_CANCELLING = 1;
    static final int MODE_TRANSPARENCY = 2;

    private static final String TAG = "HyperVolumeANC";
    private static final String SERVICE_ACTION = "miui.bluetooth.mible.BluetoothHeadsetService";
    private static final String SERVICE_PACKAGE = "com.xiaomi.bluetooth";
    private static final String DESCRIPTOR = "com.android.bluetooth.ble.app.IMiuiHeadsetService";
    private static final int TRANSACTION_CHECK_SUPPORT = 1;
    private static final int TRANSACTION_CHANGE_ANC_MODE = 9;
    private static final int TRANSACTION_SET_COMMON_COMMAND = 14;
    private static final int COMMAND_GET_CACHED_INFO = 109;

    private static final Uri AIRPODS_REPOSITORY_URI = Uri.parse(
            "content://com.android.bluetooth.ble.app.headsetdata.provider/airpodsRepository");
    private static final String AIRPODS_REPOSITORY_METHOD = "airpodsRepository";
    private static final String AIRPODS_FEATURE_NOISE_CONTROL = "NoiseControl";
    private static final String AIRPODS_KEY_ANC = "air_anc";
    private static final String AIRPODS_EXTRA_KEY = "extra_key";
    private static final String AIRPODS_EXTRA_VALUE = "extra_value";
    private static final String AIRPODS_EXTRA_FEATURE_SUPPORT = "extra_feature_support";
    private static final String AIRPODS_EXTRA_COMMAND_STATE = "extra_command_state";
    private static final String BLUETOOTH_DEVICE_EXTRA =
            "android.bluetooth.device.extra.DEVICE";

    private static final String SONY_STATE_KEY = "sonypods_state_bus_v1";
    private static final String SONY_COMMAND_ACTION = "dev.sonypods.action.command";
    private static final String SONY_COMMAND_EXTRA = "command";
    private static final String SONY_STRING_EXTRA = "value_string";
    private static final String SONY_SET_NOISE_CONTROL = "set_noise_control";

    private static final String HUAWEI_ANC_ACTION = "chen.action.huaweipods.anc_select";
    private static final String HUAWEI_REFRESH_ACTION = "chen.action.huaweipods.refresh_status";
    private static final String HUAWEI_ROUTE_EXTRA = "device_route";
    private static final String BLUETOOTH_PROCESS_PACKAGE = "com.android.bluetooth";

    private static final String OPPO_ANC_ACTION = "chen.action.oppopods.anc_select";
    private static final String OPPO_REFRESH_ACTION = "chen.action.oppopods.refresh_status";
    private static final String OPPO_STATUS_EXTRA = "status";
    private static final String OPPO_ADDRESS_EXTRA = "address";
    /** Extra understood by the upstream Leaf-lsgtky build; the 1812z fork reconnects always. */
    private static final String OPPO_RECONNECT_EXTRA = "allow_rfcomm_reconnect";
    private static final String OPPO_PODS_PACKAGE = "moe.chenxy.oppopods";
    /** How long a forwarded OppoPods state counts as proof that the module controls a headset. */
    private static final long OPPO_STATE_FRESH_MS = 120_000L;
    private static final long OPPO_RESCAN_INTERVAL_MS = 1_500L;
    private static final long OPPO_PROBE_INTERVAL_MS = 1_500L;
    private static final int OPPO_REPORT_LIMIT = 4;

    private static volatile AncController instance;

    private final Context context;
    private final Handler mainHandler;
    private final Handler worker;
    private final CopyOnWriteArrayList<ButtonBinding> bindings = new CopyOnWriteArrayList<>();
    /**
     * ANC state the OppoPods module reported, keyed by the address it belongs to (empty key
     * for a report without address), oldest entry first. Worker thread only.
     */
    private final LinkedHashMap<String, OppoReport> oppoReports = new LinkedHashMap<>();
    private volatile IBinder service;
    private volatile BluetoothDevice activeDevice;
    private volatile int currentMode = MODE_OFF;
    private volatile boolean binding;
    private volatile DeviceKind activeDeviceKind = DeviceKind.NATIVE;
    private volatile String activeHuaweiRoute;
    private volatile long oppoRescanAt;
    private volatile long oppoProbeAt;
    private volatile boolean oppoPodsVersionLogged;

    static AncController get(Context context) {
        AncController local = instance;
        if (local == null) {
            synchronized (AncController.class) {
                local = instance;
                if (local == null) {
                    Context processContext = resolveProcessContext(context);
                    local = new AncController(processContext);
                    instance = local;
                }
            }
        }
        return local;
    }

    private AncController(Context context) {
        this.context = context;
        this.mainHandler = new Handler(context.getMainLooper());
        HandlerThread thread = new HandlerThread("hypervolumeanc-controller");
        thread.start();
        this.worker = new Handler(thread.getLooper());
        HyperVolumeAncSettings.attach(context, this::applySettings);
        registerBluetoothReceiver();
        Log.i(TAG, "controller initialized context=" + describeContext(context));
    }

    private void registerBluetoothReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(OppoPodsBridge.ACTION_OPPO_STATE);
        try {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable error) {
            Log.w(TAG, "failed to register Bluetooth state receiver", error);
        }
    }

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context receiverContext, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (OppoPodsBridge.ACTION_OPPO_STATE.equals(action)) {
                onOppoState(intent);
                return;
            }
            Log.i(TAG, "Bluetooth state changed action=" + action);
            refresh();
        }
    };

    private void onOppoState(Intent intent) {
        int status = intent == null ? 0 : intent.getIntExtra(OppoPodsBridge.EXTRA_STATUS, 0);
        int mode = oppoStatusToMode(status);
        if (mode < MODE_OFF) {
            return;
        }
        String address = intent.getStringExtra(OppoPodsBridge.EXTRA_ADDRESS);
        worker.post(() -> {
            noteOppoReport(address, mode);
            BluetoothDevice device = activeDevice;
            if (device == null) {
                // The state may arrive before the headset is classified (or after a
                // SystemUI restart), so re-run the device scan instead of dropping it.
                requestOppoRescan();
                return;
            }
            String activeAddress = safeAddress(device);
            if (address != null && !address.isBlank() && activeAddress != null
                    && !address.equalsIgnoreCase(activeAddress)) {
                Log.i(TAG, "ignoring OppoPods state of another device=" + address);
                return;
            }
            Log.i(TAG, "OppoPods state device=" + safeName(device)
                    + " status=" + status + " mode=" + mode);
            publish(device, mode);
        });
    }

    /**
     * Remembers an OppoPods state report so the headset can be recognised by the address the
     * module reported, no matter how the headset is named. Entries older than
     * {@link #OPPO_STATE_FRESH_MS} are dropped, which keeps a removed or disabled module from
     * holding the row open forever.
     */
    private void noteOppoReport(String address, int mode) {
        long now = SystemClock.elapsedRealtime();
        String key = oppoReportKey(address);
        // Re-insert so the most recently reporting headset is the last entry and therefore
        // the last one the size limit below trims.
        oppoReports.remove(key);
        oppoReports.put(key, new OppoReport(mode, now));
        Iterator<Map.Entry<String, OppoReport>> entries = oppoReports.entrySet().iterator();
        while (entries.hasNext()) {
            if (!entries.next().getValue().isFresh(now)) {
                entries.remove();
            }
        }
        while (oppoReports.size() > OPPO_REPORT_LIMIT) {
            Iterator<Map.Entry<String, OppoReport>> oldest = oppoReports.entrySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /**
     * Whether the installed OppoPods build claims this headset. The address reported by the
     * module decides; only a report without any address falls back to the OPPO device name,
     * because such a report cannot be attributed in any other way.
     */
    private boolean isOppoPodsControlled(BluetoothDevice device) {
        long now = SystemClock.elapsedRealtime();
        String address = safeAddress(device);
        if (address != null && !address.isBlank()) {
            OppoReport report = oppoReports.get(oppoReportKey(address));
            if (report != null && report.isFresh(now)) {
                return true;
            }
        }
        OppoReport unaddressed = oppoReports.get("");
        return unaddressed != null && unaddressed.isFresh(now) && isOppoDevice(device);
    }

    private static String oppoReportKey(String address) {
        return address == null || address.isBlank() ? "" : address.toUpperCase(Locale.ROOT);
    }

    /** Applies the most recent state reported for this headset, if the module reported one. */
    private void applyCachedOppoState(BluetoothDevice device) {
        long now = SystemClock.elapsedRealtime();
        String address = safeAddress(device);
        OppoReport report = address == null || address.isBlank()
                ? null
                : oppoReports.get(oppoReportKey(address));
        if (report == null) {
            report = oppoReports.get("");
        }
        if (report == null || !report.isFresh(now)) {
            return;
        }
        currentMode = report.mode;
        Log.i(TAG, "restored OppoPods state mode=" + report.mode
                + " device=" + safeName(device));
    }

    void attach(VolumeButtonInjector.NativeButton button) {
        bindings.add(new ButtonBinding(button));
        button.render(currentMode, isButtonAvailable(), HyperVolumeAncSettings.cycleIncludesOff());
        ensureBound();
    }

    private boolean isButtonAvailable() {
        return activeDevice != null && HyperVolumeAncSettings.moduleEnabled();
    }

    void applySettings() {
        Log.i(TAG, "module options changed enabled=" + HyperVolumeAncSettings.moduleEnabled()
                + " includeOff=" + HyperVolumeAncSettings.cycleIncludesOff());
        publish(activeDevice, currentMode);
    }

    void refresh() {
        ensureBound();
        worker.removeCallbacks(refreshTask);
        worker.post(refreshTask);
    }

    void toggle() {
        Log.i(TAG, "ANC button clicked service=" + (service != null) + " binding=" + binding);
        ensureBound();
        worker.post(() -> {
            IBinder binder = service;
            BluetoothDevice device = resolveSupportedDevice(binder);
            if (binder == null || device == null) {
                Log.w(TAG, "toggle aborted binder=" + (binder != null) + " device=" + safeName(device));
                showToast("未找到已连接的兼容耳机");
                publish(null, MODE_OFF);
                return;
            }

            int mode = readMode(binder, device);
            int target = nextMode(mode, supportsTransparency(),
                    HyperVolumeAncSettings.cycleIncludesOff());
            Log.i(TAG, "changing ANC device=" + safeName(device)
                    + " kind=" + activeDeviceKind + " route=" + activeHuaweiRoute
                    + " current=" + mode + " target=" + target);
            if (changeMode(binder, device, target)) {
                publish(device, target);
                if (HyperVolumeAncSettings.islandNotification()) {
                    ModeIslandNotifier.show(context, target, safeDeviceName(device));
                } else {
                    Log.i(TAG, "mode island skipped: notification option disabled");
                }
                worker.postDelayed(refreshTask, 900);
            } else {
                showToast("切换失败，请查看 LSPosed 日志");
            }
        });
    }

    /**
     * @param includeOff whether the user asked for the noise cancelling / transparency / off cycle.
     */
    private int nextMode(int mode, boolean supportsTransparency, boolean includeOff) {
        if (mode == MODE_NOISE_CANCELLING) {
            return supportsTransparency ? MODE_TRANSPARENCY : MODE_OFF;
        }
        if (mode == MODE_TRANSPARENCY) {
            return includeOff ? MODE_OFF : MODE_NOISE_CANCELLING;
        }
        return MODE_NOISE_CANCELLING;
    }

    private boolean supportsTransparency() {
        if (activeDeviceKind == DeviceKind.HUAWEI) {
            return huaweiSupportsTransparency(activeHuaweiRoute);
        }
        return true;
    }

    void disconnect() {
        worker.post(() -> {
            BluetoothDevice device = activeDevice;
            if (device == null || !isConnected(device)) {
                device = resolveSupportedDevice(service);
            }
            if (device == null) {
                Log.w(TAG, "disconnect aborted: no supported connected headset");
                showToast("未找到已连接的耳机");
                publish(null, MODE_OFF);
                return;
            }

            try {
                Method method = BluetoothDevice.class.getDeclaredMethod("disconnect");
                method.setAccessible(true);
                Object result = method.invoke(device);
                if (Boolean.FALSE.equals(result)) {
                    throw new IllegalStateException("BluetoothDevice.disconnect returned false");
                }
                if (result instanceof Number status && status.intValue() != 0) {
                    throw new IllegalStateException(
                            "BluetoothDevice.disconnect failed with status " + status);
                }
                Log.i(TAG, "Bluetooth headset disconnect requested device=" + safeName(device));
                worker.postDelayed(refreshTask, 700);
                worker.postDelayed(refreshTask, 1600);
            } catch (Throwable error) {
                Log.e(TAG, "failed to disconnect Bluetooth headset " + safeName(device), error);
                showToast("断开连接失败，请查看 LSPosed 日志");
            }
        });
    }

    private final Runnable refreshTask = () -> {
        IBinder binder = service;
        BluetoothDevice device = resolveSupportedDevice(binder);
        publish(device, device == null ? MODE_OFF : readMode(binder, device));
    };

    private void ensureBound() {
        if (service != null || binding) {
            return;
        }
        binding = true;
        mainHandler.post(() -> {
            try {
                Intent intent = new Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE);
                Log.i(TAG, "binding BluetoothHeadsetService context=" + describeContext(context));
                boolean accepted = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                Log.i(TAG, "bindService accepted=" + accepted);
                if (!accepted) {
                    binding = false;
                    Log.e(TAG, "BluetoothHeadsetService bind was rejected");
                    publish(null, MODE_OFF);
                }
            } catch (Throwable error) {
                binding = false;
                Log.e(TAG, "failed to bind BluetoothHeadsetService", error);
                publish(null, MODE_OFF);
            }
        });
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            service = binder;
            String descriptor;
            try {
                descriptor = binder.getInterfaceDescriptor();
            } catch (Throwable error) {
                descriptor = "unavailable:" + error.getClass().getSimpleName();
            }
            Log.i(TAG, "BluetoothHeadsetService connected component=" + name
                    + " descriptor=" + descriptor);
            refresh();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "BluetoothHeadsetService disconnected component=" + name);
            service = null;
            activeDevice = null;
            binding = false;
            publish(null, MODE_OFF);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
            ensureBound();
        }
    };

    private BluetoothDevice resolveSupportedDevice(IBinder binder) {
        if (binder == null) {
            return null;
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "SystemUI does not hold BLUETOOTH_CONNECT");
            return null;
        }
        BluetoothDevice cached = activeDevice;
        if (cached != null && isConnected(cached) && isSupported(binder, cached)) {
            return cached;
        }
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            Set<BluetoothDevice> bonded = adapter == null ? null : adapter.getBondedDevices();
            if (bonded == null) {
                Log.w(TAG, "Bluetooth adapter or bonded device set unavailable");
                return null;
            }
            Log.i(TAG, "checking " + bonded.size() + " bonded Bluetooth devices");
            for (BluetoothDevice device : bonded) {
                boolean connected = isConnected(device);
                Log.i(TAG, "Bluetooth candidate=" + safeName(device) + " connected=" + connected);
                if (connected && isSupported(binder, device)) {
                    Log.i(TAG, "selected ANC device=" + safeName(device));
                    return device;
                }
            }
        } catch (Throwable error) {
            Log.e(TAG, "failed to find connected headset", error);
        }
        return null;
    }

    private boolean isConnected(BluetoothDevice device) {
        try {
            Method method = BluetoothDevice.class.getDeclaredMethod("isConnected");
            method.setAccessible(true);
            return Boolean.TRUE.equals(method.invoke(device));
        } catch (Throwable error) {
            Log.w(TAG, "BluetoothDevice.isConnected unavailable", error);
            return false;
        }
    }

    private boolean isSupported(IBinder binder, BluetoothDevice device) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeTypedObject(device, 0);
            if (!binder.transact(TRANSACTION_CHECK_SUPPORT, data, reply, 0)) {
                return false;
            }
            reply.readException();
            String support = reply.readString();
            Log.i(TAG, "checkSupport device=" + safeName(device) + " result=" + support);
            if (support == null || support.isBlank()) {
                if (isOppoPodsControlled(device)) {
                    return acceptOppoDevice(device);
                }
                if (!supportsAirPodsNoiseControl(device)) {
                    waitForOppoReport(device);
                    return false;
                }
                activeDeviceKind = DeviceKind.APPLE;
                activeHuaweiRoute = null;
                Log.i(TAG, "classified ANC device=" + safeName(device)
                        + " kind=" + activeDeviceKind + " route=null");
                return true;
            }
            if (!supportDeclaresNoiseControl(support)) {
                if (isOppoPodsControlled(device)) {
                    return acceptOppoDevice(device);
                }
                waitForOppoReport(device);
                Log.i(TAG, "device does not declare ANC support=" + safeName(device));
                return false;
            }
            DeviceKind deviceKind = classifyDevice(device, support);
            if (deviceKind == DeviceKind.SONY && !supportsSonyNoiseControl(device)) {
                Log.i(TAG, "Sony device has no noise-control capability=" + safeName(device));
                return false;
            }
            String huaweiRoute = huaweiRoute(device);
            if (deviceKind == DeviceKind.NATIVE && huaweiRoute == null
                    && isKnownHuaweiWithoutNoiseControl(device)) {
                Log.i(TAG, "Huawei device has no noise-control capability=" + safeName(device));
                return false;
            }
            activeDeviceKind = huaweiRoute == null ? deviceKind : DeviceKind.HUAWEI;
            activeHuaweiRoute = huaweiRoute;
            Log.i(TAG, "classified ANC device=" + safeName(device)
                    + " kind=" + activeDeviceKind + " route=" + activeHuaweiRoute);
            if (activeDeviceKind == DeviceKind.OPPO) {
                applyCachedOppoState(device);
                requestOppoStatus();
            }
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "checkSupport failed for " + safeName(device), error);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private int readMode(IBinder binder, BluetoothDevice device) {
        if (binder == null || device == null) {
            return MODE_OFF;
        }
        if (activeDeviceKind == DeviceKind.SONY) {
            return readSonyMode(device);
        }
        if (activeDeviceKind == DeviceKind.HUAWEI) {
            return currentMode;
        }
        if (activeDeviceKind == DeviceKind.OPPO) {
            return currentMode;
        }
        if (activeDeviceKind == DeviceKind.APPLE) {
            return readAirPodsMode(device);
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(COMMAND_GET_CACHED_INFO);
            data.writeString("");
            data.writeTypedObject(device, 0);
            if (!binder.transact(TRANSACTION_SET_COMMON_COMMAND, data, reply, 0)) {
                return currentMode;
            }
            reply.readException();
            String cached = reply.readString();
            Log.i(TAG, "ANC cache device=" + safeName(device) + " value=" + cached);
            if (cached == null || cached.isBlank()) {
                return currentMode;
            }
            String[] fields = cached.split("\\|", -1);
            if (fields.length != 4) {
                Log.i(TAG, "ignoring non-native ANC cache response=" + cached);
                return currentMode;
            }
            String rawMode = fields[0];
            int parsed = Integer.parseInt(rawMode);
            return parsed >= MODE_OFF && parsed <= MODE_TRANSPARENCY ? parsed : currentMode;
        } catch (Throwable error) {
            Log.w(TAG, "failed to read ANC cache for " + safeName(device), error);
            return currentMode;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean changeMode(IBinder binder, BluetoothDevice device, int mode) {
        if (activeDeviceKind == DeviceKind.SONY) {
            return changeSonyMode(device, mode);
        }
        if (activeDeviceKind == DeviceKind.HUAWEI && activeHuaweiRoute != null) {
            return changeHuaweiMode(device, mode, activeHuaweiRoute);
        }
        if (activeDeviceKind == DeviceKind.OPPO) {
            return changeOppoMode(device, mode);
        }
        if (activeDeviceKind == DeviceKind.APPLE) {
            return changeAirPodsMode(device, mode);
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(mode);
            data.writeTypedObject(device, 0);
            if (!binder.transact(TRANSACTION_CHANGE_ANC_MODE, data, reply, 0)) {
                Log.e(TAG, "changeAncMode transact returned false device=" + safeName(device)
                        + " mode=" + mode);
                return false;
            }
            reply.readException();
            Log.i(TAG, "ANC mode changed device=" + safeName(device) + " mode=" + mode);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "changeAncMode failed for " + safeName(device), error);
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void publish(BluetoothDevice device, int mode) {
        activeDevice = device;
        currentMode = mode;
        boolean available = device != null && HyperVolumeAncSettings.moduleEnabled();
        boolean includeOff = HyperVolumeAncSettings.cycleIncludesOff();
        mainHandler.post(() -> {
            Iterator<ButtonBinding> iterator = bindings.iterator();
            while (iterator.hasNext()) {
                ButtonBinding binding = iterator.next();
                VolumeButtonInjector.NativeButton button = binding.button.get();
                if (button != null && button.isAlive()) {
                    button.render(mode, available, includeOff);
                } else {
                    bindings.remove(binding);
                }
            }
        });
    }

    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private String safeName(BluetoothDevice device) {
        if (device == null) {
            return "null";
        }
        try {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return "permission-denied";
            }
            return device.getName() + "(" + device.getAddress() + ")";
        } catch (Throwable ignored) {
            return String.valueOf(device);
        }
    }

    private DeviceKind classifyDevice(BluetoothDevice device, String support) {
        String address = safeAddress(device);
        if (address != null && support.regionMatches(true, 0, address + ",", 0,
                address.length() + 1)) {
            return DeviceKind.SONY;
        }
        if (huaweiRoute(device) != null) {
            return DeviceKind.HUAWEI;
        }
        if (isOppoDevice(device)) {
            return DeviceKind.OPPO;
        }
        return DeviceKind.NATIVE;
    }

    /**
     * OPPO headsets are driven by whichever OppoPods build is installed; the upstream
     * Leaf-lsgtky project and the 1812z fork share the package name and the
     * {@code chen.action.oppopods.*} broadcast interface.
     */
    private boolean isOppoDevice(BluetoothDevice device) {
        String normalized = normalizedDeviceName(device);
        return normalized != null && normalized.contains("oppo");
    }

    /**
     * Handles OPPO headsets when the MIUI support string stays empty.
     *
     * <p>The upstream Leaf-lsgtky build does not fake {@code checkSupport}, so the usual ANC
     * bit test never passes for OPPO headsets there and this method takes over: the headset is
     * accepted as soon as the module reports ANC state for it. The 1812z fork does fake the
     * support string, but also answers these reports, so both builds share one code path.
     *
     * <p>The headset name is not the gate: it can be renamed, and the module reports state
     * keyed by address. Waiting instead of guessing means a headset the module does not
     * control stays without a row, even when it is called "OPPO something".
     */
    private boolean acceptOppoDevice(BluetoothDevice device) {
        activeDeviceKind = DeviceKind.OPPO;
        activeHuaweiRoute = null;
        applyCachedOppoState(device);
        Log.i(TAG, "classified ANC device=" + safeName(device)
                + " kind=OPPO route=null source=oppopods-report");
        return true;
    }

    /**
     * Asks the module for a status report when a headset declares no ANC capability of its
     * own. The answer arrives as a broadcast and re-runs the scan, which then accepts the
     * headset above; anything else leaves the row hidden.
     */
    private void waitForOppoReport(BluetoothDevice device) {
        requestOppoStatus();
        logOppoPodsModule();
        Log.i(TAG, "no OppoPods report for " + safeName(device) + " yet, ANC row stays hidden");
    }

    /**
     * Re-runs the headset scan after an OppoPods state arrived too early to be matched.
     * Throttled so a module that keeps reporting state without a usable device cannot
     * turn the scan into a loop.
     */
    private void requestOppoRescan() {
        long now = SystemClock.elapsedRealtime();
        if (now - oppoRescanAt < OPPO_RESCAN_INTERVAL_MS) {
            return;
        }
        oppoRescanAt = now;
        refresh();
    }

    /** Best-effort log of which OppoPods build is installed; both share the package name. */
    private void logOppoPodsModule() {
        if (oppoPodsVersionLogged) {
            return;
        }
        oppoPodsVersionLogged = true;
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(OPPO_PODS_PACKAGE, 0);
            Log.i(TAG, "OppoPods module installed version=" + info.versionName
                    + " code=" + info.versionCode);
        } catch (Throwable error) {
            Log.i(TAG, "OppoPods module not visible to this process (" + error.getClass()
                    .getSimpleName() + ")");
        }
    }

    private boolean supportDeclaresNoiseControl(String support) {
        String[] fields = support.split(",", -1);
        return fields.length == 2
                && fields[1].length() == 24
                && fields[1].charAt(16) == '1';
    }

    private boolean supportsSonyNoiseControl(BluetoothDevice device) {
        Bundle snapshot = readSonySnapshot(device);
        return snapshot != null && snapshot.getBoolean("supports_noise_control", false);
    }

    private int readSonyMode(BluetoothDevice device) {
        Bundle snapshot = readSonySnapshot(device);
        if (snapshot == null) {
            return currentMode;
        }
        String mode = snapshot.getString("nc_mode");
        int parsed = switch (mode == null ? "" : mode) {
            case "NOISE_CANCELLING" -> MODE_NOISE_CANCELLING;
            case "AMBIENT_SOUND" -> MODE_TRANSPARENCY;
            case "OFF" -> MODE_OFF;
            default -> currentMode;
        };
        Log.i(TAG, "SonyPods state mode=" + mode + " parsed=" + parsed);
        return parsed;
    }

    private Bundle readSonySnapshot(BluetoothDevice device) {
        String encoded;
        try {
            encoded = Settings.Global.getString(context.getContentResolver(), SONY_STATE_KEY);
        } catch (Throwable error) {
            Log.w(TAG, "failed to read SonyPods state", error);
            return null;
        }
        if (encoded == null || encoded.isBlank()) {
            return null;
        }

        Parcel parcel = Parcel.obtain();
        try {
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            Bundle envelope = parcel.readBundle(AncController.class.getClassLoader());
            Bundle snapshot = envelope == null ? null : envelope.getBundle("sony_state");
            if (snapshot == null || !snapshot.getBoolean("connected", false)) {
                return null;
            }
            String stateAddress = snapshot.getString("device_address");
            String deviceAddress = safeAddress(device);
            if (stateAddress != null && deviceAddress != null
                    && !stateAddress.equalsIgnoreCase(deviceAddress)) {
                Log.w(TAG, "SonyPods state belongs to another device=" + stateAddress);
                return null;
            }
            return snapshot;
        } catch (Throwable error) {
            Log.w(TAG, "failed to decode SonyPods state", error);
            return null;
        } finally {
            parcel.recycle();
        }
    }

    private boolean changeSonyMode(BluetoothDevice device, int mode) {
        String value = mode == MODE_TRANSPARENCY ? "AMBIENT_SOUND"
                : mode == MODE_NOISE_CANCELLING ? "NOISE_CANCELLING" : "OFF";
        try {
            Intent intent = new Intent(SONY_COMMAND_ACTION)
                    .setPackage(BLUETOOTH_PROCESS_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(SONY_COMMAND_EXTRA, SONY_SET_NOISE_CONTROL)
                    .putExtra(SONY_STRING_EXTRA, value);
            context.sendBroadcast(intent);
            Log.i(TAG, "SonyPods ANC command device=" + safeName(device) + " value=" + value);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "SonyPods ANC command failed for " + safeName(device), error);
            return false;
        }
    }

    private boolean changeHuaweiMode(BluetoothDevice device, int mode, String route) {
        int status = mode == MODE_TRANSPARENCY ? 3
                : mode == MODE_NOISE_CANCELLING ? 2 : 1;
        try {
            Intent intent = new Intent(HUAWEI_ANC_ACTION)
                    .setPackage(BLUETOOTH_PROCESS_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("status", status)
                    .putExtra("address", safeAddress(device))
                    .putExtra("device_name", safeDeviceName(device))
                    .putExtra(HUAWEI_ROUTE_EXTRA, route);
            context.sendBroadcast(intent);
            context.sendBroadcast(new Intent(HUAWEI_REFRESH_ACTION)
                    .setPackage(BLUETOOTH_PROCESS_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("address", safeAddress(device))
                    .putExtra("device_name", safeDeviceName(device))
                    .putExtra(HUAWEI_ROUTE_EXTRA, route));
            Log.i(TAG, "HuaweiPods ANC command device=" + safeName(device)
                    + " route=" + route + " status=" + status);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "HuaweiPods ANC command failed for " + safeName(device), error);
            return false;
        }
    }

    /**
     * OPPO headsets are driven through the OppoPods module running in the Bluetooth process.
     * Both forks listen for this action and expect the same {@code status} extra, which is
     * also why no fork-specific code is needed for the switch itself.
     */
    private boolean changeOppoMode(BluetoothDevice device, int mode) {
        int status = modeToOppoStatus(mode);
        try {
            context.sendBroadcast(new Intent(OPPO_ANC_ACTION)
                    .setPackage(BLUETOOTH_PROCESS_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(OPPO_STATUS_EXTRA, status)
                    .putExtra(OPPO_ADDRESS_EXTRA, safeAddress(device)));
            Log.i(TAG, "OppoPods ANC command device=" + safeName(device)
                    + " mode=" + mode + " status=" + status);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "OppoPods ANC command failed for " + safeName(device), error);
            return false;
        }
    }

    private void requestOppoStatus() {
        long now = SystemClock.elapsedRealtime();
        if (now - oppoProbeAt < OPPO_PROBE_INTERVAL_MS) {
            return;
        }
        oppoProbeAt = now;
        try {
            context.sendBroadcast(new Intent(OPPO_REFRESH_ACTION)
                    .setPackage(BLUETOOTH_PROCESS_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    // The 1812z fork always reconnects here; the upstream Leaf-lsgtky build
                    // only does when asked, and without it a dropped link answers nothing.
                    .putExtra(OPPO_RECONNECT_EXTRA, true));
            Log.i(TAG, "requested OppoPods status refresh");
        } catch (Throwable error) {
            Log.w(TAG, "failed to request OppoPods status", error);
        }
    }

    /**
     * OPPO status codes: 1=off, 2=noise cancelling, 3=transparency, 4=adaptive and 5..8 for
     * the smart/light/medium/deep cancellation levels only the 1812z fork reports. The volume
     * panel has no separate entry for those, so they all render as noise cancelling.
     */
    static int oppoStatusToMode(int status) {
        return switch (status) {
            case 1 -> MODE_OFF;
            case 2, 4, 5, 6, 7, 8 -> MODE_NOISE_CANCELLING;
            case 3 -> MODE_TRANSPARENCY;
            default -> -1;
        };
    }

    static int modeToOppoStatus(int mode) {
        return switch (mode) {
            case MODE_NOISE_CANCELLING -> 2;
            case MODE_TRANSPARENCY -> 3;
            default -> 1;
        };
    }

    private boolean supportsAirPodsNoiseControl(BluetoothDevice device) {
        Bundle request = airPodsRequest(device, AIRPODS_FEATURE_NOISE_CONTROL, null);
        try {
            Bundle response = context.getContentResolver().call(
                    AIRPODS_REPOSITORY_URI,
                    AIRPODS_REPOSITORY_METHOD,
                    "check_feature_support",
                    request);
            boolean supported = response != null
                    && response.getBoolean(AIRPODS_EXTRA_FEATURE_SUPPORT, false);
            Log.i(TAG, "AirPods NoiseControl support device=" + safeName(device)
                    + " supported=" + supported);
            return supported;
        } catch (Throwable error) {
            Log.w(TAG, "failed to check AirPods NoiseControl support for "
                    + safeName(device), error);
            return false;
        }
    }

    private int readAirPodsMode(BluetoothDevice device) {
        Bundle request = airPodsRequest(device, AIRPODS_KEY_ANC, null);
        try {
            Bundle response = context.getContentResolver().call(
                    AIRPODS_REPOSITORY_URI,
                    AIRPODS_REPOSITORY_METHOD,
                    "get_state",
                    request);
            String value = response == null ? null : response.getString(AIRPODS_EXTRA_VALUE);
            int parsed = switch (value == null ? "" : value) {
                case "02" -> MODE_NOISE_CANCELLING;
                case "03" -> MODE_TRANSPARENCY;
                case "01" -> MODE_OFF;
                default -> currentMode;
            };
            Log.i(TAG, "AirPods ANC state device=" + safeName(device)
                    + " value=" + value + " parsed=" + parsed);
            return parsed;
        } catch (Throwable error) {
            Log.w(TAG, "failed to read AirPods ANC state for " + safeName(device), error);
            return currentMode;
        }
    }

    private boolean changeAirPodsMode(BluetoothDevice device, int mode) {
        String value = mode == MODE_TRANSPARENCY ? "03"
                : mode == MODE_NOISE_CANCELLING ? "02" : "01";
        Bundle request = airPodsRequest(device, AIRPODS_KEY_ANC, value);
        try {
            Bundle response = context.getContentResolver().call(
                    AIRPODS_REPOSITORY_URI,
                    AIRPODS_REPOSITORY_METHOD,
                    "send_command",
                    request);
            int state = response == null
                    ? 0
                    : response.getInt(AIRPODS_EXTRA_COMMAND_STATE, 0);
            Log.i(TAG, "AirPods ANC command device=" + safeName(device)
                    + " value=" + value + " state=" + state);
            return state == 1;
        } catch (Throwable error) {
            Log.e(TAG, "AirPods ANC command failed for " + safeName(device), error);
            return false;
        }
    }

    private Bundle airPodsRequest(BluetoothDevice device, String key, String value) {
        Bundle request = new Bundle();
        request.putString(AIRPODS_EXTRA_KEY, key);
        if (value != null) {
            request.putString(AIRPODS_EXTRA_VALUE, value);
        }
        request.putParcelable(BLUETOOTH_DEVICE_EXTRA, device);
        return request;
    }

    private String huaweiRoute(BluetoothDevice device) {
        String normalized = normalizedDeviceName(device);
        if (normalized == null) {
            return null;
        }
        return switch (normalized) {
            case "huaweifreebuds3", "freebuds3" -> "HUAWEI_FREEBUDS3";
            case "huaweifreebuds4e", "freebuds4e" -> "HUAWEI_FREEBUDS4E";
            case "huaweifreebuds5", "freebuds5" -> "HUAWEI_FREEBUDS5";
            case "huaweifreebuds5i", "freebuds5i" -> "HUAWEI_FREEBUDS5I";
            case "huaweifreebudsse4anc", "freebudsse4anc" -> "HUAWEI_FREEBUDS_SE4_ANC";
            case "huaweifreebuds6i", "freebuds6i" -> "HUAWEI_FREEBUDS6I";
            case "huaweifreebudspro3", "freebudspro3" -> "HUAWEI_FREEBUDS_PRO3";
            case "huaweifreebudspro4", "freebudspro4" -> "HUAWEI_FREEBUDS_PRO4";
            case "huaweifreebudspro5", "freebudspro5" -> "HUAWEI_FREEBUDS_PRO5";
            case "huaweifreebuds7i", "freebuds7i" -> "HUAWEI_FREEBUDS7I";
            default -> null;
        };
    }

    private boolean isKnownHuaweiWithoutNoiseControl(BluetoothDevice device) {
        String normalized = normalizedDeviceName(device);
        if (normalized == null) {
            return false;
        }
        return switch (normalized) {
            case "huaweifreeclip", "freeclip",
                    "huaweifreeclip2", "freeclip2",
                    "huaweifreearc", "freearc",
                    "huaweieyewear", "huaweieyewear3", "eyewear3",
                    "huaweieyewear2", "eyewear2" -> true;
            default -> false;
        };
    }

    private String normalizedDeviceName(BluetoothDevice device) {
        String name = safeDeviceName(device);
        if (name == null) {
            return null;
        }
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < name.length(); index++) {
            char value = Character.toLowerCase(name.charAt(index));
            if (Character.isLetterOrDigit(value)) {
                normalized.append(value);
            }
        }
        return normalized.toString();
    }

    private boolean huaweiSupportsTransparency(String route) {
        if (route == null) {
            return true;
        }
        return switch (route) {
            case "HUAWEI_FREEBUDS5I", "HUAWEI_FREEBUDS_SE4_ANC",
                    "HUAWEI_FREEBUDS6I", "HUAWEI_FREEBUDS_PRO3",
                    "HUAWEI_FREEBUDS_PRO5", "HUAWEI_FREEBUDS7I" -> true;
            default -> false;
        };
    }

    private String safeAddress(BluetoothDevice device) {
        try {
            return device == null ? null : device.getAddress();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String safeDeviceName(BluetoothDevice device) {
        try {
            if (device == null) {
                return null;
            }
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
            String name = device.getName();
            return name == null || name.isBlank() ? device.getAlias() : name;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Context resolveProcessContext(Context fallback) {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            if (application instanceof Context context) {
                Log.i(TAG, "using ActivityThread application context=" + describeContext(context));
                return context;
            }
        } catch (Throwable error) {
            Log.w(TAG, "ActivityThread.currentApplication unavailable", error);
        }

        Context applicationContext = fallback.getApplicationContext();
        if (applicationContext != null) {
            Log.i(TAG, "using getApplicationContext result=" + describeContext(applicationContext));
            return applicationContext;
        }

        Log.w(TAG, "getApplicationContext returned null; using view context="
                + describeContext(fallback));
        return fallback;
    }

    private static String describeContext(Context context) {
        if (context == null) {
            return "null";
        }
        String packageName;
        try {
            packageName = context.getPackageName();
        } catch (Throwable error) {
            packageName = "unavailable";
        }
        return context.getClass().getName() + " package=" + packageName;
    }

    private static final class ButtonBinding {
        final WeakReference<VolumeButtonInjector.NativeButton> button;

        ButtonBinding(VolumeButtonInjector.NativeButton button) {
            this.button = new WeakReference<>(button);
        }
    }

    /** One ANC state the OppoPods module published, together with when it arrived. */
    private static final class OppoReport {
        final int mode;
        final long reportedAt;

        OppoReport(int mode, long reportedAt) {
            this.mode = mode;
            this.reportedAt = reportedAt;
        }

        boolean isFresh(long now) {
            return now - reportedAt <= OPPO_STATE_FRESH_MS;
        }
    }

    private enum DeviceKind {
        NATIVE,
        SONY,
        HUAWEI,
        OPPO,
        APPLE
    }
}
