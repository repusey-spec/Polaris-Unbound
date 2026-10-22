#!/usr/bin/env python3
"""Generate a lean JVM source-tree boundary for upstream Hush InnerTube core.

Keeps Hush's original core unchanged. This does NOT provide an Android player.
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

UPSTREAM_URL = "https://github.com/maxinjohn/Hush"
UPSTREAM_TAG = "v13.14.7"
UPSTREAM_SHA = "8f3e55d1114f1b844f0a21af8c365d090a43799f"
MARKER = ".polaris-hush-lite-generated"

SETTINGS = '''pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("com.github.TeamNewPipe") }
        }
    }
}
rootProject.name = "PolarisHushLiteCore"
include(":core")
'''

BUILD = '''// Isolated JVM build only. NO Android app or APK task.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
'''

SEARCH_ADAPTER = r'''/*
 * Polaris Hush Lite integration adapter
 * Preserves Hush/ArchiveTune (GPL-3.0) search results and WatchEndpoint identity.
 */
package app.hush.music.lite

import app.hush.music.innertube.YouTube
import app.hush.music.innertube.models.SongItem
import app.hush.music.innertube.models.WatchEndpoint

object HushLiteSongSearch {
    data class Candidate(
        val videoId: String,
        val title: String,
        val artistNames: List<String>,
        val artworkUrl: String,
        val endpoint: WatchEndpoint,
    )

    /** Hush-native online search; results keep their actual playable video IDs. */
    suspend fun songs(rawQuery: String, limit: Int = 30): Result<List<Candidate>> {
        val query = rawQuery.trim()
        if (query.isEmpty() || limit <= 0) return Result.success(emptyList())
        return YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).map { page ->
            page.items
                .filterIsInstance<SongItem>()
                .distinctBy { it.id }
                .take(limit.coerceAtMost(100))
                .map { song ->
                    Candidate(
                        videoId = song.id,
                        title = song.title,
                        artistNames = song.artists.map { it.name },
                        artworkUrl = song.thumbnail,
                        endpoint = song.endpoint ?: WatchEndpoint(videoId = song.id),
                    )
                }
        }
    }
}
'''

REQUIRED_FILES = (
    "core/build.gradle.kts",
    "core/src/main/kotlin/app/hush/music/innertube/YouTube.kt",
    "core/src/main/kotlin/app/hush/music/innertube/InnerTube.kt",
    "core/src/main/kotlin/app/hush/music/innertube/PlaybackAuthState.kt",
    "core/src/main/kotlin/app/hush/music/innertube/SearchFilter.kt",
    "gradle/libs.versions.toml",
    "LICENSE",
)


def source_sha(source: Path) -> str:
    try:
        return subprocess.check_output(
            ["git", "-C", str(source), "rev-parse", "HEAD"],
            encoding="utf-8", stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ValueError("Upstream source must be a git checkout pinned to v13.14.7") from exc


def stage(source: Path, dest: Path, *, expected_sha: str = UPSTREAM_SHA) -> dict:
    source = source.resolve()
    dest = dest.resolve()
    if source == dest or source in dest.parents or dest in source.parents:
        raise ValueError("Source and destination must be independent directories")
    for relative in REQUIRED_FILES:
        if not (source / relative).is_file():
            raise FileNotFoundError(f"Required Hush upstream file missing: {relative}")
    sha = source_sha(source)
    if sha != expected_sha:
        raise ValueError(f"Wrong Hush revision: expected {expected_sha}, found {sha}")
    if dest.exists():
        raise FileExistsError(f"Output exists; refusing to overwrite: {dest}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hush-lite-stage-", dir=str(dest.parent)) as tmp:
        work = Path(tmp) / "generated"
        work.mkdir()
        shutil.copytree(
            source / "core", work / "core",
            ignore=shutil.ignore_patterns("build", ".gradle", ".idea", "*.iml"),
        )
        (work / "gradle").mkdir()
        shutil.copy2(source / "gradle/libs.versions.toml", work / "gradle/libs.versions.toml")
        shutil.copy2(source / "LICENSE", work / "LICENSE")
        (work / "settings.gradle.kts").write_text(SETTINGS, encoding="utf-8")
        (work / "build.gradle.kts").write_text(BUILD, encoding="utf-8")
        adapter_path = work / "core/src/main/kotlin/app/hush/music/lite/HushLiteSongSearch.kt"
        adapter_path.parent.mkdir(parents=True, exist_ok=True)
        adapter_path.write_text(SEARCH_ADAPTER, encoding="utf-8")
        (work / MARKER).write_text(sha + "\n", encoding="utf-8")
        upstream_files = [p for p in source.rglob("*") if p.is_file() and ".git" not in p.parts]
        staged_files = [p for p in work.rglob("*") if p.is_file()]
        report = {
            "upstream": UPSTREAM_URL,
            "tag": UPSTREAM_TAG,
            "commit": sha,
            "scope": "Hush InnerTube JVM core plus thin ID-preserving song search adapter (no Android/AA/player)",
            "modules": ["core"],
            "search_adapter": "app.hush.music.lite.HushLiteSongSearch",
            "retained_core_files": len([p for p in (work / "core").rglob("*") if p.is_file()]),
            "staged_total_files": len(staged_files),
            "upstream_total_files_excluding_git": len(upstream_files),
            "compile_verified": False,
            "apk_generated": False,
        }
        (work / "stage-report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        work.rename(dest)
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, required=True, help="Exact Hush v13.14.7 git checkout")
    parser.add_argument("--out", type=Path, required=True, help="New empty output path")
    args = parser.parse_args()
    print(json.dumps(stage(args.upstream, args.out), indent=2))


if __name__ == "__main__":
    main()
