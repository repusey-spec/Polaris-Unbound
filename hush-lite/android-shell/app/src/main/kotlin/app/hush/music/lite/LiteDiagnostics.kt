package app.hush.music.lite

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phone-side diagnostics for a single-process isolated Lite test app.
 * Status strings contain NO cookie, streaming URL or auth token.
 */
internal object LiteDiagnostics {
    private val mutable = MutableStateFlow("Hush Lite: 검색 또는 로그인을 시작하세요.")
    val status: StateFlow<String> = mutable

    fun report(message: String) {
        mutable.value = message
    }
}
