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
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
 * Loads the results URL in a hidden WebView that shares the login session.
 * Reads trains from the search API response the page itself downloads (a hidden WebView is
 * throttled and may never render the cards), falling back to parsing document.body.innerText.
 * It never clicks, fills or submits anything, and never alters a request.
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
            // 1) The search API response the page itself downloaded. Works even when the
            //    hidden WebView is throttled and never renders the cards.
            when (val api = fromApi(page)) {
                is SourceResult.Success -> { lastRawText = summary(page); return api }
                SourceResult.LoginRequired, is SourceResult.Blocked -> { lastRawText = summary(page); return api }
                else -> Unit
            }
            // 2) Rendered text, as in the userscript.
            blockReason(page)?.let { lastRawText = summary(page); return SourceResult.Blocked(it) }
            if (PageParser.isLoaded(page.text)) {
                delay(SETTLE_MS) // let every train card render
                page = read(wv)
                break
            }
            val now = SystemClock.elapsedRealtime()
            if (looksLikeLogin(page)) {
                if (loginSince == 0L) loginSince = now
                else if (now - loginSince >= LOGIN_CONFIRM_MS) {
                    lastRawText = summary(page)
                    return SourceResult.LoginRequired
                }
            } else loginSince = 0L
            if (now >= deadline) break
            delay(POLL_MS)
            page = read(wv)
        }

        lastRawText = summary(page)
        CookieManager.getInstance().flush()
        val trains = PageParser.parse(page.text)
        return when {
            trains.isNotEmpty() -> SourceResult.Success(trains)
            fromApi(page) is SourceResult.Success -> fromApi(page)
            looksLikeLogin(page) -> SourceResult.LoginRequired
            else -> SourceResult.ParseFailed(lastRawText.take(SAMPLE))
        }
    }

    /** A network response captured by HOOK_JS. Bodies are kept only for train-search URLs. */
    private data class Captured(val url: String, val status: Int, val body: String?)

    private data class Page(val url: String, val text: String, val visiblePassword: Boolean, val responses: List<Captured>)

    private suspend fun read(wv: WebView): Page = suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(READ_JS) { raw ->
            if (cont.isActive) cont.resume(decode(raw))
        }
    }

    private fun decode(raw: String?): Page = try {
        val s = JSONTokener(raw ?: "null").nextValue() as? String ?: return Page("", "", false, emptyList())
        val o = JSONObject(s)
        val arr = o.optJSONArray("r")
        val responses = buildList {
            if (arr != null) for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                add(Captured(r.optString("u"), r.optInt("s"), if (r.has("b") && !r.isNull("b")) r.optString("b") else null))
            }
        }
        Page(o.optString("u"), o.optString("t"), o.optBoolean("p"), responses)
    } catch (e: Exception) {
        Page("", "", false, emptyList())
    }

    private fun isSearchApi(url: String) = SEARCH_API.containsMatchIn(url) && !url.endsWith(".js") && !url.contains("/assets/")

    /** Success / LoginRequired / Blocked from the captured search API call, or ParseFailed if there is none yet. */
    private fun fromApi(p: Page): SourceResult {
        val calls = p.responses.filter { isSearchApi(it.url) }
        for (c in calls.asReversed()) {
            when (c.status) {
                401 -> return SourceResult.LoginRequired
                403, 429 -> return SourceResult.Blocked("The site's search API answered HTTP ${c.status}")
            }
            val body = c.body ?: continue
            if (c.status in 200..299) {
                val trains = TripJsonParser.parse(body)
                if (trains.isNotEmpty()) return SourceResult.Success(trains)
            }
        }
        return SourceResult.ParseFailed("")
    }

    private fun looksLikeLogin(p: Page): Boolean {
        if (PageParser.isLoaded(p.text)) return false
        val u = p.url.lowercase()
        val onLoginRoute = "/login" in u || "/auth" in u || "/signin" in u
        // A password box in the DOM is not enough (the site keeps one even when logged in); it must be visible.
        return onLoginRoute || p.visiblePassword
    }

    private fun blockReason(p: Page): String? {
        if (PageParser.isLoaded(p.text)) return null
        return BLOCK_PATTERNS.firstOrNull { it.containsMatchIn(p.text) }
            ?.let { "The site shows a check page (\"${it.find(p.text)?.value}\")" }
    }

    /** Debug sample: URL, page text, the network calls seen, and the start of the search response. */
    private fun summary(p: Page): String = buildString {
        appendLine("URL: ${p.url}")
        appendLine("Visible password field: ${p.visiblePassword}")
        appendLine("Network calls seen (${p.responses.size}):")
        p.responses.takeLast(15).forEach { appendLine("  ${it.status} ${it.url.take(160)}") }
        p.responses.lastOrNull { isSearchApi(it.url) && it.body != null }?.let {
            appendLine("Search response (start):")
            appendLine(it.body!!.take(1500))
        }
        appendLine()
        appendLine("Page text:")
        append(p.text.take(1500))
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
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                if (!hookAtDocumentStart) view.evaluateJavascript(HOOK_JS, null)
            }

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
        installHook(wv)
        wv.onResume()
        wv.resumeTimers()
        webView = wv
        return wv
    }

    private var hookAtDocumentStart = false

    /** Records the page's own XHR/fetch responses so trains can be read without rendering. */
    private fun installHook(wv: WebView) {
        hookAtDocumentStart = try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(wv, HOOK_JS, setOf(SiteUrls.BASE))
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "document-start script unavailable", e)
            false
        }
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

        private val SEARCH_API = Regex("search|trip|train", RegexOption.IGNORE_CASE)

        private const val READ_JS = """(function(){try{
var vis=false;var ps=document.querySelectorAll('input[type=password]');
for(var i=0;i<ps.length;i++){var e=ps[i];var st=getComputedStyle(e);if(e.offsetWidth>0&&e.offsetHeight>0&&st.visibility!=='hidden'&&st.display!=='none'){vis=true;break;}}
return JSON.stringify({u:location.href,t:(document.body?document.body.innerText:''),p:vis,r:(window.__bsw?window.__bsw.r:[])});
}catch(e){return JSON.stringify({u:'',t:'',p:false,r:[]});}})()"""

        /** Read-only: copies responses the page already received. Changes no request. */
        private const val HOOK_JS = """(function(){if(window.__bsw)return;var B=window.__bsw={r:[]};
function keep(u){return /search|trip|train/i.test(u||'')&&!/\.js(\?|$)/.test(u||'');}
function rec(u,s,b){try{u=String(u||'');var e={u:u.slice(0,300),s:s|0};if(keep(u)&&typeof b==='string')e.b=b.slice(0,400000);B.r.push(e);if(B.r.length>40)B.r.shift();}catch(x){}}
var F=window.fetch;if(F){window.fetch=function(i){var u=(i&&i.url)||i;return F.apply(this,arguments).then(function(res){try{if(keep(String(u))){res.clone().text().then(function(t){rec(u,res.status,t);},function(){rec(u,res.status,null);});}else rec(u,res.status,null);}catch(x){}return res;});};}
var X=XMLHttpRequest.prototype,O=X.open,S=X.send;
X.open=function(m,u){this.__bswu=u;return O.apply(this,arguments);};
X.send=function(){var x=this;x.addEventListener('loadend',function(){var b=null;try{if(keep(String(x.__bswu))){if(x.responseType===''||x.responseType==='text')b=x.responseText;else if(x.responseType==='json')b=JSON.stringify(x.response);}}catch(e){}rec(x.__bswu,x.status,b);});return S.apply(this,arguments);};
})();"""

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
