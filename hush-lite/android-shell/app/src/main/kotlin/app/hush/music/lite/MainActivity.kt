package app.hush.music.lite

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Temporary PHONE-ONLY UI; not a new Polaris layout. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val candidates = mutableListOf<HushLiteSongSearch.Candidate>()
    private lateinit var label: TextView
    private lateinit var playbackLabel: TextView
    private lateinit var query: EditText
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.decorView.systemUiVisibility = 0
        // Show a readable startup error even if an optional YouTube core
        // class cannot initialize on the user's Android version.
        val startupError = runCatching { LiteSession.initialize(this) }.exceptionOrNull()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 12)
        }
        label = TextView(this).apply {
            text = if (startupError != null) {
                "앱 초기화 실패: " + startupError.javaClass.simpleName +
                    " · 이 화면을 촬영해 전달해 주세요."
            } else {
                "Hush Lite search + early playback. Audio not device verified."
            }
        }
        playbackLabel = TextView(this).apply { text = "재생 상태: 대기" }
        query = EditText(this).apply {
            hint = "가수와 곡 제목"
            setSingleLine()
        }
        val login = Button(this).apply { text = "YouTube Music 로그인 (임시 세션)" }
        val search = Button(this).apply { text = "Hush 검색" }
        list = ListView(this)
        root.addView(login)
        root.addView(query)
        root.addView(search)
        root.addView(label)
        root.addView(playbackLabel)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (startupError != null) {
            login.isEnabled = false
            search.isEnabled = false
            return
        }
        scope.launch {
            LiteDiagnostics.status.collect { message ->
                if (message.isNotBlank()) playbackLabel.text = message
            }
        }
        login.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
        search.setOnClickListener {
            val q = query.text.toString().trim()
            if (q.isNotEmpty()) searchSongs(q)
        }
        list.setOnItemClickListener { _, _, position, _ ->
            candidates.getOrNull(position)?.let { selected ->
                startService(Intent(this, LiteMediaService::class.java).apply {
                    action = LiteMediaService.ACTION_PLAY_ID
                    putExtra(LiteMediaService.EXTRA_VIDEO_ID, selected.videoId)
                    putExtra(LiteMediaService.EXTRA_TITLE, selected.title)
                    putExtra(LiteMediaService.EXTRA_ARTIST, selected.artistNames.joinToString(", "))
                })
                LiteDiagnostics.report("재생 요청: " + selected.title + " · 실제 출력 확인 중")
            }
        }
    }

    private fun searchSongs(q: String) {
        label.text = "Hush 검색 중…"
        candidates.clear()
        list.adapter = null
        scope.launch {
            HushLiteSongSearch.songs(q).fold(
                onSuccess = { songs ->
                    candidates.clear()
                    candidates.addAll(songs)
                    list.adapter = ArrayAdapter(
                        this@MainActivity,
                        android.R.layout.simple_list_item_1,
                        candidates.map { it.title + " — " + it.artistNames.joinToString(", ") }
                    )
                    label.text = if (songs.isEmpty()) "검색 0건 · 서버 결과 없음 또는 SongItem 파싱 실패 (진단 필요)" else "검색 결과 " + songs.size + "건 · 선택 후 동일 ID로 재생"
                },
                onFailure = { e ->
                    label.text = "검색 실패: " + e.javaClass.simpleName + ": " + e.message
                }
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
