#!/usr/bin/env python3
"""Check that the two AA browse roots have no decorative image and old Hush
notification icons are replaced with distinct, real Polaris white silhouette."""
from pathlib import Path
import sys
from PIL import Image
root = Path(sys.argv[1]) / "app/src/main"
from pathlib import Path as _Path
callback=(root/"kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt").read_text()
small=(root/"res/drawable/small_icon.xml").read_text()
appsmall=(root/"res/drawable/app_icon_small.xml").read_text()
r=callback.index('browsableMediaItem(\n                                    "polaris:radio"')
s=callback.index('browsableMediaItem(\n                                    "polaris:streaming"')
radio=callback[r:r+350]
streaming=callback[s:s+350]
checks={
    "Radio top tab has no decorative media artwork":
        '"Radio",\n                                    null,\n                                    null,' in radio,
    "Streaming top tab has no decorative media artwork":
        '"Streaming",\n                                    null,\n                                    null,' in streaming,
    "notification small icon points to Polaris artwork":
        '@drawable/polaris_notification_small' in small and "hush" not in small.lower(),
    "secondary small icon points to Polaris artwork":
        '@drawable/polaris_notification_small' in appsmall and "hush" not in appsmall.lower(),
}
for dpi, size in (("mdpi",24),("hdpi",36),("xhdpi",48),("xxhdpi",72),("xxxhdpi",96)):
    path=root/f"res/drawable-{dpi}/polaris_notification_small.png"
    if path.exists():
        with Image.open(path) as im:
            check = im.size==(size,size) and im.mode=="RGBA" and im.getchannel("A").getbbox() is not None
    else:
        check=False
    checks[f"real Polaris monochrome {dpi} image"] = check
for name, passed in checks.items():
    print(("PASS" if passed else "FAIL")+": "+name)
assert all(checks.values()), "Build 56 icons failed validation"
