# 星野 · Android 浏览器

独立 Android 浏览器，包名 `app.xvi.web`。Kotlin 原生界面、系统 WebView、本地 SQLite 存储，以及从 note 移植的 v2rayNG / Xray 应用内代理。代理实现由工作区级 `../proxy-core/` 与 note 共用，宿主只保留浏览器适配层。

## 最新更新

### 2026-10-10

#### 新增

- 关闭标签可在底部提示中撤销，“全部关闭”需确认并可整批撤销；下载显示百分比、支持单个取消和完成通知；长按图片直接保存到相册。
- 首页“继续浏览”入口与网站图标；系统文字选择菜单“用星野搜索”。

#### 优化

- 代理面板采用开关和订阅弹窗：可分别设置订阅更新、连通性测试间隔，隐藏节点详情与手动切换入口，并显示更新、检测和失败状态。
- 历史 / 书签列表可回收并在后台搜索；地址栏不再自动读取剪贴板，引擎胶囊只影响本次搜索。
- 面板底部滑入、可下滑关闭、高度自适应；按钮补充按压反馈，深色模式冷启动不闪白。
- 错误页“重试”保留前进后退记录；`BrowserActivity` 拆分为标签模型、界面组件与各面板。
- 星野应用图标统一为奶油底、灰青绿主体和暖橙星星的扁平剪纸风格，荒野图案保留不对称星星布局。

完整历史见[更新记录](docs/releases/release-notes.md)。

## 功能

- 底部地址栏、网址与搜索词识别、必应 / 百度 / Google / DuckDuckGo。
- 多标签、新窗口、前进后退、刷新、错误重试（保留前进后退记录）、重启后恢复普通标签地址，首页提供“继续浏览”。
- 关闭标签可撤销（底部提示或标签面板），“全部关闭”需确认并可整批撤销。
- 标签面板右上角 ghost 图标切换普通 / 无痕浏览，独立 Cookie 与网站存储；关闭最后一个无痕标签后清理会话数据。
- 原生首页默认展示一行书签，最多展开四行，并提供更多入口和最多五个按网站归并的最近访问入口，支持长按删除网站记录；书签、最近访问和标签列表显示网站图标（无痕模式不保存图标）。
- 地址栏联想历史与书签；搜索引擎胶囊只影响本次搜索，默认引擎在设置中修改。剪贴板只在点按“粘贴并前往”时读取，不触发系统的读取提示。
- 书签、历史搜索与删除，标准 HTML 书签导入导出。
- 原生下载服务，通过系统文件选择器指定保存位置；最多三个任务并行，通知和下载面板显示百分比与大小，可单个或全部取消，完成 / 失败有通知。失败或取消会删除未完成文件；重试优先沿用原文件，带原 Referer 和当前 Cookie。
- 网页文件上传、长按保存图片（Android 10+ 直接存入“图片/星野”）、长按链接在新标签 / 无痕标签中打开、页内查找、分享、复制链接、桌面模式、视频全屏。
- 外部应用跳转确认，显示目标应用名称，支持网页提供的备用网页地址。系统文字选择菜单“用星野搜索”。
- 网站摄像头、麦克风和定位按次授权，外部应用跳转确认。
- 系统 / 浅色 / 深色主题、网页文字大小、清理历史 / 缓存 / 网站数据、默认浏览器设置。
- 应用内代理开关，只保留一个订阅 URL，默认每天自动更新并支持手动更新；自动测试节点，可查看当前节点并切换下一个。订阅更新始终走设备直连。

代理宿主使用 `EmbeddedProxyController.get(context, Routing)`，通过一个 `observe` 接收包含订阅的完整状态，Activity 销毁时关闭返回的 `Observation`。面板打开时通过 `status` 获取快照，保存、更新和切换节点均等待真实完成回调；核心错误码由浏览器转为中文文案。后台服务只向控制器报告 `setBackgroundActive`，核心服务绑定由共享模块管理。

不包含浏览器账号、云同步、用户脚本、广告过滤。网站 JavaScript、Cookie、网站自身登录正常启用；默认禁用第三方 Cookie。应用数据不进入 Android 自动备份。

## 构建

需要 JDK 21、Android SDK Platform 37 和 Build Tools。设置 `ANDROID_HOME` 指向 SDK，或在未纳入 Git 的 `local.properties` 中配置 `sdk.dir`。

代理核心单独维护于 `xviapp/proxy-core`，需与 web 放在同级目录。首次构建前从 web 根目录执行 `git clone https://github.com/xviapp/proxy-core.git ../proxy-core`，已有共享目录则跳过。完整接入、初始化和接口说明见[共享代理核心](docs/proxy-core-api.md)。

```sh
./gradlew :app:assembleDebug
node tests/tab-protection.test.mjs
./gradlew :app:testDebugUnitTest :v2ray:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

默认包含 arm64-v8a 与 x86_64。仅构建手机版本：

```sh
./gradlew :app:assembleDebug -PwebAbis=arm64-v8a
```

常用 npm 命令：

```sh
npm run build:android:release
npm run install:android:release
npm test
npm run lint
```

release 当前仅打包 arm64-v8a，并使用本机 debug keystore 便于测试安装；APK 位于 `app/build/outputs/apk/release/app-release.apk`。发布到应用市场前，需要在 `app/build.gradle` 中替换为正式签名配置。

首次构建会下载固定版本的 Xray AAR，并校验 SHA-256。核心自动打包 Java/JNI，宿主不手工引入原生 AAR；缓存位于本工程 `build/proxy-core/xray/`，不纳入 Git。浏览器使用自己的 `WebApplication` 调用幂等的 `ProxyCore.initialize(this)`，核心可通过自身 wrapper 独立构建和测试。

## 代理行为

在「菜单 → 网络代理」填写唯一订阅并更新，然后开启代理。配置只保存在星野自己的应用目录，与 note 相互独立。

- 只使用 Proxy only 服务，本机入口仅监听 `127.0.0.1`，不修改系统代理或 VPN。
- WebView 通过 AndroidX `ProxyController` 接入；原生下载显式指定同一入口。订阅手动或定期更新始终绕过内置代理，使用设备网络直连。
- 每次订阅更新后自动测试全部节点并选择最快节点；宿主面板只展示当前节点并支持切换下一个节点。
- 等待核心确认和 WebView 代理生效后才开始导航或下载；节点选择或订阅更新后可重载核心。
- 核心启动失败、超时、退出或网络切换重载失败时，会按 1/2/4/8/16/30 秒自动重连，最多 6 次；重连与失败阶段清除应用内代理并回退直连，地址栏显示恢复状态，不提供禁止直连保证。
- 核心正常但节点连接失败或普通网页错误时，保留失败，不自动绕过节点直连。
- 已开始的下载使用启动时的网络设置；切换节点后，旧连接可能失败，需要重新下载。
- 应用内 HTTP 代理不是所有协议的 VPN 隧道，不承诺代理 WebRTC UDP 等流量。跳转到其他应用后由该应用决定网络路径。
- 代理核心使用普通服务，由浏览器的前台保活服务绑定；浏览器统一显示“星野正在后台运行”通知，代理不单独显示常驻通知。可在通知中停止后台运行；关闭代理会停止核心。

## 无痕浏览

通过「菜单 → 无痕浏览」或标签面板右上角的 ghost 图标切换模式；面板只展示当前模式的标签，普通标签会保留。无痕时底栏标签按钮使用 ghost 轮廓，中间只显示标签数量；地址栏以 ghost 图标标示无痕，锁图标只表示 HTTPS。普通浏览始终保留至少一个标签，关闭普通标签或按返回键不会转入无痕模式。无痕标签共享本次会话的登录态，与普通标签隔离，网页弹窗和长按新建标签继承来源会话。

- 不保存无痕历史、标签恢复记录及浏览器下载记录；不显示普通浏览的历史建议或最近访问。
- 关闭最后一个无痕标签或销毁浏览器 Activity 时，释放页面并异步清理该会话的网站数据，随后尝试删除该 Profile。屏幕旋转、分屏和系统深浅色切换不重建页面；系统深浅色变化时，若有无痕标签，配色在其全部关闭后才更新。在设置中切换外观会先确认，并结束无痕会话；重启不恢复无痕页面。
- 未能即时删除或异常退出遗留的无痕 Profile 在下次进程启动时删除，旧会话永不复用；启动清理失败时本次进程不开启无痕模式。WebView 仍可能临时写入磁盘，不承诺全程纯内存。
- 手动保存的书签、下载文件会保留，已开始的下载继续运行；无痕下载通知不显示文件名。选择保存位置期间无痕会话已结束时，取消下载并删除已创建的空文件。
- 需要系统 WebView 同时支持 `MULTI_PROFILE` 和 `DELETE_BROWSING_DATA`，不支持时提示更新，不回退到共享 Cookie 的模式。
- 「清除网站数据」在无痕模式下只清除本次无痕会话的数据。
- 无痕页面及其发起的对话框禁止截图和最近任务缩略图，地址栏请求输入法不进行个性化学习，复制的链接标记为敏感内容（Android 13+ 不在剪贴板预览中显示）；网站、网络服务和代理仍可能记录访问。

## 当前边界

- 最多 50 个标签，内存正常时不按固定实例数量回收。前台每 15 秒检查系统可用内存和应用堆占用，并在切换标签及系统内存通知时检查；仅进入后台不视为内存不足。
- 内存紧张时按最久未使用顺序，分批回收后台页面。当前页、手动保留的页、正在加载的页不参与；一般压力下还保护最近 30 秒使用过的页面。
- 回收前只读取“是否适合回收”的布尔结果，不保存或传出表单内容。播放中的 HTML 音视频、已填写或改动的表单、可编辑区域和特殊嵌入内容会保留；跨域框架或检查失败时也保守保留。无 DOM 媒体元素的 Web Audio、复杂画布编辑器等无法全面识别，建议在标签列表长按该标签，标记为“保留”。
- 回收时保存 WebView 导航状态和滚动位置，切回时尝试恢复；标签切换不再统一暂停后台 WebView。进程重启仅恢复普通标签的地址、标题、桌面模式、手动保留标记和选中的标签，不恢复网页完整内存。保留标记不能阻止系统终止进程或网页渲染器崩溃。
- 后台保活服务用于维持网页和代理连接，但不能保证进程不被系统终止。停止后台运行或从最近任务移除浏览器会结束保活服务；回到前台会重新启动保活并检查代理状态。文件下载使用任务通知，订阅更新由 WorkManager 后台执行，不创建代理通知。
- 代理核心不生成流量统计；只保留一个订阅、自动测试与节点切换。备份恢复、搜索、单节点导入/分享、路由/DNS/资源编辑和高级核心设置均已移除。
- 下载支持 HTTP(S)，有进度通知；暂不支持 Blob 下载、断点续传和网页视频嗅探。浏览器进程被系统结束时，进行中的下载标记为“已中断”。
- 文件上传使用系统文档选择器；不包含网页直接调用相机拍照上传的专用流程。
- 桌面模式使用桌面 UA；网页兼容性受设备系统 WebView 版本限制。
- 设备测试只使用本机测试服务；真实代理订阅、节点和凭据不会被测试代码读取。

## 验证

单元测试覆盖网址 / 搜索输入边界、首页导航与标签计数、书签展示规则、标签分组 / 关闭 / 整批撤销（`TabManagerTest`）、桌面 UA 推导、数据库查询转义与下载状态码，以及代理配置入口约束。设备测试使用本机 HTTP 服务和临时代理配置，覆盖网页导航与恢复、书签导入、文件下载内容与 Cookie、WebView / 原生代理、POST、Range、流式响应、不可用节点、核心退出和恢复。真实公网订阅与节点需要使用者自行配置后验证。

标签内存策略测试覆盖正常内存、系统 / 堆内存压力、当前页与手动保留、最近使用保护。运行 `node tests/tab-protection.test.mjs` 可独立检查媒体、表单、嵌套框架和异常情况下的保护逻辑。`TabRetentionTest` 为设备回归测试，检查四个页面同时保留及压力下仅回收可释放的后台页。

设备测试会修改 **星野测试安装** 的节点和浏览记录，请在隔离模拟器中运行，不要在含真实配置的安装上执行：

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w app.xvi.web.test/androidx.test.runner.AndroidJUnitRunner
```

`PrivateBrowsingTest` 覆盖无痕 Cookie / localStorage 隔离、同会话共享、清理不影响普通数据、历史与标签不落库、关闭与活动重建、关闭普通标签不会转入无痕模式，以及整批撤销时移除空白占位标签。标签关闭后的选中规则和返回键行为由 `BrowserNavigationTest` 单元测试覆盖。`BrowserIntegrationTest` 另覆盖无痕下载携带 Cookie 但不保存下载记录。无痕 Profile 测试在能力不足的 WebView 上跳过，需在支持上述两项能力的模拟器上验收。

## 目录

- `app/src/main/java/app/xvi/web/BrowserActivity.kt`：浏览器主界面、标签切换、导航、下载入口与对话框。
- `TabManager.kt`：与界面无关的标签列表、普通 / 无痕分组、选中与整批撤销规则。
- `BrowserUi.kt`、`BrowserSheet.kt`：配色（`res/values(-night)/colors.xml`）、通用控件与底部面板。
- `HomePage.kt`、`SearchOverlay.kt`、`BrowserPageClients.kt`：首页、地址栏联想与网页回调。
- `TabsPanel.kt`、`MenuPanel.kt`、`LibraryPanel.kt`、`DownloadsPanel.kt`、`ProxyPanel.kt`、`SettingsPanel.kt`：各面板。
- `BrowserPrefs.kt`：偏好设置与标签恢复；外观写入 `web-appearance`，供代理模块界面跟随。
- `BrowserInput.kt`：网址 / 搜索输入规则。
- `BrowserNavigation.kt`：首页与网页导航决策、标签计数显示规则。
- `HomeBookmarkPresentation.kt`：首页书签折叠与展示规则。
- `TabMemoryPolicy.kt` 与 `app/src/main/assets/tab-protection.js`：标签回收策略与页面保护检测。
- `BrowserSession.kt`：网站身份、无痕 Profile 生命周期、异常退出清理及会话 Cookie。
- `BrowserStore.kt`：进程内单例 SQLite，保存书签、历史、网站图标和下载记录（状态码与进度）。
- `BrowserProxyNetwork.java`：宿主网络状态、核心存活确认、WebView 代理与故障恢复。
- `BrowserDownloadService.kt`：带代理的原生前台下载服务。
- `BrowserKeepAliveService.kt`：浏览器后台保活、统一通知及代理服务绑定。
- `app/src/main/res/`：应用图标、界面资源和主题资源。
- `app/src/test/`、`app/src/androidTest/` 与 `tests/`：单元、设备及页面保护脚本测试。
- `../proxy-core/`：从 note 移植、由 note 与 web 共用的代理模块，保留上游来源和许可证。

详见 [第三方来源](THIRD_PARTY_NOTICES.md) 与 [代理模块说明](../proxy-core/README.md)。
