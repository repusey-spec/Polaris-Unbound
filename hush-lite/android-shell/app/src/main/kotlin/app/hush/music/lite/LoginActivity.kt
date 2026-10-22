package app.hush.music.lite

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Minimal WebView login diagnostic.
 * Does not persist credentials. A complete upstream session manager is pending.
 */
class LoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private var dataSyncId: String? = null
    private lateinit var web: WebView
    private val loginUrl = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Google 로그인 후 계정 세션 확인 (임시)" }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val host = android.net.Uri.parse(url).host.orEmpty().lowercase()
                    if (host == "music.youtube.com" || host == "www.youtube.com") {
                        view.evaluateJavascript(
                            """(function(){try{return window.ytcfg&&window.ytcfg.get?
                                  window.ytcfg.get('DATASYNC_ID')||'':''}catch(e){return ''}})()"""
                        ) { encoded ->
                            dataSyncId = try {
                                JSONObject("""{"value":""" + encoded + "}").optString("value").takeIf { it.isNotBlank() }
                            } catch (_: Exception) { null }
                            tryLogin()
                        }
                    }
                }
            }
            loadUrl(loginUrl)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }

    private fun tryLogin() {
        val cookies = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
        if (!cookies.contains("SAPISID") && !cookies.contains("__Secure-3PAPISID")) {
            status.text = "YouTube 세션 쿠키 대기 중"
            return
        }
        LiteSession.setAccount(cookies, dataSyncId)
        status.text = "쿠키 확보, YouTube 계정 확인 중…"
        scope.launch {
            app.hush.music.innertube.YouTube.accountInfo().fold(
                onSuccess = { info -> status.text = "계정 확인됨: " + info.name + " · 저장 안 됨" },
                onFailure = { e -> status.text = "계정 확인 실패: " + e.javaClass.simpleName }
            )
        }
    }

    override fun onDestroy() {
        web.destroy()
        scope.cancel()
        super.onDestroy()
    }
}
