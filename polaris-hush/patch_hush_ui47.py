#!/usr/bin/env python3
"""Polaris Build 47: user-requested Streaming chrome cleanup, AA radio metadata,
and no phantom station label before a real radio playback in this process.
Run after patch_hush_voice.py and patch_hush_ui42.py.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1])
kotlin = root / "app/src/main/kotlin/app/hush/music"
main = kotlin / "MainActivity.kt"
home = kotlin / "ui/screens/HomeScreen.kt"
service = kotlin / "playback/MusicService.kt"
callback = kotlin / "playback/MediaLibrarySessionCallback.kt"

def patch(path, old, new, name):
    source = path.read_text()
    count = source.count(old)
    assert count == 1, f"{name}: expected one upstream anchor, found {count}"
    path.write_text(source.replace(old, new, 1))

# Remove the Hush logo/clock/new-release/settings icon row only on Streaming Home.
# Search bar and the normal app bar of the other Hush pages stay available.
patch(main,
    "                                        if (shouldShowTopBar) {\n",
    "                                        if (shouldShowTopBar && currentRoute != Screens.ROUTE_HOME) {\n",
    "Streaming home top app bar suppression")

# Keep only the user's Radio/Streaming selector plus its '설정하기' link above
# the standard Hush recommendation feed; category mood chips are removed.
patch(home,
    "Spacer(Modifier.height(88.dp))",
    "Spacer(Modifier.height(36.dp))",
    "Streaming content clearance after compact top chrome")
patch(home,
    """                    if (uiState.showCategoryChips) {
                        item(
                            key = "home_category_chips",
                            contentType = "category_chips",
                        ) {
                            HomeCategoryChips(
                                chips = uiState.homePage?.chips.orEmpty(),
                                selectedChip = uiState.selectedChip,
                                onChipSelected = { onAction(HomeAction.SelectChip(it)) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
""",
    """                    // Polaris: removed the original Hush category/mood chip row.
                    // The Quick Picks, playlists, search and Streaming sources remain.
""",
    "Streaming mood/category index")

# AA browse items use stable station IDs, broadcaster title as artist, and
# dynamic programme title/artwork metadata exactly as v0.58.
patch(callback,
    """                                        MediaMetadata.Builder()
                                            .setTitle(station.title)
                                            .setIsPlayable(true)""",
    """                                        app.hush.music.polaris.PolarisRadioMetadata.mediaMetadata(station.id)
                                            .buildUpon()
                                            .setIsPlayable(true)""",
    "AA radio browse programme artwork")

# Register the 0.58 programme-boundary/local-clock metadata refresher after
# the shared Hush player and MediaSession have been initialized.
patch(service,
    "        polarisProjectionRecovery.start()\n",
    "        polarisProjectionRecovery.start()\n"
    "        app.hush.music.polaris.PolarisRadioMetadataRefresh(this, player, scope).start()\n",
    "AA metadata scheduler")

# A station restored only as a paused old queue is not an actual on-air choice.
# Avoid showing '89.1' by default; after real playback, paused radio can show.
patch(service,
    "        polarisProjectionRecovery.onRadioPlaying(isPlaying)\n",
    "        polarisProjectionRecovery.onRadioPlaying(isPlaying)\n"
    "        app.hush.music.polaris.PolarisRadioUiSession.onIsPlayingChanged(\n"
    "            isPlaying, player.currentMediaItem?.mediaId\n"
    "        )\n",
    "Mini player current-session radio state")
print("Build 47 compact Streaming + v0.58 AA radio metadata + dormant radio label guard")
