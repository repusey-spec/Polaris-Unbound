#!/usr/bin/env python3
"""Brand isolated Hush experiment as Polaris without changing stable app."""
from pathlib import Path
import sys, re
root=Path(sys.argv[1])
manifest=root/"app/src/main/AndroidManifest.xml"
s=manifest.read_text()
# Override app and launcher labels directly; avoids per-locale Hush Debug overrides.
s=s.replace('android:label="@string/app_name"', 'android:label="Polaris"')
manifest.write_text(s)
# Keep original package namespace and application id for this experimental build.
print("Set Polaris manifest app/launcher labels")
