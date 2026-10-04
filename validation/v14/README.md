# V14 界面确认

这里的图像由生产 Android 键盘视图、触摸路径、候选栏和弹字视图直接绘制，使用 Robolectric NATIVE 渲染；不是手机录屏，也不是人工重画的效果图。

- `continuous-typing.gif` / `.mp4`：每 150ms 输入一个字母，按下 50ms，20fps，含退场。
- `single-key-release.gif` / `.mp4`：单次点击与键面、柔光退场。
- `v14-number-nine-grid.png`：数字九宫格。
- `v14-symbols-*.png`：分类符号页。

GIF 为便于直接播放缩放至 480px 宽，MP4 保留 600px 原始宽度，二者均保持 20fps。

候选数据回放配套 Rime 引擎的已记录结果；本项绘制验证不代表在主机上执行 ARM Rime，也不代表新的候选质量测评。设备上的流畅度和亮度需要实际安装验证。

复现：运行 `:app:testDebugUnitTest` 后，执行 `python3 validation/v14/render-previews.py app/build/outputs/effect-checks`。
