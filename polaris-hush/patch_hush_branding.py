#!/usr/bin/env python3
"""Set and verify Polaris launcher label across Android build variants."""
from pathlib import Path
import sys
root=Path(sys.argv[1])
manifest=root/"app/src/main/AndroidManifest.xml"
s=manifest.read_text()
assert 'android:label="@string/app_name"' in s
s=s.replace('android:label="@string/app_name"', 'android:label="Polaris"')
manifest.write_text(s)
# Debug or variant resources can still define app_name; force the value in all source sets.
for p in (root/"app/src").glob("*/res/values*/strings.xml"):
    txt=p.read_text()
    import re
    txt=re.sub(r'(<string name="app_name"[^>]*>).*?(</string>)',r'\1Polaris\2',txt)
    p.write_text(txt)
print("Polaris manifest label and app_name resources patched")
