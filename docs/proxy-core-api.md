# proxy-core 宿主接口

本文是共享核心的接口基准文档，由 `proxy-core/scripts/sync-api-docs.mjs` 同步到 note、web；不要直接修改宿主中的副本。源码仓库为 [xviapp/proxy-core](https://github.com/xviapp/proxy-core)。

## 边界与默认行为

核心是 Android library，不提供 Activity、Compose、设置页或节点管理 UI。只支持一个 HTTP/HTTPS 订阅；保存地址不下载，手动更新和按设定间隔定期更新均显式使用 `Proxy.NO_PROXY`，包括重定向，不经过内置代理。系统 VPN、设备网络限制仍可能影响实际网络路径。

更新自动测试全部节点、选择延迟最低的可用节点，然后发布完整的新配置；失败保留上一次成功节点和选择。修改 URL 后，旧节点仍可用，直到新订阅成功更新。定期更新由 WorkManager 按网络约束调度，不保证精确执行时间；更新间隔默认 1440 分钟，最短 15 分钟；首次保存未更新的订阅在设定间隔后自动更新，首次使用应手动更新。

当前节点连通性检测默认间隔 5 分钟，最短 1 分钟。宿主控制器在代理 RUNNING 时调度，关闭或停止运行时取消；宿主进程退出或设备休眠可能使检测延后。平时仅检测当前节点；连续 3 次检测失败或延迟超过 2000 毫秒时，重新测试现有全部节点并选择最快的可用节点，无可用节点时保留原选择。成功检测、关闭代理或切换选择会重置连续异常计数。这个过程不下载订阅；结果通过订阅快照发布。

没有多订阅、备份恢复、搜索、节点导入/分享、手动测速、指定节点选择、配置编辑、VPN/TUN/root、系统代理或按应用分流接口。“下一个节点”是当前订阅内可用节点的循环切换，不是订阅分组切换。

## 构建与初始化

三个独立仓库放在同级目录：`note/`、`web/`、`proxy-core/`。从 note 或 web 根目录执行：

```sh
git clone https://github.com/xviapp/proxy-core.git ../proxy-core
```

若已有此目录，直接使用现有 checkout，不覆盖或重复克隆。核心可独立构建，也可作为宿主 Gradle 模块 `:v2ray` 构建；当前不发布 Maven 包。依赖版本由核心维护，不依赖宿主 `libs` version catalog；需要 Android SDK 37，最低 Android 26，源码 Java/Kotlin target 17，构建使用 JDK 21。作为源码模块引入时，宿主根工程仍需提供兼容的 Android/Kotlin 插件（当前为 AGP 9.4.1、Kotlin 2.4.20）。

```groovy
// note/android/settings.gradle
include ':v2ray'
project(':v2ray').projectDir = file('../../proxy-core')
// web/settings.gradle 使用 file('../proxy-core')
```

宿主只声明 `implementation project(':v2ray')`。核心的 `prepareXray` 自动下载固定版本并校验 SHA-256，将 Java JAR 和 JNI 库打包进 library；无需知道任务名、原生 AAR 路径或设置 `evaluationDependsOn`。加工目录为各 Gradle 根目录自己的 `build/proxy-core/xray`，独立构建则为 `build/xray`；下载使用唯一临时文件，两个宿主并行构建不会写同一缓存。旧 `libs/libv2ray.aar` 若存在且校验正确，只作为只读种子；其他旧缓存不再使用，可以保留或自行清理。

宿主保留自己的 Application，并在每个进程（包括 `:daemon`）的 `onCreate` 中调用：

```java
@Override public void onCreate() {
    super.onCreate();
    ProxyCore.initialize(this); // 默认普通核心服务，宿主负责前台保活
    // note 使用 ProxyCore.initialize(this, true)，由核心显示前台通知。
}
```

`ProxyCore.initialize(Context, boolean serviceForeground)` 是每进程幂等初始化，同一进程不能重复传入不同选项；失败后允许重试。应用包名自动用于进程通信，旧 note/web 设置键保持兼容；不再需要三个 `proxyCore*` Gradle 属性。Android 26/27 也区分主进程与 daemon，避免重复调度。

初始化负责 MMKV、迁移和主进程订阅调度，复用已初始化的 WorkManager 或宿主的 `Configuration.Provider`；模块不移除 WorkManager 的默认初始化器，也不覆盖宿主 Application。使用宿主自定义 WorkManager 配置时，默认初始化器的禁用由宿主按自身策略负责。旧更新任务因 Worker 包名变化会迁移到新的唯一任务。

`ProxyCore.isInitialized()` 查询初始化状态；`ProxyCore.info()` 返回只读 `ProxyInfo`，字段为 `sourceVersion`、`sourceCommit`、`coreVersion`（均有 Java getter）。这些接口不要求读取库 BuildConfig。获取控制器和版本信息前必须初始化。

note 使用核心前台服务通知；web 由浏览器前台保活服务负责通知，通过下述后台接口维持核心绑定。权限申请、通知权限和 Android 前台服务启动条件由宿主处理。两个应用存储彼此隔离；两套 Gradle 根目录的生成代码和输出也相互隔离。

## 唯一控制入口

包名 `com.v2ray.ang.api`，入口 `EmbeddedProxyController`。

宿主仅依赖此包。`com.v2ray.ang.internal` 下的存储、订阅、运行时、Xray 桥接和数据模型不属于兼容承诺；不要直接导入或调用。

```java
EmbeddedProxyController controller =
    EmbeddedProxyController.get(context, routing);
Observation observation = controller.observe(state -> render(state));
controller.prepare(state -> onProxyPrepared(state));
// 宿主销毁时调用；不要在普通页面重绘时重复注册。
observation.close();
```

`get(Context, Routing)` 每个进程返回一个实例，保留 applicationContext 和第一次传入的 Routing。宿主只在主应用进程创建控制器；Routing 不应持有已经销毁的 Activity。

### Routing：宿主网络适配

| 方法 | 宿主责任 |
| --- | --- |
| isSupported() | 检查 AndroidX WebView 代理覆盖是否可用 |
| setProxy(int port, Runnable applied) | 将宿主 WebView 路由指向 127.0.0.1:port 的 SOCKS 代理，生效后调用 applied |
| clearProxy(Runnable cleared) | 清除宿主代理覆盖，完成后调用 cleared |

当前运行时依赖这些完成信号推进状态；请在主线程回调，成功完成时只调用一次，不要提前回调。适配器不负责下载订阅、启动服务、存储配置或重连。现有实现见 note 的 `NoteProxyNetwork` 和 web 的 `BrowserProxyNetwork`。

### 控制器方法

下表 `done` 类型均为 `Consumer<ProxyState>`。

| 方法 | 语义 |
| --- | --- |
| observe(observer) → Observation | 在主线程先发送当前快照，再发送变化；close() 幂等，可从任意线程调用 |
| prepare(done) | 按持久化开关准备核心；回调表示路由已应用或失败已确定 |
| status(done) | 获取当前快照，不主动启动核心 |
| setEnabled(boolean, done) | 保存开关并启停；关闭后清除应用内代理 |
| retry(done) | 重载/重试当前节点；不会擅自开启已关闭的代理 |
| setSubscription(String, done) | 保存唯一 HTTP/HTTPS URL，保留现有间隔；不下载，不清除旧可用节点 |
| setSubscription(String, updateIntervalMinutes, testIntervalMinutes, done) | 同时保存地址、更新间隔（至少 15 分钟）和连通性检测间隔（至少 1 分钟） |
| refreshSubscription(done) | 直连下载、自动测试、选优、提交；并发更新合并为同一个任务，各调用者获得完成结果 |
| selectNextNode(done) | 在延迟 > 0 的可用节点中按存储顺序循环；更新中拒绝切换 |
| setBackgroundActive(boolean) | 宿主前台保活工作开始/结束时报告，核心管理 daemon 绑定和心跳 |
| static isEnabled() | 持久化开关，不代表核心已经运行 |
| static coreVersion() | 原生 Xray 版本字符串，需完成应用初始化并打包原生库 |
| static networkProxy() → java.net.Proxy | 返回已确认的本机 SOCKS 代理；未确认或清除路由后为 Proxy.NO_PROXY |

控制命令由主线程串行处理，完成回调与观察回调均在主线程。订阅命令成功仅表示对应的保存/下载/测试/选择已完成，不能据此认为 daemon 的异步重载也完成；实际网络可用性以观察到的 `phase == RUNNING` 为准。首次导航应等待 `prepare` 完成并检查状态，而非只检查 enabled。

更新期间保存地址或切换节点返回 BUSY，不排队。错误在快照里表达，不以异常作为业务协议；宿主负责本地化文案。观察者异常被记录并隔离，宿主仍应保证回调快速、无阻塞，且不要输出订阅 URL 或凭据。

## 状态模型

### ProxyState

字段同时提供 Java getter；对象是只读快照。

| 字段 | 含义 |
| --- | --- |
| phase | OFF、STARTING、RUNNING、RECONNECTING、FAILED |
| errorCode | 运行时错误码，空字符串表示当前没有错误 |
| retryAtMillis | 下次重试的 Unix 毫秒时间；无计划为 0 |
| enabled | 用户持久化代理开关 |
| hasProfile | 是否存在选中的节点配置，不代表网络连接成功 |
| subscription | 完整的 ProxySubscriptionSnapshot |

核心启动、异常退出或网络切换重载失败会按 1/2/4/8/16/30 秒最多重试六次。重连和失败阶段清除应用内代理；这是直连回退，不是“禁止联网”。普通网页/节点连接错误不会触发自动直连绕过。对严格禁止直连的场景，宿主需要另行阻止请求，当前核心不提供 kill switch。

### ProxySubscriptionSnapshot

| Getter | 含义 |
| --- | --- |
| isUpdating() | 下载/测速/发布是否进行中 |
| getError() | 订阅命令错误码，与运行时 errorCode 独立 |
| getUrl() | 当前保存地址，可能包含敏感凭据，不应记录日志 |
| getUpdateIntervalMinutes() | 订阅更新间隔，默认 1440 分钟 |
| getTestIntervalMinutes() | 当前节点检测间隔，默认 5 分钟 |
| isTesting() | 当前是否正在检测连通性 |
| getLastTested() | 本次进程中最近完成检测的 Unix 毫秒时间，未测试为 0 |
| getTestError() | 检测失败为 CONNECTIVITY_FAILED，延迟超过 2000 毫秒为 CONNECTIVITY_SLOW，正常为空；不影响更新错误 |
| getLastUpdated() | 最近成功更新 Unix 毫秒时间；未成功更新为 0，换地址会重置 |
| getNodes() | 不可修改的节点摘要列表；更新失败时保留上次成功列表 |

### ProxyNode

`getId()` 为内部节点标识，`getName()` 为显示名称，`getType()` 为协议类型，`isSelected()` 为当前选中标记。`getDelayMillis()` 大于 0 表示测试可用、负值表示失败、0 表示旧数据尚未测试。摘要不暴露节点配置或认证字段；不要把 ID 当作跨更新稳定标识。

## 错误码

| 运行时 errorCode | 含义 |
| --- | --- |
| WEBVIEW_UNSUPPORTED | WebView 不支持宿主代理覆盖 |
| NO_PROFILE | 尚无选中的节点配置 |
| START_FAILED | 启动失败或确认超时 |
| RECONNECT_FAILED | 自动重连失败 |

| subscription.error | 含义 |
| --- | --- |
| INVALID_INTERVAL | 更新间隔小于 15 分钟或检测间隔小于 1 分钟 |
| INVALID_URL | 地址不是有效 HTTP/HTTPS URL |
| NO_SUBSCRIPTION | 未保存订阅地址 |
| DOWNLOAD_FAILED | 直连下载失败 |
| NO_NODES | 订阅中没有可解析节点 |
| NO_USABLE_NODES | 没有自动测试通过的节点 |
| BUSY | 更新中，不能保存地址或切换节点 |
| STORAGE_FAILED | 保存地址或选择节点时写入失败 |
| TIMEOUT | 自动测速超时 |
| CANCELLED | 更新任务取消 |
| UPDATE_FAILED | 其他更新失败，包括更新过程中存储失败 |

宿主对未知错误码显示通用失败提示，不展示异常原文或把未知码当作成功。回调中的错误快照可能是本次命令专属结果，应直接检查返回值，不能只等待下一次观察通知。

## 文档同步与验证

在 proxy-core 根目录执行：

```sh
node scripts/sync-api-docs.mjs
node scripts/sync-api-docs.mjs --check
```

同步只覆盖同级仓库的 `note/docs/proxy-core-api.md`、`web/docs/proxy-core-api.md`，不改业务代码，不提交宿主改动。目标缺失或内容不同会在检查时失败。接口变化应先改本文，再同步两个宿主。

核心可以独立构建和测试：

```sh
# proxy-core 根目录
./gradlew testDebugUnitTest assembleDebug
./gradlew connectedDebugAndroidTest
```

Android 接口测试使用独立测试包、本机订阅服务、真实存储和外部 JNI/daemon 测试适配器，不使用真实订阅或公网测速地址。宿主保留自己的 WebView/下载集成测试，旧版本配置迁移夹具仅供测试使用，不作为生产接入方式。

也可以从宿主构建执行：

```sh
# note/android
./gradlew :v2ray:testDebugUnitTest :app:testDebugUnitTest
# web
./gradlew :v2ray:testDebugUnitTest :app:testDebugUnitTest
```

测试不读取真实订阅；真实设备的订阅、WebView 路由、服务恢复和 release/R8 行为仍需单独验证。
