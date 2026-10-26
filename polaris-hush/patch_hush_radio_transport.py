#!/usr/bin/env python3
"""Polaris 0.58 preset next/previous (wraparound) for phone and Android Auto."""
from pathlib import Path
import sys
root = Path(sys.argv[1])
service = root / "app/src/main/kotlin/app/hush/music/playback/MusicService.kt"
callback = root / "app/src/main/kotlin/app/hush/music/playback/MediaLibrarySessionCallback.kt"
connection = root / "app/src/main/kotlin/app/hush/music/playback/PlayerConnection.kt"

def replace_one(src, old, new, where):
    count=src.count(old)
    assert count == 1, f"{where}: anchor count {count}: {old[:90]!r}"
    return src.replace(old,new,1)

s=service.read_text()
s=replace_one(s, """        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike""",
"""        mediaLibrarySessionCallback.apply {
            onPolarisRadioSkip = { direction -> skipPolarisRadio(direction) }
            toggleLike = ::toggleLike""", "MusicService session")
method = """
    // 0.58's RadioPresets.orderedIds / skipRadio behavior ported to the
    // single Hush MediaLibrarySession. The logical index follows the user's
    // movable 4x4 physical slots, not the hardcoded RadioCatalog list.
    private var polarisSkipSerial = 0L
    private var pendingPolarisSkipId: String? = null

    fun skipPolarisRadio(delta: Int): Boolean {
        val currentId = player.currentMediaItem?.mediaId
            ?.takeIf { it.startsWith("polaris:station:") }
            ?.removePrefix("polaris:station:") ?: return false
        val ordered = app.hush.music.polaris.PolarisRadioLayout(this)
            .slots().filterNotNull()
        val sourceId = pendingPolarisSkipId ?: currentId
        val index = ordered.indexOf(sourceId)
        if (index < 0 || ordered.isEmpty()) return false
        val next = ordered[(index + (if (delta >= 0) 1 else -1) + ordered.size) % ordered.size]
        pendingPolarisSkipId = next
        val serial = ++polarisSkipSerial
        scope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    app.hush.music.polaris.PolarisRadioPlayback.resolveUrl(next)
                }
                if (serial != polarisSkipSerial ||
                    player.currentMediaItem?.mediaId != "polaris:station:" + currentId) {
                    return@launch
                }
                app.hush.music.polaris.PolarisRadioNetwork.radioActive = true
                player.setMediaItem(app.hush.music.polaris.PolarisRadioPlayback.mediaItem(next, url))
                player.prepare()
                player.play()
            } catch (error: Exception) {
                Timber.tag(TAG).w(error, "Polaris radio skip failed: %s", next)
            } finally {
                if (serial == polarisSkipSerial) pendingPolarisSkipId = null
            }
        }
        return true
    }

"""
anchor="    private fun createMediaSourceFactory() ="
s=replace_one(s,anchor,method+anchor,"MusicService tuner")
service.write_text(s)

s=callback.read_text()
s=replace_one(s,'        var toggleLike: () -> Unit = {}',
'''        var onPolarisRadioSkip: ((Int) -> Unit)? = null
        var toggleLike: () -> Unit = {}''',"AA callback field")
s=replace_one(s,'''            // Only the commands that mean "play me something" are answered''',
'''            if (session.player.currentMediaItem?.mediaId?.startsWith("polaris:station:") == true) {
                val direction = when (playerCommand) {
                    androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> 1
                    androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS,
                    androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> -1
                    else -> 0
                }
                if (direction != 0) {
                    onPolarisRadioSkip?.invoke(direction)
                    return SessionResult.RESULT_SUCCESS
                }
            }
            // Only the commands that mean "play me something" are answered''',"AA transport requests")
s=replace_one(s,'''            // A headset or steering-wheel key with nothing loaded''',
'''            if (session.player.currentMediaItem?.mediaId?.startsWith("polaris:station:") == true &&
                intent.action == Intent.ACTION_MEDIA_BUTTON) {
                val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (key?.action == KeyEvent.ACTION_DOWN) {
                    val delta = when (key.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_NEXT -> 1
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> -1
                        else -> 0
                    }
                    if (delta != 0) {
                        onPolarisRadioSkip?.invoke(delta)
                        return true
                    }
                }
            }
            // A headset or steering-wheel key with nothing loaded''',"AA hardware buttons")
callback.write_text(s)

s=connection.read_text()
s=replace_one(s,'''    fun seekToNext() {
        if (requestArtworkTurn(forward = true)) return''',
'''    fun seekToNext() {
        if (service.skipPolarisRadio(1)) return
        if (requestArtworkTurn(forward = true)) return''',"phone next")
s=replace_one(s,'''    fun seekToPrevious() {
        if (requestArtworkTurn(forward = false)) return''',
'''    fun seekToPrevious() {
        if (service.skipPolarisRadio(-1)) return
        if (requestArtworkTurn(forward = false)) return''',"phone previous")
s=replace_one(s,'''    fun skipNext() {
        val state =''',
'''    fun skipNext() {
        if (service.skipPolarisRadio(1)) return
        val state =''',"phone direct next")
s=replace_one(s,'''    fun skipPrevious() {
        val state =''',
'''    fun skipPrevious() {
        if (service.skipPolarisRadio(-1)) return
        val state =''',"phone direct previous")
s=replace_one(s,'''    private fun updateCanSkipPreviousAndNext(timeline: Timeline? = null) {
        val t =''',
'''    private fun updateCanSkipPreviousAndNext(timeline: Timeline? = null) {
        if (player.currentMediaItem?.mediaId?.startsWith("polaris:station:") == true) {
            canSkipPrevious.value = true
            canSkipNext.value = true
            return
        }
        val t =''',"phone enabled buttons")
connection.write_text(s)
print("Radio next/previous now routes through custom tuner on phone/AA; streaming skips unchanged")
