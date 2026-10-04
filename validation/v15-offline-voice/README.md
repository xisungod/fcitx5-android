# V15 系统离线听写

用户要求：调用系统语音听写，任何在线功能均不加入，词库更新除外。

## 已实现

- 顶栏语音按钮改为系统离线听写，默认显示；不再切换到其他语音输入法。
- 只通过 Android 12 / API 31 起的 `createOnDeviceSpeechRecognizer` 识别普通话。旧版系统或没有本地服务时显示明确提示。
- Android 13 / API 33 起检查系统已安装且可用的 `zh-CN` 模型；在线、待下载和可下载列表不作为可用依据。Android 12 通过专用本地接口尝试，错误后停止。
- 不调用通用识别服务，不通过 Activity 跳转到外部语音应用，不调用语音模型下载接口。
- 首次点击请求麦克风权限；授权后返回输入框重新点击开始，权限流程不会自动开始录音。
- 临时文字留在听写预览中，最终结果需要点击“插入文字”后才写入目标应用。“说完了”、取消和重新听写均可用。
- 校验输入会话、输入连接、光标及未完成拼音；切换输入框、收起键盘、主题重建、输入解绑和服务销毁都取消会话。拒绝或迟到的结果不写入新窗口。
- 系统识别超时、无语音、服务占用、中文不可用或权限被拒绝均停止并提示，没有在线回退。
- 主应用合并清单不含 INTERNET；云候选仍关闭。禁用系统云备份，手动本地备份/词库导入保留。
- 词库更新是唯一允许的联网功能类别；本轮没有新增自动联网词库更新器。现有应用仍通过用户选择文件导入词库。
- 长按空格移动光标、按压回弹和流光参数未变。

## 验证边界

Kotlin 编译已通过。最终完整回归 30 类、205 项全部通过，失败、错误、跳过均为 0；其中新增离线语音相关检查 32 项。测试包含真实 Android/Robolectric UI、系统接口模拟服务、会话状态、权限与合并清单检查。结果和完整日志见同目录 JSON/XML/TXT。

首轮 205 项中 6 项界面测试发现 Material 弹窗主题与现有平台键盘主题不兼容；改用项目现有平台 AlertDialog，保留原交互断言。原失败记录保存在 `first-ui-theme-failure.xml` 和 `first-test-log.txt`。

本机没有连接用户手机，未验证荣耀系统是否提供本地中文服务，未测真实语音准确率、延迟或耗电。系统本地识别由系统服务实现；应用遵守专用 on-device API 合约，不等同于对厂商系统组件完成网络抓包审计。

尚未构建或发布本轮 APK；已有测试不能当作真机听写成功证明。

## 重现测试

```sh
BUILD_VERSION_NAME=xuancai-black-v15 BUILD_ABI=arm64-v8a XUANCAI_APP_SUFFIX=.xuancai.black.v15 python3 /tmp/xuancai-run-gradle.py :app:testDebugUnitTest -x :app:generateDataDescriptor
```

本地 Gradle launcher 指向已应用累计补丁的 `/tmp/xuancai-v13-source`。发布工程的基线是 `e6199a2801b0c76e1baeff7a48f6b18e910e9dd7`，通过 `xuancai.patch` 应用新增代码；本次未推送或触发发布工作流。

## 平台依据

- [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)：专用本地识别、可用性检查、停止和销毁。
- [RecognitionSupport](https://developer.android.com/reference/android/speech/RecognitionSupport)：已安装本地模型与可下载/在线模型的区别。
- [RecognizerIntent](https://developer.android.com/reference/android/speech/RecognizerIntent)：`EXTRA_PREFER_OFFLINE` 可被实现忽略，因此不能作为唯一离线保证。

最终源码提交：`b22a860e7492264bc0d5506b83212cfca4c49e11`。累计补丁 SHA-256：`e5b9b8a560f3f7c4892cd8c7a8478856ca42058f2e88bbb3ff5a1a1efa312cde`。135 个累计修改文件与干净基线应用结果逐字节一致。
