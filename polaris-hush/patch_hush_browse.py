#!/usr/bin/env python3
"""Patch the pinned Hush source in a checkout. AA browse routing only.
This is deliberately not a radio playback implementation.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
callback = root / "app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt"
source = callback.read_text()
needle = "                        MusicService.ROOT -> {"
assert source.count(needle) == 1, "Unexpected Hush callback version"
# Root has two explicit sections. Streaming returns the original Hush root
# list verbatim, via the same branch logic. Radio is browsable but cannot
# pretend to be playable until radio MediaItems and player are implemented.
source = source.replace(needle, '''                        MusicService.ROOT -> {
                            listOf(
                                browsableMediaItem(
                                    "polaris:radio",
                                    "Radio",
                                    null,
                                    null, // Vehicle UI: no decorative icon above Radio
                                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                ),
                                browsableMediaItem(
                                    "polaris:streaming",
                                    "Streaming",
                                    null,
                                    null, // Vehicle UI: no decorative icon above Streaming
                                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                ),
                            )
                        }

                        "polaris:radio" -> {
                            // Radio station playback is NOT connected yet.
                            // Avoid advertising unplayable stations to AA.
                            emptyList()
                        }

                        "polaris:streaming" -> {''', 1)
callback.write_text(source)
print("Patched AA browse root: Radio / Streaming; Hush streaming tree retained")
