#!/usr/bin/env python3
"""Create a temporary Hush Lite Android source build; no APK generation."""
from __future__ import annotations

import argparse
import importlib.util
import json
import shutil
import tempfile
from pathlib import Path

_HERE = Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("stage_core", _HERE / "stage_core.py")
_core = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_core)
_SHELL = _HERE.parent / "android-shell" / "app"


def stage_android(source: Path, destination: Path) -> dict:
    source = source.resolve()
    destination = destination.resolve()
    if destination.exists():
        raise FileExistsError("Do not overwrite an existing Android stage")
    if source in destination.parents or destination in source.parents:
        raise ValueError("Source/output paths must be independent")
    if not (_SHELL / "build.gradle.kts").is_file():
        raise FileNotFoundError("Android source shell is missing")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hush-lite-android-", dir=destination.parent) as tmp:
        work = Path(tmp) / "staged"
        report = _core.stage(source, work)
        shutil.copytree(_SHELL, work / "app")
        settings = work / "settings.gradle.kts"
        settings.write_text(settings.read_text(encoding="utf-8") + '\ninclude(":app")\n', encoding="utf-8")
        report["scope"] = "Hush JVM InnerTube core plus minimal Android login/search/AA/player diagnostic"
        report["modules"] = ["core", "app"]
        report["android_compile_verified"] = False
        report["stream_playback_verified"] = False
        report["aa_voice_verified"] = False
        report["apk_generated"] = False
        (work / "stage-report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        work.rename(destination)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(stage_android(args.upstream, args.out), indent=2))


if __name__ == "__main__":
    main()
