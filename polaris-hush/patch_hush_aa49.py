#!/usr/bin/env python3
"""Build 49: AA content-first Streaming browse, radio queue, radio-only controls.

Run after the existing Build 47 and Build 48 patches, on the pinned Hush v13.14.7.
No changes to the phone Streaming UI or to any Hush song resolver.
"""
from pathlib import Path
import sys

root = Path(sys.argv[1])
base = root / "app/src/main/kotlin/app/hush/music"
callback = base / "playback/MediaLibrarySessionCallback.kt"
service = base / "playback/MusicService.kt"

def once(file, old, new, label):
    source = file.read_text()
    count = source.count(old)
    assert count == 1, f"{label}: expected one Hush anchor, found {count}"
    file.write_text(source.replace(old, new, 1))

# Provide a one-tap navigation item in the Radio browse folder, without
# treating that item as playable radio content.
once(callback,
    '''                        "polaris:radio" -> {
                            PolarisRadioLayout(context).slots().filterNotNull().mapNotNull(RadioCatalog::find).map { station ->''',
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
    "Radio browse cross-link to Streaming")

# Replace the old icon-only Hush menu surface with real thumbnail-bearing
# playable music tiles. Android Auto owns their size and position; group-title
# and per-item grid hints are the supported metadata-driven presentation.
# A blank Hush library still retains quick access to the existing menu folders.
new_stream = '''                        "polaris:streaming" -> {
                            fun decorateForAa(
                                item: MediaItem,
                                group: String,
                                grid: Boolean,
                            ): MediaItem {
                                val extras = android.os.Bundle(item.mediaMetadata.extras ?: android.os.Bundle())
                                extras.putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                                extras.putInt(
                                    "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT",
                                    if (grid) CONTENT_STYLE_GRID_ITEM else CONTENT_STYLE_LIST_ITEM,
                                )
                                extras.putString("android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT", group)
                                return item.buildUpon().setMediaMetadata(
                                    item.mediaMetadata.buildUpon().setExtras(extras).build()
                                ).build()
                            }
                            val picks = database.quickPicks().first().take(12)
                            val recent = database.recentSongs(16).first()
                            val selectedPicks = if (picks.isNotEmpty()) picks else recent.take(12)
                            val pickIds = selectedPicks.mapTo(mutableSetOf<String>()) { it.id }
                            val firstTiles = selectedPicks.map { song ->
                                decorateForAa(
                                    song.toMediaItem(MusicService.HOME_QUICK_PICKS),
                                    "빠른 선곡", true,
                                )
                            }
                            val recentTiles = recent.filterNot { it.id in pickIds }.take(6).map { song ->
                                decorateForAa(
                                    song.toMediaItem(MusicService.RECENT),
                                    "이어서 듣기", true,
                                )
                            }
                            val shortcuts = listOf(
                                browsableMediaItem(
                                    "polaris:radio", "Radio", "라디오 채널 선택",
                                    drawableUri(R.drawable.radio), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
                                ),
                                queueMediaItem(
                                    MusicService.QUICK_PICKS, "빠른 선곡 전체", null,
                                    drawableUri(R.drawable.playlist_play), MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                                queueMediaItem(
                                    MusicService.RECENT, "재생 기록", null,
                                    drawableUri(R.drawable.history), MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                                queueMediaItem(
                                    MusicService.LIKED, "좋아요 한 곡", null,
                                    drawableUri(R.drawable.favorite), MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                                queueMediaItem(
                                    MusicService.DOWNLOADED, "다운로드 한 곡", null,
                                    drawableUri(R.drawable.download), MediaMetadata.MEDIA_TYPE_PLAYLIST,
                                ),
                                browsableMediaItem(
                                    MusicService.PLAYLIST, "보관함 · 플레이리스트", null,
                                    drawableUri(R.drawable.playlist_play),
                                    MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
                                ),
                            ).map { decorateForAa(it, "탐색", false) }
                            return@future LibraryResult.ofItemList(
                                (firstTiles + recentTiles + shortcuts).paged(page, pageSize),
                                params,
                            )
'''
once(callback,
    '                        "polaris:streaming" -> {\n',
    new_stream,
    "Streaming thumbnail home instead of Hush icon-only root")

# Hush's original setCustomLayout creates like / repeat / shuffle / add
# buttons. In Radio, only play/pause and previous/next should remain.
once(service,
    '''            val customLayout =
                listOf(''',
    '''            val customLayout =
                if (player.currentMediaItem?.mediaId?.startsWith("polaris:station:") == true) {
                    // Only the mode switch remains; no Like/Repeat/Shuffle/Add in Radio.
                    listOf(
                        CommandButton.Builder()
                            .setDisplayName("Streaming")
                            .setIconResId(R.drawable.music_note)
                            .setSessionCommand(app.hush.music.polaris.PolarisAAModeCommand.command)
                            .build()
                    )
                } else listOf(
                    CommandButton.Builder()
                        .setDisplayName("Radio")
                        .setIconResId(R.drawable.radio)
                        .setSessionCommand(app.hush.music.polaris.PolarisAAModeCommand.command)
                        .build(),''',
    "hide music-only custom actions during live radio")

# A Media3 playlist is the source of the native AA queue button; attach all
# radio presets without destroying or restarting the playing station.
once(service,
    '''    private val polarisRadioRecovery by lazy {''',
    '''    private val polarisRadioQueue by lazy {
        app.hush.music.polaris.PolarisRadioQueue(this, player, scope)
    }
    private val polarisRadioRecovery by lazy {''',
    "radio queue lifecycle")

once(service,
    '''        polarisProjectionRecovery.onMediaItemTransition(mediaItem)
''',
    '''        polarisProjectionRecovery.onMediaItemTransition(mediaItem)
        polarisRadioQueue.onTransition(mediaItem)
        // Recalculate AA custom commands when Radio/Streaming changes.
        updateNotification()
''',
    "populate queue and refresh button layout on media transition")


# Publish a single source-switch command to Android Auto. It changes the
# selected playback source, not the Android Auto host's browse-page routing.
once(callback,
    '''        var toggleLike: () -> Unit = {}''',
    '''        var onPolarisModeSwitch: () -> Unit = {}
        var toggleLike: () -> Unit = {}''',
    "AA mode switch callback")
once(callback,
    '''                    .add(MediaSessionConstants.CommandToggleLike)''',
    '''                    .add(app.hush.music.polaris.PolarisAAModeCommand.command)
                    .add(MediaSessionConstants.CommandToggleLike)''',
    "AA switch command permissions")
once(callback,
    '''            when (customCommand.customAction) {
                MediaSessionConstants.ACTION_TOGGLE_LIKE -> {''',
    '''            when (customCommand.customAction) {
                app.hush.music.polaris.PolarisAAModeCommand.ACTION -> {
                    onPolarisModeSwitch()
                }
                MediaSessionConstants.ACTION_TOGGLE_LIKE -> {''',
    "AA mode switch action")
once(callback,
    '''        internal suspend fun resolveVoiceMediaItems(''',
    '''        internal suspend fun firstPolarisStreamingItem(): MediaItem? =
            database.quickPicks().first().firstOrNull()
                ?.toMediaItem(MusicService.HOME_QUICK_PICKS)
                ?: database.recentSongs(16).first().firstOrNull()
                    ?.toMediaItem(MusicService.RECENT)

        internal suspend fun resolveVoiceMediaItems(''',
    "Hush cached streaming recommendation for first mode switch")

# A mode switch must have an actual playback action. Remember the previously
# selected music item and radio station rather than resetting the user to 89.1.
once(service,
    '''            toggleLike = ::toggleLike''',
    '''            onPolarisModeSwitch = ::switchPolarisMode
            toggleLike = ::toggleLike''',
    "wire AA switch to MusicService")

mode_methods = """
    private var polarisLastStreamingItem: MediaItem? = null
    private var polarisLastRadioId: String? = null

    private fun switchPolarisMode() {
        val currentId = player.currentMediaItem?.mediaId.orEmpty()
        if (currentId.startsWith("polaris:station:")) {
            scope.launch {
                val saved = polarisLastStreamingItem
                    ?: withContext(Dispatchers.IO) {
                        mediaLibrarySessionCallback.firstPolarisStreamingItem()
                    }
                if (saved == null) {
                    app.hush.music.polaris.PolarisRadioDiagnostics.record(
                        this@MusicService, "AA Streaming switch: no songs available"
                    )
                    return@launch
                }
                playQueue(ListQueue(items = listOf(saved)))
            }
        } else {
            val radioId = polarisLastRadioId ?: "kr1"
            scope.launch {
                try {
                    val url = withContext(Dispatchers.IO) {
                        app.hush.music.polaris.PolarisRadioPlayback.resolveUrl(radioId)
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
once(service,
    '''    private fun createMediaSourceFactory() =''',
    mode_methods + '''    private fun createMediaSourceFactory() =''',
    "remember actual radio and streaming playback source")

once(service,
    '''        polarisRadioQueue.onTransition(mediaItem)
        // Recalculate AA custom commands when Radio/Streaming changes.''',
    '''        polarisRadioQueue.onTransition(mediaItem)
        val changedId = mediaItem?.mediaId
        if (changedId?.startsWith("polaris:station:") == true) {
            polarisLastRadioId = changedId.removePrefix("polaris:station:")
        } else if (mediaItem != null && mediaItem.mediaMetadata.isPlayable == true) {
            polarisLastStreamingItem = mediaItem
        }
        // Recalculate AA custom commands when Radio/Streaming changes.''',
    "capture source for reciprocal AA mode switch")

print("Build 49: AA thumbnail Streaming home + radio queue + reciprocal browse links + mode-switch actions")
