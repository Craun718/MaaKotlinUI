# MAAFW-Android-火影忍者手游 编译打包说明

## 项目路径
`/storage/emulated/0/火影MAA安卓脚本开发/MAAFW-Android-火影忍者手游/`

## 环境
- AndroidIDE（JDK17 / JDK21 + Gradle 8.5 + Android SDK 于 `/root/android-sdk`）
- 或 Ubuntu proot（路线 B）

## 编译命令（项目根目录执行）

```bash
export ANDROID_HOME=/root/android-sdk
bash gradlew :app:assembleDebug --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/34.0.4/aapt2
```

- 离线编译（缓存齐全）：加 `--offline`
- 分配更多内存：加 `-Dorg.gradle.jvmargs=-Xmx4g`
- 清理构建缓存：`bash gradlew clean --no-daemon`

## APK 输出
`app/build/outputs/apk/debug/app-debug.apk`

## 清理 build 中间产物（保留 APK，可到 1G+）
```bash
cd app/build && rm -rf intermediates tmp kotlin generated snapshot
```

## 注意
- AIDL 同包接口需显式 import，否则 compileDebugAidl 报错
- icons 不支持 `Icons.AutoMirrored`，用 `Icons.Filled/Rounded.*` 替代
- 首次安装建议清应用数据，避免旧数据冲突
