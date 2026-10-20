package com.iqbal.seatwatcher.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.iqbal.seatwatcher.core.model.Watch
import com.iqbal.seatwatcher.core.parser.PageParser
import com.iqbal.seatwatcher.core.parser.TripJsonParser
import com.iqbal.seatwatcher.core.source.SeatSource
import com.iqbal.seatwatcher.core.source.SiteUrls
import com.iqbal.seatwatcher.core.source.SourceResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * Each check loads the e-ticket results page (`/booking/train/search?...&class=...`) in a hidden
 * WebView that shares your login. The site's own code then runs its search; the app only reads the
 * response of that request (read-only hook in [NetHook]) and parses it. The app sends no request
 * of its own to the API, adds no headers, and never books, fills or submits anything.
 */
class WebViewSeatSource(private val app: Context) : SeatSource {
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var hookEarly = false
    private var cycles = 0
    private var load: LoadTracker? = null

    /** What the last check saw, for "Copy debug info". Never contains tokens or header values. */
    @Volatile var lastRawText: String = ""
        private set

    override suspend fun fetch(watch: Watch): SourceResult = fetchUrl(SiteUrls.search(watch))

    suspend fun fetchUrl(url: String): SourceResult = mutex.withLock {
        if (!Net.isOnline(app)) return SourceResult.Offline
        withContext(Dispatchers.Main) {
            try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) { check(url) }
                    ?: SourceResult.ParseFailed("$NOT_RESPONDING\n$lastRawText")
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                destroyWebView()
                SourceResult.ParseFailed("Exception: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun check(url: String): SourceResult {
        val wv = obtainWebView()
        val t = LoadTracker()
        load = t
        wv.loadUrl(url)
        withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { t.finished.await() } ?: return SourceResult.Offline
        if (t.rendererGone) return SourceResult.ParseFailed("WebView renderer stopped; it will be recreated.")
        if (t.mainFrameError != null) return SourceResult.Offline

        val deadline = SystemClock.elapsedRealtime() + SEARCH_TIMEOUT_MS
        var loginSince = 0L
        var st = state(wv)
        while (true) {
            st.trips?.let { trips ->
                if (trips.status >= 0) { // the site's own search finished
                    lastRawText = summary(url, st)
                    return fromTrips(trips, st)
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (looksLikeLogin(st)) {
                if (loginSince == 0L) loginSince = now
                else if (now - loginSince >= LOGIN_CONFIRM_MS) {
                    lastRawText = summary(url, st)
                    return SourceResult.LoginRequired
                }
            } else loginSince = 0L
            blockReason(st.text)?.let { lastRawText = summary(url, st); return SourceResult.Blocked(it) }
            if (now >= deadline) break
            delay(POLL_MS)
            st = state(wv)
        }

        // No search response seen: fall back to the rendered text, as the userscript did.
        lastRawText = summary(url, st)
        CookieManager.getInstance().flush()
        val trains = PageParser.parse(st.text)
        return when {
            trains.isNotEmpty() -> SourceResult.Success(trains)
            looksLikeLogin(st) -> SourceResult.LoginRequired
            else -> SourceResult.ParseFailed("$NOT_RESPONDING\n$lastRawText")
        }
    }

    private fun fromTrips(trips: Trips, st: PageState): SourceResult = when (trips.status) {
        200 -> {
            val trains = TripJsonParser.parse(trips.body)
            if (trains.isNotEmpty()) SourceResult.Success(trains)
            else SourceResult.ParseFailed("$NO_TRAINS\n$lastRawText")
        }
        401 -> SourceResult.LoginRequired
        403, 429 -> SourceResult.Blocked("The site's search answered HTTP ${trips.status}")
        0 -> SourceResult.Offline
        else -> SourceResult.ParseFailed(lastRawText)
    }

    // ---- reading the page --------------------------------------------------------------------

    private data class Trips(val status: Int, val body: String)
    private data class PageState(val url: String, val text: String, val visiblePassword: Boolean, val trips: Trips?, val hooked: Boolean)

    private suspend fun state(wv: WebView): PageState {
        val raw: String? = suspendCancellableCoroutine { cont ->
            wv.evaluateJavascript(NetHook.STATE_JS) { r -> if (cont.isActive) cont.resume(r) }
        }
        return try {
            val s = JSONTokener(raw ?: "null").nextValue() as? String ?: return PageState("", "", false, null, false)
            val o = JSONObject(s)
            val trips = o.optJSONObject("trips")?.let { Trips(it.optInt("s", -1), it.optString("b", "")) }
            PageState(o.optString("u"), o.optString("t"), o.optBoolean("p"), trips, o.optBoolean("hook"))
        } catch (e: Exception) {
            PageState("", "", false, null, false)
        }
    }

    private fun looksLikeLogin(p: PageState): Boolean {
        if (PageParser.isLoaded(p.text)) return false
        val u = p.url.lowercase()
        return "/login" in u || "/auth" in u || "/signin" in u || p.visiblePassword
    }

    private fun blockReason(text: String): String? {
        if (PageParser.isLoaded(text)) return null
        return BLOCK_PATTERNS.firstOrNull { it.containsMatchIn(text) }
            ?.let { "The site shows a check page (\"${it.find(text)?.value}\")" }
    }

    private fun summary(url: String, p: PageState) = buildString {
        appendLine("Loaded: ${url.substringBefore('?')}?${url.substringAfter('?', "")}")
        appendLine("Now at: ${p.url.substringBefore('?')}")
        appendLine("Hook active: ${p.hooked}; visible password field: ${p.visiblePassword}")
        val t = p.trips
        if (t == null) appendLine("Site's trips search: not seen")
        else {
            appendLine("Site's trips search: ${if (t.status < 0) "pending" else "HTTP ${t.status}"}")
            if (t.body.isNotEmpty()) appendLine("Response (start): ${t.body.take(600)}")
        }
        appendLine()
        appendLine("Page text:")
        append(p.text.take(1000))
    }

    // ---- the hidden WebView ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun obtainWebView(): WebView {
        cycles++
        val existing = webView
        if (existing != null && cycles % RECREATE_EVERY != 0) return existing
        destroyWebView()
        val wv = WebView(app)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = false
            blockNetworkImage = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        // Never shown, but give it a phone-sized layout so the site lays out normally.
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, WIDTH, HEIGHT)
        hookEarly = NetHook.install(wv)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!hookEarly) view.evaluateJavascript(NetHook.INLINE_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && url != "about:blank") load?.finished?.complete(Unit)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    load?.mainFrameError = error.errorCode
                    load?.finished?.complete(Unit)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https"

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                load?.rendererGone = true
                load?.finished?.complete(Unit)
                if (view === webView) webView = null
                view.destroy()
                return true // handled: never crash the app
            }
        }
        wv.onResume()
        wv.resumeTimers()
        webView = wv
        return wv
    }

    private fun destroyWebView() {
        webView?.let {
            it.stopLoading()
            it.webViewClient = WebViewClient()
            it.destroy()
        }
        webView = null
    }

    private class LoadTracker {
        val finished = CompletableDeferred<Unit>()
        var mainFrameError: Int? = null
        var rendererGone = false
    }

    companion object {
        private const val TAG = "WebViewSeatSource"
        const val NOT_RESPONDING = "SITE_NOT_RESPONDING: the site's search never finished."
        const val NO_TRAINS = "NO_TRAINS: the site lists no trains for this route and date."
        private const val WIDTH = 1080
        private const val HEIGHT = 2400
        private const val POLL_MS = 500L
        private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
        private const val SEARCH_TIMEOUT_MS = 25_000L
        private const val LOGIN_CONFIRM_MS = 3_000L
        private const val TOTAL_TIMEOUT_MS = 70_000L
        private const val RECREATE_EVERY = 60

        private val BLOCK_PATTERNS = listOf(
            Regex("captcha", RegexOption.IGNORE_CASE),
            Regex("verify (that )?you are (a )?human", RegexOption.IGNORE_CASE),
            Regex("are you a robot", RegexOption.IGNORE_CASE),
            Regex("too many requests", RegexOption.IGNORE_CASE),
            Regex("access denied", RegexOption.IGNORE_CASE),
            Regex("unusual traffic", RegexOption.IGNORE_CASE),
        )
    }
}
