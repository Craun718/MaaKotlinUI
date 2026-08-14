# 参考项目 深度拆解 12：底层细节——Root 模式 Binder 回传机制

> root 进程与 App 进程之间怎么传 binder？参考项目 用 **ContentProvider 作为握手通道**：
> root 进程经 `getContentProviderExternal` 拿到 App 的 ContentProvider，通过 `call()` 把服务 binder 送回。

---

## 1. 全链路时序

```
App（RootRemoteServiceConnector.connect）
 ├─ token = UUID.randomUUID().toString()
 ├─ RootServiceBootstrapRegistry.register(token)   // ConcurrentHashMap<token, CompletableDeferred<IBinder>>
 ├─ Shell.cmd('liblauncher.so --apk=... --starter-class=RootServiceStarter --token=<token> ... &')
 └─ withTimeout(15s) { deferred.await() }          // 挂起等 binder

root 进程（liblauncher.so → app_process → RootServiceStarter.main）
 ├─ RootUserService.create(args) → 反射实例化 RemoteServiceImpl（application context）
 ├─ RootServiceStarter.sendBinder()
 │    └─ RootServiceBootstrapClient.attachRemoteService(pkg, userId, token, serviceBinder)
 │         ├─ authority = pkg + ".root.bootstrap"
 │         ├─ provider = ActivityManager.getContentProviderExternal(authority, userId, ...)
 │         ├─ extras: { token, service_binder }
 │         └─ reply = provider.call(authority, METHOD_ATTACH_REMOTE_SERVICE, null, extras)
 │              └─ App 侧 RootServiceBootstrapProvider.call() 收到
 │                   ├─ 校验 callingUid ∈ {SHELL_UID, 0}
 │                   ├─ appBinder = Registry.attach(token, serviceBinder)  → deferred.complete()
 │                   └─ 返回 { app_binder, app_pid }
 │    ← 拿到 reply：lifecycleBinder + appPid
 ├─ primeHeartbeat(service, appPid)      // 提前喂 App pid 给引擎看门狗
 └─ linkToDeath(lifecycleBinder)         // App 死 → 销毁 root 服务
```

---

## 2. RootServiceBootstrapRegistry（App 侧登记表）

```kotlin
object RootServiceBootstrapRegistry {
    const val AUTHORITY_SUFFIX = ".root.bootstrap"
    const val METHOD_ATTACH_REMOTE_SERVICE = "attachRemoteService"
    const val KEY_TOKEN / KEY_SERVICE_BINDER / KEY_APP_BINDER / KEY_APP_PID

    private val pendingBinders = ConcurrentHashMap<String, CompletableDeferred<IBinder>>()
    private val appLifecycleBinder = Binder()   // App 侧常驻 binder（root 进程监听其死亡）

    fun register(token): CompletableDeferred<IBinder>   // 登记
    fun unregister(token)                                // 取消
    fun attach(token, binder): IBinder? {
        val deferred = pendingBinders.remove(token) ?: return null   // token 不存在 → null
        deferred.complete(binder)
        return appLifecycleBinder                                     // 把 App 生命周期 binder 给 root
    }
}
```

**token 握手**：每次启动新 token，防止旧 root 进程 binder 串线到新连接。

---

## 3. RootServiceBootstrapProvider（App 侧 ContentProvider）

```kotlin
class RootServiceBootstrapProvider : ContentProvider() {
    override fun call(method, arg, extras): Bundle? {
        if (method != ATTACH_REMOTE_SERVICE || extras == null) return super.call(...)

        // 安全校验：只接受 shell(2000) 或 root(0) 调用方
        val callingUid = Binder.getCallingUid()
        if (callingUid != SHELL_UID && callingUid != 0) return null

        val token = extras.getString(KEY_TOKEN) ?: return null
        val binder = extras.getBinder(KEY_SERVICE_BINDER) ?: return null

        val appBinder = Registry.attach(token, binder) ?: return null   // token 不存在 → 拒绝
        return Bundle().apply {
            putBinder(KEY_APP_BINDER, appBinder)   // App 生命周期 binder（root 端 linkToDeath）
            putInt(KEY_APP_PID, Process.myPid())   // App pid（引擎看门狗）
        }
    }
    // query/insert/delete/update 全部返回 null/0（仅 call 有意义）
}
```

**细节**：
- `call()` 是 ContentProvider 里唯一被用的方法（query/insert 等都是空实现）。
- `Binder.getCallingUid()` 校验调用方（防其他进程伪造）。
- 返回值 Bundle 里塞 **App 的 lifecycleBinder**——root 进程 linkToDeath 它，App 死则 root 服务自杀。

---

## 4. RootServiceBootstrapClient（root 进程侧握手）

```java
public static BootstrapResult attachRemoteService(String packageName, int userId, String token, IBinder serviceBinder) {
    String authority = packageName + ".root.bootstrap";
    IBinder providerToken = new Binder();
    IContentProvider provider = null;
    try {
        // 1. 拿 App 的 ContentProvider（shell/root 身份可 getContentProviderExternal）
        provider = ActivityManager.getContentProviderExternal(authority, userId, providerToken, authority);
        if (provider == null || !provider.asBinder().pingBinder()) return null;

        // 2. 组装 extras 调 call()
        Bundle extras = new Bundle();
        extras.putString(KEY_TOKEN, token);
        extras.putBinder(KEY_SERVICE_BINDER, serviceBinder);   // 服务 binder 放进 Bundle 跨进程
        Bundle reply = RootIContentProviderCompat.call(provider, null, null, authority,
            METHOD_ATTACH_REMOTE_SERVICE, null, extras);

        // 3. 读回 App 的 lifecycleBinder + appPid
        IBinder lifecycleBinder = reply.getBinder(KEY_APP_BINDER);
        int appPid = reply.getInt(KEY_APP_PID, 0);
        return new BootstrapResult(lifecycleBinder, appPid);
    } finally {
        if (provider != null) ActivityManager.removeContentProviderExternal(authority, providerToken);
    }
}
```

**关键 API**：`ActivityManager.getContentProviderExternal(authority, userId, token, callingTag)`——
shell/root 身份可获取**其他应用导出的 ContentProvider**（App 的 bootstrap provider 无需导出声明即可被 shell 取到？实际 Manifest 里 provider 需要 export 或 shell 权限；参考项目 的 provider 应该是 `android:exported=true` + 内部校验）。

`RootIContentProviderCompat.call`：兼容不同系统版本的 `IContentProvider.call` 签名（Android 11 加了 attributionTag 参数等）。

---

## 5. RootServiceStarter.sendBinder + 生命周期绑定

```java
private static boolean sendBinder(CreatedService createdService) {
    BootstrapResult result = RootServiceBootstrapClient.attachRemoteService(
        createdService.packageName(), createdService.userId(), createdService.token(), createdService.service());
    if (result == null) return false;

    primeHeartbeat(createdService.service(), result.appPid());   // 提前喂 App pid

    // linkToDeath：App 进程死亡 → 销毁服务并退出
    IBinder.DeathRecipient recipient = () -> {
        destroyService(createdService.service());   // transact DESTROY
        System.exit(0);
    };
    result.lifecycleBinder().linkToDeath(recipient, 0);
    appLifecycleBinder = result.lifecycleBinder();   // 强引用防 GC 导致死亡通知失效
    appDeathRecipient = recipient;
    return true;
}
```

- **appLifecycleBinder 强引用**：BinderProxy 被 GC 会导致 linkToDeath 失效——注释明确标注。
- `destroyService`：`service.transact(DESTROY_TRANSACTION_CODE=16777115, ...)` 直接发事务码（不用 AIDL，避免加载接口）。
- `primeHeartbeat`：`service.queryLocalInterface(RemoteService.class.getName())` 判类型后调 heartbeat。

---

## 6. 与火影版 RootServiceStarter 的对比

| 点 | 火影版 | 参考项目 |
|---|---|---|
| 启动器 | `CLASSPATH=apk app_process ...`（su 执行） | **liblauncher.so**（原生 so，绕过 SELinux/CLASSPATH 限制） |
| binder 回传 | ServiceManager.addService（root addService）+ App 轮询 getService | **ContentProvider call() 直达回传**（不需要 ServiceManager，Android16 兼容） |
| App 死亡感知 | app_process watch /proc | **lifecycleBinder linkToDeath** + heartbeat /proc 双保险 |
| token | 无 | **UUID token 握手**（防串线） |
| 调试 | /data/local/tmp 日志 | --log-file 到外部目录 + root_launch_debug.log |

---

## 7. 给复现者的要点清单

1. Root 回传首选 **ContentProvider.call()**（App 侧注册 bootstrap provider），shell/root 可 getContentProviderExternal。
2. **token 握手** + callingUid 校验（只收 shell/root）。
3. 回传双向：serviceBinder 给 App，lifecycleBinder 给 root（linkToDeath 保 App 死则 root 自杀）。
4. lifecycleBinder 必须强引用，否则 GC 后死亡通知失效。
5. 用 liblauncher.so 启动而非 CLASSPATH=apk（SELinux 兼容）。
6. destroy 用裸 transact 事务码（不加载 AIDL）。