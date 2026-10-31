#!/usr/bin/env python3
"""Build a genuinely new Polaris faceted-metal volume-knob adaptive launcher icon."""
from pathlib import Path
from io import BytesIO
import re
import sys
import cairosvg
from PIL import Image, ImageDraw, ImageFilter

repo_root = Path(__file__).resolve().parent
hush_root = Path(sys.argv[1])
source = repo_root / "assets" / "polaris-knob.svg"
assert source.is_file(), source
png = cairosvg.svg2png(url=str(source), output_width=640, output_height=640)
knob = Image.open(BytesIO(png)).convert("RGBA")
# The adaptive foreground is transparent and occupies Android's safe center.
sizes = {"mdpi": (48,108), "hdpi": (72,162), "xhdpi": (96,216),
         "xxhdpi": (144,324), "xxxhdpi": (192,432)}
for density, (launcher_px, fg_px) in sizes.items():
    out = hush_root / "app/src/main/res" / ("mipmap-" + density)
    out.mkdir(parents=True, exist_ok=True)
    knob.resize((fg_px, fg_px), Image.Resampling.LANCZOS).save(out / "ic_launcher_foreground.png")
    mono = Image.new("RGBA", (fg_px, fg_px))
    alpha = knob.getchannel("A").resize((fg_px, fg_px), Image.Resampling.LANCZOS)
    mono.putdata([(255,255,255,a) for a in alpha.getdata()])
    mono.save(out / "ic_launcher_monochrome.png")

    # Legacy icon tiles contain a full-bleed dark graphite background.
    b = Image.new("RGBA",(launcher_px,launcher_px), (15,21,30,255))
    dr = ImageDraw.Draw(b)
    for y in range(launcher_px):
        v = int(12 * (1-y / launcher_px))
        dr.line((0,y,launcher_px,y),fill=(17+v,23+v,33+v,255))
    scaled = knob.resize((launcher_px,launcher_px), Image.Resampling.LANCZOS)
    b.alpha_composite(scaled)
    b.save(out / "ic_launcher.png")
    b.save(out / "ic_launcher_round.png")
    Image.new("RGBA",(launcher_px,launcher_px),(17,23,32,255)).save(out / "ic_launcher_background.png")

# The Android Auto dashboard/mini media card may use the notification small
# icon rather than the launcher icon. Give both resource names a real Polaris
# monochrome mark (white silhouette on transparent background), not a color
# adaptive launcher foreground or the old Hush white vector.
base_alpha = knob.getchannel("A")
bbox = base_alpha.getbbox()
assert bbox is not None, "Polaris artwork has no opaque pixels"
cropped = base_alpha.crop(bbox)
for density, (launcher_px, _) in sizes.items():
    size = launcher_px // 2
    fit = max(1, int(size * 0.82))
    scale = min(fit / cropped.width, fit / cropped.height)
    w = max(1, int(round(cropped.width * scale)))
    h = max(1, int(round(cropped.height * scale)))
    mask = Image.new("L", (size, size), 0)
    mask.paste(cropped.resize((w, h), Image.Resampling.LANCZOS), ((size - w) // 2, (size - h) // 2))
    mark = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    mark.putalpha(mask)
    out = hush_root / "app/src/main/res" / ("drawable-" + density)
    out.mkdir(parents=True, exist_ok=True)
    mark.save(out / "polaris_notification_small.png")

small_icon_xml = '''<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@drawable/polaris_notification_small"
    android:gravity="center" />
'''
for name in ("small_icon.xml", "app_icon_small.xml"):
    target = hush_root / "app/src/main/res/drawable" / name
    assert target.is_file(), f"Missing upstream small icon: {name}"
    target.write_text(small_icon_xml)

# The adaptive XML already points to mipmap/ic_launcher_foreground.
# Change the solid background swatch and the in-app alternative icon.
for palette in (hush_root / "app/src/main/res").glob("values*/colors.xml"):
    s=palette.read_text()
    s=re.sub(r'(<color name="ic_launcher_background">).*?(</color>)',
             r'\g<1>#111720\g<2>', s)
    palette.write_text(s)
drawable = hush_root / "app/src/main/res/drawable/app_icon_foreground.xml"
if drawable.is_file():
    s=drawable.read_text()
    s=s.replace("@drawable/hush_logo_mark", "@mipmap/ic_launcher_foreground")
    drawable.write_text(s)
print("Polaris diamond-cut chrome volume knob generated for all five densities and adaptive variants")
