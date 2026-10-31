#!/usr/bin/env python3
"""Only voice-command routing: frozen Polaris v0.58 radio + original Hush v13.14.8 music.

Runs AFTER patch_v5.py. No UI, station data, playlists, branding or streaming
engine changes. Important: Google Assistant performs speech recognition;
these are the old v0.58 station-name and frequency aliases on resulting text.
"""
from pathlib import Path
import shutil
import sys

hush=Path(sys.argv[1])
repo=Path(__file__).resolve().parent
dir=hush/"app/src/main/kotlin/app/hush/music"
cb=dir/"playback/MediaLibrarySessionCallback.kt"
service=dir/"playback/MusicService.kt"
voice=dir/"polarisv5/V5Voice.kt"
assert voice.parent.is_dir()
shutil.copyfile(repo/"V5Voice.kt",voice)

def swap(s,old,new,title):
    count=s.count(old)
    assert count==1, f"{title} expected one Hush v13.14.8 anchor, got {count}"
    return s.replace(old,new,1)

# Hush's direct-intent route: MainActivity already forwards to this method.
# Keep the original Hush song search block intact; only intercept recognized
# Polaris v0.58 radio aliases, filling a 13-station queue in physical slot order.
s=service.read_text()
s=swap(s,
    "package app.hush.music.playback\n",
    "package app.hush.music.playback\n\nimport app.hush.music.polarisv5.V5Voice\n",
    "voice service import")
old="""    fun playFromVoiceSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        ensureScopesActive()
        scope.launch(SilentHandler) {
            val mediaItems =
                withContext(Dispatchers.IO) {
                    mediaLibrarySessionCallback.resolveVoiceMediaItems(trimmed)
                }
            if (mediaItems.isEmpty()) return@launch
            playQueue(ListQueue(items = mediaItems))
        }
    }
"""
new="""    fun playFromVoiceSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val radioId = V5Voice.radioIdForQuery(trimmed)
        ensureScopesActive()
        if (radioId != null) {
            scope.launch(SilentHandler) {
                val radioQueue = V5Radio.queue(this@MusicService)
                val selectedIndex = radioQueue.indexOfFirst {
                    it.mediaId == V5Radio.STATION_PREFIX + radioId
                }
                if (selectedIndex >= 0) {
                    withContext(Dispatchers.Main.immediate) {
                        player.setMediaItems(radioQueue, selectedIndex, 0L)
                        player.prepare()
                        player.play()
                    }
                }
            }
            return
        }
        scope.launch(SilentHandler) {
            val mediaItems =
                withContext(Dispatchers.IO) {
                    mediaLibrarySessionCallback.resolveVoiceMediaItems(trimmed)
                }
            if (mediaItems.isEmpty()) return@launch
            playQueue(ListQueue(items = mediaItems))
        }
    }
"""
s=swap(s,old,new,"direct Assistant intent voice route")
service.write_text(s)

# Android Auto MediaLibrarySession search, media selection and queue-add are
# distinct entry points. Register all without touching song/playlist searches.
c=cb.read_text()
c=swap(c,
    "package app.hush.music.playback\n",
    "package app.hush.music.playback\n\nimport app.hush.music.polarisv5.V5Voice\n",
    "AA voice import")
count="""                                if (q.isBlank()) {
                                    0
                                } else {"""
c=swap(c,count,'''                                if (V5Voice.radioIdForQuery(q) != null) {
                                    1
                                } else if (q.isBlank()) {
                                    0
                                } else {''',"AA radio voice search-result count")

get_result="""                val safePageSize = pageSize.coerceIn(1, 50)
                if (q.isBlank()) {"""
new_result="""                val safePageSize = pageSize.coerceIn(1, 50)
                val voiceRadioId = V5Voice.radioIdForQuery(q)
                if (voiceRadioId != null) {
                    return@future LibraryResult.ofItemList(
                        if (safePage == 0) listOf(V5Radio.item(context, voiceRadioId))
                        else emptyList(),
                        params,
                    )
                }
                if (q.isBlank()) {"""
c=swap(c,get_result,new_result,"AA getSearchResult actual playable radio result")

set_voice="""                val firstItem = mediaItems.firstOrNull() ?: return@future defaultResult
                val requestedRadioId ="""
set_new="""                val firstItem = mediaItems.firstOrNull() ?: return@future defaultResult
                val voiceRadioId =
                    V5Voice.radioIdForQuery(firstItem.requestMetadata.searchQuery)
                if (voiceRadioId != null) {
                    val voiceQueue = V5Radio.queue(context)
                    val voiceIndex = voiceQueue.indexOfFirst {
                        it.mediaId == V5Radio.STATION_PREFIX + voiceRadioId
                    }
                    if (voiceIndex >= 0) {
                        return@future MediaSession.MediaItemsWithStartPosition(
                            voiceQueue, voiceIndex, 0L,
                        )
                    }
                }
                val requestedRadioId ="""
c=swap(c,set_voice,set_new,"AA voice-to-play keeps steering radio queue")

add_voice="""                        if (query.isBlank()) {
                            listOf(item)
                        } else {
                            val resolved = resolveVoiceMediaItems(query)"""
add_new="""                        val radioId = V5Voice.radioIdForQuery(query)
                        if (radioId != null) {
                            listOf(V5Radio.item(context, radioId))
                        } else if (query.isBlank()) {
                            listOf(item)
                        } else {
                            val resolved = resolveVoiceMediaItems(query)"""
c=swap(c,add_voice,add_new,"AA addMediaItems radio voice shortcut")
cb.write_text(c)
print("Polaris v5 voice-only port complete: frozen v0.58 radio aliases, preserved Hush song voice")
