<div align="center">

<img src="app/src/main/res/drawable-nodpi/ic_launcher_foreground.png" width="180" alt="HyperVolumeANC" />

# HyperVolumeANC

**A handier noise control button for the HyperOS 4 volume panel**

[![Platform](https://img.shields.io/badge/Platform-Android%2017-green?style=flat-square&logo=android)](https://android.com)
[![Framework](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![ROM](https://img.shields.io/badge/ROM-HyperOS%204%20Beta-orange?style=flat-square)](https://hyperos.mi.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](LICENSE)
[![Stars](https://img.shields.io/github/stars/zhhhyyyyyy/HyperVolumeANC?style=flat-square&logo=github&label=Star)](https://github.com/zhhhyyyyyy/HyperVolumeANC/stargazers)

**[简体中文](README.md)** | **English**

</div>

## Support the project

HyperVolumeANC is a free module maintained in spare time. If it makes your volume panel a little handier, a ⭐ star on the [GitHub repository](https://github.com/zhhhyyyyyy/HyperVolumeANC) is the most direct way to say thanks; bug reports are welcome in [Issues](https://github.com/zhhhyyyyyy/HyperVolumeANC/issues) or the [Telegram group](https://t.me/+yCcx0sOHbMQyNTI1).

## Supported versions

Android 17 with Xiaomi HyperOS 4 Beta, using libxposed API 102 (LSPosed).

The module has only been tested on the developer's Xiaomi 17 Pro Max and Redmi K90 Pro Max (HyperOS 4). Other models and international builds may behave differently — feedback is welcome.

## Download

- Grab the latest APK from [Releases](https://github.com/zhhhyyyyyy/HyperVolumeANC/releases/latest), then enable the module in LSPosed and check both scopes below
- “Settings → Update module” reads [update.json](https://raw.githubusercontent.com/zhhhyyyyyy/HyperVolumeANC/main/update.json) from the repository and can jump straight to the download when a new version is out

## Before you start

Enable HyperVolumeANC in [LSPosed](https://github.com/LSPosed/LSPosed) with both scopes checked:

- **System UI** (`com.android.systemui`) — draws the ANC row in the volume panel
- **Bluetooth extension** (`com.xiaomi.bluetooth`) — relays headset state and broadcasts

Restart the scopes afterwards (requires root). The app's “Settings → Module status” shows whether both scopes are connected.

The module only uses the built-in headset service and the public broadcast interfaces of the supported companion modules. It does not implement any headset protocol itself.

Sony, Huawei and OPPO headsets require the corresponding third-party module; this module does not provide that compatibility on its own.

Headsets that only toggle noise cancelling without transparency (for example HUAWEI FreeBuds 5) will just switch noise cancelling on and off.

## Features

- **Volume panel button** — a third instance button next to silent and DND for switching headset noise control
- **Three states** — noise cancelling, transparency and off, with a choice between a two state (NC ⇄ transparency) and a three state (NC → transparency → off) cycle
- **Matching icons** — ring for noise cancelling, dots for transparency, adaptive glyph for off, using the same palette as the rest of the volume panel
- **Disconnect** — expanding the headset menu in the volume panel lets you disconnect the connected headset
- **Focus notification** — switching to noise cancelling or transparency shows a status bar strong toast with the matching icon; switching off stays silent. Can be turned off in Settings
- **Hide launcher icon** — removes the icon from the launcher; the app stays reachable through the module settings entry of your module manager
- **Module status check** — the settings page probes both scopes and reports live whether the module is loaded
- **Restart scopes** — restart both scoped processes from the top right corner, no phone reboot needed
- **Appearance** — language (system / 简体中文 / English), theme (light / dark / system) and bottom bar style (HyperOS bar / floating bar / liquid glass bar)
- **Update check** — check for new versions from the settings page and jump straight to the download when one is available

## Supported headsets

| Brand | Support | Required module |
| --- | --- | --- |
| Xiaomi (incl. Redmi) | Native | none |
| Apple earbuds | Native | none |
| Sony | Third party module | [SonyPods](https://github.com/Mercury000/SonyPods) |
| Huawei | Third party module | [HuaweiPods](https://github.com/Nshpiter/HuaweiPods) |
| OPPO | Third party module | [OppoPods](https://github.com/Leaf-lsgtky/OppoPods) (Leaf-lsgtky) or the [1812z fork](https://github.com/1812z/OppoPods) |

OppoPods is maintained by Leaf-lsgtky and 1812z is a fork of it. Both speak the same `chen.action.oppopods.*` broadcast interface and share the package name `moe.chenxy.oppopods`, so only one of them can be installed at a time and this module works with either:

- The **1812z fork** fakes MIUI headset support inside the Bluetooth extension process, so `checkSupport` already returns the support string with the noise control bit and the headset is accepted right away, exactly as before.
- The **upstream Leaf-lsgtky build** does not fake that string and only publishes headset state, so the headset is accepted once the module reports state for it. That decision uses the headset address carried by the broadcast, not the device name, so renamed earbuds are still recognised.

In both cases, if the module has not reported state within the last two minutes (for example right after a SystemUI restart), the row shows up one step later on the first volume panel open while a fresh state is requested; after that it is immediate again.

## Interface

The app has three tabs, and the bottom bar style can be switched in Settings:

- **Home** — module switches, cycle option with a mode illustration, supported headsets; the OPPO row opens a picker for either author's OppoPods project
- **Settings** — LSPosed connection status, module behaviour (noise control notification, hide launcher icon), language / theme / bottom bar style, update check
- **About** — big icon with an animated gradient, feature description, a note from the developer, project link, open source notices and contributors

The first launch runs a four step guide (welcome → developer → terms → done); it can be replayed from the About tab.

## Build

Requirements:

- JDK 17
- Android SDK Platform 37 (`compileSdk = 37`)
- Kotlin 2.3.x with the Compose plugin

```powershell
.\gradlew.bat assembleDebug     # keeps logging, useful for troubleshooting
.\gradlew.bat assembleRelease   # R8 shrinking with all log calls stripped
```

Dependencies: Compose Multiplatform 1.11, Miuix 0.9.3, AndroidLiquidGlass (`io.github.kyant0:backdrop` / `shapes`) and libxposed API 102.

## Project layout

```
app/src/main/java/io/github/hypervolumeanc/
├── hook/          volume panel injection, headset control, focus notification
├── nav/           bottom navigation bar, ported from HyperChanger (Apache-2.0), package renamed only
├── OobeActivity   first run guide
├── AboutPage      about tab
└── MainActivity   home / settings / about tabs
```

Starting with 1.7.2 the version code is date based: `versionCode` = year, month, day and hour (`YYMMDDHH`, last two digits of the year — 2026-09-21 at 20:00 is `26092120`) instead of a running number. The release tag in the LSPosed repository uses `versionCode-versionName`, for example `26092120-1.7.2`.

The `update.json` in the repository root is the feed used by the in-app update check — bump `VersionCode`, `VersionName`, `ReleaseNoteURL`, `APKURL` and `APKSize` together when publishing a new version.

## Community

- Telegram group: [HyperVolumeANC](https://t.me/+yCcx0sOHbMQyNTI1)
- Bug reports: [GitHub Issues](https://github.com/zhhhyyyyyy/HyperVolumeANC/issues)

Please make sure the module is enabled in LSPosed with both scopes checked, and attach the `HyperVolumeANC` lines from the LSPosed log — it makes debugging much faster.

## Credits

- [OpenAI Codex](https://openai.com/codex) — wrote most of the code and UI of this module
- [Claude](https://claude.ai) (Anthropic) — code and copy assistance
- [HyperChanger](https://github.com/ColdP/HyperChanger) (btm_m) — the three bottom bar styles are taken directly from this project, Apache-2.0
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) (Kyant0) — glass material rendering for the bar
- [MIUIX](https://github.com/compose-miuix-ui/miuix) — HyperOS style component library
- [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) — reference for the about page gradient and the onboarding layout
- [OppoPods](https://github.com/Leaf-lsgtky/OppoPods) (Leaf-lsgtky), the [1812z fork](https://github.com/1812z/OppoPods), [HuaweiPods](https://github.com/Nshpiter/HuaweiPods) (Nshpiter), [SonyPods](https://github.com/Mercury000/SonyPods) (Mercury000)
- [LibrePods](https://github.com/kavishdevar/librepods) (kavishdevar) — listening mode icon reference

See [NOTICE](NOTICE) for the full third party notices.

## License

[Apache License 2.0](LICENSE).

## Disclaimer

This module changes how the system UI and Bluetooth related components look and behave through the Xposed API, and is only adapted and tested on Xiaomi HyperOS 4. Flashing, module conflicts and system updates can all cause misbehaviour; use it at your own risk. Any direct or indirect damage caused by using this module is the responsibility of the user.

This project is not affiliated with Xiaomi, Apple, Sony, Huawei or OPPO.
