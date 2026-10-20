package com.iqbal.seatwatcher.source

import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.iqbal.seatwatcher.core.source.SiteUrls

/**
 * Diagnostics for the visible in-app browser: a read-only log of the site's own XHR/fetch calls.
 * It records method, URL, status, the NAMES of request headers (never their values), whether
 * cookies were sent, and the start of search responses. It changes no request.
 */
object NetHook {
    private const val JS = """(function(){if(window.__bswLog)return;var L=window.__bswLog=[];
function keep(u){return /search|trip|train|seat|auth|token|user/i.test(String(u||''))&&!/\.js(\?|#|$)|\/assets\//i.test(String(u||''));}
function add(m,u){var e={m:String(m||'GET').toUpperCase(),u:String(u||'').split('?')[0].slice(0,200),q:String(u||'').indexOf('?')>=0,s:-1,h:[],c:''};if(keep(u)){L.push(e);if(L.length>40)L.shift();}return e;}
var F=window.fetch;if(F){window.fetch=function(i,init){var u=(i&&i.url)||i;var e=add((init&&init.method)||(i&&i.method),u);
try{var hd=(init&&init.headers)||(i&&i.headers);if(hd){if(typeof Headers!=='undefined'&&hd instanceof Headers)hd.forEach(function(v,k){e.h.push(k);});else if(Array.isArray(hd))hd.forEach(function(p){e.h.push(p[0]);});else Object.keys(hd).forEach(function(k){e.h.push(k);});}
e.c='fetch credentials='+String((init&&init.credentials)||(i&&i.credentials)||'default');}catch(x){}
return F.apply(this,arguments).then(function(r){e.s=r.status;try{if(/search/i.test(e.u))r.clone().text().then(function(t){e.b=t.slice(0,300);});}catch(x){}return r;},function(err){e.s=0;e.err=String(err);throw err;});};}
var X=XMLHttpRequest.prototype,O=X.open,S=X.send,H=X.setRequestHeader;
X.open=function(m,u){this.__bswE=add(m,u);return O.apply(this,arguments);};
X.setRequestHeader=function(k,v){try{if(this.__bswE)this.__bswE.h.push(k);}catch(e){}return H.apply(this,arguments);};
X.send=function(){var x=this,e=x.__bswE;try{if(e)e.c='xhr withCredentials='+x.withCredentials;}catch(z){}
x.addEventListener('loadend',function(){if(!e)return;e.s=x.status;try{if(/search/i.test(e.u)&&(x.responseType===''||x.responseType==='text'))e.b=String(x.responseText).slice(0,300);}catch(z){}});
return S.apply(this,arguments);};})();"""

    /** Returns the log as plain text. */
    const val LOG_JS = """(function(){try{var L=window.__bswLog||[];var out=['Page: '+location.href.split('?')[0],'Calls logged: '+L.length,''];
L.forEach(function(e){out.push(e.m+' '+(e.s<0?'PENDING':e.s)+' '+e.u+(e.q?'?…':''));out.push('  request header names: '+(e.h.length?e.h.join(', '):'(none set by the page)'));if(e.c)out.push('  '+e.c);if(e.err)out.push('  error: '+e.err);if(e.b)out.push('  response start: '+e.b);});
var ls=[];try{for(var i=0;i<localStorage.length;i++)ls.push(localStorage.key(i));}catch(x){}var ss=[];try{for(var j=0;j<sessionStorage.length;j++)ss.push(sessionStorage.key(j));}catch(x){}
out.push('');out.push('localStorage keys: '+ls.join(', '));out.push('sessionStorage keys: '+ss.join(', '));
out.push('cookie names: '+document.cookie.split(';').map(function(c){return c.split('=')[0].trim();}).filter(Boolean).join(', '));
return out.join('\n');}catch(x){return 'log error: '+x;}})()"""

    /** Installs the logger before any page script runs; returns false if the WebView can't do that. */
    fun install(wv: WebView): Boolean = try {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, JS, setOf(SiteUrls.BASE))
            true
        } else false
    } catch (e: Exception) {
        Log.w("NetHook", "document-start script unavailable", e)
        false
    }

    /** Fallback when document-start scripts are unsupported (may miss the earliest calls). */
    const val INLINE_JS = JS
}
