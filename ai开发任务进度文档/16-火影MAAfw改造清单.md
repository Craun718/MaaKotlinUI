# 火影 MAA 项目对标 参考项目 改造清单

> 基于前 15 篇对 参考项目 的底层拆解，逐项比对火影 MAAFW 项目（com.maafw.naruto）现状，
> 找出缺失/可优化的细节，以及它们**会导致什么情况无法运行**。
> 优先级：P0=会导致运行失败/明显异常，P1=稳定性与可诊断性，P2=体验与架构。

---

## ✅ 已完成的改造（本会话已落地）

| 项 | 状态 | 对应问题 |
|---|---|---|
| 引擎侧 SharedPreferences 治本（EngineSharedConfig 共享配置） | ✅ | 引擎进程 `context.applicationContext==` NPE |
| StartGameToVirtualDisplay 替代原版 StartApp | ✅ | StartApp 不带 displayId 把游戏挪出虚拟屏 → 黑屏 |
| Shizuku 绑定：独立 connection + 随机 tag + version 递增 + cleanup(remove=true) | ✅ | 服务端 record 卡死 → "首次成功之后全部超时" |
| 开始任务按钮禁用（未连接/绑定中不可点） | ✅ | 绑定期间重复点击堆积任务 |
| 导出日志三态提示（成功/无可导出/失败） | ✅ | 长串吐司放不下 |
| MaaFrameworkEngine `appContext ?: context` 兜底、AgentManager uid 自判 | ✅ | 潜在 NPE / 错误读配置 |
| 虚拟屏全 flags（TRUSTED/ALWAYS_UNLOCKED/OWN_FOCUS 等）+ 失败降级 + requestDisplayPower 点亮 | ✅（核对确认） | 防冻结黑屏、Android14 显式点亮 |
| 触摸注入 ACTION_CANCEL 防护 + ASYNC 适配 + 坐标 clamp/重试 | ✅（核对确认） | 防滑动卡死（DOWN 用 ASYNC 系火影适配选择） |
| 帧停滞 15s 自愈（重亮+重建预览）+ 虚拟屏 userActivity 保亮 | ✅ | 防 Doze 灭屏/预览黑屏 |

---

## 🔴 P0：会导致无法运行 / 明显异常（建议尽快补）

### P0-1 引擎进程无心跳看门狗（App 死后引擎残留）
- **现状**：火影引擎 `destroy()` 靠 App 主动调用；App 被 force-stop/崩溃时，引擎进程（Shizuku UserService）可能残留，继续占虚拟屏/唤醒锁/权限。
- **会导致**：残留引擎与下次任务冲突（虚拟屏被占、截图错乱）；资源泄漏。
- **Meow 做法**：`heartbeat(pid)` 喂 App pid + 每 5s 查 `/proc/<pid>`，App 死则 `destroy()` 自杀；Root 模式 `primeHeartbeat` 提前喂。
- **改造**：RemoteEngineServiceImpl 加 heartbeat AIDL + 看门狗线程；App 绑定成功即调 `heartbeat(Process.myPid())`。

### P0-2 无 shutdown hook 紧急清理（引擎被强杀不恢复环境）
- **现状**：火影 destroy() 有 stopAll，但引擎被系统/看门狗强杀（SIGKILL 除外）时，静音的游戏音量、虚拟屏、强制显示尺寸可能残留。
- **会导致**：游戏永久静音、屏幕状态异常。
- **Meow 做法**：init 里 `Runtime.addShutdownHook` → `performEmergencyCleanup()`（恢复音频/释放 Power/Screen/MaaCore）。
- **改造**：RemoteEngineServiceImpl init 加 shutdown hook；把清理逻辑集中成 `performEmergencyCleanup()`，destroy/看门狗/hook 三处共用。

### P0-3 虚拟屏 flags —— ✅ 已实现（修正）
- **现状核对**：火影 `VirtualDisplayManager.buildFlags()` 已含 `TRUSTED/OWN_DISPLAY_GROUP/ALWAYS_UNLOCKED/TOUCH_FEEDBACK_DISABLED`（API33+）+ `OWN_FOCUS/DEVICE_DISPLAY_GROUP/STEAL_TOP_FOCUS_DISABLED`（API34+），
  且带**失败降级**（full flags 失败回退 safe flags）与 `requestDisplayPower` 点亮（Android14 必须显式点亮）。
- **结论**：无需再改；若仍黑屏，重点排查 P0-4（游戏被系统冻结）与游戏是否在虚拟屏上（upcall 层防漂移）。

### P0-4 无游戏电池豁免/后台不受限授予（vivo 后台杀游戏）
- **现状**：火影启动任务**不主动给游戏授予**省电豁免/后台不受限；vivo/澎湃/i管家对后台游戏激进清理。
- **会导致**：**"游戏启动几秒就停止"**（你观察到的现象）——游戏在虚拟屏被系统冻结/杀死，虚拟屏黑屏，识别全失败。
- **Meow 做法**：任务启动前 `grantGameBatteryExemption(clientType)`：经引擎 shell 身份调 `grantPermissions(PERM_BATTERY|PERM_BACKGROUND)` 给游戏授权（`AppOpsManager.setMode`/`PowerWhitelist`/后台不受限）。
- **改造**：RemoteEngineServiceImpl 增加 `grantPermissions(pkg, PERM_BATTERY|PERM_BACKGROUND)`（shell 权限可代授）；火影 App 在 startTasksJson 前调用。

### P0-5 无 AppWatchdog（游戏进程/显示漂移运行期守护）
- **现状**：火影只在启动时 ensureAppOnDisplay 一次；**运行中游戏死了/被挪走无感知**，MaaCore 继续对着空帧/黑屏识别空转。
- **会导致**：任务"看似在跑"实则无效执行几十分钟。
- **Meow 做法**：后台模式 `AppWatchdog` 每 5s：`isAppAlive`（pidof）→ 死则上报；`isAppOnBackgroundDisplay` → 漂移 5s 宽限期后拉回（上限 3 次）。
- **改造**：App 侧加 AppWatchdog（复用引擎 `isAppAlive`/`moveAppToVirtualDisplay` AIDL）。

### P0-6 无服务状态机 + 自动重连
- **现状**：火影 `remoteBound` 布尔 + 手动 bind；绑定失败/binder 死亡后**无自动重试**（用户要手动重启 App/Shizuku）。
- **会导致**：引擎崩了之后任务全失败，体验差。
- **Meow 做法**：`RemoteServiceManager` 状态机（Disconnected/Connecting/Connected/Died/Error）+ `useRemoteService{}` 统一入口 + binder death 自动感知。
- **改造**：把火影绑定逻辑收敛为状态机（可在现有 MainActivity 绑定基础上加：binderDied 自动重绑、绑定失败自动重试 1-2 轮、心跳保活）。

### P0-7 游戏音频静音无恢复
- **现状**：火影 `setAudioMuted(true)` 静音游戏，但引擎异常退出时**不恢复**。
- **会导致**：游戏永久静音（用户以为坏了）。
- **Meow 做法**：`GameAudioMuteController.restoreAll()` 在 shutdown hook/destroy 里必调。
- **改造**：引擎侧记录静音状态，紧急清理时恢复。

---

## 🟠 P1：稳定性与可诊断性（强烈建议）

### P1-1 无三层诊断日志
- **现状**：火影只有 app/maafw/logcat 文件；**绑定失败/引擎起不来时无法定位是 IPC 卡住还是 native 崩溃**（用户遇到过 logcat 43 字节空）。
- **Meow 做法**：`ServiceBootLogger`（App 绑定链路 service_bind_debug.log）+ `RemoteBootTrace`（引擎启动 service_boot_debug.log，路径自行推导）+ `root_launch_debug.log`。
- **改造**：低成本高价值——引擎构造阶段 RemoteBootTrace.mark（CTOR_START→MAA_LOAD→CTOR_DONE），App 绑定链路 ServiceBootLogger.event。

### P1-2 无独立 logcat 服务进程
- **现状**：火影 `captureLogcat` 用引擎进程 exec logcat，返回空（权限/过滤问题）。
- **会导致**：导出日志缺系统侧证据（用户"logcat 暂无输出"）。
- **Meow 做法**：独立 `LogcatCaptureServiceImpl`（UserService/root 进程），`logcat -T 10 --pid=<appPid>/<servicePid>` 按 pid 抓。
- **改造**：把 captureLogcat 改为按 pid 过滤 + 落盘 debug/logcat/，而非一次性 dump。

### P1-3 StartResult 无分级（失败原因笼统）
- **现状**：火影 `startEnabledTasks` 失败统一"启动远端引擎失败: xxx"。
- **会导致**：用户/开发者难判断是资源/初始化/连接哪一步挂了。
- **Meow 做法**：`StartResult` 分级（Resource/Initialization(phase)/Connection(phase, shizukuAsRoot)/Start/Portrait/Aspect/Connecting/Unavailable）。
- **改造**：火影 startEnabledTasks 返回分级结果并给针对性提示（如"虚拟屏失败：Shizuku 以 root 运行，可切 Root 模式"）。

### P1-4 触摸注入 —— 🟡 已实现 CANCEL 防护（核对确认）
- **现状核对**：火影 `InputInjector` **已实现**：DOWN 前补 `ACTION_CANCEL`、`@Synchronized`、坐标 clamp、失败重试 3 次；
  **DOWN/MOVE/UP 均用 ASYNC**——注释说明"WAIT_FOR_FINISH 在部分 ROM 会失败/卡住"（与 Meow 相反，属于火影的适配选择）。
- **结论**：CANCEL 防护已到位，无需改；若仍有滑动卡死，排查方向是"DOWN 同步性"（可做 A/B 验证 ASYNC vs WAIT_FOR_FINISH），而非补 CANCEL。

### P1-5 Root 模式回传依赖 ServiceManager/广播（Android16 风险）
- **现状**：火影 Root 引擎 binder 走 `ServiceManager.addService` + App 轮询 `getService`/显式广播。
- **会导致**：Android 16+ `ServiceManager.getService` 对普通 App 返回 null（火影代码里已有 workaround 注释），广播可能被系统丢弃（uid0 限制）。
- **Meow 做法**：`ContentProvider.call()` 双向握手（BootstrapRegistry token + lifecycleBinder）。
- **改造**：把火影 Root 回传改为 ContentProvider 握手（可复用 Meow 的 RootServiceBootstrap 思路）。

### P1-6 Root 启动用 `CLASSPATH=apk app_process`（SELinux 风险）
- **现状**：火影 RootServiceStarter 由 su + `CLASSPATH=$apk app_process` 启动。
- **会导致**：部分 ROM/SELinux 拒绝读取 /data/app APK（用户 23:11 日志曾见 "Bad file descriptor"）。
- **Meow 做法**：`liblauncher.so`（nativeLibraryDir 原生 so）作为启动器，绕开 CLASSPATH/SELinux。
- **改造**：编译期放一个 launcher so（C 实现 exec app_process）替换 CLASSPATH 方式。

### P1-7 无 logcat 看门狗/游戏存活轮询
- 同 P0-5（AppWatchdog）——火影需补 `isAppAlive`（引擎侧 pidof）AIDL + App 轮询。

---

## 🟡 P2：体验与架构（可后续）

| 项 | Meow 做法 | 火影现状 | 收益 |
|---|---|---|---|
| FakeContext 方案 | `getApplicationContext()=this`、包名 shell、权限永放行 | Context 构造器 + EngineSharedConfig 绕开 | 引擎侧 Context 全场景安全 |
| DataStore 设置 | Preferences DataStore + StateFlow | SharedPreferences | 类型安全、Flow、免 NPE |
| Shizuku 安装引导 | assets 内置 shizuku.apk + FileProvider 一键装 | 仅文字提示 | 新用户引导 |
| 引擎代授权限 | grantPermissions（悬浮窗/通知/省电/后台） | 无 | 一键授权体验 |
| force_stop 策略 | connect config `force_stop=true`（强制重启游戏） | `force_stop=false` | 游戏状态不干净时更稳（需权衡重启成本） |
| 前台服务 FGS 竞态 | 先 startForeground 再判终态、终态自 stopSelf | MaaEngineService 需核对 | 防系统杀前台服务 |
| 定时任务启动兜底 | 每次启动 rescheduleAll | 已有 ScheduleHelper | 防国产 ROM 丢闹钟 |
| 服务超时对齐 | App 侧 20s 兜底 | 已改 15s/方案 | 已基本对齐 |
| 触摸预览回调 | setTouchCallback（失败自动注销） | 已有 touchNotify | 已覆盖 |
| 外部 Intent 联动 | LaunchIntentMapper（Tasker/MacroDroid） | 无 | 自动化生态 |

---

## 📋 落地优先级建议

```
第一批（解决用户已遇到的现象）：
  P0-4 游戏电池豁免/后台不受限   ← "游戏启动几秒就停止"最可能主因（vivo 后台杀）
  P0-3 虚拟屏 Android13+ flags   ← "预览黑屏"的隐藏因素
  P0-1 心跳看门狗               ← 引擎残留/下次任务冲突
  P0-2 shutdown hook 紧急清理    ← 静音/屏幕残留

第二批（稳定性）：
  P0-5 AppWatchdog、P0-6 状态机+自动重连、P0-7 音频恢复
  P1-1 三层诊断日志、P1-2 logcat 服务、P1-3 StartResult 分级

第三批（深水区）：
  P1-4 触摸 CANCEL 防护、P1-5/P1-6 Root ContentProvider+liblauncher
  P2 各项
```

---

## 🎯 最可能造成"某些情况无法运行"的 Top 3

1. **P0-4 无游戏省电/后台豁免** → vivo/澎湃把虚拟屏上的游戏冻结或杀死 → **"游戏启动几秒停止、虚拟屏黑屏"**（你已亲历）。
2. **P0-3 虚拟屏 flags 缺防冻结位** → 系统冻结虚拟屏渲染 → **截图黑屏、识别全失败**（与 StartApp 跳屏叠加）。
3. **P0-1 引擎无心跳看门狗 + P0-6 无自动重连** → App 重启后旧引擎残留或新引擎起不来 → **绑定反复失败**（接近你 23:38 起遇到的现象）。

> 建议优先落地 P0-4 + P0-3（改动小、直击用户现象），再做 P0-1/P0-2 与诊断体系。
> 需要我直接按此清单改造火影项目，从 P0-4 开始动手即可。

---

## 📎 执行版

各项**涉及文件 / AIDL 改动 / 关键代码 / 验证方法**见：《17-改造任务执行拆解.md》（可直接照做）。