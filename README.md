# HyperVolumeANC

适用于小米澎湃 OS 4、libxposed API 102 的音量面板 ANC 快捷按钮。

模块包名为 `io.github.hypervolumeanc`（旧版 `io.github.volumeanc` 已改名，请卸载旧版后重新在 LSPosed 中启用新模块）。

首次启动会进入四步引导（欢迎 → 开发者 → 条款 → 完成），文案与链接集中在 `app/src/main/res/values/oobe.xml`，开发者头像使用 `app/src/main/res/drawable-nodpi/developer_avatar.jpg`；关于页里有“重新查看引导”入口。

主界面分为“主页”（模块开关与支持的耳机）、“设置”（LSPosed 状态、语言/主题/底栏样式与更新）和“关于”三个标签页。底部导航栏直接移植自 HyperChanger（Apache-2.0，作者 btm_m）：`app/src/main/java/io/github/hypervolumeanc/nav/` 下的 GlassNavigation / NativeViewBackdrop / LiquidInteraction 等文件为原样搬运，仅改了包名，因此 HyperOS 底栏 / 悬浮底栏 / 液态玻璃底栏三种样式与上游完全一致；依赖 Compose Multiplatform 1.11、Miuix 0.9.3、backdrop 2.0.0，compileSdk 需要 37。关于页与引导页使用 AGSL 运行时着色器绘制动态渐变背景（做法参考 HyperCeiler），渐变位于内容下方并随页面一起滚动。

构建要求：Kotlin 2.3.x + Compose 插件、Android SDK Platform 37（`platforms/android-37`）。

界面文案在 `app/src/main/res/values/strings.xml`（中文）与 `values-en/strings.xml`（英文）中，语言、主题与底栏样式都能在应用内切换；项目链接在 `strings.xml` 的 `project_url` 中填写（留空时显示“尚未发布”）。

## 使用

1. 安装构建生成的 APK。
2. 在 LSPosed 中启用模块，作用域包含“系统界面 (`com.android.systemui`)”和“蓝牙扩展 (`com.xiaomi.bluetooth`)”。
3. 重启手机。
4. 连接支持控制降噪/通透的小米、Sony、Huawei 耳机或 AirPods，唤出音量面板后点击 ANC 按钮切换模式；没有兼容耳机连接时按钮不会显示。
5. 展开音量面板后，ANC 行右侧会显示“断开连接”按钮，可直接断开当前受支持的耳机。
6. 连接 OPPO 耳机时，需要额外安装并启用 [OppoPods](https://github.com/1812z/OppoPods) 模块（作用域包含 `com.android.bluetooth` 与 `com.xiaomi.bluetooth`），本模块通过它的广播接口控制降噪并在音量面板上回显状态。

三种模式在音量面板上分别是：降噪（圆环）、通透（光点）、关闭（自适应图标）。

## 设置

设置页提供以下选项，修改后立即下发给作用域进程，无需重启：

- **启用音量面板按钮**：关闭后音量面板不再注入 ANC 行与断开连接按钮。
- **循环包含关闭模式**：开启后点击音量按钮按“降噪 → 通透 → 关闭”依次循环，关闭时只在“降噪 ⇄ 通透”之间切换；卡片下方用图标标出切换顺序。
- **LSPosed 模块状态**：设置页顶部每次进入都会向系统界面与蓝牙扩展发一次探测，只有模块进程应答才显示“已连接”，停用模块后立即变回“未连接”。
- 右上角**重启作用域**按钮：通过 root 重启 `com.android.systemui` 与 `com.xiaomi.bluetooth` 两个作用域进程。
- 切换降噪或通透时会通过状态栏强通知在超级岛显示“降噪开启 / 通透开启”，左侧为对应的模式图标、右侧为文字（`strong_toast_action` 必须带 `island_param`，否则 SystemUI 会静默丢弃），关闭模式不弹通知。
- 支持设备列表为 Xiaomi（含 Redmi）、Apple、Sony、Huawei、Oppo 各自带品牌矢量图标，图标槽位统一宽度，字标与图形标志视觉体量一致。
- 应用信息中包含关于、开源代码声明、贡献者与 MIUIX 构建说明。

支持的耳机：小米耳机与 AirPods 由系统原生支持；Sony 耳机需要 [SonyPods](https://github.com/Mercury000/SonyPods)，Huawei 耳机需要 [HuaweiPods](https://github.com/Nshpiter/HuaweiPods)，OPPO 耳机需要 [OppoPods](https://github.com/1812z/OppoPods)。

模块调用系统自带的 `com.xiaomi.bluetooth` 耳机服务、AirPods Repository，以及 Sony、Huawei、OPPO 兼容模块公开的广播接口，不自行实现耳机私有协议。

## 构建

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease   # 混淆 + 去除全部日志输出，使用 debug 签名便于直接安装
```
