# 编译指南（给 AI / 后续开发者）

> 目标：让任何 AI 或开发者**在当前设备上编译此项目**并产出可用 APK。
> 最后更新：2026-08-12

---

## 一、项目位置与环境

| 项 | 值 |
|---|---|
| 项目根目录 | `/storage/emulated/0/火影MAA安卓脚本开发/MAAFW-Android-火影忍者手游/` |
| 引擎进程 | **shell/root 独立进程**（`RemoteEngineServiceImpl`），App 通过 binder 通信 |
| 构建环境 | **PC/终端执行 Gradle**（设备 sdcard 是 noexec，无法直接跑 AAPT2，**必须在可执行区构建**） |
| 构建工作区 | `/root/maa_build/MAAFW-Android-火影忍者手游/`（proot 环境，可执行） |

---

## 二、编译命令（标准流程）

```bash
# 0) 把最新源码同步到构建工作区（sdcard → /root/maa_build）
rm -rf /root/maa_build/MAAFW-Android-火影忍者手游/app/src/main
cp -r "/sdcard/火影MAA安卓脚本开发/MAAFW-Android-火影忍者手游/app/src" /root/maa_build/MAAFW-Android-火影忍者手游/app/

# 1) 构建（在 /root/maa_build，需已配置 gradle/jdk）
cd /root/maa_build/MAAFW-Android-火影忍者手游
sh gradlew :app:assembleDebug --offline -q 2>&1 | tail -20

# 2) 把 APK 同步回项目目录 + Download（供安装）
cp app/build/outputs/apk/debug/app-debug.apk \
   "/sdcard/火影MAA安卓脚本开发/MAAFW-Android-火影忍者手游/app/build/outputs/apk/debug/app-debug.apk"
cp app/build/outputs/apk/debug/app-debug.apk "/sdcard/Download/maafw-naruto-debug.apk"
```

**注意**：
- 若只改了 Kotlin/资源（assets），用上面命令即可（原生 libbridge.so 已预编译在 `jniLibs`，无需重编 native）。
- `gradlew` 需要 `JAVA_HOME` 与 gradle 缓存（`--offline`）；若报依赖缺失，去掉 `--offline` 或先 `gradlew :app:dependencies`。

---

## 三、⚠️ 绝对不能做的事（历史踩坑，违反会卡死/崩溃）

参考 `滑动卡死问题修复报告.md` 详细分析。核心红线：

| # | 红线 | 原因 |
|---|---|---|
| 1 | **不要重编译 libbridge.so** | 项目用的是原版（md5 `6807aea6`，82KB，来自发行版 0.1）；任何 ndk-build 重编译产物（含 fps 统计）会导致滑动/多点注入异常卡死 |
| 2 | **不要注册 `MaaTaskerAddContextSink`（focus 监听）** | v5.12.3 / v5.13.0-beta.2 节点事件回调会导致引擎随机崩溃/卡死 |
| 3 | **不要用 MaaFramework Swipe 多途径点**（`end` 数组 + `duration` 数组） | 引擎卡死（`NonlinearSwipe` 已改为引擎层直接注入，见 `CustomActions.kt`） |
| 4 | **不要在 CustomRecognition 里嵌套 OCR**（`MaaContextRunRecognitionDirect`） | 死锁（v5.12.3/v5.13 beta 都验证）——`point_race_challenge` 已改原生 OCR |
| 5 | **CustomAction 拿不到 image** | 无法在动作阶段做 OCR（战力对比受限，当前点第一个挑战） |

**libbridge.so / MaaFramework .so 现状**（`app/src/main/jniLibs/arm64-v8a/`）：
- `libbridge.so`：**原版**（`6807aea6`，82KB，来自发行版 0.1，勿替换/勿重编译）
- `libMaaFramework.so` 等全套：**v5.12.3 稳定版**（md5 `b67ad411`），来自 `/sdcard/Download/QuarkDownloads/MAA-android-aarch64-v5.12.3.zip`
- `liblauncher.so`：项目原生（勿动）

---

## 四、native 源码（仅供阅读，不建议编译）

`app/src/main/native/`：libbridge 源码（bridge.cpp / bridge_frame_buffer / bridge_input / launcher）。
- 原版用 CMake（`CMakeLists.txt`）编译，**不要用 Android.mk/ndk-build 重编译**（ABI 不兼容）。
- 原版 libbridge.so 在发行版 0.1 APK 中：`/sdcard/Download/QQ/Maafw-火影忍者手游-发行版0.1.apk`（`lib/arm64-v8a/libbridge.so`）。
- 若丢失，从上述 APK 提取还原，**不要自行重编**。

---

## 五、关键架构文件速览

| 文件 | 作用 |
|---|---|
| `app/src/main/java/com/maafw/naruto/remote/RemoteEngineServiceImpl.kt` | 引擎服务：任务执行、引擎复用、后台保障（唤醒锁/保亮）、focus 已禁用 |
| `app/src/main/java/com/maafw/naruto/maa/CustomActions.kt` | 自定义动作（NonlinearSwipe 直接注入、MultiSwipeCustom、SelectLowestChallenge 等） |
| `app/src/main/java/com/maafw/naruto/maa/CustomRecognitions.kt` | 自定义识别（FindToChallenge 已不再被 point_race 使用） |
| `app/src/main/java/com/maafw/naruto/maa/AssetResourceDeployer.kt` | 资源部署（含**版本号校验**：`.maafw_version`，versionCode 变化强制重部署） |
| `app/src/main/java/com/maafw/naruto/service/` | 前台服务、通知系统（任务开始/完成/出错独立开关 + 第三方推送） |
| `app/src/main/java/com/maafw/naruto/schedule/` | 定时任务（导入/导出/定位、Root 守护、唤醒） |
| `app/src/main/java/com/maafw/naruto/ui/schedule/ScheduleListView.kt` | 定时任务页（右下角导入/导出/定位按钮） |
| `app/src/main/assets/interface.json` | 界面配置（选项 override） |
| `app/src/main/assets/resource/base/pipeline/*.json` | 脚本 pipeline（MaaFramework 节点） |

---

## 六、资源部署与测试注意

- 资源在 `assets/resource/base/`，启动时 `AssetResourceDeployer` 复制到
  `/storage/emulated/0/Android/data/com.maafw.naruto/files/resource/base`。
- **改资源内容后**：磁盘旧资源不会自动更新（原只比文件数）。现已加 `.maafw_version` 校验——
  **升级 `build.gradle` 的 `versionCode`** 即可强制重新部署。测试新资源前务必升 versionCode。
- 安装新 APK：`/sdcard/Download/maafw-naruto-debug.apk`（或项目 `app/build/outputs/apk/debug/app-debug.apk`）。

---

## 七、日志位置（排查用）

- App 日志 / 引擎日志导出：`/storage/emulated/0/MaaFw日志/`（设置页「导出日志」生成）
- 引擎实时日志：`/storage/emulated/0/Android/data/com.maafw.naruto/files/maa_logs/maafw.log`
- 排查套路：任务卡住 → 看 maafw.log 末尾最后一条 Node/Action/Recognition 事件，定位卡死节点 → 对照红线表。

---

## 八、版本记录

| 版本 | 说明 |
|---|---|
| v1.1（当前） | 引擎 v5.12.3 + 原版 libbridge；point_race 原生 OCR；通知系统完善；定时任务导入/导出；引导页权限申请 |
| 发行版 0.1 | 原版参考（含原版 libbridge.so，`/sdcard/Download/QQ/`） |
