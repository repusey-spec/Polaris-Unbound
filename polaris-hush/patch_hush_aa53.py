#!/usr/bin/env python3
"""Build 53: integrate the Work playlist fixes and outstanding AA radio UI fixes.

This overlay runs after Build 52 source patches. It deliberately removes
Build 49's big cross-link rows and never changes Media3's timeline from within
onMediaItemTransition; queues are created before setMediaItems.
"""
from pathlib import Path
import sys
root=Path(sys.argv[1])
base=root/"app/src/main/kotlin/app/hush/music"
callback=base/"playback/MediaLibrarySessionCallback.kt"
service=base/"playback/MusicService.kt"
radio=base/"polaris/PolarisRadioScreen.kt"

def once(path,old,new,name):
    s=path.read_text()
    n=s.count(old)
    assert n==1, f"{name}: anchor count {n}"
    path.write_text(s.replace(old,new,1))

# AA native "목록" reads the actual player timeline. Return the full queue
# through MediaSession.onSetMediaItems (IO context), never modify playback from
# an onMediaItemTransition callback as Build49 did.
once(callback,
    '''                val radioStation = RadioCatalog.stations.firstOrNull {
                    firstItem.mediaId == "polaris:station:" + it.id
                }
                if (radioStation != null) {
                    val url = PolarisRadioPlayback.resolveUrl(radioStation.id)
                    val item = PolarisRadioPlayback.mediaItem(radioStation.id, url)
                    return@future MediaSession.MediaItemsWithStartPosition(listOf(item), 0, 0L)
                }
''',
    '''                val selectedRadioId = mediaItems.getOrNull(
                    startIndex.coerceIn(mediaItems.indices)
                )?.mediaId?.removePrefix("polaris:station:")
                    ?.takeIf { RadioCatalog.find(it) != null }
                if (selectedRadioId != null) {
                    val queue = app.hush.music.polaris.PolarisRadioQueueBuilder
                        .build(context, selectedRadioId)
                    return@future MediaSession.MediaItemsWithStartPosition(
                        queue.items, queue.index, 0L,
                    )
                }
''',
    "build radio AA queue before starting playback")

# AA Radio folder should start directly with stations, not a giant Streaming
# navigational tile; top Radio|Streaming tabs are already built-in.
once(callback,
    '''                        "polaris:radio" -> {
                            listOf(
                                browsableMediaItem(
                                    "polaris:streaming",
                                    "Streaming",
                                    "음악 추천과 보관함",
                                    drawableUri(R.drawable.music_note),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                )
                            ) + PolarisRadioLayout(context).slots().filterNotNull().mapNotNull(RadioCatalog::find).map { station ->''',
    '''                        "polaris:radio" -> {
                            PolarisRadioLayout(context).slots().filterNotNull().mapNotNull(RadioCatalog::find).map { station ->''',
    "remove Radio folder giant Streaming shortcut")

once(callback,
    '''                            val shortcuts = listOf(
                                browsableMediaItem(
                                    "polaris:radio", "Radio", "라디오 채널 선택",
                                    drawableUri(R.drawable.radio), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                ),
                                queueMediaItem(''',
    '''                            val shortcuts = listOf(
                                queueMediaItem(''',
    "remove Streaming folder giant Radio shortcut")

# The phone's radio tile and handset next/previous use the exact same queue
# as AA. No reentrant modification of an already-playing timeline.
once(radio,
    '''                    val item = PolarisRadioPlayback.mediaItem(id, url)
                    val player = connection?.player''',
    '''                    val queue = try {
                        withContext(Dispatchers.IO) {
                            PolarisRadioQueueBuilder.build(context, id)
                        }
                    } catch (e: Exception) {
                        radioError = "라디오 목록 생성 실패: " + e.javaClass.simpleName
                        return@launch
                    }
                    if (!PolarisPlaybackRequestGate.isCurrent(polarisRequest)) return@launch
                    val player = connection?.player''',
    "radio grid queue constructed before player operation")
once(radio,
    '''                        player.setMediaItem(item)
                        player.prepare()
                        player.play()''',
    '''                        player.setMediaItems(queue.items, queue.index, 0L)
                        player.prepare()
                        player.play()''',
    "radio grid full channel queue")

# Re-use tuner skip's fresh URL and full queue even when steering-wheel media
# buttons / Android Auto skip arrive. Preserve original request serial guards.
once(service,
    '''                player.setMediaItem(app.hush.music.polaris.PolarisRadioPlayback.mediaItem(next, url))
                player.prepare()
                player.play()''',
    '''                val nextQueue = withContext(Dispatchers.IO) {
                    app.hush.music.polaris.PolarisRadioQueueBuilder.build(this@MusicService, next)
                }
                if (serial != polarisSkipSerial ||
                    player.currentMediaItem?.mediaId != "polaris:station:" + currentId) {
                    return@launch
                }
                player.setMediaItems(nextQueue.items, nextQueue.index, 0L)
                player.prepare()
                player.play()''',
    "tuner skip retains native AA queue")

# The Build52 switch must restore the full music queue, not only one item.
# On switching *into* Radio, the same radio queue is used for the AA Queue.
once(service,
    '''                    val url = withContext(Dispatchers.IO) {
                        app.hush.music.polaris.PolarisRadioPlayback.resolveUrl(radioId)
                    }
                    if (!app.hush.music.polaris.PolarisPlaybackRequestGate.isCurrent(request))''',
    '''                    val radioQueue = withContext(Dispatchers.IO) {
                        app.hush.music.polaris.PolarisRadioQueueBuilder.build(
                            this@MusicService, radioId
                        )
                    }
                    if (!app.hush.music.polaris.PolarisPlaybackRequestGate.isCurrent(request))''',
    "switch into radio prepares validated queue")
once(service,
    '''                    player.setMediaItem(
                        app.hush.music.polaris.PolarisRadioPlayback.mediaItem(radioId, url)
                    )
                    player.prepare()
                    player.play()''',
    '''                    player.setMediaItems(radioQueue.items, radioQueue.index, 0L)
                    player.prepare()
                    player.play()''',
    "mode switch plays all radio presets")

# Voice-radio commands also pass through the safe resolved queue helper, not
# the old single-item direct setMediaItem path.
once(service,
    '''                val radioItem = try {
                    withContext(Dispatchers.IO) {
                        app.hush.music.polaris.PolarisRadioPlayback.mediaItem(
                            radioId, app.hush.music.polaris.PolarisRadioPlayback.resolveUrl(radioId)
                        )
                    }
                } catch (error: Exception) {''',
    '''                val radioQueue = try {
                    withContext(Dispatchers.IO) {
                        app.hush.music.polaris.PolarisRadioQueueBuilder.build(
                            this@MusicService, radioId
                        )
                    }
                } catch (error: Exception) {''',
    "voice radio obtains safe queue")
once(service,
    '''                player.setMediaItem(radioItem)
                player.prepare()
                player.play()''',
    '''                player.setMediaItems(radioQueue.items, radioQueue.index, 0L)
                player.prepare()
                player.play()''',
    "voice radio exposes queue")

# Official Media3 media-button preferences are now the primary way to publish
# custom commands; the host decides actual location (not next to top Queue).
once(service,
    '''            mediaSession.setCustomLayout(customLayout)
''',
    '''            mediaSession.setCustomLayout(customLayout)
            mediaSession.setMediaButtonPreferences(customLayout)
''',
    "publish switch control as AA media button preference")


# Avoid duplicate slow URL resolutions; queue builder already resolves selected.
once(radio,
    '''                    val url = try {
                        withContext(Dispatchers.IO) { PolarisRadioPlayback.resolveUrl(id) }
                    } catch (e: Exception) {
                        radioError = "라디오 주소 확인 실패: ${e.message}"
                        return@launch
                    }
''',
    "",
    "remove duplicate grid URL lookup")
once(service,
    '''                val url = withContext(Dispatchers.IO) {
                    app.hush.music.polaris.PolarisRadioPlayback.resolveUrl(next)
                }
                if (serial != polarisSkipSerial ||
                    player.currentMediaItem?.mediaId != "polaris:station:" + currentId) {
                    return@launch
                }
''',
    "",
    "remove duplicate tuner URL lookup")

print("Build 53: safe AA 13-station queue + reciprocal playback mode control + no giant browse shortcut")
