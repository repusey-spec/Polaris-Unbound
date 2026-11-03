package app.hush.music.polaris

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.*
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import app.hush.music.R
import kotlinx.coroutines.*

abstract class PolarisCarScreen(context: CarContext, protected val catalog: PolarisCarCatalog) : Screen(context) {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var watch: Job? = null
            override fun onStart(owner: LifecycleOwner) {
                watch = scope.launch { catalog.changes.collect { invalidate() } }
            }
            override fun onStop(owner: LifecycleOwner) { watch?.cancel() }
            override fun onDestroy(owner: LifecycleOwner) { scope.cancel() }
        })
    }
    protected fun icon(resource: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, resource)).build()
    protected fun connectionTemplate(): Template? {
        if (catalog.connected) return null
        return MessageTemplate.Builder(catalog.connectionError ?: "Polaris 재생 서비스 연결 중…")
            .setTitle("Polaris").setHeaderAction(Action.APP_ICON)
            .setLoading(catalog.connectionError == null).build()
    }
    protected fun playbackAction() = Action.Builder().setTitle("재생화면")
        .setIcon(icon(R.drawable.polaris_small_icon)).setOnClickListener { showPlayback() }.build()
    protected fun searchAction() = Action.Builder().setIcon(icon(R.drawable.search))
        .setOnClickListener { screenManager.push(PolarisCarSearchScreen(carContext, catalog)) }.build()
    protected fun showPlayback() {
        // Repeated source changes must not accumulate a six-screen stack.
        screenManager.popToRoot()
        if (screenManager.top !is PolarisCarPlaybackScreen) {
            screenManager.push(PolarisCarPlaybackScreen(carContext, catalog))
        }
    }
    protected fun rowFor(item: MediaItem, onSelect: (MediaItem) -> Unit): Row {
        val metadata = item.mediaMetadata
        val folder = metadata.isBrowsable == true
        return Row.Builder().setTitle(metadata.title?.toString()?.takeIf { it.isNotBlank() } ?: "제목 없음")
            .setBrowsable(folder)
            .apply {
                val detail = metadata.subtitle?.toString()?.takeIf { it.isNotBlank() }
                    ?: metadata.artist?.toString()?.takeIf { it.isNotBlank() }
                if (detail != null) addText(detail)
                catalog.icon(item)?.let { setImage(it) }
                setOnClickListener { onSelect(item) }
            }.build()
    }
    protected fun select(item: MediaItem) {
        if (item.mediaMetadata.isBrowsable == true) {
            screenManager.push(PolarisCarBrowseScreen(carContext, catalog, item.mediaId,
                item.mediaMetadata.title?.toString() ?: "보관함"))
        } else if (item.mediaMetadata.isPlayable == true) {
            scope.launch {
                try { catalog.play(item); showPlayback() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { PolarisErrorReport.failure("car06-select", error, item) }
            }
        }
    }
    protected fun rowLimit(): Int = runCatching {
        carContext.getCarService(ConstraintManager::class.java)
            .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    }.getOrDefault(6).coerceIn(2, 50)
}

class PolarisCarHomeScreen(context: CarContext, catalog: PolarisCarCatalog) : PolarisCarScreen(context, catalog) {
    override fun onGetTemplate(): Template {
        connectionTemplate()?.let { return it }
        val items = ItemList.Builder()
            .addItem(Row.Builder().setTitle("Radio").setImage(icon(R.drawable.radio)).setBrowsable(true)
                .setOnClickListener { screenManager.push(PolarisCarBrowseScreen(carContext, catalog,
                    PolarisCarNavigation.RADIO, "라디오 채널")) }.build())
            .addItem(Row.Builder().setTitle("Streaming").setImage(icon(R.drawable.music_note)).setBrowsable(true)
                .setOnClickListener { screenManager.push(PolarisCarBrowseScreen(carContext, catalog,
                    PolarisCarNavigation.STREAMING, "스트리밍 보관함")) }.build())
            .addItem(Row.Builder().setTitle("음악검색").setImage(icon(R.drawable.search)).setBrowsable(true)
                .setOnClickListener { screenManager.push(PolarisCarSearchScreen(carContext, catalog)) }.build())
        return ListTemplate.Builder().setHeader(Header.Builder().setTitle("Polaris")
            .setStartHeaderAction(Action.APP_ICON).addEndHeaderAction(playbackAction()).build())
            .setSingleList(items.build()).build()
    }
}

class PolarisCarPlaybackScreen(context: CarContext, catalog: PolarisCarCatalog) : PolarisCarScreen(context, catalog) {
    /** Host callback: browsing alone never invokes catalog.play or a Player method. */
    fun openOpposite() {
        val target = PolarisCarNavigation.opposite(catalog.currentId)
        PolarisErrorReport.record("car06 opposite-open source=" +
            (if (PolarisCarNavigation.isRadio(catalog.currentId)) "radio" else "streaming") + " target=" + target)
        screenManager.push(PolarisCarBrowseScreen(carContext, catalog, target, PolarisCarNavigation.title(target)))
    }
    override fun onGetTemplate(): Template {
        connectionTemplate()?.let { return it }
        val mode = if (PolarisCarNavigation.isRadio(catalog.currentId)) "Radio" else "Streaming"
        return MediaPlaybackTemplate.Builder().setHeader(Header.Builder().setTitle(mode)
            .setStartHeaderAction(if (screenManager.stackSize > 1) Action.BACK else Action.APP_ICON)
            .addEndHeaderAction(Action.Builder().setTitle("목록").setIcon(icon(R.drawable.playlist_play))
                .setOnClickListener { openOpposite() }.build()).build()).build()
    }
}

class PolarisCarBrowseScreen(context: CarContext, catalog: PolarisCarCatalog,
    val parent: String, private val title: String) : PolarisCarScreen(context, catalog) {
    private var page = 0
    private var items: List<MediaItem>? = null
    private var loading = false
    private var error: String? = null
    private var pageSize = 0

    private fun load() {
        if (loading || items != null || !catalog.connected) return
        loading = true
        pageSize = (rowLimit() - 2).coerceAtLeast(1) // reserve previous/next rows
        scope.launch {
            yield()
            try { items = catalog.children(parent, page, pageSize); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = "목록을 불러오지 못했습니다"
                items = emptyList()
                PolarisErrorReport.failure("car06-browse parent=$parent", failure)
            } finally { loading = false; invalidate() }
            catalog.loadIcons(items.orEmpty())
        }
    }
    override fun onGetTemplate(): Template {
        connectionTemplate()?.let { return it }
        load()
        val header = Header.Builder().setTitle(if (page == 0) title else "$title · ${page + 1}")
            .setStartHeaderAction(Action.BACK).addEndHeaderAction(playbackAction())
        if (parent == PolarisCarNavigation.STREAMING) header.addEndHeaderAction(searchAction())
        val template = ListTemplate.Builder().setHeader(header.build())
        if (loading || items == null) return template.setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage(error ?: "항목이 없습니다")
        if (page > 0) {
            list.addItem(Row.Builder().setTitle("이전 목록").setOnClickListener {
                page--; items = null; invalidate()
            }.build())
        }
        items.orEmpty().forEach { item -> list.addItem(rowFor(item, ::select)) }
        if (error != null) {
            list.addItem(Row.Builder().setTitle("다시 불러오기").setOnClickListener {
                items = null; error = null; invalidate()
            }.build())
        } else if (items.orEmpty().size == pageSize) {
            list.addItem(Row.Builder().setTitle("다음 목록").setOnClickListener {
                page++; items = null; invalidate()
            }.build())
        }
        return template.setSingleList(list.build()).build()
    }
}

class PolarisCarSearchScreen(context: CarContext, catalog: PolarisCarCatalog) : PolarisCarScreen(context, catalog) {
    private var request: Job? = null
    private var items = emptyList<MediaItem>()
    private var loading = false
    private var message = "노래나 가수를 검색해 주세요"
    private var query = ""
    private val callback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) { search(searchText, 500L) }
        override fun onSearchSubmitted(searchText: String) { search(searchText, 0L) }
    }
    private fun search(text: String, waitMs: Long) {
        query = text.trim()
        request?.cancel()
        if (query.isBlank()) {
            items = emptyList(); loading = false; message = "노래나 가수를 검색해 주세요"; invalidate(); return
        }
        val selectedQuery = query
        loading = true
        invalidate()
        request = scope.launch {
            delay(waitMs)
            try {
                val found = catalog.search(selectedQuery, rowLimit())
                if (query != selectedQuery) return@launch
                items = found
                message = "검색 결과가 없습니다"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                items = emptyList(); message = "검색하지 못했습니다. 다시 시도해 주세요"
                PolarisErrorReport.failure("car06-search", error)
            } finally {
                if (query == selectedQuery) { loading = false; invalidate() }
            }
            catalog.loadIcons(items)
        }
    }
    override fun onGetTemplate(): Template {
        connectionTemplate()?.let { return it }
        val builder = SearchTemplate.Builder(callback).setHeaderAction(Action.BACK)
            .setSearchHint("YouTube Music · 노래 또는 가수").setShowKeyboardByDefault(true)
            .setActionStrip(ActionStrip.Builder().addAction(playbackAction()).build())
        if (loading) return builder.setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage(message)
        items.forEach { list.addItem(rowFor(it, ::select)) }
        return builder.setItemList(list.build()).build()
    }
}
