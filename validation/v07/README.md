# V07 实际绘制检查

图像直接来自 Robolectric 的 Android 原生绘制，没有使用概念图替代代码效果。

- layout-english.png：真实 TextKeyboard 和 CandidateUi，英文状态、小写字母、24sp 示例候选、六键底行和 8dp 短竖线。测试使用英文系统资源，所以空格显示 Space；中文手机显示“空格”。候选数据为固定中文样例，未连接实际输入引擎，因此不代表英文模式的真实候选内容。
- idle-sides-start.png / idle-sides-merged.png：同一个 IdleGlowPainter 的两个阶段，用相同亮度检查扩散方向；实际运行还会叠加随时间变化的呼吸亮度。

这些是绘制检查产物，不是手机截图。完整测试报告见 Actions 的 xuancai-v07-render-checks 产物。
