# V08 界面确认与验证记录

构建提交：`bc70583673c52c0c7bfa2732fcb779f8ae469ad2`  
构建记录：https://github.com/xisungod/fcitx5-android/actions/runs/37131862919

## 图片

这些图由最终构建中的实际 Android View 代码在 Robolectric 原生绘图模式生成，不是真机截图。候选栏图片使用固定示例词；真实 Rime 候选另见 `native-candidates.txt`。

| 文件 | 显示内容 |
| --- | --- |
| 01-layout-english.png | 小写英文、顶部编辑/撤销/重做、三星式七键底行、居中空格与短竖线 |
| 02-key-ignite.png | 单键先亮起；当前示例为紫色，实际每次随机选择霓虹色 |
| 03-candidate-ripple.png | 同一次按键的同色光向外扩散至候选栏 |
| 04-idle-breath.png | 两侧呼吸光向中间扩散的较亮时刻 |
| 05-layout-chinese.png | 中文模式与加大候选词 |
| 06-idle-sides.png | 呼吸光从两侧开始 |

## 验证结果

- Android 自动测试 36 项，失败 0、错误 0、跳过 0。
- 实际 librime 1.12.0、Lua 与 octagram 已完成候选、长句、纠错、日期、计算器及英文直接上屏验证。
- `shuagkashuangdai` 与正确拼写 `shuangkashuangdai` 均首选“双卡双待”；邻键错拼 `nihso` 的候选中包含“你好”。
- 中英切换本身保留拼音；英文逐字提交无预编辑内容；中文待选时开始英文输入会先确认中文。
- 长按空格后左右滑动移动光标，松手不插入空格；候选横向滑动、模式切换、功能键无硬矩形、先亮后扩散、底行波纹到候选栏、闲置停止均有验证。
- 英文长按省略号使用文本提交，避免把三个点误作一个按键。
- 主程序与 Rime 插件均为 arm64，包名配套、同一签名；APK 内词库、Lua、语法模型、预编译表及源码包哈希核验通过。

## 保留的限制

- 固定签名所需的 Actions Secrets 权限仍返回 403，当前使用 `.xuancai.black.v08` 独立体验包，不能继承 V07 数据。
- 界面和输入引擎验证均已通过；手机手感、帧率、耗电和目标应用的撤销/重做支持仍需真机验证。
- 尚未发布 V08 Release；等待用户确认这些图片后再发布安装包。

完整功能说明见仓库根目录 `XUANCAI-V08.md`。
