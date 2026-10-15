# 星野 · Android 浏览器

面向 Android 8.0+ 的浏览器，包名 `app.xvi.web`。使用 Kotlin 原生界面、系统 WebView 和本地 SQLite；可经本机或局域网的外部 SOCKS5 / HTTP 代理上网。

## 最新更新

### 2026-10-15

#### 修复

- 修复部分网站因安全策略限制而无法下载网页生成文件的问题，并在未指定下载名称时保留文件原名。

完整历史见[更新记录](docs/releases/release-notes.md)。

## 功能

- 底部地址栏、历史与书签联想、网址补全；内置四种搜索引擎，支持自定义搜索地址。默认引擎在设置中选择。
- 多标签、后台打开链接、关闭撤销、最近关闭、普通标签恢复；支持独立会话的无痕浏览。
- 首页书签和最近访问、书签拖动排序与 HTML 导入导出、按日期整理和删除历史。
- 文件与图片下载、进度通知、取消和断点续传；支持网页生成的 `blob:` / `data:` 文件。上传可选择文件、拍照或录像。
- 页内查找、桌面模式、阅读模式（可调背景、字体和行距）、网页翻译、保存网页、打印 / PDF、查看源代码和二维码识别。
- AI 助手：使用自己的 DeepSeek API Key（或其他兼容 OpenAI 接口的服务）总结网页、针对网页提问，以及对选中文字解释、翻译、总结和改写。
- 广告拦截：Adblock Plus 语法的请求拦截与元素隐藏，规则每周更新，官方源不可达时使用 jsDelivr / GitHub 镜像。
- 网页应用：桌面图标或独立窗口打开网站，窗口在最近任务中单独显示，状态栏随网站主题色。
- 网站权限与独立网站设置、浅色 / 深色外观、文字大小、缩放、数据清理、备份恢复和默认浏览器设置。
- 全屏视频与画中画、后台媒体通知与播放控制；可使用外部 SOCKS5 / HTTP 代理，代理断开时自动改用直连并提示。

## 构建与验证

需要 JDK 21、Android SDK Platform 37、Build Tools；脚本测试需要 Node.js。通过 `ANDROID_HOME` 或未纳入 Git 的 `local.properties` 中的 `sdk.dir` 指定 SDK。

日常构建和检查：

```sh
./gradlew :app:assembleDebug
npm test
npm run lint
```

`npm test` 运行页面保护脚本和浏览器单元测试；`npm run lint` 运行 Android Lint。无 npm 依赖需要安装。

默认构建包含 `arm64-v8a` 与 `x86_64`，可追加 `-PwebAbis=arm64-v8a` 只构建手机版本。`npm run build:android:release` 构建仅含 arm64 的 release APK，输出到 `app/build/outputs/apk/release/app-release.apk`；`npm run install:android:release` 安装该版本，可追加 `-- --device <设备序列号>` 指定设备。release 使用本机 `.signing/release.properties` 配置的 PKCS12 密钥（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`）；缺少配置时构建失败。签名文件不进入 Git，请安全备份密钥与密码，后续升级必须使用相同签名。

来源和许可证见[第三方声明](THIRD_PARTY_NOTICES.md)。

### 设备测试

测试覆盖导航、下载、代理、标签保留和无痕隔离，会写入浏览数据、书签和代理配置；无痕用例会在 WebView 能力不足时跳过，真实代理需另行验证。加上 `-PwebIdSuffix=.verify` 构建一份与日常安装并存的副本，再针对它运行：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PwebIdSuffix=.verify
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.xvi.web.verify.test/androidx.test.runner.AndroidJUnitRunner
```

注意：在 Android 16 设备上，`BrowserIntegrationTest` 中两个走 127.0.0.1 本地下载服务器的用例会挂起（原始版本同样如此，与本仓库改动无关），单独运行其余类即可。

## 使用边界

### 代理

浏览器不内置代理核心。在「菜单 → 设置 → 代理」填写本机或局域网代理的地址（如 Clash、v2rayNG、sing-box 的端口）并开启，设置保存在本应用中。

- 地址支持 `socks5://主机:端口` 和 `http://主机:端口`，只写 `主机:端口` 时按 SOCKS5 处理；不支持用户名密码和 `https://` 代理。推荐 SOCKS5：域名由代理解析，检测也能确认对方是 SOCKS5 服务。
- 仅代理应用内流量，不设置系统 VPN 或系统代理。本机、局域网和保留网段直连。地址判断不做 DNS 解析，解析到内网 IP 的普通域名仍走代理。
- 用到网络时才检测代理（SOCKS5 握手，HTTP 只检测端口；连接超时 1.5 秒）。检测结果成功后保留 60 秒、失败后保留 15 秒，过期后下一次请求前重新检测。打开代理、改地址、点「检测」、网络切换，以及经代理的网页主框架或应用内请求连接失败时，会立即重新检测。
- 代理连不上时改为直连，并提示一次「代理 … 无法连接，已改用直连」；之后检测成功会自动切回，并提示「代理已恢复」。网页因代理断开加载失败时，会直连重新加载一次。代理断开时流量会直接发出，不提供“暂停加载”的选项。
- 「连接 VPN 时直连」：本应用的默认网络经过 VPN 时（分应用排除本应用的 VPN 不算），网页和应用内请求都直连，也不检测代理；VPN 断开后恢复代理。
- 已开始的下载沿用开始时的线路。应用内代理不保证覆盖 WebRTC UDP 等流量，外部应用使用自己的网络设置。
- 从旧版本升级后，内置代理的订阅、节点和开关不会保留，旧数据在首次启动时清理。

### 隐私与数据

- 无痕浏览需要 WebView 同时支持 `MULTI_PROFILE` 和 `DELETE_BROWSING_DATA`；不支持时提示更新。无痕与普通会话的 Cookie、网站存储隔离，不保存无痕历史、标签恢复和下载记录。
- 关闭最后一个无痕标签或结束浏览器 Activity 后清理无痕会话；异常退出遗留数据在下次启动清理。WebView 可能临时写入磁盘，无痕浏览不能隐藏网站、网络服务或代理侧的记录。
- 手动保存的书签和下载文件会保留，无痕下载可继续完成。无痕页面禁止截图；切换外观会提示关闭无痕标签。
- 搜索引擎联想默认关闭；开启后输入会发送给所选引擎，无痕模式不请求。翻译会将页面地址发送给 Google。剪贴板文字仅在点按“粘贴并前往”时读取。
- 不提供浏览器账号或云同步，应用数据不进入 Android 自动备份；可在「设置 → 数据」手动备份到文件。代理地址和开关随设置备份；AI 密钥只在勾选时写入备份，且为明文。清除网站数据会删除相应会话的 Cookie 和登录状态。
- 网页应用与浏览器共用登录状态，访问计入历史，不保存为标签页。跳转到其他网站的整页登录流程会交给浏览器，可先在浏览器中登录。

### 标签、下载与后台运行

- 最多 50 个标签。内存紧张时回收可释放的后台页面，优先保护当前页、手动保留页、加载中页面、表单和媒体；复杂编辑器可手动标记保留。进程重启只恢复普通标签信息，不恢复完整网页内存。
- 下载最多三个并行；续传依赖服务器支持 Range 及有效的 ETag 或 Last-Modified。`blob:` / `data:` 单文件上限 200 MB，`blob:` 需要来源页面仍在；不支持网页视频嗅探。
- 后台保活在媒体播放或设置开启“后台保持运行”时启用，不能阻止系统结束进程。可在通知中停止；文件下载使用独立任务通知。

## 代码入口

- `app/src/main/java/app/xvi/web/`：`PageHost` 是承载网页的窗口基类（会话、下载、上传、权限、全屏和后台保活），`BrowserActivity` 在其上提供标签和工具栏，`WebAppActivity` 是网页应用的独立窗口；`TabManager` / `BrowserSession` 管理标签与会话；`BrowserStore` / `BrowserPrefs` 管理本地数据；`BrowserUi`、首页和各面板负责界面。
- `AdFilter.kt`、`AdBlock.kt`：过滤规则引擎，以及列表的加载、更新和镜像回退；`BrowserBackup.kt`、`Backups.kt`：备份文件格式与导入导出；`WebAppScope.kt`、`SiteIcons.kt`：网页应用的站点范围、主题色和桌面图标。
- `MediaProbe.kt`、`BrowserHttp.kt`：页面媒体探测与通知栏控制；浏览器自身发起的请求共用的连接池。
- `BrowserProxyNetwork.java`、`ProxyAddress.java`、`ProxyProbe.java`、`BrowserDownloadService.kt`、`BrowserKeepAliveService.kt`：外部代理的线路选择与检测、下载和后台运行。
- `app/src/main/assets/tab-protection.js`：页面回收前的媒体与编辑状态检查；`assets/adblock/`：内置过滤规则。
- `app/src/test/`、`app/src/androidTest/`、`tests/`：单元、设备和脚本测试。
