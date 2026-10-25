#!/usr/bin/env python3
"""Route only Polaris radio player errors to its own original-style URL recovery."""
from pathlib import Path
import sys

p = Path(sys.argv[1]) / "app/src/main/kotlin/app/hush/music/playback/MusicService.kt"
s = p.read_text()

def replace_once(before, after):
    global s
    count = s.count(before)
    assert count == 1, f"Expected one anchor ({count}): {before[:100]}"
    s = s.replace(before, after, 1)

replace_once(
    "    @Inject\n    lateinit var database: MusicDatabase",
    "    private val polarisRadioRecovery by lazy {\n"
    "        app.hush.music.polaris.PolarisRadioRecovery(player)\n"
    "    }\n\n"
    "    @Inject\n    lateinit var database: MusicDatabase"
)

replace_once(
    "        super.onMediaItemTransition(mediaItem, reason)",
    "        super.onMediaItemTransition(mediaItem, reason)\n"
    "        polarisRadioRecovery.onItemTransition(mediaItem)"
)
replace_once(
    "        super.onPlaybackStateChanged(playbackState)",
    "        super.onPlaybackStateChanged(playbackState)\n"
    "        polarisRadioRecovery.onPlaybackStateChanged(playbackState)"
)
replace_once(
    "        super.onPlayWhenReadyChanged(playWhenReady, reason)",
    "        super.onPlayWhenReadyChanged(playWhenReady, reason)\n"
    "        polarisRadioRecovery.onPlayWhenReadyChanged(playWhenReady)"
)
replace_once(
    "        super.onIsPlayingChanged(isPlaying)",
    "        super.onIsPlayingChanged(isPlaying)\n"
    "        polarisRadioRecovery.onIsPlayingChanged(isPlaying)"
)
replace_once(
    "        publishWazePlaybackSnapshot(force = true)\n\n"
    "        val currentMediaId = player.currentMediaItem?.mediaId ?: return",
    "        publishWazePlaybackSnapshot(force = true)\n\n"
    "        // Polaris radio URLs expire: bypass Hush's YouTube-track repair path.\n"
    "        if (polarisRadioRecovery.onPlayerError()) return\n\n"
    "        val currentMediaId = player.currentMediaItem?.mediaId ?: return"
)
replace_once(
    "    override fun onDestroy() {\n        super.onDestroy()",
    "    override fun onDestroy() {\n"
    "        if (this::player.isInitialized) polarisRadioRecovery.dispose()\n"
    "        super.onDestroy()"
)
p.write_text(s)
print("Patched Hush Media3 service: radio-only error/reconnect lifecycle")
