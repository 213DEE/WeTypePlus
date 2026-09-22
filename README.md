# WeType Plus · 微信输入法增强

**为小米 18 Fold 准备** —— 解锁**微信输入法**的最大宽度限制，并强制开启单手模式。

**LSPosed 模块** · 不改宿主 APK · 不碰签名 · 只 hook 方法

[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%2012%2B-green.svg)](#测试环境)
[![Framework](https://img.shields.io/badge/framework-LSPosed%20(libxposed%20API%20102)-orange.svg)](#测试环境)

[中文](#中文) · [English](#english)

---

## 中文

### 它解决什么

以**小米 18 Fold** 为例：展开成一块大屏之后，微信输入法的键盘**宽度被卡死在一个最大值**——屏幕再宽，键盘也只是中间一根窄柱子，两侧留一大片空白。这不是 bug，是宿主主动加的限制：宽度链路里所有尺寸都乘同一个缩放系数，而那个系数的分子取的是手机短边。

本模块做两件宿主自己不肯做的事：

1. **解除键盘最大宽度限制**——抬高那个上限，不写死任何尺寸。所以键盘自带的「尺寸调节」滑块**仍然可用**，而且 100% 现在等于整屏宽，而不是一根手机宽的柱子。
2. **强制开启单手模式**——宿主在展开屏上把单手模式整个禁用了（判断链里写死「非展开屏」）。本模块把它强行打开，让单手键盘在折叠屏展开时也能用。

### 功能

**两个开关**（设置页里可关，默认都开）：

| 开关 | 作用 |
|---|---|
| **解除键盘宽度上限** | 抬高展开屏 / 横屏下的宽度上限。调节滑块的全部行程保留 |
| **强开单手模式** | 宿主在展开屏上直接禁用了单手模式（判断链里写死了「非展开屏」）。开启后强制生效 |

> ⚠️ **强开单手模式的代价**：宿主的单手布局（辅助键位置、字号、最大宽度约束）都是按手机宽度设计的。在展开屏上强行打开，**布局会走形、字号会变小**。这是宿主的设计前提决定的，不是本模块能修的。如果你不接受，把它关掉即可。

### 测试环境

本项目在下列环境完成实机验证：

| 项 | 值 |
|---|---|
| 设备 | Xiaomi 18 Fold（产品代号 `lhasa` / 型号 `2608BPX34C`） |
| 系统 | Android **17**（API 37）· 小米澎湃 OS **OS4.0.11.0.XPNCNXM** · 安全补丁 2026-08-01 · `arm64-v8a` |
| 屏幕 | 外屏 1168 × 1712 @ 440 dpi；内屏 1672 × 2364（展开横屏 2364 × 1672） |
| 宿主 | 微信输入法 `com.tencent.wetype` **3.5.3.56201** |
| 框架 | **LSPosed v2.2.0 (7854)**，运行在 **KernelSU v3.3.0**（Zygisk 启用）· libxposed API **102** |
| 模块构建 | JDK 21.0.2 · Gradle 9.6.0 · Kotlin 2.3.21 · AGP 9.4.0 · compileSdk 37 / minSdk 31 / targetSdk 37 |

宿主的布局逻辑跑在独立的 `:hld` 进程里，装完模块**必须重启一次微信输入法**，否则那个进程里没有模块。

其他机型 / 系统版本未验证。宿主换版后若内部方法改名，对应功能会静默失效（不会崩，也不会把输入法搞挂）。

### 安装

1. 设备需已 root 并装有 LSPosed。
2. 安装 APK。
3. 在 **LSPosed** 里启用本模块。
4. **作用域勾选「微信输入法」**（`com.tencent.wetype`），其他不用勾。
5. 强制结束微信输入法一次，让它重新加载（设置页里有「重启微信输入法」按钮）。
6. 打开模块的设置页按需开关。

> 如果你之前装过 `com.xposed.wetypehook` 这个旧模块，**请先把它关掉**——两个模块 hook 同一批方法，同时开启会重复 hook。

### 日志

模块的日志标签是 **`WeTypePlus`**。排查时看 LSPosed 日志里有没有这几类行：

```
Success: ...          hook 装上了
Adjust b: left,right=218,836 -> 527,527   留白联动（左+右）/2
Panel preview host:  ...                  调节遮罩改写前后
Failed: ...           某个 hook 没装上（不影响其他功能）
```

### 常见问题

**装完没反应？** 微信输入法的键盘逻辑跑在 `:hld` 进程里，这个进程往往在装模块之前就起来了。**强制结束一次微信输入法**再来。

**调节完之后又弹回去了？** 那是宿主在面板重建时用「展开态留白」覆盖了你的值。本模块把那个分支旁路了，如果还出现，看日志里的 `Padding write:` 行。

**键盘在横屏下还是只占 71%？** 宿主的缩放系数在横屏下拿屏幕**短边**当分子，所以横竖屏键盘宽度基本一致。这是上游设计，本模块的「解除宽度上限」抬高的是上限，不改变这个基准。

### 权限与隐私

本应用**只申请两条权限**，都是普通权限，装完即生效、不需要运行时授权：

| 权限 | 用途 |
|---|---|
| `KILL_BACKGROUND_PROCESSES` | 「重启微信输入法」那个按钮，用来让模块在 `:hld` 进程里重新加载 |
| `INTERNET` | **仅用于检查更新**（1.0.26 起）：打开应用时向 GitHub 查一次有没有新版本，你也可以在「关于」里手动查 |

关于第二条，说明白一点：

- **模块的状态上报不走网络。** 组件在微信输入法进程里看到的东西（框架版本、装上了多少 Hook 等）只通过**进程间广播**送到本应用自己的私有存储，**全程不出设备** —— 这条路跟 `INTERNET` 权限没有关系。
- **除了检查更新，本应用不会发起任何网络请求。** 全项目的联网代码只有一处（`UpdateCheck.kt`），它只读 GitHub 的 releases 接口，只为比对版本号和取更新说明。
- **没有统计、没有埋点、没有崩溃上报。** 应用里显示的那些环境信息，只有你自己点「导出日志」才会离开设备。
- ⚠️ 但要说清楚：**有了这条权限，应用在系统看来就是「能联网的应用」**。1.0.25 及更早的版本连这条权限都没有申请过，它是 1.0.26 才加的。

### 支持作者

**关于收费**：Alpha 阶段将始终保持免费——所有功能默认就是开的，不存在解锁一说；不排除将来推出 Beta 或正式版后，部分功能收费的可能。

下面的收款码是纯粹的打赏——不问、不跳、不弹窗，你要翻到设置里的「支持作者」才会看到。打赏不会带来额外功能、优先支持或任何授权。

<img src="docs/donate_wechat_qr.png" width="220" alt="微信打赏收款码">

### 免责声明

- 本项目**非官方**，与腾讯公司无关。
- 它只修改**本机**输入法的运行时布局，不修改、不重打包宿主 APK，不触碰其签名。
- 仅供学习与个人使用。使用前请自行评估风险并备份数据。
- 「微信」「微信输入法」商标归腾讯公司所有，本项目仅作指称使用。

### License

[GNU AGPL-3.0](LICENSE)。改了这个项目并通过网络提供服务，你需要公开你的源码。

---

## English

### What it is

Take the **Xiaomi 18 Fold**. Unfold it and WeType's keyboard is **capped at a maximum width** — no matter how wide the screen gets, the keyboard stays a narrow column in the middle with large blank margins either side. That is not a bug; it is a host-imposed ceiling: every width in the layout chain is multiplied by one scale factor, and that factor is derived from the phone's short edge.

This module does the two things the host refuses to do:

1. **Removes the keyboard's maximum-width cap.** It raises that ceiling and hard-codes no sizes, so WeType's own size adjuster still works and 100% means the full screen width instead of a phone-wide column.
2. **Forces single-hand mode on.** The host disables single-hand mode outright on large screens (the check chain hard-codes "not unfolded"). The module switches it back on, so the single-hand keyboard is usable on an unfolded foldable too.

**LSPosed module. No host APK modification, no repackaging, no signature changes — methods only.**

### Features

**Two switches** (both default to on):

| Switch | What it does |
|---|---|
| **Unlock keyboard width** | Raises the width ceiling on unfolded / landscape screens. The built-in adjuster keeps its full travel |
| **Unlock single-hand mode** | WeType disables single-hand mode outright on large screens (the check chain hard-codes "not unfolded"). This forces it back on |

> ⚠️ **The cost of forcing single-hand mode**: the host's single-hand layout (modifier keys, font size, max width constraints) was designed around phone widths. Forcing it on a large screen **distorts the layout and shrinks the font**. That follows from the host's own design assumptions; this module cannot fix it. Leave the switch off if you don't want it.

### Test environment

Verified on the following setup:

| | |
|---|---|
| Device | Xiaomi 18 Fold (codename `lhasa`, model `2608BPX34C`) |
| OS | Android **17** (API 37) · HyperOS **OS4.0.11.0.XPNCNXM** · security patch 2026-08-01 · `arm64-v8a` |
| Display | Outer 1168 × 1712 @ 440 dpi; inner 1672 × 2364 (landscape 2364 × 1672) |
| Host | WeType `com.tencent.wetype` **3.5.3.56201** |
| Framework | **LSPosed v2.2.0 (7854)** on **KernelSU v3.3.0** (Zygisk enabled) · libxposed API **102** |
| Build | JDK 21.0.2 · Gradle 9.6.0 · Kotlin 2.3.21 · AGP 9.4.0 · compileSdk 37 / minSdk 31 / targetSdk 37 |

WeType runs its keyboard logic in a separate `:hld` process. **You must restart WeType after installing the module**, otherwise that process has no module loaded.

Other devices and OS versions are untested. If a future host build renames the internal methods, the affected feature degrades to "not applied" rather than crashing.

### Install

1. Rooted device with LSPosed installed.
2. Install the APK.
3. Enable the module in **LSPosed**.
4. Set its scope to **WeType** (`com.tencent.wetype`) — nothing else is needed.
5. Force-stop WeType once so it reloads (there is a "Restart WeType" button in the app).
6. Open the module's settings and toggle what you need.

> If you still have the older `com.xposed.wetypehook` module installed, **disable it first**. Both hook the same methods, and running them together double-hooks.

### Permissions and privacy

The app requests exactly two permissions, both normal ones - granted at install time, no runtime prompt:

| Permission | Purpose |
|---|---|
| `KILL_BACKGROUND_PROCESSES` | The "restart WeType" button, which makes the module reload inside the `:hld` process |
| `INTERNET` | **Update checks only** (since 1.0.26): one GitHub request when the app opens, plus whatever you ask for from "Check for updates" in About |

On the second one, plainly:

- **Status reports do not use the network.** What the module sees inside WeType's process - framework version, how many hooks installed - reaches this app over an **in-process broadcast** and lands in its private storage. **Nothing leaves the device.** The `INTERNET` permission plays no part in that path.
- **Apart from the update check, the app makes no network requests at all.** There is exactly one piece of networking code in the project (`UpdateCheck.kt`); it reads the GitHub releases endpoint and nothing else - read-only, and only to compare version numbers and fetch release notes.
- **No analytics, no telemetry, no crash reporting.** Every environment detail shown in the app leaves the device only when you tap "Export log" yourself.
- ⚠️ Stated plainly: **with this permission the app is, as far as the system is concerned, an app that can use the network.** Versions 1.0.25 and earlier never requested it; it arrives in 1.0.26.

### Support

**On pricing**: free throughout the Alpha stage — every feature is on by default, and there is nothing to unlock. Charging for some features after a future Beta or stable release is **not ruled out**.

The QR code below is purely a tip jar. It is buried in Settings → Support, it never pops up, and donating gets you no extra features, no priority support and no licence.

<img src="docs/donate_wechat_qr.png" width="220" alt="WeChat tip QR code">

### Disclaimer

- Unofficial project, not affiliated with Tencent.
- It only changes the runtime layout of the input method **on your own device**. It does not modify, repackage or re-sign the host APK.
- For learning and personal use. Assess the risk yourself and back up your data.
- "微信" / "WeChat" / "微信输入法" / "WeType" are trademarks of Tencent. Used here for reference only.

### License

[GNU AGPL-3.0](LICENSE). If you modify this project and offer it over a network, you must publish your source.
