"""Turn a Higgsfield-generated tile into the Fire TV icon set.

Crops to exactly 16:9, snaps the faint off-white background noise to pure white,
re-centers the logo, saves the 1280x720 master tile, and writes the launcher icon +
banner at every screen density.
"""
import sys
import os
from PIL import Image
os.makedirs("art", exist_ok=True)

src = Image.open(sys.argv[1]).convert("RGB")
W, H = src.size
ch = round(W * 9 / 16)
src = src.crop((0, (H - ch) // 2, W, (H - ch) // 2 + ch)).resize((1280, 720), Image.LANCZOS)

# near-white -> pure white
px = src.load()
for y in range(720):
    for x in range(1280):
        r, g, b = px[x, y]
        if r > 244 and g > 244 and b > 244:
            px[x, y] = (255, 255, 255)

# re-center the logo on a clean white canvas
mask = Image.eval(src.convert("L"), lambda v: 255 if v < 245 else 0)
box = mask.getbbox()
logo = src.crop(box)
if logo.width > 882 or logo.height > 448:          # shrink to fit Amazon's center safe area
    s = min(882 / logo.width, 448 / logo.height)
    logo = logo.resize((int(logo.width * s), int(logo.height * s)), Image.LANCZOS)
tile = Image.new("RGB", (1280, 720), (255, 255, 255))
tile.paste(logo, ((1280 - logo.width) // 2, (720 - logo.height) // 2))
print("logo size", logo.size, "-> safe area 882x448:", logo.width <= 882 and logo.height <= 448)

tile.save("art/kfdx-plus-firetv-tile-1280x720.png", optimize=True)
for d, (w, h) in {"mdpi": (160, 90), "hdpi": (240, 135), "xhdpi": (320, 180),
                  "xxhdpi": (480, 270), "xxxhdpi": (640, 360)}.items():
    small = tile.resize((w, h), Image.LANCZOS)
    small.save(f"app/src/main/res/mipmap-{d}/ic_launcher.png", optimize=True)
    small.save(f"app/src/main/res/drawable-{d}/banner.png", optimize=True)
