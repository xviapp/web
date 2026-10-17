# 星野 · Android 浏览器

基于系统 WebView，支持 Android 8.0 及以上版本。

[下载 APK](https://github.com/xviapp/web/releases) · [问题反馈](https://github.com/xviapp/web/issues)

## 功能

- 底部地址栏、历史和书签联想、自定义搜索引擎。
- 多标签、关闭撤销、标签恢复、无痕浏览。
- 书签目录、拖动排序、HTML 导入导出。
- 文件和图片下载、断点续传；支持文件上传、拍照和录像。
- 广告拦截、自定义规则和订阅。
- 阅读模式、网页翻译、页内查找、桌面模式、保存网页、打印 / PDF、二维码识别。
- AI 网页总结、问答和划词处理，需自行配置 DeepSeek 或兼容 OpenAI 接口的服务及 API Key。
- 网站桌面快捷方式、独立窗口、视频画中画和后台播放。
- 网站权限、深色模式、数据清理、备份恢复、SOCKS5 / HTTP 代理。

## 使用说明

### 代理

在「设置 → 代理」填写本机或局域网代理地址并开启，例如 `socks5://127.0.0.1:7890`。浏览器不内置代理服务。

- 支持 `socks5://主机:端口` 和 `http://主机:端口`，省略协议时按 SOCKS5 处理；不支持账号密码或 HTTPS 代理。
- 本机和局域网地址直连，不保证代理 WebRTC UDP 等流量。
- 代理不可用时自动直连并提示，恢复后切回代理。已开始的下载沿用原线路。
- 开启「连接 VPN 时直连」后，本应用经过 VPN 时不再使用所配置的代理。

### 隐私与数据

- 浏览数据保存在本机，无账号和云同步。应用不使用 Android 自动备份，可在「设置 → 数据」手动备份；勾选 AI 密钥后，密钥会以明文写入备份文件。
- 无痕浏览隔离 Cookie 和网站存储，不保存历史、标签恢复和下载记录。关闭全部无痕标签后清理会话，异常退出遗留数据在下次启动清理。主动保存的书签和下载文件仍会保留。
- 无痕浏览不能隐藏网站、网络服务、代理或 AI 服务端的记录。部分旧版 WebView 不支持无痕浏览，需更新后使用。
- 搜索联想默认关闭，开启后会将输入发送给搜索引擎；无痕模式不请求联想。网页翻译会将页面地址发送给 Google。
- AI 网页问答会向所配置的服务发送正文、标题、网址和对话；划词会发送选中文字，并可能附带所在段落或标题。请求可能消耗 API 额度。首次发送、更换服务及每次无痕发送前会询问。
- 网页应用与普通浏览共用登录状态。清除网站数据会退出相关网站的登录。

完整隐私说明见「设置 → 关于星野」。

### 使用限制

- 最多 50 个标签。内存不足时会释放后台页面，重要页面可手动固定；重启后只恢复普通标签信息。
- 最多同时下载 3 个文件，续传需要服务器支持。`blob:` / `data:` 文件上限为 200 MB，`blob:` 下载需保留来源页面。不支持视频嗅探。
- 后台播放和下载仍可能被系统结束。

## 构建

需要 JDK 21、Android SDK Platform 37 和 Build Tools。脚本测试需要 Node.js，无需安装 npm 依赖。通过 `ANDROID_HOME` 或 `local.properties` 中的 `sdk.dir` 指定 SDK 路径。

```sh
./gradlew :app:assembleDebug
npm test
npm run lint
```

发布版：

```sh
npm run build:android:release
```

APK 输出到 `app/build/outputs/apk/release/app-release.apk`。发布版需在 `.signing/release.properties` 中配置 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，使用 PKCS12 密钥。请备份签名密钥，后续升级需使用相同签名。

默认构建支持 arm64 和 x86_64，发布脚本仅构建 arm64；可用 `-PwebAbis=arm64-v8a` 指定架构。

### 设备测试

设备测试会修改浏览数据。使用 `.verify` 后缀安装独立副本：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PwebIdSuffix=.verify
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w app.xvi.web.verify.test/androidx.test.runner.AndroidJUnitRunner
```

无痕用例会在 WebView 不支持时跳过。Android 16 上，`BrowserIntegrationTest` 的两个本地下载用例可能挂起，可单独运行其他用例。

## 许可证

原创代码采用 [MIT](LICENSE)，第三方依赖和资源见[第三方声明](THIRD_PARTY_NOTICES.md)。
