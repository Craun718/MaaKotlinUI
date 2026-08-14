# 参考项目 深度拆解 09：底层细节——ActivityThread 与系统上下文构造

> 引擎进程（shell/root）没有正常 App 生命周期，怎么获得可用的 Context？
> 三个关键类：`Workarounds`（ActivityThread 构造）、`FakeContext`（shell 假上下文）、
> `RootUserService`（root 进程建服务）。本文逐个拆到反射字段级。

---

## 1. Workarounds：手搓 ActivityThread（scrcpy 同款）

引擎进程里没有 `ActivityThread`（那是 App 启动时由 zygote 创建的）。参考项目 静态块里**反射 new 一个**：

```java
static {
    prepareMainLooper();

    // 1. new ActivityThread()
    ACTIVITY_THREAD_CLASS = Class.forName("android.app.ActivityThread");
    Constructor<?> ctor = ACTIVITY_THREAD_CLASS.getDeclaredConstructor();
    ctor.setAccessible(true);
    ACTIVITY_THREAD = ctor.newInstance();

    // 2. ActivityThread.sCurrentActivityThread = activityThread   （静态，单例语义）
    Field f1 = ACTIVITY_THREAD_CLASS.getDeclaredField("sCurrentActivityThread");
    f1.setAccessible(true);
    f1.set(null, ACTIVITY_THREAD);

    // 3. activityThread.mSystemThread = true   （标记为系统线程，绕过系统上下文校验）
    Field f2 = ACTIVITY_THREAD_CLASS.getDeclaredField("mSystemThread");
    f2.setAccessible(true);
    f2.setBoolean(ACTIVITY_THREAD, true);
}
```

### prepareMainLooper

```java
// 与 Looper.prepareMainLooper() 等价，但 quitAllowed=true（进程要常驻）
if (Looper.myLooper() != null) return;
Looper.prepare();
synchronized (Looper.class) {
    Field f = Looper.class.getDeclaredField("sMainLooper");   // 把当前 looper 注册为 sMainLooper
    f.setAccessible(true);
    f.set(null, Looper.myLooper());
}
```

### apply()：给 ActivityThread 补三件套

```kotlin
fun apply() {
    if (SDK >= 31) fillConfigurationController();   // 三星 DisplayManagerGlobal 需要非空 ConfigurationController
    if (!BRAND.equalsIgnoreCase("ONYX")) fillAppInfo();  // ONYX 上填 appInfo 会破坏投屏，跳过
    fillAppContext();
}
```

**fillConfigurationController**（Android 12+ 必需）：
```java
// new ConfigurationController(ACTIVITY_THREAD); ACTIVITY_THREAD.mConfigurationController = ...
// 修复：DisplayManagerGlobal.getDisplayInfoLocked() 调 ActivityThread.currentActivityThread().getConfiguration()
// 缺它 → 三星设备 getDisplayInfo 空指针（scrcpy#4467）
```

**fillAppInfo**（AppBindData + mBoundApplication）：
```java
// new ActivityThread.AppBindData(); appInfo = new ApplicationInfo(); appInfo.packageName = "com.android.shell"
// activityThread.mBoundApplication = appBindData
// 让 getApplicationInfo() 有值（某些系统 API 依赖）
```

**fillAppContext**（mInitialApplication）：
```java
Application app = Instrumentation.newApplication(Application.class, FakeContext.get());
// activityThread.mInitialApplication = app
// 让 currentApplication() 非空
```

### getSystemContext

```java
// ACTIVITY_THREAD.getSystemContext() —— FakeContext 的 base context
```

---

## 2. FakeContext：冒用 shell 身份的 ContextWrapper

引擎进程里所有需要 Context 的地方都用它（ActivityUtils、wrappers 等）：

```java
public final class FakeContext extends ContextWrapper {
    public static final String PACKAGE_NAME = "com.android.shell";   // 冒用 shell 包名！
    private static final FakeContext INSTANCE = new FakeContext();
    private FakeContext() { super(Workarounds.getSystemContext()); }

    @Override public String getPackageName() { return PACKAGE_NAME; }   // 系统认为是 shell
    @Override public Context getApplicationContext() { return this; }   // ★ 永不为 null
    @Override public int checkCallingPermission(String p) { return PERMISSION_GRANTED; }  // ★ 永远放行

    @Override public AttributionSource getAttributionSource() {
        return new AttributionSource.Builder(Process.SHELL_UID)   // 身份：shell uid 2000
            .setPackageName(PACKAGE_NAME).build();
    }

    // ContentResolver：通过 ActivityManager.getContentProviderExternal 取外部 provider
    private final ContentResolver contentResolver = new ContentResolver(this) {
        protected IContentProvider acquireProvider(Context c, String name) {
            return ServiceManager.getActivityManager().getContentProviderExternal(name, new Binder());
        }
        ...
    };
}
```

**设计意图**：
1. `getPackageName()=com.android.shell`：引擎进程冒充 shell 应用，`am`/系统服务按 shell 身份放行（BAL、注入、创建虚拟屏）。
2. `getApplicationContext()=this`：**根除 `applicationContext` 为 null 的一切问题**——任何 `context.applicationContext.xxx` 都安全。
3. `checkCallingPermission` 永远 GRANTED：内部权限检查永远通过。
4. `getAttributionSource` 显式给 shell uid：Android 12+ 的属性溯源正确。
5. ContentResolver 走 `getContentProviderExternal`：引擎进程也能访问 ContentProvider（Root 模式回传 binder 就靠它）。

---

## 3. RootUserService：root 进程内建服务（与 Shizuku 服务端同思路）

Root 模式下，`RootServiceStarter.main()` 被 app_process 拉起后，由 `RootUserService.create()` 构造引擎服务：

```java
public static CreatedService create(String[] args) {
    int userId = uid / 100000;

    // 1. ActivityThread.systemMain()（不是 new——root 进程用系统主线程模式）
    Object activityThread = createActivityThread();   // systemMain()
    Context systemContext = getSystemContext(activityThread);

    // 2. createPackageContextAsUser(pkg, INCLUDE_CODE|IGNORE_SECURITY, userHandle)
    Context packageContext = createPackageContextAsUser(systemContext, pkg, userId);

    // 3. makeApplication（LoadedApk.makeApplication(true, null)）
    Application application = makeApplication(activityThread, packageContext);
    //    ★ 失败降级：MIUI 等 OEM 改了 makeApplication，shell 身份下可能初始化失败；
    //      packageContext 本身可用 → 直接用 packageContext 作为构造器 context
    Context constructorContext = application != null ? application : packageContext;

    // 4. 加载服务类，优先 (Context) 构造器，否则无参
    Class<?> clazz = constructorContext.getClassLoader().loadClass(className);
    IBinder service = instantiateService(clazz, constructorContext);

    return new CreatedService(service, token, pkg, userId);
}
```

### 与 Shizuku 服务端的对照

| 步骤 | Shizuku 服务端 UserService.java | 参考项目 RootUserService.java |
|---|---|---|
| ActivityThread | `ActivityThread.systemMain()` | `ActivityThread.systemMain()`（同） |
| context | `createPackageContextAsUser` + `makeApplication` | 同 |
| 构造器 | `(Context)` 优先，无参兜底 | 同 |
| 降级 | 无（异常直接返回 null） | **makeApplication 失败降级用 packageContext**（应对 MIUI） |

**验证**：两种模式的服务类都**不需要实现任何接口**，只要 `(Context)` 构造器或无参构造，且实例 `instanceof IBinder`。

### makeApplication（LoadedApk.makeApplication 反射）

```java
Field mPackageInfo = packageContext.getClass().getDeclaredField("mPackageInfo");
Object loadedApk = mPackageInfo.get(packageContext);
Method makeApplication = loadedApk.getClass()
    .getDeclaredMethod("makeApplication", boolean.class, Instrumentation.class);
Application app = (Application) makeApplication.invoke(loadedApk, true, null);
// ActivityThread.mInitialApplication = app（回写）
```

---

## 4. 三者关系图

```
Workarounds（引擎进程启动时执行）
  ├─ new ActivityThread() + sCurrentActivityThread + mSystemThread=true
  ├─ fillConfigurationController / fillAppInfo / fillAppContext
  └─ getSystemContext()  →  FakeContext 的 base

FakeContext（引擎进程通用 Context）
  ├─ super(Workarounds.getSystemContext())
  ├─ 包名=com.android.shell、uid=shell、getApplicationContext()=this、权限永远放行
  └─ 供 ActivityUtils / wrappers / DriverClass 使用

RootUserService（仅 Root 模式，root 进程内）
  ├─ systemMain() + createPackageContextAsUser + makeApplication（失败降级）
  └─ 反射实例化 RemoteServiceImpl → 传给 RootServiceStarter 回传 binder
```

---

## 5. 给复现者的要点清单

1. 引擎进程要先 `Workarounds.apply()`（或等价）才有可用 ActivityThread/系统上下文。
2. Android 12+ 必须填 `mConfigurationController`，否则三星等设备 getDisplayInfo NPE。
3. FakeContext 三件套：`getPackageName()=com.android.shell`、`getApplicationContext()=this`、`checkCallingPermission=GRANTED`。
4. Root 模式 makeApplication 失败要降级 packageContext（OEM 兼容）。
5. 服务类无需接口，`(Context)` 构造器或无参构造即可，实例是 IBinder。