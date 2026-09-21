<div align="center">

<img src="app/src/main/res/drawable-nodpi/ic_launcher_foreground.png" width="180" alt="HyperVolumeANC" />

# HyperVolumeANC

**给澎湃 OS 4 的音量面板加一个更顺手的降噪按钮**

[![Platform](https://img.shields.io/badge/Platform-Android%2017-green?style=flat-square&logo=android)](https://android.com)
[![Framework](https://img.shields.io/badge/Framework-LSPosed-blueviolet?style=flat-square)](https://github.com/LSPosed/LSPosed)
[![ROM](https://img.shields.io/badge/ROM-澎湃OS%204%20Beta-orange?style=flat-square)](https://hyperos.mi.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue?style=flat-square)](LICENSE)
[![Stars](https://img.shields.io/github/stars/zhhhyyyyyy/HyperVolumeANC?style=flat-square&logo=github&label=Star)](https://github.com/zhhhyyyyyy/HyperVolumeANC/stargazers)

**简体中文** | **[English](README_EN.md)**

</div>

## 当前支持的版本

Android 17 的小米澎湃 OS 4 Beta，模块基于 libxposed API 102（LSPosed）。

目前仅在开发者的小米 17 Pro Max 与红米 K90 Pro Max（小米澎湃 OS 4）上做过完整测试，其它机型与国外地区版本可能存在差异，欢迎反馈。

## 下载

- 从 [Releases](https://github.com/zhhhyyyyyy/HyperVolumeANC/releases/latest) 下载最新 APK，安装后在 LSPosed 中启用模块，并勾选下面两个作用域
- 应用内「设置 → 更新模块」会读取仓库里的 [update.json](https://raw.githubusercontent.com/zhhhyyyyyy/HyperVolumeANC/main/update.json) 检查新版本，发现更新可以直接跳转下载

## 使用前说明

请在 [LSPosed](https://github.com/LSPosed/LSPosed) 中启用 HyperVolumeANC，作用域需要同时勾选：

- **系统界面**（`com.android.systemui`）—— 音量面板里的 ANC 行由它显示
- **蓝牙扩展**（`com.xiaomi.bluetooth`）—— 耳机状态与广播经由它转发

勾选后重启作用域（需要 Root 权限），App 的「设置 - 模块状态」会显示两个作用域是否都已连接。

本模块只调用系统自带的耳机服务与各兼容模块公开的广播接口，不包含任何耳机私有协议实现。

Sony、Huawei、OPPO 耳机需要额外安装对应的第三方模块，本模块本身不提供这些品牌的兼容能力。

只支持降噪开关、不支持通透的耳机（例如 HUAWEI FreeBuds 5），按钮只会在降噪开启与关闭之间切换。

## 功能

- **音量面板按钮** —— 在静音与勿扰之外新增一个实例按钮，一键切换耳机降噪模式
- **三种状态** —— 降噪、通透、关闭；可自行选择是「降噪 ⇄ 通透」两态循环，还是「降噪 → 通透 → 关闭」三态循环
- **状态图标** —— 降噪是圆环、通透是光点、关闭是自适应图标，与音量面板其余元素同一套配色
- **断开耳机** —— 展开音量面板的耳机菜单后，可直接断开当前连接的耳机
- **超级岛提示** —— 切到降噪或通透时，通过状态栏强通知显示「降噪开启 / 通透开启」与对应图标，切到关闭不打扰
- **模块状态检测** —— 设置页会向两个作用域发探测请求，实时显示是否已连接，而不是沿用旧状态
- **重启作用域** —— 右上角一键重启两个作用域进程，修改或更新后无需重启手机
- **外观选项** —— 语言（跟随系统 / 简体中文 / English）、主题（浅色 / 深色 / 跟随系统）、底部导航栏样式（HyperOS 底栏 / 悬浮底栏 / 液态玻璃底栏）
- **更新检查** —— 设置页可检查新版本，发现新版本后直接跳转下载页

## 支持的耳机

| 品牌 | 支持方式 | 依赖模块 |
| --- | --- | --- |
| Xiaomi（含 Redmi） | 系统原生 | 无需额外模块 |
| Apple 耳机 | 系统原生 | 无需额外模块 |
| Sony 耳机 | 第三方模块 | [SonyPods](https://github.com/Mercury000/SonyPods) |
| Huawei 耳机 | 第三方模块 | [HuaweiPods](https://github.com/Nshpiter/HuaweiPods) |
| OPPO 耳机 | 第三方模块 | [OppoPods](https://github.com/1812z/OppoPods) |

## 界面

应用分为三个标签页，底部导航栏可在设置中切换样式：

- **主页** —— 模块开关、循环方式与模式示意、支持的耳机列表
- **设置** —— LSPosed 连接状态、语言 / 主题 / 底栏样式、更新模块
- **关于** —— 大图标与渐变动效、功能说明、开发者的话、项目链接、开源代码声明与贡献者

首次安装会进入四步引导（欢迎 → 开发者 → 条款 → 完成），「关于」页可以随时重新查看。

## 构建

环境要求：

- JDK 17
- Android SDK Platform 37（`compileSdk = 37`）
- Kotlin 2.3.x 与 Compose 插件

```powershell
.\gradlew.bat assembleDebug     # 带日志，排查问题用
.\gradlew.bat assembleRelease   # R8 混淆并剥离全部日志输出
```

依赖：Compose Multiplatform 1.11、Miuix 0.9.3、AndroidLiquidGlass（`io.github.kyant0:backdrop` / `shapes`）、libxposed API 102。

## 项目结构

```
app/src/main/java/io/github/hypervolumeanc/
├── hook/          音量面板注入、耳机控制、超级岛提示（运行在被注入的进程里）
├── nav/           底部导航栏，移植自 HyperChanger（Apache-2.0），仅改包名
├── OobeActivity   首次启动引导
├── AboutPage      关于页
└── MainActivity   主页 / 设置 / 关于三个标签页
```

模块侧不自行实现耳机协议：小米与 AirPods 走系统接口，Sony、Huawei、OPPO 分别走对应模块公开的广播接口。

根目录的 `update.json` 是应用内更新检查读取的更新源，发布新版本时把 `VersionCode`、`VersionName`、`ReleaseNoteURL`、`APKURL` 与 `APKSize` 一起改掉即可。

## 支持这个项目

HyperVolumeANC 是用业余时间维护的免费模块。如果它让你的音量条顺手了一点，欢迎到 [GitHub 仓库](https://github.com/zhhhyyyyyy/HyperVolumeANC) 点一个 ⭐ Star —— 这是对作者最直接的鼓励；遇到问题也欢迎到 [Issues](https://github.com/zhhhyyyyyy/HyperVolumeANC/issues) 或 [Telegram 群组](https://t.me/+yCcx0sOHbMQyNTI1) 反馈。

## 交流 & 反馈

- Telegram 群组：[HyperVolumeANC](https://t.me/+yCcx0sOHbMQyNTI1)
- 问题反馈：[GitHub Issues](https://github.com/zhhhyyyyyy/HyperVolumeANC/issues)

反馈前请先确认 LSPosed 已启用模块、两个作用域都已勾选，并附上 LSPosed 日志里 `HyperVolumeANC` 相关的行，定位会快很多。

## 感谢

- [HyperChanger](https://github.com/ColdP/HyperChanger)（btm_m）—— 底部导航栏三种样式的实现直接来自该项目，Apache-2.0
- [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（Kyant0）—— 底栏的玻璃质感渲染
- [MIUIX](https://github.com/compose-miuix-ui/miuix) —— HyperOS 风格组件库
- [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) —— 关于页渐变与引导页版式参考
- [OppoPods](https://github.com/1812z/OppoPods)（1812z）、[HuaweiPods](https://github.com/Nshpiter/HuaweiPods)（Nshpiter）、[SonyPods](https://github.com/Mercury000/SonyPods)（Mercury000）
- [LibrePods](https://github.com/kavishdevar/librepods)（kavishdevar）—— 模式图标参考

完整的第三方声明见 [NOTICE](NOTICE)。

## 许可证

[Apache License 2.0](LICENSE)。

## 免责声明

本模块通过 Xposed API 修改系统界面与蓝牙相关的显示和行为，仅在小米澎湃 OS 4 上做过适配与测试。刷机、模块冲突、系统升级都可能造成界面异常或功能失效，请在了解风险的前提下使用；由使用本模块产生的任何直接或间接损失由使用者自行承担。

本项目与小米、Apple、Sony、Huawei、OPPO 及其关联公司均无关联。
