# 星野 · Android 浏览器

独立 Android 浏览器，包名 `app.xvi.web`。Kotlin 原生界面、系统 WebView、本地 SQLite 存储，以及从 note 移植的 v2rayNG / Xray 应用内代理。源码和构建均不依赖工作区中的其他项目。

## 最新更新

### 2026-10-09

#### 优化

- 星野应用图标统一为奶油底、灰青绿主体和暖橙星星的扁平剪纸风格，荒野图案保留不对称星星布局。

完整历史见[更新记录](docs/releases/release-notes.md)。

## 功能

- 底部地址栏、网址与搜索词识别、必应 / 百度 / Google / DuckDuckGo。
- 多标签、新窗口、前进后退、刷新、错误重试、重启后恢复标签地址。
- 原生首页默认展示一行书签，最多展开四行，并提供更多入口和最多五个按网站归并的最近访问入口，支持长按删除网站记录。
- 书签、历史搜索与删除，标准 HTML 书签导入导出。
- 原生下载服务，通过系统文件选择器指定保存位置，下载记录、进度通知、取消下载和文件打开。
- 网页文件上传、长按保存图片、页内查找、分享、复制链接、桌面模式、视频全屏。
- 网站摄像头、麦克风和定位按次授权，外部应用跳转确认。
- 系统 / 浅色 / 深色主题、网页文字大小、清理历史 / 缓存 / 网站数据、默认浏览器设置。
- 应用内代理开关，完整节点、订阅、路由、DNS、自定义配置和本地配置备份界面。

不包含浏览器账号、云同步、用户脚本、广告过滤或无痕模式。网站 JavaScript、Cookie、网站自身登录正常启用；默认禁用第三方 Cookie。应用数据不进入 Android 自动备份。

## 构建

需要 JDK 21、Android SDK Platform 37 和 Build Tools。设置 `ANDROID_HOME` 指向 SDK，或在未纳入 Git 的 `local.properties` 中配置 `sdk.dir`。

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

首次构建会下载固定版本的 Xray 和分流数据，并校验 SHA-256。下载缓存位于 `v2ray/libs/`，不纳入 Git。

## 代理行为

在「菜单 → 网络代理 → 节点、订阅与完整配置」导入并选中节点，然后开启代理。配置只保存在星野自己的应用目录，与 note 相互独立。

- 只使用 Proxy only 服务，本机入口仅监听 `127.0.0.1`，不修改系统代理或 VPN。
- WebView 通过 AndroidX `ProxyController` 接入；原生下载显式指定同一入口。订阅和规则资源更新保留内嵌模块行为。
- 等待核心确认和 WebView 代理生效后才开始导航或下载；节点 / 规则修改可重载核心。
- 核心启动失败、超时、退出或网络切换重载失败时，会按 1/2/4/8/16/30 秒自动重连，最多 6 次；地址栏显示恢复状态，连续失败后才恢复直连。
- 核心正常但节点连接失败、规则阻止或普通网页错误时，保留失败，不绕过规则、不自动直连。
- 已开始的下载使用启动时的网络设置；切换节点后，旧连接可能失败，需要重新下载。
- 应用内 HTTP 代理不是所有协议的 VPN 隧道，不承诺代理 WebRTC UDP 等流量。跳转到其他应用后由该应用决定网络路径。
- 代理核心使用普通服务，由浏览器的前台保活服务绑定；浏览器统一显示“星野正在后台运行”通知，代理不单独显示常驻通知。可在通知中停止后台运行；关闭代理会停止核心。

## 当前边界

- 最多 50 个标签，内存正常时不按固定实例数量回收。前台每 15 秒检查系统可用内存和应用堆占用，并在切换标签及系统内存通知时检查；仅进入后台不视为内存不足。
- 内存紧张时按最久未使用顺序，分批回收后台页面。当前页、手动保留的页、正在加载的页不参与；一般压力下还保护最近 30 秒使用过的页面。
- 回收前只读取“是否适合回收”的布尔结果，不保存或传出表单内容。播放中的 HTML 音视频、已填写或改动的表单、可编辑区域和特殊嵌入内容会保留；跨域框架或检查失败时也保守保留。无 DOM 媒体元素的 Web Audio、复杂画布编辑器等无法全面识别，建议在标签列表长按该标签，标记为“保留”。
- 回收时保存 WebView 导航状态和滚动位置，切回时尝试恢复；标签切换不再统一暂停后台 WebView。进程重启恢复地址、标题、桌面模式、手动保留标记和选中的标签，不恢复网页完整内存。保留标记不能阻止系统终止进程或网页渲染器崩溃。
- 后台保活服务用于维持网页和代理连接，但不能保证进程不被系统终止。停止后台运行或从最近任务移除浏览器会结束保活服务；回到前台会重新启动保活并检查代理状态。文件下载和订阅更新使用各自的任务通知。
- 代理核心不生成速度通知或流量统计；节点格式、订阅更新、路由规则和 DNS 配置保留。
- 下载支持 HTTP(S)，有进度通知；暂不支持 Blob 下载、断点续传和网页视频嗅探。
- 文件上传使用系统文档选择器；不包含网页直接调用相机拍照上传的专用流程。
- 桌面模式使用桌面 UA；网页兼容性受设备系统 WebView 版本限制。
- 不读取、导入或自动测试用户的真实代理订阅、节点和凭据。

## 验证

单元测试覆盖网址 / 搜索输入边界、首页导航与标签计数、书签展示规则及代理配置入口约束。设备测试使用本机 HTTP 服务和临时代理配置，覆盖网页导航与恢复、书签导入、文件下载内容与 Cookie、WebView / 原生代理、POST、Range、流式响应、规则阻止、节点失败、核心退出和恢复。真实公网订阅与节点需要使用者自行配置后验证。

标签内存策略测试覆盖正常内存、系统 / 堆内存压力、当前页与手动保留、最近使用保护。运行 `node tests/tab-protection.test.mjs` 可独立检查媒体、表单、嵌套框架和异常情况下的保护逻辑。`TabRetentionTest` 为设备回归测试，检查四个页面同时保留及压力下仅回收可释放的后台页。

设备测试会修改 **星野测试安装** 的节点、规则和浏览记录，请在隔离模拟器中运行，不要在含真实配置的安装上执行：

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w app.xvi.web.test/androidx.test.runner.AndroidJUnitRunner
```

## 目录

- `app/src/main/java/app/xvi/web/BrowserActivity.kt`：原生浏览器界面和网页交互。
- `BrowserInput.kt`：网址 / 搜索输入规则。
- `BrowserNavigation.kt`：首页与网页导航决策、标签计数显示规则。
- `HomeBookmarkPresentation.kt`：首页书签折叠与展示规则。
- `TabMemoryPolicy.kt` 与 `app/src/main/assets/tab-protection.js`：标签回收策略与页面保护检测。
- `BrowserStore.kt`：SQLite 书签、历史、下载记录及标签偏好。
- `BrowserProxyNetwork.java`：宿主网络状态、核心存活确认、WebView 代理与故障恢复。
- `BrowserDownloadService.kt`：带代理的原生前台下载服务。
- `BrowserKeepAliveService.kt`：浏览器后台保活、统一通知及代理服务绑定。
- `app/src/main/res/`：应用图标、界面资源和备份规则。
- `app/src/test/`、`app/src/androidTest/` 与 `tests/`：单元、设备及页面保护脚本测试。
- `v2ray/`：从 note 移植、独立维护的代理模块，保留上游来源和许可证。

详见 [第三方来源](THIRD_PARTY_NOTICES.md) 与 [代理模块说明](v2ray/README.md)。
