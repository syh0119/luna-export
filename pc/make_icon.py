# -*- coding: utf-8 -*-
"""生成 汽水导出 的图标（蓝色圆角底 + 白色下载箭头），输出多尺寸 ICO"""
from PIL import Image, ImageDraw

S = 1024
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
W = (255, 255, 255, 255)

# 圆角蓝底（与 UI 主色一致 #3482FF）
d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.235), fill=(52, 130, 255, 255))

cx = S // 2
# 竖杆
d.rectangle([cx - int(S * 0.055), int(S * 0.22), cx + int(S * 0.055), int(S * 0.55)], fill=W)
# 箭头
d.polygon([(cx - int(S * 0.165), int(S * 0.49)),
           (cx + int(S * 0.165), int(S * 0.49)),
           (cx, int(S * 0.725))], fill=W)
# 底部托线
d.rounded_rectangle([int(S * 0.255), int(S * 0.775), int(S * 0.745), int(S * 0.845)],
                    radius=int(S * 0.035), fill=W)

out = r"icon.ico"
img.save(out, format="ICO",
         sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
import os
print("图标已生成:", out, os.path.getsize(out), "B")
