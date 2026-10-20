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
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.iqbal.seatwatcher.core.model.Watch
import com.iqbal.seatwatcher.core.parser.PageParser
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
 * Loads the public results URL in a hidden WebView that shares the login session,
 * waits for the Angular cards to render, and parses document.body.innerText.
 * It never clicks, fills or submits anything.
 */
class WebViewSeatSource(private val app: Context) : SeatSource {
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var tracker: LoadTracker? = null
    private var cycles = 0

    /** Text of the most recent read, for "Copy debug info". */
    @Volatile var lastRawText: String = ""
        private set

    override suspend fun fetch(watch: Watch): SourceResult = fetchUrl(SiteUrls.search(watch))

    suspend fun fetchUrl(url: String): SourceResult = mutex.withLock {
        if (!Net.isOnline(app)) return SourceResult.Offline
        withContext(Dispatchers.Main) {
            try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) { runCheck(url) }
                    ?: if (tracker?.finished?.isCompleted == true) SourceResult.ParseFailed(lastRawText.take(SAMPLE))
                    else SourceResult.Offline
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                destroyWebView()
                SourceResult.ParseFailed("Exception: ${e.javaClass.simpleName}: ${e.message}\n${lastRawText.take(SAMPLE)}")
            }
        }
    }

    private suspend fun runCheck(url: String): SourceResult {
        val wv = obtainWebView()
        val t = LoadTracker()
        tracker = t
        wv.loadUrl(url)

        withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { t.finished.await() } ?: return SourceResult.Offline
        if (t.rendererGone) return SourceResult.ParseFailed("WebView renderer stopped; it will be recreated.")
        t.mainFrameError?.let { return SourceResult.Offline }
        t.httpStatus?.let { if (it == 403 || it == 429) return SourceResult.Blocked("The site answered HTTP $it") }

        val deadline = SystemClock.elapsedRealtime() + RENDER_TIMEOUT_MS
        var loginSince = 0L
        var page = read(wv)
        while (true) {
            blockReason(page)?.let { lastRawText = page.text; return SourceResult.Blocked(it) }
            if (PageParser.isLoaded(page.text)) {
                delay(SETTLE_MS) // let every train card render
                page = read(wv)
                break
            }
            val now = SystemClock.elapsedRealtime()
            if (looksLikeLogin(page)) {
                if (loginSince == 0L) loginSince = now
                else if (now - loginSince >= LOGIN_CONFIRM_MS) {
                    lastRawText = page.text
                    return SourceResult.LoginRequired
                }
            } else loginSince = 0L
            if (now >= deadline) break
            delay(POLL_MS)
            page = read(wv)
        }

        lastRawText = page.text
        CookieManager.getInstance().flush()
        val trains = PageParser.parse(page.text)
        return when {
            trains.isNotEmpty() -> SourceResult.Success(trains)
            looksLikeLogin(page) -> SourceResult.LoginRequired
            else -> SourceResult.ParseFailed("URL: ${page.url}\n\n${page.text.take(SAMPLE)}")
        }
    }

    private data class Page(val url: String, val text: String, val hasPassword: Boolean)

    private suspend fun read(wv: WebView): Page = suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(READ_JS) { raw ->
            if (cont.isActive) cont.resume(decode(raw))
        }
    }

    private fun decode(raw: String?): Page = try {
        val s = JSONTokener(raw ?: "null").nextValue() as? String ?: return Page("", "", false)
        val o = JSONObject(s)
        Page(o.optString("u"), o.optString("t"), o.optBoolean("p"))
    } catch (e: Exception) {
        Page("", "", false)
    }

    private fun looksLikeLogin(p: Page): Boolean {
        if (PageParser.isLoaded(p.text)) return false
        val u = p.url.lowercase()
        return p.hasPassword || "/login" in u || "/auth" in u || "/signin" in u
    }

    private fun blockReason(p: Page): String? {
        if (PageParser.isLoaded(p.text)) return null
        return BLOCK_PATTERNS.firstOrNull { it.containsMatchIn(p.text) }
            ?.let { "The site shows a check page (\"${it.find(p.text)?.value}\")" }
    }

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
        // Never shown, but give it a phone-sized layout so innerText has real line breaks.
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, WIDTH, HEIGHT)
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && url != "about:blank") tracker?.finished?.complete(Unit)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    tracker?.mainFrameError = error.errorCode
                    tracker?.finished?.complete(Unit)
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) tracker?.httpStatus = response.statusCode
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https" // the hidden view only follows https navigations

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                tracker?.rendererGone = true
                tracker?.finished?.complete(Unit)
                if (view === webView) webView = null
                view.destroy()
                return true // handled: do not crash the app
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
        var httpStatus: Int? = null
        var rendererGone = false
    }

    companion object {
        private const val TAG = "WebViewSeatSource"
        private const val WIDTH = 1080
        private const val HEIGHT = 2400
        private const val POLL_MS = 600L
        private const val SETTLE_MS = 1200L
        private const val RENDER_TIMEOUT_MS = 20_000L
        private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
        private const val TOTAL_TIMEOUT_MS = 55_000L
        private const val LOGIN_CONFIRM_MS = 2_500L
        private const val RECREATE_EVERY = 30
        private const val SAMPLE = 2048

        private const val READ_JS = """(function(){try{return JSON.stringify({u:location.href,t:(document.body?document.body.innerText:''),p:!!document.querySelector('input[type=password]')});}catch(e){return JSON.stringify({u:'',t:'',p:false});}})()"""

        private val BLOCK_PATTERNS = listOf(
            Regex("captcha", RegexOption.IGNORE_CASE),
            Regex("verify (that )?you are (a )?human", RegexOption.IGNORE_CASE),
            Regex("are you a robot", RegexOption.IGNORE_CASE),
            Regex("too many requests", RegexOption.IGNORE_CASE),
            Regex("access denied", RegexOption.IGNORE_CASE),
            Regex("unusual traffic", RegexOption.IGNORE_CASE),
            Regex("request (was )?blocked", RegexOption.IGNORE_CASE),
        )
    }
}
