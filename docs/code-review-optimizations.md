# 代码可优化清单

审查范围：`app/src/` 全部 8679 行 Kotlin/Java 源码、构建脚本、测试与清单文件。
审查方式：逐文件静态阅读 + 强制重跑 Android Lint + 运行现有测试。**未做真机性能剖析**，P0 各项的收益来自调用路径分析，需要实测确认。

## 验证基线

| 检查项 | 结果 |
| --- | --- |
| `./gradlew :app:lintDebug`（touch 源文件强制重跑） | **0 error，1 warning**，仅 `OldTargetApi`（`targetSdk 36`，最新为 37） |
| `node tests/tab-protection.test.mjs` | 9/9 通过 |
| `./gradlew :app:testDebugUnitTest` | 构建干净、任务 up-to-date |
| 单元测试文件 | 10 个，覆盖输入解析、导航、联想排序、历史分组、存储、标签管理 |

结论：这不是缺陷排查。代码 lint 干净，纯逻辑层（`TabManager`、`BrowserNavigation`、`SuggestionRanking`、`TabMemoryPolicy`、`HistoryDays`、`DownloadResume`、`DataUrl`、`BrowserInput`、`ReaderExtractor`）拆分得好且有测试。以下清单关注的是**主线程热路径开销、写法一致性与可扩展性**。

---

## P0 — 主线程逐事件开销

这些代码在每次进度回调、焦点变化或按键时执行，成本会直接体现为卡顿。

### 1. `updateChrome()` 无条件分配对象并重新解析地址

位置：[BrowserActivity.kt:631-658](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L631-L658)

调用来源包括每一次 `onPageStarted`、`onPageFinished`、`doUpdateVisitedHistory`、`onPageCommitVisible`、进度 tick、焦点变化和标签切换。每次都会做：

- `ColorStateList.valueOf(...)` + `str(...)` + 三次属性赋值，用于代理按钮（[updateProxyButton:618-622](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L618-L622)）
- `ui.tintedIcon(R.drawable.ic_lock_24, …)` —— 每次都重新 `getDrawable` + `mutate` + `tint`（[第 648 行](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L648)）
- `isSecure()` 里的 `java.net.URI(url).host`（[第 669 行](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L669)）
- 4 处无障碍描述的 `str()`，外加 `displayTitle` / `tabCountLabel`

**改法：** 在 `buildChrome` 里把锁 / 信息两个 tinted `Drawable` 缓存起来；对 `(url → isSecure)` 做记忆化；代理按钮只在 `proxyPhase` 真正变化时更新。

### 2. `applyPageDarkening()` 在新建 WebView 时遍历所有标签

位置：[BrowserActivity.kt:1044-1048](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L1044-L1048)

`createWebView` 会在构建视图的过程中调用它，此时 WebView 的 settings 还处于配置中途。单视图分支只处理一个视图，成本可控，但建议把这次调用移到 `webViewClient` 赋值之后。

### 3. `applyInputPrivacy` 在热路径上调用 `restartInput`

位置：[BrowserActivity.kt:312-321](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L312-L321)

`updateChrome()` 每次都会调用本方法。`restartInput` 是一次 IME 跨进程调用。虽然已有 `options != input.imeOptions` 守卫，但紧随其后的 `importantForAutofill` 是无条件写入的。建议按 `EditText` 缓存上次应用的 `private` 值并提前返回。

### 4. `applyContentInset` 重复投递且未去重

位置：[BrowserActivity.kt:418-420](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L418-L420)

`chrome.addOnLayoutChangeListener { … content.post { applyContentInset() } }` 每次布局都新建一个 `Runnable`，可能堆积多个待执行任务。建议改用 `content.removeCallbacks` + 单个可复用 `Runnable`，或在投递前先比较 `params.bottomMargin`。

### 5. 每 15 秒探测所有标签的媒体状态，值得重新评估

位置：[BrowserActivity.kt:141-148](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L141-L148)、[926-955](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L926-L955)

`memoryCheck` 每 15 秒无条件执行 `probeMedia()`，而 `probeMedia` 会向**每一个**持有存活 WebView 的标签注入约 1 KB 的 `MEDIA_PROBE` 脚本，走一次 JS→Java 桥。10 个后台标签意味着仅仅在阅读页面时每分钟就有 40 次脚本往返。`AudioManager.AudioPlaybackCallback` 已经能在真实媒体事件发生时触发探测，因此周期扫描可以只探测当前标签，或在 `audioChanges` 报告无活动时退避。

### 6. `HomePage.render()` 重建整棵首页视图树

位置：[HomePage.kt:59-115](../../app/src/main/java/app/xvi/web/HomePage.kt#L59-L115)

每次 `refreshHome()` → `showHome()` → `render()` 都会重建 `ScrollView`、header 和 capsule，并且要先 `removeView` 再重新挂载 `proxyButton` 与 `capsule`，还会重复调用 `getLocationOnScreen`。`inputBottomIn()` 只在 overlay 的 `overlayTop` lambda 中被调用，这些 capsule / header 的反复销毁重建并没有换来任何收益。建议缓存视图树，只重绘 `sections` 列。

### 7. 列表适配器逐行调用 `siteKey()` / `domain()`

位置：[LibraryPanel.kt:222-231](../../app/src/main/java/app/xvi/web/LibraryPanel.kt#L222-L231)、[TabsPanel.kt:100-141](../../app/src/main/java/app/xvi/web/TabsPanel.kt#L100-L141)

`LibraryPanel.adapter.getView` 每行都执行 `BrowserStore.siteKey(entry.url)` + `host.domain(entry.url)` + `ui.str(...)`，而每次 `notifyDataSetChanged` 都会触发全量 `getView`，滚动时等于逐帧执行。`siteKey` 内部是 `URI` 解析 + `lowercase` + `removePrefix`。建议增加一个带上限的 URL→(siteKey, 显示文本) 记忆化缓存。

---

## P1 — 一致性与结构性成本

### 8. 存储层存在两条并行的写入通道

`BrowserStore.write { }`（[第 108 行](../../app/src/main/java/app/xvi/web/BrowserStore.kt#L108)）与 `store.io`（[第 105 行](../../app/src/main/java/app/xvi/web/BrowserStore.kt#L105)）背后是同一个 executor，调用方却随意二选一：`BrowserActivity.bookmarkImport:188` 用 `withContext(store.io) { store.importBookmarks(...) }`，而 `SitePermissions.store()`、`SiteSettings.set`、`SettingsPanel` 等处用 `store.write { … }`。这一点重要是因为：在已经运行于该 dispatcher 的协程里再 `withContext(store.io)` 会死锁。当前代码恰好避开了，但没有任何机制约束。**建议二选一（改为基于 `io` 的 suspend 函数）并把另一个设为 private。**

### 9. 每次操作新建三个 `OkHttpClient`

位置：[PageTools.kt:122](../../app/src/main/java/app/xvi/web/PageTools.kt#L122)、[BrowserActivity.kt:983](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L983)、[SearchOverlay.kt:213](../../app/src/main/java/app/xvi/web/SearchOverlay.kt#L213)

`viewSource` 与 `loadMediaArt` 都在**每次调用**时新建 `OkHttpClient`，而且是在实际工作内部创建。每个实例自带 `Dispatcher`（线程池 + 60 秒空闲保活）、连接池和路由表。`SearchOverlay` 至少缓存了基础 client、每次派生新实例，是相对正确的写法。建议统一为共享基础 client + `.newBuilder().proxySelector(LocalDirect(proxy)).build()`，也就是 [BrowserDownloadService.kt:317-320](../../app/src/main/java/app/xvi/web/BrowserDownloadService.kt#L317-L320) 已经采用的模式。

### 10. `BrowserActivity` 有 1374 行，是 12 个协作者的枢纽

位置：[BrowserActivity.kt](../../app/src/main/java/app/xvi/web/BrowserActivity.kt)

`BrowserActivity` 把自身作为 `host` 传给 `PageInteractions`、`PageTools`、`DownloadController`、`SitePermissions`、`UploadController`、`HomePage`、`SearchOverlay`、`FindBar`、五个面板、`DragReorder` 和 `SwipeBar`。任何东西都能从任何地方访问，导致脱离 Activity 就无法做单元测试。上一次重构拆出了**文件**，但没有拆出**接缝**。

建议优先提取以下三块，它们的状态本身已接近自包含：

- 工具栏构建 + `updateChrome` / `updateProgress` / `updateTabCounter` / `applyContentInset` → `BrowserChrome`，通过回调与 Activity 通信（约 250 行，同时解决 P0 #1）
- 标签生命周期（`showTab` / `replace` / `createWebView` / 新建标签）→ `TabPresenter`（约 300 行）
- 媒体子系统（`probeMedia` / `track` / `loadMediaArt` / `controlMedia` / `seekMedia` / `mediaPaused` / `mediaArt` / `audioChanges`）→ `MediaProbe`（约 180 行，并顺带解决 P0 #5）

上述纯逻辑部分已经是好范例，请保持同样风格。

### 11. 重复的布局样板代码

`LinearLayout.LayoutParams(0, -2, 1f)` 与 `dp(48)` 点击区域各出现约 80 次。`BrowserUi` 已经有 `asButton` / `iconAction` / `textAction` 等工具方法，再补 `weighted()` / `fixed(w,h)` / `fillRow()` 能显著降低噪音。`dp(48)` 这个魔数可以提升为常量，与更新记录里已声明的“可点区域不小于 48dp”策略放在一起。

### 12. `probeMedia` / `controlMedia` 手写回调计数器

位置：[BrowserActivity.kt:926-955](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L926-L955)、[1022-1041](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L1022-L1041)

`var waiting = live.size` + `if (--waiting == 0) settle()` + `runCatching{…}.onFailure{…}` 相当于把倒计时闩锁实现了两遍，且存在两条递减路径需要人工保持一致。`probeMedia` 的 `done` 回调只服务于 `onPause` 里“保活已启动则恢复播放”这一个场景。项目已依赖协程，用 `awaitAll` 风格的收集会更短、更不容易出错。

---

## P2 — 较小收益与健壮性

### 13. `DataUrl` / blob 解码把整个负载同时留在内存

位置：[DataUrl.kt:30-34](../../app/src/main/java/app/xvi/web/DataUrl.kt#L30-L34)、[DownloadController.kt:225-236](../../app/src/main/java/app/xvi/web/DownloadController.kt#L225-L236)

`write()` 先 `require(url.length <= limit * 4/3 + 1024)`，再 `file.writeBytes(decode(url))`。在 `MAX_PAGE_FILE` 200 MB 的上限下，base64 `String`、`URLDecoder` 的输出以及最终字节数组会同时存在，峰值约为文件大小的 3 倍——而 `TabMemoryPolicy` 已经把堆用量 70% 视为紧张。`readBlob` 路径已经按 2M 字符分块，建议 `DataUrl` 也改为流式逐块解码以压低峰值。

### 14. `prefs.saveTabs` 在主线程同步写入

位置：[BrowserActivity.kt:520-527](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L520-L527)

`persist()` 会被 `showTab()`、`addTab()`、`closeTab()`、页面回调和 `onPause` 调用，内部构造两个 `JSONArray` 并通过 `SharedPreferences.edit{}` 写出，即调用线程上的同步提交语义。`schedulePersist()` 已经用 500ms 合并突发写入，`persist()` 可以把落盘交给 store 的写线程，同时保持内存快照的同步更新。

### 15. `externalLink` 解析后直接启动，缺少兜底

位置：[PageInteractions.kt:128-140](../../app/src/main/java/app/xvi/web/PageInteractions.kt#L128-L140)

`pm.resolveActivity(target, 0)` 在 API 30+ 需要 `<queries>` 才能看到其他应用，因此 Android 11 及以上 `app` 会静默为 `null`，对话框会退化成显示 scheme 而不是应用名（仅影响观感）。更值得处理的是：`host.startActivity(target)` 写在按钮回调里且**没有** `runCatching` 包裹，只有解析那一步有。一旦抛出 `SecurityException` / `ActivityNotFoundException`，应用会崩溃——而这里恰恰是面向用户点击的路径，最需要兜底。

### 16. 对框架调用使用非空断言

[BrowserActivity.kt:188](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L188) 的 `contentResolver.openInputStream(uri)!!` 位于 `runCatching` 内（不会崩溃，但 `!!` 掩盖了意图）；[BrowserActivity.kt:670](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L670) 的 `target!!.session` 出现在一个并不构成智能转换的 `target?.error != null` 判断之后。两处都值得改成显式处理。

### 17. `::x.isInitialized` 守卫方式不统一

`findBar`、`searchOverlay`、`tabButton`、`prefs` 各自在不同的方法里有自定义守卫，有的方法却没有：[`updateBackCallback`](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L680-L685) 守卫了 `findBar`，[`updateTabCounter`](../../app/src/main/java/app/xvi/web/BrowserActivity.kt#L608-L617) 守卫了 `tabButton`，而 `hideSearch` 没有。目前能正常工作，但对 `buildChrome()` 的重排序很脆弱。建议合并为单一门控，例如 `private val chromeReady get() = ::snackbar.isInitialized`，从根上消除这类问题。

### 18. `SearchOverlay.typed` 中的冗余条件

[SearchOverlay.kt:135](../../app/src/main/java/app/xvi/web/SearchOverlay.kt#L135)：`start in 0 until text.length && field.selectionEnd == text.length` —— 后一个条件已隐含前一个。

### 19. `SettingsPanel` / `SiteInfoPanel` 每次重绘都重算设置映射

`SiteInfoPanel.siteSettings` 每个开关都会调用一次 `host.siteSettings.get(...)`（内部先做 `siteKey` 的 URI 解析再查 Map），`sizeLabel()` 每次渲染也会重算。影响不大，但与 P0 #7 是同一类 `siteKey` 成本。

---

## P3 — 构建、测试与仓库规范

### 20. 没有 CI

仓库里完全没有 `.github/` 目录。项目已有三项可靠检查（`npm test`、`npm run lint`、`assembleDebug`），并且 `lint { abortOnError true }` 早已配置好。加一个 push 触发的工作流即可防止 P1 #15 这类问题流入主干，也能避免 lint 干净的状态随时间腐化。

### 21. `targetSdk 36` 配 `compileSdk 37`

这是 Lint 报告里唯一的警告。要么把 `targetSdk` 提到 37，要么在 `lint.xml` 里加一条带日期和理由的抑制；留着一个已知警告，会让下一个真正需要关注的警告容易被忽略。

### 22. 测试覆盖存在几处明显缺口

纯逻辑覆盖得不错，但以下都没有测试：`ReaderExtractor` 面对恶意 HTML 的行为（`unlikely` 正则 + `Safely` 这条路径与安全相关）、`HistoryDays` 在夏令时边界的行为（`Math.round` 吸收一小时的做法未被验证）、`LocalAddress` 的 IPv6 分支（`fc00::/7`、`fe80::/10` 以及 `%zone` 剥离）、`DataUrl` 的体积上限拒绝。这些都是纯函数，按现有 JUnit 风格补测试成本很低。

### 23. 缺少性能回归信号

P0 全部关于主线程热路径，但目前完全不可观测。从 `@VisibleForTesting internal fun trimTabs` 可以看出项目愿意为测试开放内部结构。引入一个 Macrobenchmark 模块，甚至只是在 `updateChrome` 上加一个每页加载调用次数计数器，就能让“这次改动是否变快了”变成可回答的问题。

---

## 建议执行顺序

1. **P0 #1、#3、#4** —— 同一文件，约 40 行，直接去掉逐事件的分配和一次 IME 跨进程调用。
2. **P1 #9** —— 共享一个 OkHttp 基础 client，删掉“每次调用新建两个 client”。
3. **P0 #5 + P1 #12** —— 把 `MediaProbe` 作为基于协程的独立单元提取出来，同时解决 15 秒全量扫描与重复的倒计时闩锁。
4. **P1 #8** —— 合并两条存储写入通道；这是最可能预防未来死锁的一项改动。
5. **P0 #6、#7** —— 首页视图树缓存与 URL 记忆化。
6. **P3 #20、#22** —— CI 加上四个缺失的纯函数测试。
7. **P1 #10** —— 工具栏与标签展示层提取，放在最后做，此时上面各项已经把方法体压小了。

---

## 执行结果

按上述顺序已完成的改动（`git log` 中三个提交）：

| 项 | 状态 | 落点 |
| --- | --- | --- |
| P0 #1 工具栏刷新开销 | 完成 | 缓存锁 / 信息图标与代理颜色；`isSecure` 记忆化；窗口标志仅在变化时设置 |
| P0 #3 输入法重启 | 完成 | `applyInputPrivacy` 按输入框记录上次状态 |
| P0 #4 高度调整重复投递 | 完成 | 复用单个 `Runnable`，投递前先 `removeCallbacks` |
| P0 #5 周期媒体探测 | 完成（保守版） | 未出现过媒体时只探测当前标签；出现过则仍全量探测，避免漏掉后台静音播放 |
| P0 #6 首页视图树重建 | 完成 | 视图只构建一次，`render()` 只更新文字、提示、继续卡片与列表 |
| P0 #7 列表逐行解析地址 | 完成 | `BrowserInput.display` 与 `BrowserStore.siteKey` 加入有上限的记忆化 |
| P1 #8 存储写入通道 | 完成 | `write` 变为挂起、新增 `writeAsync`；顺带修复权限列表「刚清除又出现」的竞态 |
| P1 #9 OkHttpClient | 完成 | 新增 `BrowserHttp`，查看源代码 / 媒体封面 / 搜索联想共用连接池 |
| P1 #12 手写回调计数器 | 完成 | 随 `MediaProbe` 提取一并整理 |
| P3 #20 CI | 完成 | `.github/workflows/ci.yml`：脚本测试、单元测试、Lint、debug APK（设备测试不在其中，无模拟器） |
| P3 #22 缺失的纯函数测试 | 完成 | `ReaderSafetyTest`，以及 `DataUrl` 上限、夏令时 / 跨年、本机地址边界与 IPv6 |
| P3 #21 `targetSdk` | 未做 | 需要真机回归，未在无设备环境下单独升级 |
| P0 #2 `applyPageDarkening` 时机 | 未做 | 需要真机确认页面变暗行为，收益也小于其他各项 |
| P1 #11 布局样板代码 | 未做 | 约 130 处机械替换，会淹没真正有意义的 diff，收益仅为可读性 |
| P1 #10 工具栏与标签层提取 | 未做 | 需要设备测试才能验证；见下 |
| P2 #13–#19 | 未做 | 其中 #15（`externalLink` 的 `startActivity` 缺少 `runCatching`）仍建议优先处理 |

### 执行过程中发现并修复的额外缺陷

- **历史分组跨夏令时 / 跨年算错**：原来用毫秒差做四舍五入，夏令时切换后相隔 25 小时的两天会被算成两天前。改为按历法天数（epoch day）计算，并补了测试。
- **权限重置后列表显示旧值**：`SiteInfoPanel` 排队写入后立即重读数据库，可能读到尚未删除的行。改为等待写入完成再重读。
- **`DataUrl` 上限测试缺失**：补测时确认限额在解码前生效，且被拒绝时不会留下文件。

### 关于 P1 #10（已评估，结论是「不做视图搬运」）

真机可用后重新评估了这一项，方法是不再靠印象，而是先把两个候选区域的成员依赖算出来：

| 候选 | 用到的 Activity 成员数 | 与其他区域的交集 |
| --- | --- | --- |
| 工具栏（构建 + `updateChrome` / `updateProgress` / `updateTabCounter` / 地址同步 / 收起展开） | 34 | 与标签区域重叠 20+ |
| 标签（`showTab` / `load` / `addTab` / 关闭 / 恢复 / 错误页） | 28 | 同上 |

也就是说，把工具栏搬进 `BrowserChrome` 需要给它一个约 25 个成员的接口（地址、进度、面板、搜索层、键盘状态、标签状态、网络……），耦合不会消失，只会从「同一对象的私有成员」变成「一个宽接口」。同时这些代码绝大部分是视图操作，没有 Robolectric 之类的手段就无法在 JVM 上断言，搬运并不能换来可测试性。

所以这一轮的结论是：**不搬运视图代码**，改为把其中真正是决策的部分抽成纯逻辑并加测试，这也正是 `BrowserNavigation` 已经在用的写法。本轮据此新增：

- `PageShell`：返回键先关闭哪一层界面（全屏视频 → 阅读模式 → 页内查找 → 地址编辑），以及浏览器是否接管返回键。顺序原本散在 `handleOnBackPressed` 的 `when` 里且没有任何测试，现在有 5 个用例固定它。

如果将来确实要拆 `BrowserActivity`，正确的投入顺序是：**先引入 Robolectric**（让视图层可断言），再做 `BrowserChrome`；否则建议只继续按上面这种方式抽取决策逻辑。这比直接搬迁视图代码更有价值。
