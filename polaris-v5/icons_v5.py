#!/usr/bin/env python3
"""Generate Polaris v5 icons for launcher, notification, media card and headers."""
from pathlib import Path
import re
import sys
import io
import cairosvg
from PIL import Image, ImageDraw

hush=Path(sys.argv[1])
source=Path(__file__).parent/"polaris-knob.svg"
knob=Image.open(io.BytesIO(cairosvg.svg2png(url=str(source),output_width=640,output_height=640))).convert("RGBA")
dpis={"mdpi":(48,108),"hdpi":(72,162),"xhdpi":(96,216),"xxhdpi":(144,324),"xxxhdpi":(192,432)}
for dpi,(launcher,adaptive) in dpis.items():
    dest=hush/"app/src/main/res"/("mipmap-"+dpi)
    dest.mkdir(parents=True,exist_ok=True)
    knob.resize((adaptive,adaptive),Image.Resampling.LANCZOS).save(dest/"ic_launcher_foreground.png")
    alpha=knob.getchannel("A").resize((adaptive,adaptive),Image.Resampling.LANCZOS)
    mono=Image.new("RGBA",(adaptive,adaptive),(255,255,255,255))
    mono.putalpha(alpha)
    mono.save(dest/"ic_launcher_monochrome.png")
    bg=Image.new("RGBA",(launcher,launcher),(18,24,33,255))
    bg.alpha_composite(knob.resize((launcher,launcher),Image.Resampling.LANCZOS))
    bg.save(dest/"ic_launcher.png")
    bg.save(dest/"ic_launcher_round.png")
    Image.new("RGBA",(launcher,launcher),(18,24,33,255)).save(dest/"ic_launcher_background.png")

    size=launcher//2
    box=knob.getchannel("A").getbbox()
    assert box is not None
    cut=knob.getchannel("A").crop(box)
    fit=int(size*0.77)
    ratio=min(fit/cut.width,fit/cut.height)
    w,h=max(1,round(cut.width*ratio)),max(1,round(cut.height*ratio))
    out_alpha=Image.new("L",(size,size),0)
    out_alpha.paste(cut.resize((w,h),Image.Resampling.LANCZOS),((size-w)//2,(size-h)//2))
    mark=Image.new("RGBA",(size,size),(255,255,255,255))
    mark.putalpha(out_alpha)
    d=hush/"app/src/main/res"/("drawable-"+dpi)
    d.mkdir(parents=True,exist_ok=True)
    mark.save(d/"polaris_v5_small.png")

drawable=hush/"app/src/main/res/drawable"
xml='''<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@drawable/polaris_v5_small" android:gravity="center" />
'''
for name in ("small_icon.xml","app_icon_small.xml"):
    assert (drawable/name).exists()
    (drawable/name).write_text(xml)
fg=drawable/"app_icon_foreground.xml"
if fg.exists():
    txt=fg.read_text().replace("@drawable/hush_logo_mark","@mipmap/ic_launcher_foreground")
    fg.write_text(txt)
for f in (hush/"app/src/main/res").glob("values*/colors.xml"):
    txt=f.read_text()
    txt=re.sub(r'(<color name="ic_launcher_background">).*?(</color>)',r'\g<1>#121821\g<2>',txt)
    f.write_text(txt)
# The original Hush also paints a logo directly in the phone header:
# update those references, not only the launcher.
for file in (hush/"app/src/main/kotlin/app/hush/music").rglob("*.kt"):
    t=file.read_text()
    o=t
    t=t.replace("R.drawable.hush_logo_mark","R.mipmap.ic_launcher_foreground")
    t=t.replace("R.drawable.hush_app_icon","R.mipmap.ic_launcher")
    if t!=o: file.write_text(t)
for file in (hush/"app/src/main/res").rglob("*.xml"):
    t=file.read_text()
    o=t
    t=t.replace("@drawable/hush_logo_mark","@mipmap/ic_launcher_foreground")
    t=t.replace("@drawable/hush_app_icon","@mipmap/ic_launcher")
    if t!=o:file.write_text(t)
for p in (hush/"app/src").glob("*/res/values*/strings.xml"):
    t=p.read_text()
    t=re.sub(r'(<string name="app_name"[^>]*>).*?(</string>)',r'\1Polaris v5\2',t)
    p.write_text(t)
print("Polaris v5: five-density launcher, monochrome AA/notification, phone header branding applied")
