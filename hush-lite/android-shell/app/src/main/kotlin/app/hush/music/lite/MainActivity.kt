package app.hush.music.lite

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
    private lateinit var query: EditText
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiteSession.initialize(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 12)
        }
        label = TextView(this).apply {
            text = "Hush Lite search + early playback. Audio not device verified."
        }
        query = EditText(this).apply {
            hint = "가수와 곡 제목"
            setSingleLine()
        }
        val login = Button(this).apply { text = "YouTube Music 로그인 (임시 세션)" }
        val search = Button(this).apply { text = "Hush 검색" }
        list = ListView(this)
        root.addView(label)
        root.addView(login)
        root.addView(query)
        root.addView(search)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
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
                label.text = "재생 요청: " + selected.title + " (" + selected.videoId + ") · 결과 미확인"
            }
        }
    }

    private fun searchSongs(q: String) {
        label.text = "Hush 검색 중…"
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
                    label.text = "검색 결과 " + songs.size + "건 · 선택 후 동일 ID로 재생"
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
