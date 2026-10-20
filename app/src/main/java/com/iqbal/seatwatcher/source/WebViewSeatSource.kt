package com.iqbal.seatwatcher.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.iqbal.seatwatcher.core.model.Watch
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
 * Gets trains through the site's own search API (`search-trips-v2`), called from inside a hidden
 * WebView that has the e-ticket site open. The request is the one the site's page makes: same URL,
 * the user's own bearer token as saved by the site, no other headers added, nothing spoofed.
 * The token never leaves the WebView. It never books, fills or submits anything.
 *
 * The site renews its token itself when its page loads, so on a 401 the page is reloaded once
 * before reporting LoginRequired.
 */
class WebViewSeatSource(private val app: Context) : SeatSource {
    private val mutex = Mutex()
    private var webView: WebView? = null
    private var loadedAt = 0L
    private var calls = 0
    private var load: CompletableDeferred<Int>? = null

    /** What the last check saw, for "Copy debug info". Never contains the token. */
    @Volatile var lastRawText: String = ""
        private set

    override suspend fun fetch(watch: Watch): SourceResult = fetchApi(SiteUrls.api(watch))

    suspend fun fetchApi(apiUrl: String): SourceResult = mutex.withLock {
        if (!Net.isOnline(app)) return SourceResult.Offline
        withContext(Dispatchers.Main) {
            try {
                withTimeoutOrNull(TOTAL_TIMEOUT_MS) { check(apiUrl) }
                    ?: SourceResult.ParseFailed("$NOT_RESPONDING\n$lastRawText")
            } catch (e: Exception) {
                Log.w(TAG, "check failed", e)
                destroyWebView()
                SourceResult.ParseFailed("Exception: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private suspend fun check(apiUrl: String): SourceResult {
        if (!ensureSitePage(forceReload = false)) return SourceResult.Offline
        var r = call(apiUrl)
        if (r.status == 401 || (r.status != 200 && !r.hasToken)) {
            // Let the site's own page renew its session, then try once more.
            if (!ensureSitePage(forceReload = true)) return SourceResult.Offline
            delay(RENEW_WAIT_MS)
            r = call(apiUrl)
        }
        lastRawText = r.describe(apiUrl)
        return when {
            r.status == 200 -> {
                val trains = TripJsonParser.parse(r.body)
                if (trains.isNotEmpty()) SourceResult.Success(trains)
                else SourceResult.ParseFailed("No trains in the API response.\n$lastRawText")
            }
            r.status == 401 || (!r.hasToken && r.status in 400..499) -> SourceResult.LoginRequired
            r.status == 403 || r.status == 429 -> SourceResult.Blocked("The site's search API answered HTTP ${r.status}")
            r.status == 0 -> SourceResult.Offline
            r.status < 0 -> SourceResult.ParseFailed("$NOT_RESPONDING\n$lastRawText")
            else -> SourceResult.ParseFailed(lastRawText)
        }
    }

    // ---- API call inside the site's page ----------------------------------------------------

    private data class ApiResult(
        val status: Int,
        val body: String,
        val hasToken: Boolean,
        val tokenExp: Long,
        val error: String,
    ) {
        fun describe(url: String) = buildString {
            appendLine("API: ${url.substringBefore('?')}")
            appendLine("Query: ${url.substringAfter('?', "")}")
            appendLine("Status: ${if (status < 0) "no answer" else status.toString()}")
            append("Login token found: $hasToken")
            if (tokenExp > 0) append(" (expires ${java.util.Date(tokenExp * 1000)})")
            appendLine()
            if (error.isNotBlank()) appendLine("Error: $error")
            appendLine("Response (start):")
            append(body.take(1500))
        }
    }

    private suspend fun call(apiUrl: String): ApiResult {
        val wv = webView ?: return ApiResult(-1, "", false, 0, "no page")
        val id = JSONObject.quote("c${++calls}")
        val token = SessionToken.value?.let { JSONObject.quote(it) } ?: "null"
        evaluate(wv, "(function(){$API_JS})()($id,${JSONObject.quote(apiUrl)},$token)")
        val poll = "(function(){var R=window.__bswApi||{};var r=R[$id];if(r&&r.done){delete R[$id];}return JSON.stringify(r||null);})()"
        val deadline = SystemClock.elapsedRealtime() + API_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_MS)
            val o = decodeObject(evaluate(wv, poll)) ?: continue
            if (!o.optBoolean("done")) continue
            return ApiResult(
                status = o.optInt("s", 0),
                body = o.optString("b", ""),
                hasToken = o.optBoolean("tok"),
                tokenExp = o.optLong("exp", 0),
                error = o.optString("err", ""),
            )
        }
        return ApiResult(-1, "", true, 0, "the API did not answer within ${API_TIMEOUT_MS / 1000}s")
    }

    private suspend fun evaluate(wv: WebView, js: String): String? = suspendCancellableCoroutine { cont ->
        wv.evaluateJavascript(js) { raw -> if (cont.isActive) cont.resume(raw) }
    }

    /** evaluateJavascript returns a JSON-encoded string; unwrap it and parse the object inside. */
    private fun decodeObject(raw: String?): JSONObject? = try {
        val s = JSONTokener(raw ?: "null").nextValue() as? String
        if (s == null || s == "null") null else JSONObject(s)
    } catch (e: Exception) {
        null
    }

    // ---- The hidden site page -----------------------------------------------------------------

    /** Makes sure the hidden WebView shows an e-ticket page, so the API call carries the site's origin. */
    private suspend fun ensureSitePage(forceReload: Boolean): Boolean {
        val fresh = webView == null || calls >= RECREATE_EVERY_CALLS
        val stale = SystemClock.elapsedRealtime() - loadedAt > RELOAD_EVERY_MS
        if (!fresh && !stale && !forceReload && loadedAt > 0) return true
        if (fresh) {
            destroyWebView()
            calls = 0
            createWebView()
        }
        val wv = webView ?: return false
        val l = CompletableDeferred<Int>()
        load = l
        wv.loadUrl(SiteUrls.BASE)
        val error = withTimeoutOrNull(PAGE_LOAD_TIMEOUT_MS) { l.await() }
        if (error == null || error != 0) {
            loadedAt = 0
            return false
        }
        delay(PAGE_SETTLE_MS) // let the site's scripts start (and renew the session if needed)
        loadedAt = SystemClock.elapsedRealtime()
        CookieManager.getInstance().flush()
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
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
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (url != null && url != "about:blank") load?.complete(0)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) load?.complete(error.errorCode)
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "https"

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                load?.complete(-1)
                if (view === webView) {
                    webView = null
                    loadedAt = 0
                }
                view.destroy()
                return true // handled: never crash the app
            }
        }
        wv.onResume()
        wv.resumeTimers()
        webView = wv
        loadedAt = 0
    }

    private fun destroyWebView() {
        webView?.let {
            it.stopLoading()
            it.webViewClient = WebViewClient()
            it.destroy()
        }
        webView = null
        loadedAt = 0
    }

    companion object {
        private const val TAG = "WebViewSeatSource"
        const val NOT_RESPONDING = "SITE_NOT_RESPONDING: the site's search request never finished."
        private const val POLL_MS = 300L
        private const val API_TIMEOUT_MS = 20_000L
        private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
        private const val PAGE_SETTLE_MS = 3_000L
        private const val RENEW_WAIT_MS = 5_000L
        private const val TOTAL_TIMEOUT_MS = 90_000L
        private const val RELOAD_EVERY_MS = 30 * 60_000L
        private const val RECREATE_EVERY_CALLS = 120

        /**
         * Body returning a JS function(id, url, fallbackToken). It finds the site's own login token
         * in its storage (a JWT issued for the train IAM), calls the search API the way the site does,
         * and stores {done, s, b, tok, exp} in window.__bswApi[id]. The token itself is never returned.
         */
        private const val API_JS = """return function(id,url,fallback){
var R=window.__bswApi=window.__bswApi||{};R[id]={done:false};
function claims(t){try{var p=t.split('.')[1].replace(/-/g,'+').replace(/_/g,'/');while(p.length%4)p+='=';return JSON.parse(atob(p));}catch(e){return null;}}
var best=null,exp=0;
[window.localStorage,window.sessionStorage].forEach(function(st){try{for(var i=0;i<st.length;i++){var v=st.getItem(st.key(i))||'';
var m=v.match(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g)||[];
m.forEach(function(t){var c=claims(t);if(!c)return;var who=String(c.iss||'')+' '+String(c.aud||'');
if(/train/i.test(who)&&(c.exp||0)>exp){best=t;exp=c.exp||0;}});}}catch(e){}});
if(!best&&fallback){best=fallback;var fc=claims(fallback);exp=(fc&&fc.exp)||0;}
var h={'Accept':'application/json'};if(best)h['Authorization']='Bearer '+best;
try{fetch(url,{method:'GET',headers:h}).then(function(r){return r.text().then(function(b){R[id]={done:true,s:r.status,b:b.slice(0,600000),tok:!!best,exp:exp};});})
.catch(function(e){R[id]={done:true,s:0,err:String(e),tok:!!best,exp:exp};});}
catch(e){R[id]={done:true,s:0,err:String(e),tok:!!best,exp:exp};}
return 'started';}"""
    }
}

/**
 * In-memory copy of the site's login token, read from the visible login WebView when the user taps
 * Done. Used only if the hidden page finds none in its own storage (e.g. the site keeps it per tab).
 * Never written to disk; gone when the app process ends.
 */
object SessionToken {
    @Volatile var value: String? = null

    /** JS for the visible WebView: returns the freshest train-IAM JWT from the site's storage, or null. */
    const val READ_JS = """(function(){function claims(t){try{var p=t.split('.')[1].replace(/-/g,'+').replace(/_/g,'/');while(p.length%4)p+='=';return JSON.parse(atob(p));}catch(e){return null;}}
var best=null,exp=0;[window.localStorage,window.sessionStorage].forEach(function(st){try{for(var i=0;i<st.length;i++){var v=st.getItem(st.key(i))||'';
var m=v.match(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g)||[];m.forEach(function(t){var c=claims(t);if(!c)return;
if(/train/i.test(String(c.iss||'')+' '+String(c.aud||''))&&(c.exp||0)>exp){best=t;exp=c.exp||0;}});}}catch(e){}});return best;})()"""
}
