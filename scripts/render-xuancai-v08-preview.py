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
d.text((40,87),'三星式底行 · 空格居中 · 单键先变色，再扩散至候选栏',font=font(23),fill='#a5adbf')
scenes=[
 ('layout-english.png','01  英文与顶部工具栏','编辑 / 撤销 / 重做置顶；长按空格滑动光标',38,155),
 ('layout-key-ignite.png','02  按键先亮起','每键随机一种霓虹色；先亮时长可设置',620,155),
 ('layout-candidate-ripple.png','03  同色向外扩散','柔雾延伸至候选栏；候选字保持清晰',38,694),
 ('layout-glow-inward.png','04  闲置呼吸光','两侧向中间扩散；每周期随机换色',620,694),
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
