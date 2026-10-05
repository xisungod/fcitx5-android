# Sam 水波、按键留色与邻键误触验证

2026-10-05（UTC），以 `d038013632cc56b48fb1d0f50b853e508136ff6b` 的 dev.9 为基线，验证本 PR 最新工作区（源码与补丁身份见 [`source-and-patch.json`](source-and-patch.json)）。最终全量 Android JVM/Robolectric 回归：**56 个测试类、335 项测试，失败 0、错误 0、跳过 0**；Gradle 退出码 0，耗时 1 分 27 秒。

## 可审查证据

- [`test-summary.json`](test-summary.json)：全部 56 个类的计数、运行时间和环境，及归档 XML 的 SHA-256。
- [`baseline/TapAccuracyTest`](baseline/TEST-org.fcitx.fcitx5.android.TapAccuracyTest.xml)：旧 dev.9 配合最终修正的测试夹具，6 项中 5 项失败。
- [`current/TapAccuracyTest`](current/TEST-org.fcitx.fcitx5.android.TapAccuracyTest.xml)：同一测试夹具用于本次修复，6 项全部通过。
- [`baseline/V15HitTargetTest`](baseline/TEST-org.fcitx.fcitx5.android.V15HitTargetTest.xml)：旧版本已有的 5 项命中测试全部通过，确认基线环境可运行。
- [`current/SamWavePropagationTest`](current/TEST-org.fcitx.fcitx5.android.SamWavePropagationTest.xml)：4 项像素测试，验证亮度波峰依次到达近处和远处、宽波带及暗区、波后留色，以及扩散/停留/消退设置影响实际时间。
- [`current/SamKeyboardRenderingTest`](current/TEST-org.fcitx.fcitx5.android.SamKeyboardRenderingTest.xml)：6 项完整键盘绘制测试，涵盖独立拖尾、按键回弹后的留色、新参数生效及旧模式行为。
- [`current/KeyLegendInkTest`](current/TEST-org.fcitx.fcitx5.android.KeyLegendInkTest.xml)：3 项字体测试，涵盖窄键边界、去除暗色轮廓、拉丁字母/数字字形及复用弹窗后的中文系统字体恢复。

旧基线由 `e6199a2801b0c76e1baeff7a48f6b18e910e9dd7` 加上 `d038013632cc56b48fb1d0f50b853e508136ff6b` 中 `patches/` 组装的 dev.9 补丁重建。向旧基线复制最终 `TapAccuracyTest.kt`，生产代码保持旧版本。在 320dp、mdpi 和 xhdpi 两种密度下，按下 N 边缘后向 B 轻微漂移、直接抬手，旧版均产生 **expected `shang` / actual `shabg`**。本次修复通过相同断言，并覆盖 320/360/480dp、单键宽度调整、N/B/L/K、主动滑动、多指和取消事件。

首轮新增测试曾在活动窗口重布局时改变触摸视口，新版的一个多指断言因此失败。最终夹具将键盘放入固定尺寸的 `FrameLayout`，并在每次事件后断言视口宽高不变；随后在新旧版本上重跑。这里归档的旧版反例和新版结果均使用**修正后的同一夹具**，结论不依赖初轮夹具。旧版 5 个失败是业务断言失败，编译和测试运行均正常。

## 原视频补充后的宽波带验收

参考观察见 [`video-reference.md`](video-reference.md)。在本 PR 早期的 Sam 实现上新增 `travellingBandStaysBroadAndVisibleBeyondItsInitialBurst`，初次运行在 **240ms** 处失败：连续亮带仅 **39px**，不满足较宽的传播波带验收。原始单项失败 XML 保存在 [`intermediate/SamWavePropagationTest`](intermediate/TEST-org.fcitx.fcitx5.android.SamWavePropagationTest.xml)。这份证据来自本 PR 的早期实现，不能混同于上面的 dev.9 触摸基线；失败说明视觉形态仍不够宽，并非触摸路由错误。

随后调整波带宽度、后程能量和源头衰减，最终 `SamWavePropagationTest` **4/4 通过**。新增断言检查 240ms 与 400ms 的波带仍明显可见、连续亮带至少 84px（亮度不低于当帧峰值的 55%），同时保留波前暗区，并在 400ms 检查源头先于外围波带变暗。这些像素数和时刻来自固定测试画布及实现验收条件，**不是三星视频的实测尺寸或参数**。最终全量结果为 335 项通过。

## 本次实际命令与环境

```bash
# 全量回归，未追加其他测试过滤参数
/tmp/axiang-gradle -I /tmp/axiang-jvm-tests.init.gradle \
  :app:testDebugUnitTest -x :app:generateDataDescriptor

# 旧 dev.9：复制最终 TapAccuracyTest.kt 后复现
AXIANG_SOURCE_ROOT=/tmp/axiang-source /tmp/axiang-gradle \
  -I /tmp/axiang-jvm-tests.init.gradle \
  :app:testDebugUnitTest -x :app:generateDataDescriptor --tests '*TapAccuracyTest'

# 旧 dev.9：既有命中测试环境基线
AXIANG_SOURCE_ROOT=/tmp/axiang-source /tmp/axiang-gradle \
  -I /tmp/axiang-jvm-tests.init.gradle \
  :app:testDebugUnitTest -x :app:generateDataDescriptor --tests '*V15HitTargetTest'
```

`/tmp/axiang-gradle` 只设置 JDK/SDK 路径，保留继承的网络代理和系统 CA 信任，进入指定源码目录，然后执行 `./gradlew --max-workers=2`。使用官方 Eclipse Temurin JDK `17.0.20.1+1`、Gradle `9.6.1`、Android SDK Platform `36`、Build Tools `36.1.0`、Robolectric `4.17`，系统为 Linux x86_64。Sherpa `1.13.8` 官方 AAR 校验 SHA-256 后仅提取编译用 `classes.jar`。

本目录保留与当时相同的 [`jvm-tests.init.gradle`](jvm-tests.init.gradle)。已准备好 JDK/SDK 和编译 JAR 的环境也可从源码根目录运行：

```bash
./gradlew --max-workers=2 -I validation/sam-wave-touch-fix/jvm-tests.init.gradle \
  :app:testDebugUnitTest -x :app:generateDataDescriptor
```

## PR 工作流修复状态

原 [PR 构建 37266911993](https://github.com/xisungod/fcitx5-android/actions/runs/37266911993) 的已检查失败日志均停在 `:app:checkReleaseDuplicateClasses`，报告缺少 `app/libs/sherpa-onnx-1.13.8.jar`。原通用多平台 PR 流程没有生成该依赖；当前内置 Rime 同样要求先准备完整词典和预编译数据。此问题来自原通用矩阵与 arm64 内置 Rime/ASR 构建流程不匹配。

本次改造 [`pull_request.yml`](../../.github/workflows/pull_request.yml)，在 Ubuntu/arm64 上直接构建当前 PR 合并树，准备完整 ASR/Rime 资产，验证真实语音推理、Rime 候选、native APK 构建和 JVM 测试，随后校验签名及包内资产。全部检查成功后才上传隔离包名的 PR APK；失败时可保留测试证据。没有切回旧基线或用分发补丁替代 PR 源码。

工作流已通过 `actionlint 1.7.12`（退出码 0）、YAML 与 Bash 语法、引用脚本路径/AST 和 `git diff --check` 静态验证。**修订后的完整远端构建仍待运行确认**；本地 335 项 JVM 通过不能算作远端 APK 构建通过。

## 验证范围

临时 init 脚本关闭 CMake `externalNativeBuild` 和 Prefab，命令排除 `:app:generateDataDescriptor`；实际生产 Kotlin/Java 代码、Android 资源和 JVM 测试正常编译运行。**本次没有完整 APK 构建、签名、安装或真机验证，也没有 native C++/JNI 构建、实际 Rime 候选/词典打包检查、完整 ASR 模型准备或推理检查。**335 项通过仅说明本次 JVM 回归结果，不能替代上述检查。

本目录不包含完整编译日志、凭证、用户图片或 APK。
