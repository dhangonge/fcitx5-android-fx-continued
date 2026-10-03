# fcitx5-android-fx-continued

[小企鹅输入法](https://github.com/fcitx5-android/fcitx5-android)（fcitx5-android）**FX 版**的延续维护分支。

FX 版（[`fxliang/fcitx5-android`](https://github.com/fxliang/fcitx5-android) 的 `fx` 分支）已停止更新，
本项目在其基础上继续开发。

包名保持 `org.fcitx.fcitx5.android.fx` 不变，可与原版小企鹅输入法及其他 fork 同时安装。

## 血缘关系

```
fcitx5-android/fcitx5-android   原版（上游官方）
        └── fxliang/fcitx5-android  fx 分支（功能超集，已停更）
                └── dhangonge/fcitx5-android-fx-continued   本仓库
```

分叉点为 FX 的 `57cb3517`（`nightly-0.1.3-436-g45ff6b22-20260909-095200`）。

## 相对原版多出来的功能

以下均继承自 FX：

- 可自定义虚拟键盘布局——拖拽式编辑器、布局档案、二维码与 ZIP 分享 / 导入
- 自定义图标主题（PNG / SVG / XML drawable / ZIP / 二维码）与自定义字体（`fontset.json`），
  工具栏与 Kawaii Bar 按钮图标可换
- Macro 宏按键，支持图层切换与分方向行为
- 语音输入：ASR AIDL 桥接（`IVoiceInputProvider` / `IVoiceInputCallback`），
  IME 侧录音、插件侧识别，详见 [ASR_VOICE_INPUT_AIDL_INTEGRATION.md](ASR_VOICE_INPUT_AIDL_INTEGRATION.md)
- 额外汇总插件：`pinyin-lm`、`text-editor`（正则、Tab 视图）、`table-data`
- Kawaii Bar / 浮动模式、数字键盘布局覆盖、BACK 层切换

## 构建

前置条件：

- JDK 17 或更高
- Android SDK，含 `platforms/android-36` 与 `build-tools/36.1.0`
- Android NDK `28.0.13004108`
- CMake `3.31.6`，且必须是**从 Android SDK 安装的**那一个
  （如果 SDK 里的安装残缺、只剩一个 `.installer` 目录，Gradle 会报
  `[CXX1300] CMake '3.31.6' was not found`；用 `sdkmanager "cmake;3.31.6"` 重装）
- 宿主机需要 `extra-cmake-modules` 与 `gettext`

创建 `local.properties` 指向你的 SDK（该文件已被 gitignore）：

```properties
sdk.dir=/path/to/Android/Sdk
```

然后：

```bash
git clone --recursive https://github.com/dhangonge/fcitx5-android-fx-continued.git
cd fcitx5-android-fx-continued

# 默认构建 fx 变体，包名 org.fcitx.fcitx5.android.fx
./gradlew :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/fx/debug/`，同时会往 `app/build/outputs/apk/debug/`
同步一份以兼容常见脚本。

常用变体：

```bash
# 只编一个 ABI，快很多；默认是四个全编（armeabi-v7a、arm64-v8a、x86、x86_64）
./gradlew -PbuildABI=arm64-v8a :app:assembleDebug

# 与原版一致的包名与命名（org.fcitx.fcitx5.android）
./gradlew -PincludeMainlineFlavor=true :app:assembleMainlineDebug

# 插件 APK
./gradlew :assembleReleasePlugins
```

内存较小的机器上必须限制并行度，否则原生构建会把 Gradle daemon 挤爆（OOM kill）：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk CMAKE_BUILD_PARALLEL_LEVEL=2 \
./gradlew -PbuildABI=arm64-v8a -Dorg.gradle.workers.max=2 \
  -Pkotlin.daemon.jvmargs=-Xmx1024m :app:assembleDebug
```

## 状态

刚起步，暂无 tagged release。构建已验证可用：`:app:assembleDebug` 可产出
34 MB 的 `arm64-v8a` APK。

## 反馈

Issue 与 PR：<https://github.com/dhangonge/fcitx5-android-fx-continued/issues>。

注意 FX 项目本身已无人维护——继承自它的 bug 欢迎在这里提；
但属于上游原版 fcitx5-android 自身的 bug，应当提给上游。

## 致谢与许可

本项目基于众多贡献者的工作分叉而来：

- [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)——上游 Android 移植版
- [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android)——本仓库延续的 FX 分支，
  上述自定义功能大多由该项目添加
- [fcitx5](https://github.com/fcitx/fcitx5)、[libime](https://github.com/fcitx/libime)
  及各个输入法引擎

沿用上游许可：[LGPL-2.1-or-later](LICENSE)。
