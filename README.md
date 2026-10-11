# 星野 · Android 浏览器

面向 Android 8.0+ 的浏览器，包名 `app.xvi.web`。使用 Kotlin 原生界面、系统 WebView 和本地 SQLite；应用内代理由同级目录的 [`proxy-core`](https://github.com/xviapp/proxy-core) 提供。

## 最新更新

### 2026-10-11

#### 优化

- 首页移除中英文宣传语，设置页直接显示应用版本，取消重复的关于弹窗。

完整历史见[更新记录](docs/releases/release-notes.md)。

## 功能

- 底部地址栏、历史与书签联想、网址补全；内置四种搜索引擎，支持自定义搜索地址。默认引擎在设置中选择。
- 多标签、后台打开链接、关闭撤销、最近关闭、普通标签恢复；支持独立会话的无痕浏览。
- 首页书签和最近访问、书签拖动排序与 HTML 导入导出、按日期整理和删除历史。
- 文件与图片下载、进度通知、取消和断点续传；支持网页生成的 `blob:` / `data:` 文件。上传可选择文件、拍照或录像。
- 页内查找、桌面模式、阅读模式、网页翻译、保存网页、打印 / PDF、查看源代码和二维码识别。
- 网站权限与独立网站设置、浅色 / 深色外观、文字大小、缩放、数据清理和默认浏览器设置。
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

默认构建包含 `arm64-v8a` 与 `x86_64`，可追加 `-PwebAbis=arm64-v8a` 只构建手机版本。`npm run build:android:release` 构建仅含 arm64 的 release APK，输出到 `app/build/outputs/apk/release/app-release.apk`；`npm run install:android:release` 安装该版本。当前 release 使用本机 debug keystore，正式发布前须替换签名配置。

首次构建自动下载固定版本的 Xray AAR 并校验 SHA-256，缓存位于 `build/proxy-core/xray/`。接入约定见[代理核心接口](docs/proxy-core-api.md)，来源和许可证见[第三方声明](THIRD_PARTY_NOTICES.md)。

### 设备测试

设备测试会修改测试安装的代理配置和浏览数据，请在隔离模拟器中运行：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w app.xvi.web.test/androidx.test.runner.AndroidJUnitRunner
```

测试覆盖导航、下载、代理、标签保留和无痕隔离；无痕用例会在 WebView 能力不足时跳过。真实订阅和公网节点需另行验证。

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
- 不提供浏览器账号或云同步，应用数据不进入 Android 自动备份。清除网站数据会删除相应会话的 Cookie 和登录状态。

### 标签、下载与后台运行

- 最多 50 个标签。内存紧张时回收可释放的后台页面，优先保护当前页、手动保留页、加载中页面、表单和媒体；复杂编辑器可手动标记保留。进程重启只恢复普通标签信息，不恢复完整网页内存。
- 下载最多三个并行；续传依赖服务器支持 Range 及有效的 ETag 或 Last-Modified。`blob:` / `data:` 单文件上限 200 MB，`blob:` 需要来源页面仍在；不支持网页视频嗅探。
- 后台保活在代理开启、媒体播放或设置开启“后台保持运行”时启用，不能阻止系统结束进程。可在通知中停止；文件下载使用独立任务通知。

## 代码入口

- `app/src/main/java/app/xvi/web/`：`BrowserActivity` 协调导航和生命周期；`TabManager` / `BrowserSession` 管理标签与会话；`BrowserStore` / `BrowserPrefs` 管理本地数据；`BrowserUi`、首页和各面板负责界面。
- `BrowserProxyNetwork.java`、`BrowserDownloadService.kt`、`BrowserKeepAliveService.kt`：代理适配、下载和后台运行。
- `app/src/main/assets/tab-protection.js`：页面回收前的媒体与编辑状态检查。
- `app/src/test/`、`app/src/androidTest/`、`tests/`：单元、设备和脚本测试。
- `../proxy-core/`：独立维护的代理核心；本仓库的接口文档由核心同步。
