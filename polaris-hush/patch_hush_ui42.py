#!/usr/bin/env python3
"""Build #42: radio-only full artwork player, Polaris chrome knob branding,
and stable vertical clearance for Hush Streaming category chips.
Runs after the pinned Hush mini-player, phone and icon patches.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1])

def patch_one(path, before, after, label):
    s = path.read_text()
    count = s.count(before)
    assert count == 1, f"{label}: expected one source anchor, got {count}"
    path.write_text(s.replace(before, after, 1))

main = root / "app/src/main/kotlin/app/hush/music/MainActivity.kt"
patch_one(main,
    "painter = painterResource(R.drawable.hush_logo_mark),\n"
    "                                                            contentDescription = null,\n"
    "                                                            contentScale = ContentScale.Fit,",
    "painter = painterResource(R.mipmap.ic_launcher_foreground),\n"
    "                                                            contentDescription = \"Polaris 볼륨 노브\",\n"
    "                                                            contentScale = ContentScale.Fit,",
    "Streaming header chrome knob")

home = root / "app/src/main/kotlin/app/hush/music/ui/screens/HomeScreen.kt"
patch_one(home,
    "                ) {\n"
    "                    // Show a prominent progress indicator at the top when chips are loading",
    "                ) {\n"
    "                    // The fixed Radio/Streaming selector + Hush home app bar overlay\n"
    "                    // the top of Home's original chips. Reserve clearance *inside*\n"
    "                    // the scrolling feed so the chips stay visible beneath the bars.\n"
    "                    item(key = \"polaris_streaming_header_clearance\") {\n"
    "                        Spacer(Modifier.height(88.dp))\n"
    "                    }\n"
    "                    // Show a prominent progress indicator at the top when chips are loading",
    "Streaming category row below Polaris selector/header")

mini = root / "app/src/main/kotlin/app/hush/music/ui/player/MiniPlayerComponents.kt"
patch_one(mini,
    "    downloadProgress: Float? = null,\n"
    ") {",
    "    downloadProgress: Float? = null,\n"
    "    radioStationId: String? = null,\n"
    ") {",
    "MiniPlayerArtwork param")

patch_one(mini,
    "            Crossfade(\n"
    "                targetState = artworkUrl,\n",
    "            if (radioStationId != null) {\n"
    "                // Radio uses the same Polaris diamond-cut knob as the app icon.\n"
    "                // Streaming continues showing its song cover unchanged.\n"
    "                Image(\n"
    "                    painter = painterResource(R.mipmap.ic_launcher_foreground),\n"
    "                    contentDescription = \"Polaris Radio\",\n"
    "                    contentScale = ContentScale.Fit,\n"
    "                    modifier = Modifier.fillMaxSize().clip(CircleShape),\n"
    "                )\n"
    "            } else {\n"
    "            Crossfade(\n"
    "                targetState = artworkUrl,\n",
    "MiniPlayerArtwork radio art")

patch_one(mini,
    "            }\n\n"
    "            if (pulseMatrixEnabled && pulseMatrixMiniPlayer) {",
    "            }\n"
    "            }\n\n"
    "            if (pulseMatrixEnabled && pulseMatrixMiniPlayer) {",
    "close MiniPlayerArtwork radio art branch")

patch_one(mini,
    "            downloadProgress = activeDownloadFraction,\n"
    "        )",
    "            downloadProgress = activeDownloadFraction,\n"
    "            radioStationId = polarisStationId,\n"
    "        )",
    "radio art passed to mini player")

player = root / "app/src/main/kotlin/app/hush/music/ui/player/Player.kt"
patch_one(player,
    "import app.hush.music.LocalPlayerConnection\n",
    "import app.hush.music.LocalPlayerConnection\n"
    "import app.hush.music.polaris.PolarisRadioFullPlayer\n"
    "import app.hush.music.polaris.rememberPolarisRadioStationId\n",
    "player sheet import radio")

patch_one(player,
    "    ) {\n"
    "        val playbackPosition = LocalPlayerPlaybackPosition.current\n",
    "    ) {\n"
    "        val radioStationId = rememberPolarisRadioStationId(playerConnection)\n"
    "        if (radioStationId != null) {\n"
    "            // Radio has a Media3 item but no Hush database MediaMetadata;\n"
    "            // the original full music player would consequently be blank.\n"
    "            PolarisRadioFullPlayer(\n"
    "                stationId = radioStationId,\n"
    "                playerConnection = playerConnection,\n"
    "                onClose = { state.collapseSoft() },\n"
    "            )\n"
    "            return@BottomSheet\n"
    "        }\n"
    "        val playbackPosition = LocalPlayerPlaybackPosition.current\n",
    "radio full player in shared bottom sheet")

print("Build 42 phone UI: streaming chips offset, header knob, radio mini artwork and full Now Playing")
