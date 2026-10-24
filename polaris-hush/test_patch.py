"""Static contract checks for Polaris browse integration."""
from pathlib import Path
import tempfile
import subprocess
import sys

patch = Path(__file__).with_name("patch_hush_browse.py")
sample = """when (parentId) {
                        MusicService.ROOT -> {
                            originalHushItems()
                        }
                    }"""
with tempfile.TemporaryDirectory() as td:
    f = Path(td) / "app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt"
    f.parent.mkdir(parents=True)
    f.write_text(sample)
    subprocess.run([sys.executable, str(patch), td], check=True)
    result = f.read_text()
    assert result.count('"polaris:radio"') == 2
    assert result.count('"polaris:streaming"') == 2
    assert "originalHushItems()" in result
    assert result.index('"polaris:radio"') < result.index('"polaris:streaming"')
    assert "polaris:can" not in result
print("PASS: root order, Hush contents preserved, CAN not exposed")
