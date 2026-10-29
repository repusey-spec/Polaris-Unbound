#!/usr/bin/env python3
"""Build 51: reject late voice/radio network responses and restore music queue.

Runs after Build 50 and playlist patch. Hush's Media3 player and Radio
network routing remain unchanged.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1])
base=root/"app/src/main/kotlin/app/hush/music"
service=base/"playback/MusicService.kt"
radio=base/"polaris/PolarisRadioScreen.kt"

def once(path, old, new, desc):
    s=path.read_text()
    n=s.count(old)
    assert n==1, f"{desc}: expected 1 anchor, got {n}"
    path.write_text(s.replace(old,new,1))

# A radio tap wins against every earlier slow radio/voice resolution.
once(radio,
    '''            onPlayStation = { id ->
                scope.launch {''',
    '''            onPlayStation = { id ->
                val polarisRequest = PolarisPlaybackRequestGate.begin()
                scope.launch {''',
    "radio tap ticket")
once(radio,
    '''                    val item = PolarisRadioPlayback.mediaItem(id, url)
                    val player = connection?.player''',
    '''                    if (!PolarisPlaybackRequestGate.isCurrent(polarisRequest)) return@launch
                    val item = PolarisRadioPlayback.mediaItem(id, url)
                    val player = connection?.player''',
    "do not play obsolete radio result")

# Voice media and radio searches must also be last-request-wins.
once(service,
    '''        val radioId = app.hush.music.polaris.PolarisVoiceRouter.radioIdForQuery(trimmed)
        if (radioId != null) {''',
    '''        val polarisRequest = app.hush.music.polaris.PolarisPlaybackRequestGate.begin()
        val radioId = app.hush.music.polaris.PolarisVoiceRouter.radioIdForQuery(trimmed)
        if (radioId != null) {''',
    "voice request ticket")
once(service,
    '''                app.hush.music.polaris.PolarisRadioNetwork.radioActive = true
                player.setMediaItem(radioItem)
                player.prepare()
                player.play()''',
    '''                if (!app.hush.music.polaris.PolarisPlaybackRequestGate.isCurrent(polarisRequest)) {
                    return@launch
                }
                app.hush.music.polaris.PolarisRadioNetwork.radioActive = true
                player.setMediaItem(radioItem)
                player.prepare()
                player.play()''',
    "late radio voice response ignored")
once(service,
    '''            if (mediaItems.isEmpty()) return@launch
            playQueue(ListQueue(items = mediaItems))''',
    '''            if (mediaItems.isEmpty() ||
                !app.hush.music.polaris.PolarisPlaybackRequestGate.isCurrent(polarisRequest)) {
                return@launch
            }
            playQueue(ListQueue(items = mediaItems))''',
    "late music voice response ignored")
once(service,
    '''        polarisRadioRecovery.onPlayWhenReadyChanged(playWhenReady)''',
    '''        polarisRadioRecovery.onPlayWhenReadyChanged(playWhenReady)
        if (!playWhenReady) {
            // A pause must invalidate any outstanding slow radio/voice request.
            app.hush.music.polaris.PolarisPlaybackRequestGate.cancel()
        }''',
    "pause invalidates old network responses")

s=service.read_text()
start=s.find('    private var polarisLastStreamingItem: MediaItem? = null')
end=s.find('    private fun createMediaSourceFactory() =', start)
assert start!=-1 and end!=-1 and s.count("    private fun switchPolarisMode() {")==1
replacement="""
    private var polarisLastStreamingItem: MediaItem? = null
    private var polarisLastRadioId: String? = null
    private var polarisSavedStreamingQueue: List<MediaItem> = emptyList()
    private var polarisSavedStreamingIndex = 0
    private var polarisSavedStreamingPosition = 0L

    private fun rememberPolarisStreamingQueue() {
        if (player.currentMediaItem?.mediaId?.startsWith("polaris:station:") == true) return
        val count = player.mediaItemCount
        if (count <= 0) return
        polarisSavedStreamingQueue = (0 until count).map { player.getMediaItemAt(it) }
        polarisSavedStreamingIndex = player.currentMediaItemIndex.coerceIn(0, count - 1)
        polarisSavedStreamingPosition = player.currentPosition.coerceAtLeast(0L)
    }

    private fun switchPolarisMode() {
        val request = app.hush.music.polaris.PolarisPlaybackRequestGate.begin()
        val currentId = player.currentMediaItem?.mediaId.orEmpty()
        if (currentId.startsWith("polaris:station:")) {
            scope.launch {
                val fallback = if (polarisSavedStreamingQueue.isEmpty()) {
                    polarisLastStreamingItem ?: withContext(Dispatchers.IO) {
                        mediaLibrarySessionCallback.firstPolarisStreamingItem()
                    }
                } else null
                if (!app.hush.music.polaris.PolarisPlaybackRequestGate.isCurrent(request)) {
                    return@launch
                }
                val queue = polarisSavedStreamingQueue.ifEmpty {
                    listOfNotNull(fallback)
                }
                if (queue.isEmpty()) {
                    app.hush.music.polaris.PolarisRadioDiagnostics.record(
                        this@MusicService, "AA Streaming switch: no music in history"
                    )
                    return@launch
                }
                playQueue(
                    ListQueue(
                        items = queue,
                        startIndex = polarisSavedStreamingIndex.coerceIn(queue.indices),
                        position = polarisSavedStreamingPosition,
                    )
                )
            }
        } else {
            // Snapshot the full playlist while it still belongs to the player.
            rememberPolarisStreamingQueue()
            val radioId = polarisLastRadioId ?: "kr1"
            scope.launch {
                try {
                    val url = withContext(Dispatchers.IO) {
                        app.hush.music.polaris.PolarisRadioPlayback.resolveUrl(radioId)
                    }
                    if (!app.hush.music.polaris.PolarisPlaybackRequestGate.isCurrent(request)) {
                        return@launch
                    }
                    app.hush.music.polaris.PolarisRadioNetwork.radioActive = true
                    player.setMediaItem(
                        app.hush.music.polaris.PolarisRadioPlayback.mediaItem(radioId, url)
                    )
                    player.prepare()
                    player.play()
                } catch (error: Exception) {
                    app.hush.music.polaris.PolarisRadioDiagnostics.record(
                        this@MusicService,
                        "AA Radio switch failed=" + error.javaClass.simpleName
                    )
                }
            }
        }
    }

"""
s=s[:start]+replacement+s[end:]
service.write_text(s)

once(service,
    '''        } else if (mediaItem != null && mediaItem.mediaMetadata.isPlayable == true) {
            polarisLastStreamingItem = mediaItem
        }''',
    '''        } else if (mediaItem != null && mediaItem.mediaMetadata.isPlayable == true) {
            polarisLastStreamingItem = mediaItem
            rememberPolarisStreamingQueue()
        }''',
    "save streaming queue after media transition")
once(service,
    '''        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        val isSeekDiscontinuity =''',
    '''        super.onPositionDiscontinuity(oldPosition, newPosition, reason)
        rememberPolarisStreamingQueue()
        val isSeekDiscontinuity =''',
    "save streaming seek and next-track position")

print("Build 51: stale voice/radio responses discarded, pause invalidates requests, mode switch resumes full streaming queue")
