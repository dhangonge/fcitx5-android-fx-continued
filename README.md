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
- 语音输入：离线识别引擎（sherpa-onnx），流式 zipformer 与 SenseVoice 整句两种模式，
  在输入法进程内直接跑（引擎原生库单独打一个「语音引擎」APK，装一次即可，识别仍在输入法进程里，
  不会被墓碑机制冻结）；模型在设置里下载或导入，见下文「语音输入」
- 仍然保留 ASR AIDL 桥接（`IVoiceInputProvider` / `IVoiceInputCallback`），第三方插件 APK
  可以把识别放在自己的进程里，详见 [ASR_VOICE_INPUT_AIDL_INTEGRATION.md](ASR_VOICE_INPUT_AIDL_INTEGRATION.md)
- 额外汇总插件：`pinyin-lm`、`text-editor`（正则、Tab 视图）、`table-data`、`extra-data`
  （Unicode 字符数据与英文拼写词典，不装则这两项功能无数据，键盘本身不受影响）
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

## 语音输入

识别跑在输入法进程里，但要先装一个「语音引擎」APK（`voice_engine`，约 12 MB）：原生识别库
单独打包，主程序因此不必为不用语音的人背 9 MB。装完一次就不用再管，**不需要重启手机**：

1. 从 Releases 里装主程序，再装同一版本的 `voice_engine` 插件 APK（同一签名，可直接互相安装）
2. 设置 → 虚拟键盘 → 工具栏与候选栏 → 打开「显示语音输入按钮」
3. 同一个页面点「首选语音输入」，选「小企鹅语音（内置） · 流式」（边说边出字）
   或「小企鹅语音（内置） · 整句」（说完再整句转写）
4. 没有模型时先装模型：同一个页面往下滚到「语音模型」，点「国内镜像下载」（hf-mirror.com，
   不用翻墙，实测 6–8 MB/s）。流式：中文普通话轻量版 24 MB、2025 更准版 160 MB、英文 20M 版 42 MB；
   整句：SenseVoice 中英日韩粤 240 MB（推荐）、Paraformer 中文轻量 82 MB。后面那个 GitHub
   按钮是整包下载，出境线路差时会卡住，卡住可以点「取消下载」。也可以从文件导入（支持
   `.tar.bz2` / `.zip`）。
5. 键盘弹起后点工具栏上的话筒即可开始／停止录音，识别结果直接上屏。

模型可以并存，随时切换，也能删掉。识别全程离线，不联网、不上传录音。

没装引擎时，「语音模型」页面顶部会提示引擎未安装，话筒也会直接报「不可用」而不是卡在连接中；
已下载的模型在装上引擎后照常可用。引擎与主程序之间的 JNI 接口版本写在 `gradle.properties`
的 `voiceEngineApi`，两边的签名和版本对不上时主程序会拒绝加载，而不是在 JNI 里崩掉。

`plugin/voice-asr` 已经不再构建，仓库里只留下源码作为 AIDL 契约的参考实现（引擎与内置的是同一套
代码，而独立进程恰恰是墓碑机制冻结的目标）。曾经选过旧插件的用户，首次启动时会自动把选择迁到
内置引擎；旧插件如果还装着，仍然会出现在列表里，但不再是推荐形态。

## 状态

已经有可安装的预编译包：见
[Releases](https://github.com/dhangonge/fcitx5-android-fx-continued/releases)。
当前只构建 `arm64-v8a`（主流 Android 手机都是这个 ABI），APK 用本仓库的发布密钥签名，
主程序与插件同一签名，可以直接互相安装。

源码构建同样验证可用：`:app:assembleDebug` 可产出 17 MB 量级的 `arm64-v8a` APK（不含语音引擎）；
需要语音时再 `:plugin:voice-engine:assembleDebug`（本地调试想省掉这一步，可以加
`-PvoiceEngineEmbedded=true` 把原生库塞回主程序）。

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
