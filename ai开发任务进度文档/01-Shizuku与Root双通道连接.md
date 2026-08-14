# 参考项目 深度拆解 01：Shizuku 与 Root 双通道连接

> 本文深入连接层：App 如何拿到引擎进程的 binder。这是全项目最容易出问题的环节，
> 参考项目 的设计（随机 tag + 递增 version + 独立 connection + 状态机）是经过实战打磨的可靠方案。

---

## 1. 连接抽象：RemoteServiceConnectorBackend

两种后端（Shizuku / Root）实现同一个接口，上层无感：

```kotlin
interface RemoteServiceConnectorBackend {
    val backend: RemoteBackend          // SHIZUKU / ROOT
    fun connect(callbacks: Callbacks)   // 异步发起连接
    fun disconnect(currentBinder: IBinder?)

    interface Callbacks {
        fun onConnected(backend: RemoteBackend, binder: IBinder)
        fun onDisconnected(backend: RemoteBackend)
        fun onError(backend: RemoteBackend, throwable: Throwable)
    }
}
```

---

## 2. 连接状态机：RemoteServiceManager（单例）

这是连接层的心脏，用 `MutableStateFlow` 暴露状态供 UI/业务观察：

```kotlin
sealed class ServiceState {
    object Disconnected   // 未连接
    object Connecting     // 连接中
    object Died           // 曾连接但进程死亡
    class  Connected(val service: RemoteService)
    class  Error(val exception: Throwable)
}
```

### 关键设计点

**a) 全部状态迁移在单一 `lock` 内完成**，避免并发竞态：

```kotlin
private val lock = Any()
private val currentBinder = AtomicReference<IBinder>()
private var currentDeathRecipient: BindingDeathRecipient? = null  // guarded by lock
private val _state = MutableStateFlow<ServiceState>(Disconnected)
```

**b) binder 死亡用「携带 binder 身份的 DeathRecipient」防误判**：

```kotlin
private class BindingDeathRecipient(val binder: IBinder) : IBinder.DeathRecipient {
    override fun binderDied() = onBinderDied(this)
}
// onBinderDied 里校验 currentBinder.get() === recipient.binder，否则视为过期回调直接丢弃
```

迟到的死亡通知（旧 binder 已解绑、新 binder 已上任）靠身份比对丢弃，避免把新连接误判为 Died。

**c) `bind()` 幂等 + 先解旧再绑新**：

```kotlin
fun bind() {
    val backend = RemoteAccessCoordinator.refresh().configuredBackend
    if (!RemoteAccessCoordinator.isGranted(backend)) { ...state=Error; return }
    synchronized(lock) {
        if (state is Connecting && boundBackend == backend) return   // 已在连接，跳过
        if (boundBackend != null) unbindLocked()                     // 先解旧
        boundBackend = backend
        attempt = connectAttempt.incrementAndGet()
        state = Connecting
        connectors.getValue(backend).connect(connectorCallbacks)     // 委托后端
    }
    startConnectTimeout(attempt, backend)
}
```

**d) 连接超时兜底（20s）**：Shizuku 路径无自带超时，用协程延时检查；超时则 disconnect + 复位 + Error(TimeoutException)。

**e) `getInstance()` 挂起等待连接**：

```kotlin
suspend fun getInstance(timeoutMs: Long = 10_000): RemoteService {
    getInstanceOrNull()?.let { return it }
    bind()
    return withTimeout(timeoutMs) {
        _state.first { it is Connected || it is Error }  // 等待终态
    }...
}
```

**f) `useRemoteService {}` 业务便捷入口**：检查授权 → 必要时切换后端 → 拿服务 → 执行 action。所有业务（任务、资源加载）都走它。

---

## 3. Shizuku 连接器：ShizukuRemoteServiceConnector

**这是解决"首次成功、之后全部超时"的关键实现**：

```kotlin
object ShizukuRemoteServiceConnector : RemoteServiceConnectorBackend {
    private val serviceTag = UUID.randomUUID().toString()      // 固定 tag，但只生成一次？NO——见下
    private val serviceVersion = AtomicInteger(100)

    override fun connect(callbacks: Callbacks) {
        val args = createServiceArgs()          // 每次 connect 都调用 → 每次新 tag/version
        val connection = object : ServiceConnection { ... }

        val binding = ActiveBinding(args, connection)
        activeBinding = binding                 // 替换旧绑定
        runCatching {
            Shizuku.bindUserService(args, connection)
        }.onFailure { ...callbacks.onError(...) }
    }

    private fun createServiceArgs(): Shizuku.UserServiceArgs {
        return Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, RemoteServiceImpl::class.java.name)
        ).apply {
            processNameSuffix("service")
            daemon(false)
            tag(serviceTag)                     // ← 每次 connect 重新生成的随机 UUID
            version(serviceVersion.incrementAndGet())  // ← version 递增
            debuggable(BuildConfig.DEBUG)
        }
    }
}
```

### 为什么随机 tag + 递增 version 能根治绑定失败

Shizuku 服务端（shizuku_server）按 `key = packageName + ":" + tag` 管理 UserService 记录：

```java
// UserServiceManager.java（Shizuku 源码）
String key = packageName + ":" + (tag != null ? tag : className);
UserServiceRecord newRecord = createUserServiceRecordIfNeededLocked(record, key, versionCode, daemon, packageInfo);
if (newRecord.service != null && pingBinder()) {
    broadcastBinderReceived();          // 复用存活服务
} else if (!newRecord.starting) {
    startUserService(...);              // 只有 !starting 才启动新进程
}
// starting==true（上次启动卡住/失败未复位）→ 直接 return，App 永远等不到！
```

- **固定 tag 时**：上次引擎 `exitProcess(0)` 强杀后，服务端 record 可能停在 `starting=true` 卡死态 → 后续所有绑定命中同一 key → 服务端不再启动新进程 → 8s 全部超时（这正是火影 MAA 项目踩的坑）。
- **随机 tag**：每次绑定都是全新 key → 服务端必然新建 record 并启动进程 → 永不命中卡死记录。
- **version 递增**：即使 key 相同，version 不匹配也会强制移除旧 record 重建（双保险）。
- **unbind(remove=true)**：主动解绑时销毁服务端 record，保证下次干净。

### 独立 connection + stale 检查

每次 connect 创建**新** ServiceConnection，并校验 `activeBinding?.connection === this` 再处理回调——忽略过期 connection 的回调（旧绑定超时后新绑定已开始）。

### disconnect

```kotlin
override fun disconnect(currentBinder: IBinder?) {
    val binding = activeBinding ?: return
    activeBinding = null
    runCatching { Shizuku.unbindUserService(binding.args, binding.connection, true) }
}
```

---

## 4. Root 连接器：RootRemoteServiceConnector

Root 模式不依赖 Shizuku，用 **su + liblauncher.so** 拉起 root 进程，binder 经 **ContentProvider 回传**：

```
App: RootRemoteServiceConnector.connect()
 ├─ token = UUID.randomUUID()
 ├─ RootServiceBootstrapRegistry.register(token)   // 登记待接收的 binder（CompletableDeferred）
 ├─ startRemoteService(token)                      // Shell.cmd(启动命令)
 │    └─ 命令：'liblauncher.so' --apk=... --process-name=... --starter-class=RootServiceStarter
 │             --token=<token> --package=... --class=RemoteServiceImpl --uid=... [--keep-root] --log-file=... &
 └─ withTimeout(15s) { deferred.await() }          // 等 root 进程经 ContentProvider 回传 binder
```

### root 进程侧回传链路

```
liblauncher.so（root 启动器，C 实现）
 → 以 root 启动 app_process：RootServiceStarter.main()
 → RootUserService.create()（反射实例化 RemoteServiceImpl，传入 Application context）
 → RootServiceStarter.sendBinder()
    → RootServiceBootstrapClient.attachRemoteService(pkg, userId, token, serviceBinder)
       → 通过 ContentProvider（authority=pkg.root.bootstrap）调用 attachRemoteService
       → App 侧 RootServiceBootstrapProvider 收到 → Registry.attach(token, binder) → deferred.complete()
 → linkToDeath(App 的 lifecycleBinder)（App 死亡时销毁 root 服务）
```

关键点：
- **token 随机**：每次启动新 token，Registry 按 token 匹配，防止旧进程 binder 串线。
- **`--keep-root`**（Android 14+）：launcher 保持 root 身份，避免降权后无法创建虚拟屏。
- **`--log-file`**：root 启动日志落盘（root_launch_debug.log），启动失败可读。
- **primeHeartbeat**：提前把 App pid 喂给 RemoteServiceImpl 的 /proc 看门狗。
- **liblauncher.so** 是原生 so（编译期随 APK 打包到 nativeLibraryDir），避免用 `CLASSPATH=apk app_process` 方式被 SELinux 拒绝。

### RootUserService（root 进程内创建服务，与 Shizuku 服务端同思路）

```java
public static CreatedService create(String[] args) {
    ActivityThread at = ActivityThread.systemMain();
    Context systemContext = at.getSystemContext();
    // createPackageContextAsUser(pkg, INCLUDE_CODE|IGNORE_SECURITY, userHandle)
    // makeApplication() → Application
    Class<?> clazz = classLoader.loadClass(cls);
    // 优先 (Context) 构造器，否则无参构造；强转 IBinder
    IBinder service = clazz.getConstructor(Context.class).newInstance(application);
    return new CreatedService(service, token, pkg, userId);
}
```

**验证结论**：Shizuku/Root 的服务类**都不要求实现任何接口**，只需 `(Context)` 构造器或无参构造，且实例是 IBinder。

---

## 5. Shizuku 授权管理：ShizukuManager

```kotlin
object ShizukuManager : RemoteAccessPermissionBackend {
    fun initSui(pkg) { isSui = Sui.init(pkg) }            // Sui root 检测
    fun isAvailable() = Shizuku.pingBinder()
    fun isGranted() = Shizuku.checkSelfPermission() == GRANTED
    fun isRunningAsRoot() = Shizuku.getUid() == 0         // 区分 root 授权 vs adb/shell

    override suspend fun requestPermission(): Boolean {
        // 回调式授权，封装成 suspend（callbackFlow + withTimeoutOrNull(15s)）
        val granted = withTimeoutOrNull(15_000) {
            callbackFlow {
                val requestCode = (1000..9999).random()
                Shizuku.addRequestPermissionResultListener(listener)
                Shizuku.requestPermission(requestCode)
                awaitClose { Shizuku.removeRequestPermissionResultListener(listener) }
            }.first()
        }
        return granted ?: false
    }

    fun ensureStateObservation() {
        Shizuku.addBinderReceivedListenerSticky { notifyStateChanged() }
        Shizuku.addBinderDeadListener { notifyStateChanged() }
    }
}
```

---

## 6. 后端选择与协调：RemoteAccessCoordinator

```kotlin
object RemoteAccessCoordinator {
    private val backends = mapOf(
        ROOT to RootManager,
        SHIZUKU to ShizukuManager
    )

    fun configuredBackend() = appSettings.startupBackend.value ?: SHIZUKU

    fun snapshot() = RemoteAccessState(
        shizukuAvailable, shizukuGranted, rootAvailable, rootGranted, configuredBackend
    )

    suspend fun request(backend) {
        if (!isGranted && isAvailable) backends[backend].requestPermission()
    }
    // initialize 时若配置后端=ROOT，自动请求 Root 授权
}
```

UI 的 ShizukuReadinessGate/ShizukuReadinessDialog 观察该状态，决定是否放行用户操作。

---

## 7. 前端就绪门（UI 层）

- `ShizukuReadinessGate`：Compose 包装，Shizuku 未就绪时显示引导/阻止交互。
- `ShizukuInstallHelper`：检测 Shizuku 未安装时引导下载（打开官方页面/引导安装）。

---

## 8. 给复现者的要点清单

1. `RemoteServiceManager` 单例状态机：Disconnected/Connecting/Connected/Died/Error，所有迁移加锁。
2. binder 死亡用携带 binder 的 DeathRecipient + 身份校验。
3. **Shizuku 绑定：随机 tag + version 递增 + 独立 connection + unbind(remove=true)**——这是血泪经验。
4. Root 模式：liblauncher.so 启动 + token 匹配 + ContentProvider 回传 binder + lifecycleBinder 死亡通知。
5. 连接超时兜底（App 侧 20s；Root 侧自带 15s）。
6. 授权流程必须 suspend 化（callbackFlow），并考虑 preV11 兼容。
7. 服务类无需实现接口，提供 (Context) 构造器即可；RootUserService 与 Shizuku 服务端逻辑一致。