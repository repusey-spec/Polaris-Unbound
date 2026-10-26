#!/usr/bin/env python3
"""Build 41: AA projection lifecycle and live-radio recovery only.

Every Hush upstream anchor must match exactly once. Streaming playback and AA
safety restrictions are not changed.
"""
from pathlib import Path
import sys
root = Path(sys.argv[1])
service = root / "app/src/main/kotlin/app/hush/music/playback/MusicService.kt"
callback = root / "app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt"

def once(s, old, new, name):
    n = s.count(old)
    assert n == 1, f"{name} Hush anchor count {n}, expected one"
    return s.replace(old, new, 1)

s = service.read_text()
s = once(s,
    "    private val polarisRadioRecovery by lazy {\n",
    "    private val polarisProjectionRecovery by lazy {\n"
    "        app.hush.music.polaris.PolarisProjectionRecovery(this, player, scope)\n"
    "    }\n"
    "    private val polarisRadioRecovery by lazy {\n", "companion")

s = once(s,
    "        startUrlCacheRefreshJob()\n",
    "        startUrlCacheRefreshJob()\n"
    "        polarisProjectionRecovery.start()\n", "observer init")

s = once(s,
    "        polarisRadioRecovery.onItemTransition(mediaItem)\n",
    "        polarisRadioRecovery.onItemTransition(mediaItem)\n"
    "        polarisProjectionRecovery.onMediaItemTransition(mediaItem)\n", "onMediaItemTransition")

s = once(s,
    "        polarisRadioRecovery.onPlaybackStateChanged(playbackState)\n",
    "        polarisRadioRecovery.onPlaybackStateChanged(playbackState)\n"
    "        polarisProjectionRecovery.onPlayerState(playbackState)\n", "state logging")

s = once(s,
    "        polarisRadioRecovery.onIsPlayingChanged(isPlaying)\n",
    "        polarisRadioRecovery.onIsPlayingChanged(isPlaying)\n"
    "        polarisProjectionRecovery.onRadioPlaying(isPlaying)\n", "audible radio")

s = once(s,
    "        if (polarisRadioRecovery.onPlayerError()) return\n",
    "        polarisProjectionRecovery.onPlayerError(error.errorCode)\n"
    "        if (polarisRadioRecovery.onPlayerError()) return\n", "player error")

s = once(s,
    "        if (this::player.isInitialized) polarisRadioRecovery.dispose()\n",
    "        if (this::player.isInitialized) {\n"
    "            polarisProjectionRecovery.dispose()\n"
    "            polarisRadioRecovery.dispose()\n"
    "        }\n", "observer dispose")

s = once(s,
    "        lastAudioOutputDeviceSignature = outputSignature\n"
    "        audioOutputResolver.refresh()\n",
    "        lastAudioOutputDeviceSignature = outputSignature\n"
    "        polarisProjectionRecovery.onAudioRouteChanged()\n"
    "        audioOutputResolver.refresh()\n", "audio output trace")

s = once(s,
    "        rebindAudioEffectsAfterRouteChange()\n\n"
    "        if (!shouldRebuildPlaybackForAudioRouteChange()) return\n",
    "        rebindAudioEffectsAfterRouteChange()\n\n"
    "        // Saved-position seeking is valid for songs, never live radio.\n"
    "        // The projection observer rebuilds the source on AA reconnect.\n"
    "        if (player.currentMediaItem?.mediaId?.startsWith(\"polaris:station:\") == true) return\n"
    "        if (!shouldRebuildPlaybackForAudioRouteChange()) return\n", "avoid old radio position")

s = once(s,
    "        return player.mediaItemCount > 0 &&\n"
    "            player.currentMediaItem != null &&\n"
    "            state != Player.STATE_IDLE &&\n"
    "            state != Player.STATE_ENDED\n",
    "        val radioRecovering = polarisRadioRecovery.isRecovering &&\n"
    "            player.currentMediaItem?.mediaId?.startsWith(\"polaris:station:\") == true\n"
    "        return player.mediaItemCount > 0 &&\n"
    "            player.currentMediaItem != null &&\n"
    "            (radioRecovering || (state != Player.STATE_IDLE && state != Player.STATE_ENDED))\n",
    "foreground session on radio error")
service.write_text(s)

c = callback.read_text()
c = once(c,
    "        override fun onConnect(\n"
    "            session: MediaSession,\n"
    "            controller: MediaSession.ControllerInfo,\n"
    "        ): MediaSession.ConnectionResult {\n"
    "            val connectionResult = super.onConnect(session, controller)\n",
    "        override fun onConnect(\n"
    "            session: MediaSession,\n"
    "            controller: MediaSession.ControllerInfo,\n"
    "        ): MediaSession.ConnectionResult {\n"
    "            app.hush.music.polaris.PolarisRadioDiagnostics.record(context, \"MediaSession connect package=\" + controller.packageName)\n"
    "            val connectionResult = super.onConnect(session, controller)\n",
    "session controller log")

c = once(c,
    "        override fun onGetChildren(\n"
    "            session: MediaLibrarySession,\n"
    "            browser: MediaSession.ControllerInfo,\n"
    "            parentId: String,\n"
    "            page: Int,\n"
    "            pageSize: Int,\n"
    "            params: MediaLibraryService.LibraryParams?,\n"
    "        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =\n"
    "            scope.future(Dispatchers.IO) {\n",
    "        override fun onGetChildren(\n"
    "            session: MediaLibrarySession,\n"
    "            browser: MediaSession.ControllerInfo,\n"
    "            parentId: String,\n"
    "            page: Int,\n"
    "            pageSize: Int,\n"
    "            params: MediaLibraryService.LibraryParams?,\n"
    "        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =\n"
    "            scope.future(Dispatchers.IO) {\n"
    "                if (parentId == MusicService.ROOT || parentId == \"polaris:radio\" || parentId == \"polaris:streaming\") {\n"
    "                    app.hush.music.polaris.PolarisRadioDiagnostics.record(context, \"AA browse parent=\" + parentId + \" page=\" + page)\n"
    "                }\n",
    "AA browse event log")

c = once(c,
    "            return MediaSession.MediaItemsWithStartPosition(\n"
    "                result.items,\n"
    "                result.startIndex,\n"
    "                result.startPositionMs,\n"
    "            )\n",
    "            // A Media3 resumption request must not reopen persisted live radio at\n"
    "            // yesterday's stream token or previous buffer position.\n"
    "            val resumedId = result.items.getOrNull(result.startIndex.coerceAtLeast(0))\n"
    "                ?.mediaId?.takeIf { it.startsWith(\"polaris:station:\") }\n"
    "                ?.removePrefix(\"polaris:station:\")\n"
    "            if (isForPlayback && resumedId != null && app.hush.music.polaris.RadioCatalog.find(resumedId) != null) {\n"
    "                try {\n"
    "                    val url = withContext(Dispatchers.IO) { app.hush.music.polaris.PolarisRadioPlayback.resolveUrl(resumedId) }\n"
    "                    app.hush.music.polaris.PolarisRadioDiagnostics.record(context, \"AA playback resumption refreshed radio=\" + resumedId)\n"
    "                    return MediaSession.MediaItemsWithStartPosition(\n"
    "                        listOf(app.hush.music.polaris.PolarisRadioPlayback.mediaItem(resumedId, url)), 0, 0L\n"
    "                    )\n"
    "                } catch (e: Exception) {\n"
    "                    app.hush.music.polaris.PolarisRadioDiagnostics.record(context, \"AA radio resumption resolve failed=\" + e.javaClass.simpleName)\n"
    "                }\n"
    "            }\n"
    "            return MediaSession.MediaItemsWithStartPosition(\n"
    "                result.items,\n"
    "                result.startIndex,\n"
    "                result.startPositionMs,\n"
    "            )\n",
    "AA resumption live resolver")
callback.write_text(c)

gradle = root / "app/build.gradle.kts"
g = gradle.read_text()
g = once(g,
    "    implementation(libs.guava)\n",
    "    implementation(libs.guava)\n"
    "    implementation(\"androidx.car.app:app:1.7.0\")\n",
    "AA CarConnection library")
gradle.write_text(g)
print("Build 41: live radio projection reset/restore, session continuity, and AA diagnostics patched")
