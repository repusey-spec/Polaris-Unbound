#!/usr/bin/env python3
"""Show Polaris programme/station in Hush's existing bottom mini player only for Radio."""
from pathlib import Path
import sys

p = Path(sys.argv[1]) / "app/src/main/kotlin/app/hush/music/ui/player/MiniPlayerComponents.kt"
s = p.read_text()

def replace_once(old, new):
    global s
    count = s.count(old)
    assert count == 1, f"MiniPlayer source changed: expected one anchor, got {count}: {old[:90]}"
    s = s.replace(old, new, 1)

replace_once(
    "import app.hush.music.LocalPlayerConnection\n",
    "import app.hush.music.LocalPlayerConnection\n"
    "import app.hush.music.polaris.PolarisRadioNowPlaying\n"
    "import app.hush.music.polaris.rememberPolarisRadioStationId\n",
)
replace_once(
    "    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()\n",
    "    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()\n"
    "    val polarisStationId = rememberPolarisRadioStationId(playerConnection)\n",
)
replace_once(
    """        mediaMetadata?.let {
            MiniPlayerInfo(
                mediaMetadata = it,
                colors = colors,
            )
        } ?: Spacer(Modifier.weight(1f))""",
    """        if (polarisStationId != null) {
            PolarisRadioNowPlaying(stationId = polarisStationId, colors = colors)
        } else {
            mediaMetadata?.let {
                MiniPlayerInfo(
                    mediaMetadata = it,
                    colors = colors,
                )
            } ?: Spacer(Modifier.weight(1f))
        }""",
)
p.write_text(s)
print("Patched Hush mini player: Polaris programme/station on Radio; original title/artist on Streaming")
