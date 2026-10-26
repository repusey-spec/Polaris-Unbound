#!/usr/bin/env python3
"""Build #42: radio artwork/expanded player and streaming-home visual polish.

Apply AFTER patch_hush_phone_ui.py and patch_hush_mini_player.py so this patch
only changes presentation; the stable #41 AA lifecycle and Streaming player stay.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1])

def replace_once(source: str, before: str, after: str, description: str) -> str:
    count = source.count(before)
    assert count == 1, f"{description}: expected unique anchor, got {count}"
    return source.replace(before, after, 1)

# 1) Keep existing Hush mini-player layout and song metadata, but paint the
# Polaris launcher knob in the circular media artwork whenever radio is active.
mini = root / "app/src/main/kotlin/app/hush/music/ui/player/MiniPlayerComponents.kt"
s = mini.read_text()
s = replace_once(s,
    "    playerConnection: PlayerConnection,\n"
    "    auraAccents: List<Color> = emptyList(),\n",
    "    playerConnection: PlayerConnection,\n"
    "    isPolarisRadio: Boolean = false,\n"
    "    auraAccents: List<Color> = emptyList(),\n",
    "mini artwork radio flag")
s = replace_once(s,
    "        MiniPlayerArtwork(\n"
    "            isPlaying = isPlaying,\n",
    "        MiniPlayerArtwork(\n"
    "            isPlaying = isPlaying,\n"
    "            isPolarisRadio = polarisStationId != null,\n",
    "mini artwork call")
s = replace_once(s,
    "            ) { url ->\n"
    "                if (url != null) {\n"
    "                    AsyncImage(",
    "            ) { url ->\n"
    "                if (isPolarisRadio) {\n"
    "                    Image(\n"
    "                        painter = painterResource(R.mipmap.ic_launcher),\n"
    "                        contentDescription = \"Polaris 라디오\",\n"
    "                        contentScale = ContentScale.Crop,\n"
    "                        modifier = Modifier.fillMaxSize().clip(CircleShape),\n"
    "                    )\n"
    "                } else if (url != null) {\n"
    "                    AsyncImage(",
    "mini artwork bitmap")
s = replace_once(s,
    "            if (pulseMatrixEnabled && pulseMatrixMiniPlayer) {\n",
    "            if (!isPolarisRadio && pulseMatrixEnabled && pulseMatrixMiniPlayer) {\n",
    "preserve radio icon above pulse visualizer")
mini.write_text(s)

# 2) The white blank page is Hush's song metadata screen with null radio song
# info. Render a radio-specific expanded player at the SAME BottomSheet surface.
# A local lambda return skips all normal song-only artwork/metadata branches.
player = root / "app/src/main/kotlin/app/hush/music/ui/player/Player.kt"
s = player.read_text()
s = replace_once(s,
    "import app.hush.music.LocalMiniPlayerBottomPadding\n",
    "import app.hush.music.LocalMiniPlayerBottomPadding\n"
    "import app.hush.music.polaris.PolarisRadioFullPlayer\n"
    "import app.hush.music.polaris.rememberPolarisRadioStationId\n",
    "expanded radio imports")
s = replace_once(s,
    "    ) {\n"
    "        val playbackPosition = LocalPlayerPlaybackPosition.current\n",
    "    ) {\n"
    "        // No song DB row exists for live radio. Keep the same BottomSheet,\n"
    "        // but show the large radio tile rather than Hush's empty song page.\n"
    "        val polarisRadioId = rememberPolarisRadioStationId(playerConnection)\n"
    "        if (polarisRadioId != null) {\n"
    "            PolarisRadioFullPlayer(connection = playerConnection, stationId = polarisRadioId)\n"
    "            return@BottomSheet\n"
    "        }\n"
    "        val playbackPosition = LocalPlayerPlaybackPosition.current\n",
    "expanded radio content")
player.write_text(s)

# 3) Floating Polaris tabs overlay Hush's scrollable Home chips. Push only
# that first chip row down, without moving the audio bar or changing routing.
home = root / "app/src/main/kotlin/app/hush/music/ui/screens/HomeScreen.kt"
s = home.read_text()
s = replace_once(s,
    "                                modifier = Modifier.animateItem(),\n"
    "                            )\n"
    "                        }\n"
    "                    }\n\n"
    "                    if (uiState.quickPicks.isNotEmpty())",
    "                                modifier = Modifier.animateItem().padding(top = 72.dp),\n"
    "                            )\n"
    "                        }\n"
    "                    }\n\n"
    "                    if (uiState.quickPicks.isNotEmpty())",
    "Streaming category chips below fixed tabs")
home.write_text(s)

# 4) Match the phone Streaming header to the new faceted knob launcher.
main = root / "app/src/main/kotlin/app/hush/music/MainActivity.kt"
s = main.read_text()
s = replace_once(s,
    "painter = painterResource(R.drawable.hush_logo_mark)",
    "painter = painterResource(R.mipmap.ic_launcher)",
    "Streaming Polaris header logo")
main.write_text(s)
print("Build 42: Streaming chips padded, Polaris header+mini icons and large radio now-playing surface patched")
