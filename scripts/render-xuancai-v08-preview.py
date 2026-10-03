#!/usr/bin/env python3
"""Assemble actual Android View renderings into a labelled review sheet; never repaint UI pixels."""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
p=argparse.ArgumentParser()
p.add_argument('checks',type=Path)
p.add_argument('output',type=Path)
p.add_argument('--font',type=Path,required=True)
a=p.parse_args()
font=lambda size:ImageFont.truetype(str(a.font),size)
canvas=Image.new('RGB',(1200,1300),'#0b0c10')
d=ImageDraw.Draw(canvas)
d.text((38,25),'曜黑 V08 · 界面确认',font=font(40),fill='#f5f7ff')
d.text((40,87),'空格居中 · 编辑 / 撤销 / 重做置顶 · 波纹延伸至候选栏',font=font(23),fill='#a5adbf')
scenes=[
 ('layout-english.png','01  英文与顶部工具栏','小写键帽；英文逐字上屏；空格长按后滑动',38,155),
 ('layout-candidate-ripple.png','02  中文候选与按键波纹','大字候选可滑动；光效绘制在候选字后方',620,155),
 ('layout-glow-sides.png','03  两侧起光','闲置开始呼吸；每个周期随机霓虹色',38,694),
 ('layout-glow-inward.png','04  向中央扩散','淡入淡出；时间、亮度和闲置停止可设置',620,694),
]
for file,title,caption,x,y in scenes:
 d.text((x,y),title,font=font(25),fill='#f1f4fa')
 source=next(a.checks.rglob(file))
 image=Image.open(source).convert('RGB')
 # Resize the captured pixels; do not substitute hand-drawn keys or candidates.
 image=image.resize((540,468),Image.Resampling.LANCZOS)
 canvas.paste(image,(x,y+43))
 d.rounded_rectangle((x-1,y+42,x+540,y+511),radius=12,outline='#2c303a',width=1)
 d.text((x,y+514),caption,font=font(19),fill='#a5adbf')
d.text((40,1254),'实际 Android View 代码绘制验证图 · 非真机截图 · 待你确认后发布',font=font(21),fill='#8691a7')
a.output.parent.mkdir(parents=True,exist_ok=True)
canvas.save(a.output)
print(a.output)
