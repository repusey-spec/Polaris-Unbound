#!/usr/bin/env python3
"""Build 48: expose locally generated station art to Android Auto via content://.

The provider contains only public generated station artwork, never song files.
Run after branding/UI patches and before Kotlin compilation.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
manifest = root / "app/src/main/AndroidManifest.xml"
source = manifest.read_text()
anchor = "    </application>"
assert source.count(anchor) == 1, "Expected exactly one Hush application terminator"
assert "PolarisRadioArtworkProvider" not in source, "Provider already installed"
provider = '''        <!-- Read-only, generated station tiles accessible to Android Auto. -->
        <provider
            android:name="app.hush.music.polaris.PolarisRadioArtworkProvider"
            android:authorities="${applicationId}.polaris.radioart"
            android:exported="true"
            android:grantUriPermissions="true" />
'''
manifest.write_text(source.replace(anchor, provider + anchor, 1))
print("Build 48: Android Auto radio station artwork content provider registered")
