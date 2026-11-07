package app.hush.music.utils.potoken

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.MainThread
import android.os.Handler
import android.os.Looper
import app.hush.music.polaris.PolarisPendingRequests
import app.hush.music.polaris.PolarisResourceDiagnostics
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Generates cryptographically valid PoTokens by running YouTube's BotGuard
 * challenge inside a headless [WebView].
 *
 * ## Lifecycle
 *
 * 1. [initialize] — called once from `Application.onCreate()` with the app context.
 * 2. [preWarm] — optional, boots the WebView at startup to avoid first-call delay.
 * 3. [mintToken] — suspend function, called per video. Reuses the engine until
 *    it expires (~50 min) or the session changes.
 * 4. [onAppBackgrounded] — releases the WebView to free ~50 MB of memory.
 *
 * ## Optimizations
 *
 * - **Suspend API** — [mintToken] is a suspend function, never blocks the calling thread.
 * - **Pre-warm** — [preWarm] bootstraps the engine at app startup so the first
 *   real mint is instant.
 * - **Player token cache** — Caches per-video tokens (LRU, max 200) to avoid
 *   redundant mints when the same video is played multiple times.
 * - **Session token reuse** — The session token is minted once per engine and
 *   reused for all videos until the engine expires or the session changes.
 * - **Background cleanup** — [onAppBackgrounded] releases the WebView to free memory.
 *   The engine is recreated on the next [mintToken] call.
 */
object BotGuardTokenGenerator {
    private const val TAG = "BotGuardTokenGen"

    /** What [readinessDetail] reports when a token for the session can be minted immediately. */
    private const val READY = "ready"
    private const val CREATE_URL = "https://www.youtube.com/api/jnn/v1/Create"
    private const val GENERATE_IT_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT"
    private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
    private const val API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
    private const val WV_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
    private const val JS_BRIDGE = "BotGuardBridge"

    /** Timeout for first call (cold start: WebView boot + BotGuard bootstrap). */
    private const val COLD_START_TIMEOUT_MS = 15_000L

    /** Timeout for subsequent calls (warm: just mint a token). */
    private const val WARM_TIMEOUT_MS = 5_000L

    /** Maximum number of cached player tokens. */
    private const val PLAYER_TOKEN_CACHE_SIZE = 200

    private val httpClient = OkHttpClient.Builder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build()

    // ── state ────────────────────────────────────────────────────────
    private var appContext: Context? = null
    private var permanentlyBroken = false

    private val mutex = Mutex()
    private var engine: BotGuardEngine? = null
    private var engineSessionId: String? = null
    private var cachedSessionToken: String? = null

    @Volatile
    private var engineReady = false

    /**
     * LRU cache for per-video player tokens.
     * Key: session ID + video ID, Value: token string.
     * Avoids redundant mints when the same video is played multiple times.
     */
    private data class PlayerTokenCacheKey(
        val sessionId: String,
        val videoId: String,
    )

    private val playerTokenCache: LinkedHashMap<PlayerTokenCacheKey, String> =
        object : LinkedHashMap<PlayerTokenCacheKey, String>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PlayerTokenCacheKey, String>): Boolean = size > PLAYER_TOKEN_CACHE_SIZE
        }

    // ── public API ───────────────────────────────────────────────────

    /** Call once from `Application.onCreate()`. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        Timber.tag(TAG).d("Initialized")
    }

    /**
     * Pre-warm the BotGuard engine at app startup.
     * This bootstraps the WebView and generates the session token so that
     * the first real [mintToken] call is instant.
     *
     * Safe to call from a background coroutine. No-op if already warmed.
     */
    suspend fun preWarm(sessionId: String) {
        val ctx = appContext ?: return
        if (permanentlyBroken) return
        val isReadyForSession =
            mutex.withLock {
                engineReady && engineSessionId == sessionId && engine?.isExpired == false
            }
        if (isReadyForSession) return

        try {
            withTimeout(COLD_START_TIMEOUT_MS) {
                ensureEngineReady(ctx, sessionId)
            }
            Timber.tag(TAG).d("Pre-warm complete")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            PolarisResourceDiagnostics.event("botguard bootstrap-timeout")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Pre-warm failed (non-fatal)")
        }
    }

    /**
     * Whether a token for [sessionId] can be minted right now, without waiting.
     *
     * The engine bootstrap loads BotGuard inside a WebView, and on a cold start that is the single
     * longest thing the app does - measured at 8.6s from app start to a usable minter on the reporting
     * device (`Page loaded` 19.898 → `Minter ready` 28.460). [mintToken] waits for it, so a caller that
     * needs a token *immediately* - the first track of a session - should ask this first and take the
     * token-free path instead of holding playback behind a WebView. The pre-warm keeps running, so the
     * token is there for the resolves that follow.
     */
    fun isReady(sessionId: String): Boolean = readinessDetail(sessionId) == READY

    /**
     * Why [isReady] answered what it did.
     *
     * A cold start turns on this answer, and "not ready" is not a diagnosis: the engine may still be
     * bootstrapping, may belong to a session that has since changed, or may have expired. Naming which
     * one is what makes the next report about a slow first track answerable.
     */
    fun readinessDetail(sessionId: String): String =
        when {
            appContext == null -> "not initialised"
            permanentlyBroken -> "WebView is permanently broken"
            !engineReady -> "engine still bootstrapping"
            engineSessionId != sessionId -> "engine belongs to a different session"
            engine?.isExpired != false -> "engine expired"
            else -> READY
        }

    /**
     * Mint a PoToken pair for the given [videoId] and [sessionId].
     *
     * Returns `null` when:
     * - [initialize] was never called
     * - The system has no usable WebView
     * - BotGuard bootstrap timed out
     * - The WebView is permanently broken
     *
     * This is a **suspend function** — never blocks the calling thread.
     * Call from a coroutine context (e.g. `Dispatchers.IO`).
     */
    suspend fun mintToken(
        videoId: String,
        sessionId: String,
    ): PoTokenResult? {
        val ctx =
            appContext ?: run {
                Timber.tag(TAG).w("initialize() not called")
                return null
            }
        if (permanentlyBroken) return null

        // Check player token cache first
        mutex.withLock {
            val cachedPlayer = playerTokenCache[PlayerTokenCacheKey(sessionId, videoId)]
            if (
                cachedPlayer != null &&
                cachedSessionToken != null &&
                engineReady &&
                engineSessionId == sessionId &&
                engine?.isExpired == false
            ) {
                Timber.tag(TAG).d("Cache hit for $videoId")
                return PoTokenResult(playerToken = cachedPlayer, sessionToken = cachedSessionToken!!)
            }
        }

        val isFirstCall =
            mutex.withLock {
                !engineReady || engineSessionId != sessionId || engine?.isExpired != false
            }
        val timeout = if (isFirstCall) COLD_START_TIMEOUT_MS else WARM_TIMEOUT_MS

        return try {
            withTimeout(timeout) {
                mintTokenInternal(ctx, videoId, sessionId, forceNewEngine = false)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Timber.tag(TAG).w("Timed out after ${timeout}ms — proceeding without PoToken")
            PolarisResourceDiagnostics.event("botguard mint-timeout")
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: BrokenWebViewException) {
            Timber.tag(TAG).e(e, "Permanently broken WebView")
            permanentlyBroken = true
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "mintToken failed: ${e.message}")
            null
        }
    }

    /**
     * Release the WebView to free memory (~50 MB).
     * Call from `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` or similar.
     * The engine is recreated on the next [mintToken] call.
     */
    suspend fun onAppBackgrounded() {
        mutex.withLock {
            if (engine != null) {
                Timber.tag(TAG).d("Releasing engine (app backgrounded)")
                destroyEngineLocked()
            }
        }
    }

    /**
     * Invalidate the player token cache for a specific video.
     * Useful when the user's auth state changes.
     */
    suspend fun invalidatePlayerToken(videoId: String) {
        mutex.withLock {
            playerTokenCache.keys.removeAll { it.videoId == videoId }
        }
    }

    /**
     * Invalidate all cached tokens.
     * Call when the user logs out or auth state changes.
     */
    suspend fun invalidateAll() {
        mutex.withLock {
            destroyEngineLocked()
        }
    }

    // ── internal ─────────────────────────────────────────────────────

    private suspend fun ensureEngineReady(
        ctx: Context,
        sessionId: String,
    ): PoTokenResult = mintTokenInternal(ctx, "__warmup__", sessionId, forceNewEngine = false)

    private suspend fun mintTokenInternal(
        ctx: Context,
        videoId: String,
        sessionId: String,
        forceNewEngine: Boolean,
    ): PoTokenResult {
        val (eng, sessionTok, wasNew) =
            mutex.withLock {
                val needsNew =
                    forceNewEngine ||
                        engine == null ||
                        engine!!.isExpired ||
                        engineSessionId != sessionId

                if (needsNew) {
                    destroyEngineLocked()
                    val created = BotGuardEngine.create(ctx)
                    try {
                        val token = created.mint(sessionId)
                        engine = created
                        engineSessionId = sessionId
                        cachedSessionToken = token
                        engineReady = true
                    } catch (failure: Throwable) {
                        withContext(NonCancellable + Dispatchers.Main) { created.close() }
                        throw failure
                    }
                }

                Triple(engine!!, cachedSessionToken!!, needsNew)
            }

        val playerTok =
            try {
                eng.mint(videoId)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (wasNew) throw e
                Timber.tag(TAG).w(e, "mint failed, retrying with fresh engine")
                return mintTokenInternal(ctx, videoId, sessionId, forceNewEngine = true)
            }

        val result = PoTokenResult(playerToken = playerTok, sessionToken = sessionTok)
        if (videoId != "__warmup__") {
            mutex.withLock {
                if (engine === eng && engineSessionId == sessionId && engineReady) {
                    playerTokenCache[PlayerTokenCacheKey(sessionId, videoId)] = playerTok
                }
            }
        }
        return result
    }

    /** Caller owns mutex. Clear references before a non-cancellable Main teardown. */
    private suspend fun destroyEngineLocked() {
        val previous = engine
        engine = null
        engineSessionId = null
        cachedSessionToken = null
        engineReady = false
        playerTokenCache.clear()
        withContext(NonCancellable + Dispatchers.Main) { previous?.close() }
    }

    // ── WebView wrapper ──────────────────────────────────────────────

    private class BotGuardEngine private constructor(
        private val webView: WebView,
        private val readySignal: CancellableContinuation<BotGuardEngine>,
    ) {
        private val scope = MainScope()
        private val main = Handler(Looper.getMainLooper())
        private var closed = false
        private val pendingMints = PolarisPendingRequests<String>()
        init { PolarisResourceDiagnostics.engine(1) }
        private fun onMain(block: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
        }
        private lateinit var expiry: Instant

        val isExpired: Boolean get() = Instant.now().isAfter(expiry)

        fun startBootstrap() {
            scope.launch(exceptionHandler) {
                val html =
                    withContext(Dispatchers.IO) {
                        webView.context.assets
                            .open("po_token.html")
                            .bufferedReader()
                            .use { it.readText() }
                    }
                val patched = html.replaceFirst("</script>", "\n$JS_BRIDGE.onPageLoaded()</script>")
                webView.loadDataWithBaseURL(
                    "https://www.youtube.com",
                    patched,
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }

        @JavascriptInterface
        fun onPageLoaded() {
            Timber.tag(TAG).d("Page loaded — requesting challenge from Create")
            postToBotGuard(CREATE_URL, "[ \"$REQUEST_KEY\" ]") { body ->
                val challengeJson = parseCreateChallenge(body)
                webView.evaluateJavascript(
                    """
                    try {
                        var data = $challengeJson;
                        runBotGuard(data).then(function(r) {
                            this.webPoSignalOutput = r.webPoSignalOutput;
                            $JS_BRIDGE.onBotGuardReady(r.botguardResponse);
                        }, function(e) {
                            $JS_BRIDGE.onFatalError(e + "\n" + e.stack);
                        });
                    } catch(e) { $JS_BRIDGE.onFatalError(e + "\n" + e.stack); }
                    """.trimIndent(),
                    null,
                )
            }
        }

        @JavascriptInterface
        fun onBotGuardReady(botguardResponse: String) {
            Timber.tag(TAG).d("BotGuard executed — requesting integrity token from GenerateIT")
            postToBotGuard(GENERATE_IT_URL, "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]") { body ->
                try {
                    val (tokenU8, lifetimeSec) = parseIntegrityToken(body)
                    expiry = Instant.now().plusSeconds(lifetimeSec).minus(10, ChronoUnit.MINUTES)

                    webView.evaluateJavascript(
                        """
                        try {
                            this.integrityToken = $tokenU8;
                            createPoTokenMinter(webPoSignalOutput, integrityToken).then(function() {
                                $JS_BRIDGE.onMinterReady();
                            }).catch(function(e) {
                                $JS_BRIDGE.onFatalError(e + "\n" + (e.stack || ''));
                            });
                        } catch(e) { $JS_BRIDGE.onFatalError(e + "\n" + e.stack); }
                        """.trimIndent(),
                        null,
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "parseIntegrityToken failed")
                    signalError(PoTokenException("GenerateIT parse failed: ${e.message}"))
                }
            }
        }

        @JavascriptInterface
        fun onMinterReady() {
            Timber.tag(TAG).d("Minter ready")
            onMain { if (!closed && readySignal.isActive) readySignal.resume(this@BotGuardEngine) }
        }

        @JavascriptInterface
        fun onFatalError(error: String) {
            Timber.tag(TAG).e("Fatal JS error: $error")
            signalError(classifyJsError(error))
        }

        suspend fun mint(identifier: String): String =
            withContext(Dispatchers.Main) {
                check(!closed) { "BotGuard engine closed" }
                suspendCancellableCoroutine { cont ->
                    val requestId = pendingMints.add(cont)
                    val u8Arg = stringToJsUint8Array(identifier)
                    webView.evaluateJavascript(
                        """
                        try {
                            obtainPoToken($u8Arg).then(function(u8) {
                                $JS_BRIDGE.onMintOk("$requestId", u8.join(","));
                            }).catch(function() { $JS_BRIDGE.onMintErr("$requestId", "mint rejected"); });
                        } catch(e) { $JS_BRIDGE.onMintErr("$requestId", "mint failed"); }
                        """.trimIndent(), null,
                    )
                }
            }

        @JavascriptInterface
        fun onMintOk(
            identifier: String,
            csvBytes: String,
        ) {
            val base64 = commaSeparatedBytesToBase64(csvBytes)
            Timber.tag(TAG).d("Minted token for $identifier (${base64.length} chars)")
            onMain { if (!closed) pendingMints.complete(identifier, base64) }
        }

        @JavascriptInterface
        fun onMintErr(
            identifier: String,
            error: String,
        ) {
            Timber.tag(TAG).e("Mint failed for $identifier: $error")
            onMain { if (!closed) pendingMints.fail(identifier, classifyJsError(error)) }
        }

        private val exceptionHandler = CoroutineExceptionHandler { _, t -> signalError(t) }

        private fun signalError(error: Throwable) = onMain {
            if (!closed) {
                if (readySignal.isActive) readySignal.resumeWithException(error)
                close()
            }
        }

        private fun postToBotGuard(
            url: String,
            jsonBody: String,
            onSuccess: (String) -> Unit,
        ) {
            scope.launch(exceptionHandler) {
                val request =
                    okhttp3.Request
                        .Builder()
                        .url(url)
                        .post(jsonBody.toRequestBody())
                        .headers(
                            mapOf(
                                "User-Agent" to WV_USER_AGENT,
                                "Accept" to "application/json",
                                "Content-Type" to "application/json+protobuf",
                                "x-goog-api-key" to API_KEY,
                                "x-user-agent" to "grpc-web-javascript/0.1",
                            ).toHeaders(),
                        ).build()

                val body = runInterruptible(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        if (response.code != 200) throw PoTokenException("BotGuard HTTP ${response.code}")
                        response.body?.string() ?: throw PoTokenException("BotGuard empty body")
                    }
                }
                if (!closed) onSuccess(body)
            }
        }

        @MainThread
        fun close() {
            if (Looper.myLooper() != Looper.getMainLooper()) { main.post { close() }; return }
            if (closed) return
            closed = true
            pendingMints.cancelAll()
            if (readySignal.isActive) readySignal.cancel(CancellationException("BotGuard closed"))
            scope.cancel()
            runCatching { webView.stopLoading() }
            runCatching { webView.removeJavascriptInterface(JS_BRIDGE) }
            runCatching { webView.onPause() }
            runCatching { webView.removeAllViews() }
            runCatching { webView.destroy() }
            PolarisResourceDiagnostics.engine(-1)
        }

        companion object {
            suspend fun create(context: Context): BotGuardEngine {
                return withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        var creating: BotGuardEngine? = null
                        val wv =
                            WebView(context).apply {
                                settings.javaScriptEnabled = true
                                settings.userAgentString = WV_USER_AGENT
                                settings.blockNetworkLoads = true
                                webChromeClient =
                                    object : WebChromeClient() {
                                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                                            if (m.message().contains("Uncaught") && cont.isActive) {
                                                val err = "\"${m.message()}\", ${m.sourceId()} (${m.lineNumber()})"
                                                cont.resumeWithException(BrokenWebViewException(err))
                                                creating?.close()
                                            }
                                            return super.onConsoleMessage(m)
                                        }
                                    }
                                webViewClient =
                                    object : WebViewClient() {
                                        override fun onRenderProcessGone(
                                            view: WebView,
                                            detail: android.webkit.RenderProcessGoneDetail,
                                        ): Boolean {
                                            Timber.tag(TAG).w("WebView renderer gone (crashed=${detail.didCrash()})")
                                            runCatching {
                                                if (cont.isActive) cont.resumeWithException(
                                                    PoTokenException("WebView renderer process gone"),
                                                )
                                            }
                                            creating?.close()
                                            return true
                                        }
                                    }
                            }
                        val engine = BotGuardEngine(wv, cont)
                        creating = engine
                        wv.addJavascriptInterface(engine, JS_BRIDGE)
                        cont.invokeOnCancellation { engine.onMain { engine.close() } }
                        if (cont.isActive) engine.startBootstrap() else engine.close()
                    }
                }
            }
        }
    }
}
