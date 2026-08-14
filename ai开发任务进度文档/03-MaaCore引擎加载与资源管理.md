# 参考项目 深度拆解 03：MaaCore 引擎加载与资源管理

> 本文覆盖：JNA 绑定 libMaaCore.so、资源从 assets 解压到磁盘、多目录加载、
> tasks.json 兼容、global（国际服）资源、版本化校验。

---

## 1. JNA 绑定：MaaCoreLibrary

```java
public interface MaaCoreLibrary extends Library {
    Pointer AsstCreate();
    Pointer AsstCreateEx(AsstApiCallback callback, Pointer customArg);   // 带回调创建实例
    void    AsstDestroy(Pointer handle);

    int     AsstAsyncConnect(Pointer handle, String adbPath, String address, String config, byte block);
    void    AsstSetConnectionExtras(String name, String extras);
    int     AsstAsyncClick(Pointer handle, int x, int y, byte block);
    int     AsstAsyncScreencap(Pointer handle, byte block);

    int     AsstAppendTask(Pointer handle, String type, String params);
    byte    AsstSetTaskParams(Pointer handle, int id, String params);

    boolean AsstSetUserDir(String path);
    boolean AsstLoadResource(String path);
    boolean AsstSetStaticOption(int key, String value);
    boolean AsstSetInstanceOption(Pointer handle, int key, String value);

    boolean AsstStart(Pointer handle);
    boolean AsstStop(Pointer handle);
    boolean AsstRunning(Pointer handle);
    boolean AsstConnected(Pointer handle);
    boolean AsstBackToHome(Pointer handle);

    long    AsstGetImage(Pointer handle, Pointer buff, long bufferSize);
    long    AsstGetImageBgr(Pointer handle, Pointer buff, long bufferSize);
    long    AsstGetUUID(Pointer handle, Pointer buff, long bufferSize);
    long    AsstGetTasksList(Pointer handle, int[] buff, long bufferSize);

    String  AsstGetVersion();
    void    AsstLog(String level, String message);
}
```

- 引擎进程通过 `MaaCoreManager.MaaContext` 惰性 `Native.load("MaaCore", ...)` 加载；
- **关键选项**：
  - `AsstSetUserDir(userDir)`：设 MAA 工作目录（日志、缓存）。
  - `AsstLoadResource(path)`：加载资源目录（pipeline/tasks 等），**可多次调用追加**。
  - `AsstSetStaticOption` / `AsstSetInstanceOption`：全局/实例选项（如 TOUCH_MODE=ANDROID、DEPLOYMENT_WITH_PAUSE）。
  - `AsstCreateEx(callback, null)`：创建带回调的实例（回调线程为 core 内部 msg_proc）。

### 实例化流程（MaaCompositionService.ensureMaaInstance）

```kotlin
if (maa.hasInstance()) return null
if (!maa.CreateInstance(callback)) → InitializationError(CREATE_INSTANCE)
if (!maa.SetInstanceOption(TOUCH_MODE, ANDROID)) → InitializationError(SET_TOUCH_MODE)
```

### 连接流程（setupDisplayAndConnect → asyncConnect）

```kotlin
val deferred = CompletableDeferred<Boolean>()
connectDeferred.set(deferred)
maa.AsyncConnect("", "Android", config, false)   // config 见下
val ret = withTimeoutOrNull(2000) { deferred.await() }
if (ret != true) → ConnectionError(MAA_CONNECT)

// connect config（buildConnectConfig）：
{
  "library_path": "libbridge.so",        // 控制器桥接 so
  "screen_resolution": { "width": W, "height": H },
  "display_id": displayId,               // 目标虚拟屏/物理屏
  "force_stop": true                     // 连接时强制重启游戏（参考项目 选 true）
}
```

`AsyncCallInfo` 回调完成时 `onAsyncConnectCallback` 用 CompletableDeferred 通知连接结果。

---

## 2. 资源初始化：ResourceInitService（assets → 磁盘）

```kotlin
class ResourceInitService(context, assetExtractor, pathConfig) {
    val state: StateFlow<ResourceInitState>   // NotChecked/Checking/Extracting/Ready/Failed

    suspend fun checkAndInit() {
        if (pathConfig.isResourceReady) { state = Ready; return }   // 已就绪跳过
        doExtractFromAssets()
    }

    suspend fun doExtractFromAssets() {
        // 1. 清理旧资源目录（deleteRecursively）后重建
        // 2. assetExtractor.extract(assetDir=ASSET_DIR_NAME, destDir=resourceDir, onProgress)
        // 3. 成功 → pathConfig.markAppVersion() + doForceSyncOverridesTemplate()
    }
}
```

- `ASSET_DIR_NAME` 是 APK assets 里的资源根目录；
- **每次提取先删旧目录**（保证干净，配合版本化）；
- 提取进度上报 UI（Extracting(extractedCount, totalCount, currentFile)）；
- `markAppVersion()`：写版本标记文件（version.json），`isResourceReady` 校验资源存在 + 版本匹配，**避免重复解压**；
- `doForceSyncOverridesTemplate()`：把 assets 里的 overrides 模板同步到覆盖目录。

### AssetExtractor（assets 解压器）

按 asset-manifest（assets 文件清单）批量解压，支持进度回调；逐文件复制、校验大小，失败给 i18n 错误文案。

---

## 3. 资源加载：MaaResourceLoader（多目录叠加）

```kotlin
class MaaResourceLoader(pathConfig, appSettings, chainState, ...) {
    sealed class State { NotLoaded / Loading / Reloading / Ready / Failed(message, permanent) }

    suspend fun load(clientType): Result<Unit> {
        if (!pathConfig.isResourceReady) → Failed("资源未就绪，请重新初始化", permanent = true)

        doLoadDepsInfo(clientType)   // 异步加载 干员数据/物品表/活动表（30s 超时）

        useRemoteService { srv ->
            srv.setup(pathConfig.rootDir, debugMode)            // AsstSetUserDir
            srv.setForceFullscreenOnVirtualDisplay(...)

            if (debugMode) { /* 绑定 logcat 服务开始抓日志 */ }

            val maa = srv.maaCoreService
            val isGlobal = clientType !in ["", "Official", "Bilibili"]   // 国际服判断

            copyTasksJson(pathConfig.cacheResourceDir)           // tasks.json → tasks/tasks.json
            loadResIfExists(maa, pathConfig.rootDir)             // 主资源
            followUps.forEach { loadResIfExists(maa, it) }       // 缓存目录 + 国际服目录
            if (tasksOverrideEnabled) loadResIfExists(maa, overridesDir)  // 覆盖目录最后加载

            state = Ready
        }
    }
}
```

### 多目录加载顺序（后者覆盖前者）

```
1. rootDir/resource            （主资源：pipeline/image/model/tasks）
2. cacheDir/resource           （缓存资源）
3. globalResourceDir(clientType)/resource + globalCacheResourceDir  （仅国际服）
4. overridesDir/resource       （用户覆盖，最后加载生效）
```

`AsstLoadResource` 可多次调用叠加（MaaFramework 的 ResourceMgr 支持多 bundle）。

### copyTasksJson（新旧目录结构兼容）

```kotlin
// tasks.json → tasks/tasks.json，MaaFramework 新版本要求 tasks/ 子目录结构
if (dest 存在且大小/时间戳一致) return   // 幂等
src.copyTo(dest, overwrite = true)
```

### doLoadDepsInfo（干员/物品/活动数据）

```kotlin
withTimeout(30_000) {
    listOf(
        async { resourceDataManager.load(clientType, displayLanguage) },
        async { itemHelper.load() },
        async { activityManager.load(clientType) }
    ).awaitAll()
}
```

---

## 4. 路径规划：MaaPathConfig

统一管理所有目录（数据/资源/缓存/覆盖/日志/导出）：

| 概念 | 路径 |
|---|---|
| rootDir | `{externalFilesDir}/Maa`（AsstSetUserDir 目标） |
| resourceDir | `{rootDir}/resource`（主资源，assets 解压目标） |
| cacheDir / cacheResourceDir | `{rootDir}/cache`、`{rootDir}/cache/resource` |
| globalResourceDir(client) | `{rootDir}/resource_global/{client}` |
| overridesDir | `{rootDir}/overrides`（用户覆盖） |
| debugDir | `{rootDir}/debug`（service_boot_debug.log、root_launch_debug.log 等） |

`isResourceReady` = resource 目录存在且 `version.json` 的 appVersion == 当前 BuildConfig.VERSION_CODE。

---

## 5. 资源加载失败分级

```kotlin
data class Failed(val message: String, val permanent: Boolean = false)
// permanent=true：资源文件缺失，重试无意义，需用户手动重新初始化
// permanent=false：IPC/IO 临时失败，ensureLoaded() 可再次尝试

suspend fun ensureLoaded(): Result<Unit> = when (state) {
    Ready -> success
    Failed -> if (permanent) failure else load()      // 临时失败自动重试
    Loading/Reloading -> state.first { Ready || Failed }   // 等当前加载结束，避免并发误报
    else -> load()
}
```

## 6. 给复现者的要点清单

1. JNA 接口按 Asst* API 完整声明；AsstCreateEx 带回调、AsstLoadResource 可叠加。
2. 资源解压先删旧目录再全量解压（保证干净），解压成功写版本标记避免重复。
3. 多目录叠加加载（主/缓存/国际服/覆盖），覆盖目录最后。
4. `tasks.json → tasks/tasks.json` 兼容新旧目录结构。
5. 失败分级 permanent/临时，ensureLoaded 幂等重试。
6. AsyncConnect 用 CompletableDeferred + 回调（AsyncCallInfo）驱动，2s 超时。
7. connect config 里 `library_path=libbridge.so`、`force_stop=true`、`display_id=目标屏`。