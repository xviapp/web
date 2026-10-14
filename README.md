# 星野 · Android 浏览器

面向 Android 8.0+ 的浏览器，包名 `app.xvi.web`。使用 Kotlin 原生界面、系统 WebView 和本地 SQLite；应用内代理由同级目录的 [`proxy-core`](https://github.com/xviapp/proxy-core) 提供。

## 最新更新

### 2026-10-14

#### 新增

- 标签页：地址栏左右滑动即可切换标签（编辑地址时不触发），切换时新页面从滑动方向平移淡入。标签超过 5 个时列表顶部出现搜索框，按标题和网址过滤；长按标签可复制链接或复制此标签页。
- 长按后退 / 前进按钮列出当前标签最近 12 条历史，点哪条就直接跳到哪条。
- 下载：已完成的条目长按可打开、分享文件、复制链接或删除；文件已被移走或删除时标出，并可一键重新下载。
- 阅读模式顶栏新增「阅读样式」：背景（跟随外观 / 白色 / 米黄 / 深色）、字体（无衬线 / 衬线）、行距（紧凑 / 标准 / 宽松）。选择会记住，并随备份保存和恢复。
- AI 助手支持自定义 API 地址和模型名，兼容 OpenAI 接口的服务（如硅基流动、Ollama、自建转发）均可使用；留空即使用 DeepSeek。非本机或局域网地址须使用 HTTPS；更换地址会清除已保存的 API Key。非 DeepSeek 服务的测试连接改为读取模型列表。

#### 优化

- 工具栏按同一方向的累计滚动距离收起或展开，慢速滑动不再来回闪烁；滚到页面底部时自动出现。
- 窗口销毁时关闭仍打开的面板；退出清理缓存时改用 Activity 创建 WebView。

#### 测试

- 新增 API 地址校验（HTTPS 要求、本地地址例外、路径规范化）、非 DeepSeek 请求体和阅读样式测试。

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
- 全屏视频与画中画、后台媒体通知与播放控制；应用内代理支持单订阅、定期更新、自动或手动选点。

## 构建与验证

需要 JDK 21、Android SDK Platform 37、Build Tools；脚本测试需要 Node.js。通过 `ANDROID_HOME` 或未纳入 Git 的 `local.properties` 中的 `sdk.dir` 指定 SDK。

`web/` 与 `proxy-core/` 必须位于同级目录。缺少核心时，在本仓库根目录执行：

```sh
git clone https://github.com/xviapp/proxy-core.git ../proxy-core
```

日常构建和检查：

```sh
./gradlew :app:assembleDebug
npm test
npm run lint
```

`npm test` 运行页面保护脚本、浏览器和代理核心单元测试；`npm run lint` 运行 Android Lint。无 npm 依赖需要安装。

默认构建包含 `arm64-v8a` 与 `x86_64`，可追加 `-PwebAbis=arm64-v8a` 只构建手机版本。`npm run build:android:release` 构建仅含 arm64 的 release APK，输出到 `app/build/outputs/apk/release/app-release.apk`；`npm run install:android:release` 安装该版本，可追加 `-- --device <设备序列号>` 指定设备。当前 release 使用本机 debug keystore，正式发布前须替换签名配置。

首次构建自动下载固定版本的 Xray AAR 并校验 SHA-256，缓存位于 `build/proxy-core/xray/`。接入约定见[代理核心接口](docs/proxy-core-api.md)，来源和许可证见[第三方声明](THIRD_PARTY_NOTICES.md)。

### 设备测试

测试覆盖导航、下载、代理、标签保留和无痕隔离，会写入浏览数据、书签和代理配置；无痕用例会在 WebView 能力不足时跳过，真实订阅和公网节点需另行验证。加上 `-PwebIdSuffix=.verify` 构建一份与日常安装并存的副本，再针对它运行：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PwebIdSuffix=.verify
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.xvi.web.verify.test/androidx.test.runner.AndroidJUnitRunner
```

注意：在 Android 16 设备上，`BrowserIntegrationTest` 中两个走 127.0.0.1 本地下载服务器的用例会挂起（原始版本同样如此，与本仓库改动无关），单独运行其余类即可。

## 使用边界

### 代理

在「菜单 → 代理」添加订阅、更新并开启。配置保存在本应用中，与其他宿主隔离。

- 仅代理应用内流量，不设置系统 VPN 或系统代理；订阅更新始终走设备直连。
- 本机、局域网和保留网段直连。地址判断不做 DNS 解析，解析到内网 IP 的普通域名仍走代理。
- 自动模式选择最快可用节点；手动模式按订阅列表位置记忆选择，不可用时暂用其他节点，恢复后切回。
- 启动失败或核心退出后会重试；重连和失败阶段回退直连，不保证禁止直连。普通网页或节点请求失败不会自动绕过代理。
- 已开始的下载沿用启动时的网络设置。应用内代理不保证覆盖 WebRTC UDP 等流量，外部应用使用自己的网络设置。

### 隐私与数据

- 无痕浏览需要 WebView 同时支持 `MULTI_PROFILE` 和 `DELETE_BROWSING_DATA`；不支持时提示更新。无痕与普通会话的 Cookie、网站存储隔离，不保存无痕历史、标签恢复和下载记录。
- 关闭最后一个无痕标签或结束浏览器 Activity 后清理无痕会话；异常退出遗留数据在下次启动清理。WebView 可能临时写入磁盘，无痕浏览不能隐藏网站、网络服务或代理侧的记录。
- 手动保存的书签和下载文件会保留，无痕下载可继续完成。无痕页面禁止截图；切换外观会提示关闭无痕标签。
- 搜索引擎联想默认关闭；开启后输入会发送给所选引擎，无痕模式不请求。翻译会将页面地址发送给 Google。剪贴板文字仅在点按“粘贴并前往”时读取。
- 不提供浏览器账号或云同步，应用数据不进入 Android 自动备份；可在「设置 → 数据」手动备份到文件。代理订阅和 AI 密钥只在勾选时写入备份，且为明文。清除网站数据会删除相应会话的 Cookie 和登录状态。
- 网页应用与浏览器共用登录状态，访问计入历史，不保存为标签页。跳转到其他网站的整页登录流程会交给浏览器，可先在浏览器中登录。

### 标签、下载与后台运行

- 最多 50 个标签。内存紧张时回收可释放的后台页面，优先保护当前页、手动保留页、加载中页面、表单和媒体；复杂编辑器可手动标记保留。进程重启只恢复普通标签信息，不恢复完整网页内存。
- 下载最多三个并行；续传依赖服务器支持 Range 及有效的 ETag 或 Last-Modified。`blob:` / `data:` 单文件上限 200 MB，`blob:` 需要来源页面仍在；不支持网页视频嗅探。
- 后台保活在代理开启、媒体播放或设置开启“后台保持运行”时启用，不能阻止系统结束进程。可在通知中停止；文件下载使用独立任务通知。

## 代码入口

- `app/src/main/java/app/xvi/web/`：`PageHost` 是承载网页的窗口基类（会话、下载、上传、权限、全屏和后台保活），`BrowserActivity` 在其上提供标签和工具栏，`WebAppActivity` 是网页应用的独立窗口；`TabManager` / `BrowserSession` 管理标签与会话；`BrowserStore` / `BrowserPrefs` 管理本地数据；`BrowserUi`、首页和各面板负责界面。
- `AdFilter.kt`、`AdBlock.kt`：过滤规则引擎，以及列表的加载、更新和镜像回退；`BrowserBackup.kt`、`Backups.kt`：备份文件格式与导入导出；`WebAppScope.kt`、`SiteIcons.kt`：网页应用的站点范围、主题色和桌面图标。
- `MediaProbe.kt`、`BrowserHttp.kt`：页面媒体探测与通知栏控制；浏览器自身发起的请求共用的连接池。
- `BrowserProxyNetwork.java`、`BrowserDownloadService.kt`、`BrowserKeepAliveService.kt`：代理适配、下载和后台运行。
- `app/src/main/assets/tab-protection.js`：页面回收前的媒体与编辑状态检查；`assets/adblock/`：内置过滤规则。
- `app/src/test/`、`app/src/androidTest/`、`tests/`：单元、设备和脚本测试。
- `../proxy-core/`：独立维护的代理核心；本仓库的接口文档由核心同步。
