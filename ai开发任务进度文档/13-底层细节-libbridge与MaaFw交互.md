# 参考项目 深度拆解 13：底层细节——libbridge.so 与 MaaCore 原生交互

> 引擎进程里 JNA 加载 libMaaCore.so（识别/决策）+ System.loadLibrary 加载 libbridge.so
> （截屏/注入桥），两者如何联动：connect config、DriverClass upcall、首帧等待。

---

## 1. 两个原生库的分工

| 库 | 加载方式 | 职责 |
|---|---|---|
| `libMaaCore.so`（MaaFramework） | JNA `Native.load("MaaCore", MaaCoreLibrary)` | 任务编排、图像识别、OCR、决策；通过 AndroidNativeController 配置连到 libbridge |
| `libbridge.so`（bridge） | `System.loadLibrary("bridge")`（NativeBridgeLib） | 屏幕捕获器（capturer）+ 触摸注入通道；提供 getFrameCount/GetLockedPixels/setPreviewSurface 等 |

联动关系：
```
MaaCore（识别）--(AndroidNativeController config: library_path=libbridge.so)--> libbridge（截屏/注入）
MaaCore --(Tasker/Action: Click/Swipe)--> DriverClass.upcall --> InputControlUtils --> InputManager.inject
```

---

## 2. NativeBridgeLib（libbridge 的 Java 绑定）

```java
public class NativeBridgeLib {
    static { try { System.loadLibrary("bridge"); LOADED = true; } catch (...) { LOADED = false; } }

    @FastNative public static native String ping();                       // 探活
    public static native Surface setupNativeCapturer(int width, int height); // 建捕获器，返回 Surface 投给虚拟屏
    public static native void releaseNativeCapturer();
    @FastNative public static native void setPreviewSurface(Object surface); // 预览 Surface
    public static native Bitmap getFrameBufferBitmap();                   // 测试：拿帧
    @FastNative public static native long getFrameCount();                // 帧计数（等首帧/看黑屏）
}
```

- **`@FastNative`**：ART 的快速 JNI 调用注解（减少 JNI 开销，用于高频 getFrameCount/setPreviewSurface）。
- `setupNativeCapturer(w, h)` 返回 Surface → `WindowManager.createNewVirtualDisplay(..., surface, flags)`：
  **虚拟屏的渲染目标就是 libbridge 捕获器的 Surface**，捕获器把虚拟屏画面锁到内存帧缓冲供 MaaCore screencap。

---

## 3. connect config：把 MaaCore 连到 libbridge

```kotlin
private fun buildConnectConfig(width: Int, height: Int, displayId: Int): String {
    return buildJsonObject {
        put("library_path", "libbridge.so")      // AndroidNativeController 加载的桥
        put("screen_resolution", { width; height })
        put("display_id", displayId)             // 目标屏（虚拟屏或物理屏）
        put("force_stop", true)                  // 连接时强制重启游戏（参考项目 选 true）
    }.toString()
}
// 调用：maa.AsyncConnect("", "Android", config, false)
// 结果经 AsyncCallInfo 回调 → CompletableDeferred（2s 超时）
```

MaaCore 的 AndroidNativeController 收到 config 后：
- `dlopen("libbridge.so")` 在同一进程加载；
- 调 bridge 的 capturer 初始化（与 Java 侧 setupNativeCapturer 同一实例）；
- screencap 时 `GetLockedPixels` 从帧缓冲取图；
- 输入时走 DriverClass upcall（Java 侧 InputControlUtils）。

---

## 4. DriverClass：JNI upcall 入口

libbridge.so 在 JNI 层回调 Java 静态方法（`com.refproject.maa.DriverClass`）：

```java
public static boolean startApp(String packageName, int displayId, boolean forceStop) {
    if (displayId == PRIMARY_DISPLAY_ID) return ActivityUtils.startApp(pkg, displayId, forceStop);
    boolean ret = ActivityUtils.startApp(pkg, displayId, forceStop, true);
    if (ret) ret = ActivityUtils.ensureAppOnDisplay(pkg, displayId);   // ★ 防漂移（upcall 层兜底）
    if (ret) awaitFirstFrame();                                        // ★ 等首帧
    return ret;
}

public static boolean touchDown/Move/Up(int x, int y, int displayId) → InputControlUtils
public static boolean keyDown/keyUp(int keyCode, int displayId) → InputControlUtils
```

### awaitFirstFrame（等画面流动）

```java
private static void awaitFirstFrame() {
    long baseline = NativeBridgeLib.getFrameCount();
    int elapsed = 0;
    while (getFrameCount() <= baseline && elapsed < 5000) {   // 帧数没增长 → 画面未流动
        sleep(50); elapsed += 50;
    }
    if (elapsed >= 5000) Ln.w("awaitFirstFrame timed out after 5000ms");
}
```

**为什么必须等首帧**：虚拟屏刚创建是空屏，SurfaceFlinger 不产生帧；MaaCore screencap 拿空帧会卡死在 start_up。等帧计数增长 = 画面开始渲染，再继续任务。

---

## 5. 防漂移的完整防线（三层）

```
① DriverClass.startApp → ensureAppOnDisplay（MaaCore upcall 层，每次启动都拉回）
② ActivityUtils.ensureAppOnDisplay → repinAppToDisplay（moveTask → am move-stack → 强制 FULLSCREEN 重投）
③ AppWatchdog.checkDisplayPinned（后台模式每 5s 轮询，5s 宽限期 + 3 次上限）
```
三者叠加：启动时拉回 + 漂移时持续拉回 + 拉不回的兜底上报。

---

## 6. 调试手段

- `NativeBridgeLib.ping()`：引擎 init 时探活 libbridge；
- `getFrameBufferBitmap()`：测试用，可把虚拟屏当前帧存 PNG（captureFramePng）；
- `getFrameCount()`：等首帧/判断黑屏（帧停滞 = 画面冻结）；
- MaaCore 日志（AsstLog/回调）→ Ln 双通道 + maafw 日志文件。

---

## 7. 给复现者的要点清单

1. libMaaCore 用 JNA、libbridge 用 System.loadLibrary，同一进程两套加载不冲突。
2. 虚拟屏 Surface 来自 `setupNativeCapturer`——捕获器与显示绑定。
3. connect config：`library_path=libbridge.so`、`display_id=目标屏`、`force_stop=true`。
4. **DriverClass upcall 层做 startApp + ensureAppOnDisplay + awaitFirstFrame**——防漂移/防空帧的最上游防线。
5. 等首帧用 getFrameCount 增长判断（5s 超时）。
6. @FastNative 加速高频 JNI（getFrameCount/setPreviewSurface）。