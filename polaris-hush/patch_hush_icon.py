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
    out = hush_root / "app/src/main/res" / ("mipmap-" + density + "-v4")
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
