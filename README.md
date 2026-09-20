# WeType Plus

给**微信输入法**在大屏设备（折叠屏展开、横屏平板）上补回它自己关掉的键盘布局能力。

一个 LSPosed 模块，**独立实现**，不含任何第三方项目源码。

---

## 它解决什么

微信输入法按"手机竖屏宽度"设计，到了展开屏 / 横屏上就变成中间一根窄柱子，两侧一大片留白。这不是 bug，是宿主主动加的限制。本模块把限制的**上限**抬高，不写死任何尺寸。

| 开关 | 作用 | 默认 |
|---|---|---|
| **解除键盘宽度上限** | 抬高展开屏 / 横屏下的宽度上限。键盘自带的尺寸调节滑块**仍然可用**，100% 现在等于整屏宽，而不是一根手机宽的柱子 | 开 |
| **强开单手模式** | 宿主在展开屏上直接禁用了单手模式（判断链里写死了「非展开屏」）。开启后强制生效 | 开 |
| **左右留白联动** | 拖动一侧留白时，另一侧自动跟同，键盘保持居中 | 开 |
| **单手 / 分体互斥** | 开一个自动关另一个 | 开 |

全部默认开启。关掉任意一项，对应行为立刻恢复成宿主默认，不需要重启输入法。

> ⚠️ **强开单手模式的代价**：宿主的单手布局（辅助键位置、字号、最大宽度约束）都是按手机宽度设计的。在展开屏上强行打开，**布局会走形、字号会变小**。这是宿主的设计前提决定的，不是本模块能修的。如果你不接受，把它关掉即可。

---

## 它是怎么做的

**只 hook 方法，不改宿主 APK，不碰签名。**

- **不写死尺寸**。宽度链路里所有尺寸都乘同一个缩放系数，本模块改的是这个系数，所以整个链路的**上限**一起抬高，而调节滑块的全部行程保留。
- **不读宿主静态字段**。解析宿主类一律用 `Class.forName(name, false, …)`（不触发 `<clinit>`）。宿主的静态初始化依赖已 attach 的 `Application`，在安装 hook 阶段触发它会**让输入法进程启动即崩溃**。
- **失败即降级**。每个 hook 入口都 fail-closed：宿主将来改名或改结构，对应功能变成"未生效"，不会把输入法搞崩。

### 两个进程，一条正规通道

模块需要把开关送到 **微信输入法进程**里运行的 hook。跨进程读私有目录是行不通的（uid 不同），所以要有一条通道。本项目用的是一个**导出的 `ContentProvider`**：

```
[设置 App 进程]                        [微信输入法进程]
  MainActivity                           WeTypeLayoutHooks
      │ 写                                   │ 读（1 秒缓存）
      ▼                                      ▼
  SharedPreferences  ──►  SettingsProvider  ──►  content://cn.dsr213.wetypeplus.settings
```

于是设置界面是一个**普通 App 的普通 Activity**，不需要把 UI 注入宿主、不需要 root、不需要共享文件。通道表面只有一个 `query`，返回一行四个 0/1。

---

## 目录结构

```
app/src/main/java/cn/dsr213/wetypeplus/
├── ModuleEntry.kt              libxposed 入口（java_init.list 里注册的就是它）
├── KeyboardSettings.kt         四个开关的数据模型（App / Provider / Hook 三方共用）
├── AppSettings.kt              本 App 自己的持久化
├── bridge/
│   ├── Bridge.kt               全项目唯一接触 libxposed 的文件
│   ├── SettingsProvider.kt     跨进程通道
│   └── ...
├── hook/
│   ├── WeTypeLayoutHooks.kt    真正干活的 hook（宿主逆向的成果都在这）
│   └── HookSettings.kt         hook 侧只读缓存
└── ui/
    ├── MainActivity.kt
    ├── SettingsScreen.kt       Miuix 设置页
    ├── SupportScreen.kt        打赏页
    └── HostRestart.kt
```

---

## 构建

需要 JDK 21 + Android SDK（compileSdk 37）。

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/WeTypePlus-<version>_release.apk`

未签名。自己签一下：

```bash
zipalign -f -p 4 WeTypePlus-1.0.0_release.apk aligned.apk
apksigner sign --ks your.jks --out WeTypePlus-1.0.0-signed.apk aligned.apk
```

## 安装

1. 装上 APK。
2. 在 **LSPosed** 里启用本模块。
3. **作用域勾选「微信输入法」**（`com.tencent.wetype`），其他不用勾。
4. 强制结束微信输入法一次，让它重新加载（设置页里有「重启微信输入法」按钮）。
5. 在设置页里按需开关。

日志标签：`WeTypePlus`

---

## 支持作者

**本模块免费，并且会一直免费。所有功能默认就是开的，不存在解锁一说。**

设置页里那个收款码是纯粹的打赏——不问、不跳、不弹窗，你要翻到「支持作者」里才会看到。打赏不会带来额外功能、优先支持或任何授权。

> 如果你 fork 了这个项目并想换成自己的收款方式：删掉 `app/src/main/res/drawable-nodpi/donate_wechat_qr.png`、改掉 `SupportScreen.kt` 与 `strings.xml` 里的相关文案即可。
>
> 另外提醒一句：**个人收款码放在公开页面上有一定风控风险**（可能被判定为经营性收款），且二维码图片被替换你察觉不到。风险更低的替代是爱发电 / GitHub Sponsors 这类赞助平台链接。

---

## 独立性声明

本项目为**独立实现**：仓库内所有源码均为自行编写，未复制任何第三方项目的代码。

宿主侧的结论全部来自对 `com.tencent.wetype` 安装包的反汇编分析。项目在设想的阶段参考过同类模块的公开讨论，但架构与实现（尤其是跨进程设置通道、hook 入口组织方式）是自选的——例如本项目**不**把设置界面注入宿主进程。

## 免责声明

- 本项目**非官方**，与腾讯公司无关。
- 它只修改**本机**输入法的运行时布局，不修改、不重打包宿主 APK，不触碰其签名。
- 仅供学习与个人使用。使用前请自行评估风险并备份数据。
- 「微信」「微信输入法」商标归腾讯公司所有，本项目仅作指称使用。

## License

MIT，见 [LICENSE](LICENSE)。
