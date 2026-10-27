#!/usr/bin/env python3
"""Restore two voice-command paths in Polaris, without modifying Hush song engines.

The original Hush MediaSession, music search, stream resolver and the Polaris
Radio station resolver remain intact. Run AFTER all radio transport/projection
patches and BEFORE compiling the staged Kotlin files.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
src = root / "app/src/main/kotlin/app/hush/music"
callback = src / "playback/MediaLibrarySessionCallback.kt"
service = src / "playback/MusicService.kt"
activity = src / "MainActivity.kt"

def patch_one(path, before, after, label):
    text = path.read_text()
    count = text.count(before)
    assert count == 1, f"{label}: expected one pinned Hush anchor, found {count}"
    path.write_text(text.replace(before, after, 1))

# Keep the original intent handler, add a privacy-safe diagnostic entry.
patch_one(
    activity,
    '        if (intent.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") {\n',
    '        if (intent.action == "android.media.action.MEDIA_PLAY_FROM_SEARCH") {\n'
    '            app.hush.music.polaris.PolarisRadioDiagnostics.record(this, "voice direct-intent received")\n',
    "voice direct intent",
)

# Direct Android intent -> MainActivity -> MusicService (Hush's original route).
original_voice = """    fun playFromVoiceSearch(query: String) {
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
unified_voice = """    fun playFromVoiceSearch(query: String) {
        val trimmed = app.hush.music.polaris.PolarisVoiceRouter.musicQuery(query)
        if (trimmed.isBlank()) return
        ensureScopesActive()
        val radioId = app.hush.music.polaris.PolarisVoiceRouter.radioIdForQuery(trimmed)
        if (radioId != null) {
            scope.launch(SilentHandler) {
                val radioItem = try {
                    withContext(Dispatchers.IO) {
                        app.hush.music.polaris.PolarisRadioPlayback.mediaItem(
                            radioId, app.hush.music.polaris.PolarisRadioPlayback.resolveUrl(radioId)
                        )
                    }
                } catch (error: Exception) {
                    app.hush.music.polaris.PolarisRadioDiagnostics.record(
                        this@MusicService, "voice direct-intent radio resolver failed id=" + radioId
                    )
                    return@launch
                }
                app.hush.music.polaris.PolarisRadioDiagnostics.record(
                    this@MusicService, "voice direct-intent routed radio id=" + radioId
                )
                app.hush.music.polaris.PolarisRadioNetwork.radioActive = true
                player.setMediaItem(radioItem)
                player.prepare()
                player.play()
            }
            return
        }
        app.hush.music.polaris.PolarisRadioDiagnostics.record(
            this, "voice direct-intent routed Hush streaming"
        )
        scope.launch(SilentHandler) {
            val mediaItems = withContext(Dispatchers.IO) {
                mediaLibrarySessionCallback.resolveVoiceMediaItems(trimmed)
            }
            app.hush.music.polaris.PolarisRadioDiagnostics.record(
                this@MusicService, "voice direct-intent music results=" + mediaItems.size
            )
            if (mediaItems.isEmpty()) return@launch
            playQueue(ListQueue(items = mediaItems))
        }
    }
"""
patch_one(service, original_voice, unified_voice, "voice MediaService route")

# The shared AA MediaLibrarySession handles both Google/AA voice searches and
# song queries; station voice searches must not be sent to the YouTube song index.
patch_one(
    callback,
    "package app.hush.music.playback\n",
    "package app.hush.music.playback\n"
    "\nimport app.hush.music.polaris.PolarisVoiceRouter\n"
    "import app.hush.music.polaris.PolarisVoiceSearchItem\n",
    "voice routing imports",
)

# Normalize the app invocation for both Android Auto search entry points.
# The original Hush code still searches artists, albums and songs untouched.
patch_one(
    callback,
    """            Futures.immediateFuture(LibraryResult.ofVoid(params)).also {
                val q = query.trim()""",
    """            Futures.immediateFuture(LibraryResult.ofVoid(params)).also {
                val q = PolarisVoiceRouter.musicQuery(query)""",
    "AA onSearch normalize Polaris invocation",
)
patch_one(
    callback,
    """            scope.future(Dispatchers.IO) {
                val q = query.trim()
                val safePage = page.coerceAtLeast(0)""",
    """            scope.future(Dispatchers.IO) {
                val q = PolarisVoiceRouter.musicQuery(query)
                val safePage = page.coerceAtLeast(0)""",
    "AA getSearchResult normalize Polaris invocation",
)

# Android Auto: advertise a station result for radio speech, not a song match.
patch_one(
    callback,
    """                                if (q.isBlank()) {
                                    0
                                } else {""",
    """                                if (PolarisVoiceRouter.radioIdForQuery(q) != null) {
                                    1
                                } else if (q.isBlank()) {
                                    0
                                } else {""",
    "AA search count",
)
patch_one(
    callback,
    """                val requested = (safePage + 1) * safePageSize
                val items = ArrayList<MediaItem>(min(requested, 200))""",
    """                val radioId = PolarisVoiceRouter.radioIdForQuery(q)
                if (radioId != null) {
                    val resultItems = if (safePage == 0) {
                        listOf(PolarisVoiceSearchItem.mediaItem(radioId))
                    } else {
                        emptyList()
                    }
                    return@future LibraryResult.ofItemList(resultItems, params)
                }
                val requested = (safePage + 1) * safePageSize
                val items = ArrayList<MediaItem>(min(requested, 200))""",
    "AA search radio result",
)

# Radio stations are now genuine searchable media items as well as browse items.
patch_one(
    callback,
    """                when {
                    mediaId == MusicService.ROOT -> {""",
    """                when {
                    mediaId.startsWith("polaris:station:") &&
                        RadioCatalog.find(mediaId.removePrefix("polaris:station:")) != null -> {
                        LibraryResult.ofItem(
                            PolarisVoiceSearchItem.mediaItem(mediaId.removePrefix("polaris:station:")),
                            null,
                        )
                    }
                    mediaId == MusicService.ROOT -> {""",
    "AA get searchable radio item",
)

# Android Auto / Media3 voice-to-play, including queries delivered directly
# by Assistant rather than onGetSearchResult.
patch_one(
    callback,
    """                val voiceQuery =
                    firstItem.requestMetadata.searchQuery
                        ?.trim()
                        .orEmpty()
                if (voiceQuery.isNotBlank()) {
                    val offlineSongs = searchOfflineSongs(voiceQuery, previewSize = 50)""",
    """                val voiceQuery = PolarisVoiceRouter.musicQuery(
                    firstItem.requestMetadata.searchQuery?.trim().orEmpty()
                )
                if (voiceQuery.isNotBlank()) {
                    val radioId = PolarisVoiceRouter.radioIdForQuery(voiceQuery)
                    if (radioId != null) {
                        app.hush.music.polaris.PolarisRadioDiagnostics.record(
                            context, "voice media-session routed radio id=" + radioId
                        )
                        val url = PolarisRadioPlayback.resolveUrl(radioId)
                        return@future MediaSession.MediaItemsWithStartPosition(
                            listOf(PolarisRadioPlayback.mediaItem(radioId, url)), 0, 0L
                        )
                    }
                    app.hush.music.polaris.PolarisRadioDiagnostics.record(
                        context, "voice media-session routed Hush streaming"
                    )
                    val offlineSongs = searchOfflineSongs(voiceQuery, previewSize = 50)""",
    "AA setMediaItems voice path",
)

# Queue-add is a distinct voice command entry point in Media3.
patch_one(
    callback,
    """                        if (query.isBlank()) {
                            listOf(item)
                        } else {
                            val resolved = resolveVoiceMediaItems(query)
                            resolved.ifEmpty { listOf(item) }
                        }""",
    """                        if (query.isBlank()) {
                            listOf(item)
                        } else {
                            val radioId = PolarisVoiceRouter.radioIdForQuery(query)
                            if (radioId != null) {
                                app.hush.music.polaris.PolarisRadioDiagnostics.record(
                                    context, "voice addMediaItems routed radio id=" + radioId
                                )
                                val url = PolarisRadioPlayback.resolveUrl(radioId)
                                listOf(PolarisRadioPlayback.mediaItem(radioId, url))
                            } else {
                                app.hush.music.polaris.PolarisRadioDiagnostics.record(
                                    context, "voice addMediaItems routed Hush streaming"
                                )
                                val resolved = resolveVoiceMediaItems(PolarisVoiceRouter.musicQuery(query))
                                resolved.ifEmpty { listOf(item) }
                            }
                        }""",
    "AA addMediaItems voice path",
)

print("Unified Polaris voice: radio alias + original Hush song search, AA search, play and direct intents")
