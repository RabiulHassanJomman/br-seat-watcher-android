package com.iqbal.seatwatcher.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.iqbal.seatwatcher.source.SessionToken
import org.json.JSONTokener

/**
 * Visible WebView on the same session as the background checks. Login mode shows "Done".
 * The app never fills or submits anything here: you log in and book yourself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(url: String, loginMode: Boolean, onClose: () -> Unit, onDone: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var title by remember { mutableStateOf(if (loginMode) "Log in" else "Booking") }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = {
                            val wv = webView
                            if (wv != null && wv.canGoBack()) wv.goBack() else onClose()
                        }) {
                            Icon(
                                if (canGoBack) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                                contentDescription = if (canGoBack) "Back" else "Close",
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload")
                        }
                        if (loginMode) TextButton(onClick = {
                            CookieManager.getInstance().flush()
                            val wv = webView
                            if (wv == null) onDone()
                            else wv.evaluateJavascript(SessionToken.READ_JS) { raw ->
                                // Keep the site's token in memory only, in case the site stores it per tab.
                                val t = runCatching { JSONTokener(raw ?: "null").nextValue() as? String }.getOrNull()
                                if (!t.isNullOrBlank()) SessionToken.value = t
                                onDone()
                            }
                        }) { Text("Done") }
                    },
                )
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    with(settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        setSupportMultipleWindows(false)
                        allowFileAccess = false
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                        override fun onReceivedTitle(view: WebView, t: String?) { if (!t.isNullOrBlank()) title = t }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { canGoBack = view.canGoBack() }
                        override fun onPageFinished(view: WebView, u: String?) {
                            canGoBack = view.canGoBack()
                            CookieManager.getInstance().flush()
                        }
                        override fun doUpdateVisitedHistory(view: WebView, u: String?, isReload: Boolean) { canGoBack = view.canGoBack() }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val scheme = request.url.scheme ?: return true
                            if (scheme == "https") return false
                            if (scheme == "http") return true // HTTPS only
                            // Payment apps (bKash, Nagad, cards) and tel/mailto links open outside.
                            return try {
                                val intent = if (scheme == "intent") Intent.parseUri(request.url.toString(), Intent.URI_INTENT_SCHEME)
                                else Intent(Intent.ACTION_VIEW, request.url)
                                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                                intent.component = null
                                intent.selector = null
                                view.context.startActivity(intent)
                                true
                            } catch (e: ActivityNotFoundException) {
                                true
                            } catch (e: Exception) {
                                true
                            }
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            if (view === webView) webView = null
                            view.destroy()
                            onClose()
                            return true
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
            onRelease = {
                CookieManager.getInstance().flush()
                if (webView === it) webView = null
                it.destroy()
            },
        )
    }
}
