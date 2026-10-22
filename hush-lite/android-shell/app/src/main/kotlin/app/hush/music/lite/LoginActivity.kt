package app.hush.music.lite

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Minimal independent YTM login. Follows the Hush v13.14.7 session inputs:
 * multi-origin cookie, DataSyncId, visitorData, then accountInfo verification.
 * Never log cookies/tokens; persist ONLY after successful account verification.
 */
class LoginActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var web: WebView
    private var dataSyncId: String? = null
    private var visitorData: String? = null
    private var verifying = false
    private val loginUrl =
        "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LiteSession.initialize(this)
        status = TextView(this).apply {
            text = "YouTube Music 로그인 후 계정 연결을 확인합니다."
        }
        val retry = Button(this).apply {
            text = "계정 확인 다시 시도"
            setOnClickListener { readPageSession() }
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val host = Uri.parse(url).host.orEmpty().lowercase()
                    if (host == "music.youtube.com" || host == "www.youtube.com") {
                        readPageSession()
                    }
                }
            }
            loadUrl(loginUrl)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(retry)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }

    private fun readPageSession() {
        val host = Uri.parse(web.url.orEmpty()).host.orEmpty().lowercase()
        if (host != "music.youtube.com" && host != "www.youtube.com") {
            status.text = "먼저 YouTube Music 로그인을 완료해 주세요."
            return
        }
        web.evaluateJavascript(
            """(function(){try{var y=window.ytcfg;return y&&y.get?
                  y.get('VISITOR_DATA')||'':''}catch(e){return ''}})()"""
        ) { encoded ->
            visitorData = parseJsString(encoded) ?: visitorData
        }
        web.evaluateJavascript(
            """(function(){try{var y=window.ytcfg;return y&&y.get?
                  y.get('DATASYNC_ID')||'':''}catch(e){return ''}})()"""
        ) { encoded ->
            dataSyncId = parseJsString(encoded) ?: dataSyncId
            tryLogin()
        }
    }

    private fun parseJsString(encoded: String?): String? =
        runCatching {
            if (encoded == null || encoded == "null") return null
            JSONObject("""{"value":""" + encoded + "}").optString("value")
                .trim().takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()

    private fun mergeYouTubeCookies(): String {
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        val parts = linkedMapOf<String, String>()
        listOf(
            "https://music.youtube.com",
            "https://www.youtube.com",
            "https://youtube.com"
        ).forEach { origin ->
            cookieManager.getCookie(origin)
                .orEmpty()
                .split(";")
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach { pair ->
                    val separator = pair.indexOf("=")
                    if (separator > 0) {
                        val name = pair.substring(0, separator).trim()
                        if (name.isNotBlank()) parts[name] = pair.substring(separator + 1).trim()
                    }
                }
        }
        return parts.entries.joinToString("; ") { it.key + "=" + it.value }
    }

    private fun tryLogin() {
        if (verifying) return
        val cookies = mergeYouTubeCookies()
        if (!cookies.contains("SAPISID")) {
            status.text = "YouTube 로그인 세션 쿠키를 확인하지 못했습니다."
            return
        }
        if (dataSyncId.isNullOrBlank()) {
            status.text = "계정 DataSyncId가 아직 확인되지 않았습니다. 로그인 후 재확인해 주세요."
            return
        }
        verifying = true
        LiteSession.setAccount(cookies, dataSyncId, visitorData)
        status.text = "계정 확인 중…"
        scope.launch {
            app.hush.music.innertube.YouTube.accountInfo().fold(
                onSuccess = { info ->
                    LiteSession.setAccount(cookies, dataSyncId, visitorData)
                    val saved = LiteSession.persistVerifiedAccount(this@LoginActivity)
                    status.text = if (saved) {
                        "계정 확인 및 암호화 저장 완료: " + info.name
                    } else {
                        "계정 확인됨: " + info.name + " / 암호화 저장 실패"
                    }
                },
                onFailure = { e ->
                    status.text = "계정 확인 실패: " + e.javaClass.simpleName
                }
            )
            verifying = false
        }
    }

    override fun onDestroy() {
        web.destroy()
        scope.cancel()
        super.onDestroy()
    }
}
