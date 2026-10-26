#!/usr/bin/env python3
"""Restore Polaris v0.58 direct radio HTTP/HLS loading within Hush's one player."""
from pathlib import Path
import sys

p = Path(sys.argv[1]) / "app/src/main/kotlin/app/hush/music/playback/MusicService.kt"
s = p.read_text()

def once(before, after):
    global s
    n = s.count(before)
    assert n == 1, f"Expected exactly one upstream anchor, got {n}: {before[:100]!r}"
    s = s.replace(before, after, 1)

once(
    "        polarisRadioRecovery.onItemTransition(mediaItem)\n",
    "        polarisRadioRecovery.onItemTransition(mediaItem)\n"
    "        app.hush.music.polaris.PolarisRadioNetwork.radioActive =\n"
    "            mediaItem?.mediaId?.startsWith(\"polaris:station:\") == true\n"
    "        // Hush's YouTube automix, prefetch and queue hydration do not apply to live radio.\n"
    "        if (app.hush.music.polaris.PolarisRadioNetwork.radioActive) {\n"
    "            currentMediaMetadata.value = player.currentMetadata\n"
    "            return\n"
    "        }\n",
)
# Critical difference versus Build #37: Hush wraps every HTTP open in a
# YouTube resolving/caching chain. Radio must bypass it all, including HLS child
# playlist and segment fetches, while streaming retains exactly the old chain.
once(
    """        return ResolvingDataSource.Factory(routingFactory) { dataSpec ->
            resolvePlaybackDataSpec(dataSpec = dataSpec)
        }
    }

    private fun createResolvedUpstreamDataSourceFactory()""",
    """        val radioHttpFactory =
            DefaultDataSource.Factory(
                this,
                androidx.media3.datasource.DefaultHttpDataSource.Factory()
                    .setUserAgent("Polaris-Unbound/0.58")
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(10_000)
                    .setReadTimeoutMs(15_000),
            )
        val splitFactory =
            DataSource.Factory {
                app.hush.music.polaris.PolarisRadioRoutingDataSource(
                    songFactory = routingFactory,
                    radioFactory = radioHttpFactory,
                )
            }
        return ResolvingDataSource.Factory(splitFactory) { dataSpec ->
            if (app.hush.music.polaris.PolarisRadioNetwork.isRadioRequest(dataSpec)) {
                dataSpec
            } else {
                resolvePlaybackDataSpec(dataSpec = dataSpec)
            }
        }
    }

    private fun createResolvedUpstreamDataSourceFactory()""",
)
# Read by Media3's loading thread, not by Hush's music metadata database.
# Keep Stream source unchanged; only radio handling is ported.
p.write_text(s)
print("Radio direct-data-source route installed; Hush streaming source path preserved")
