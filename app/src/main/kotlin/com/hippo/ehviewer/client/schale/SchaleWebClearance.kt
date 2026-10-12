package com.hippo.ehviewer.client.schale

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.ktor.CHROME_MOBILE_USER_AGENT
import com.hippo.ehviewer.util.setDefaultSettings
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx

private const val TAG = "SchaleClearanceSync"

/**
 * Reads the clearance the site itself keeps in `localStorage["clearance"]`.
 *
 * The site stores it as soon as the captcha is solved and the WebView profile is shared with the
 * screens the app already opens, so a verification completed there can be adopted without asking
 * for the captcha again. The value is polled from the first moment, before the site's own refresh
 * logic can drop it, and a token the auth server rejects is never adopted.
 */
object SchaleWebClearance {
    private const val TIMEOUT_MS = 12_000L
    private const val POLL_INTERVAL_MS = 350L

    /** Spawning a WebView is expensive, so a failed read is not retried right away. */
    private const val MIN_INTERVAL_MS = 15_000L

    private val mutex = Mutex()

    @Volatile
    private var lastAttempt = 0L

    suspend fun read(): String? = mutex.withLock {
        val now = System.currentTimeMillis()
        if (now - lastAttempt < MIN_INTERVAL_MS) {
            logcat(TAG) { "skipped, last read was ${(now - lastAttempt) / 1000}s ago" }
            return null
        }
        lastAttempt = now
        withContext(Dispatchers.Main) { readOnMain() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun readOnMain(): String? {
        val webView = try {
            createWebView()
        } catch (e: Throwable) {
            logcat(TAG) { "cannot create a WebView: ${e.message}" }
            return null
        }
        return try {
            webView.loadUrl(EhUrl.HOST_SCHALE)
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            var token: String? = null
            while (token == null && System.currentTimeMillis() < deadline) {
                delay(POLL_INTERVAL_MS)
                token = readStoredClearance(webView)
            }
            if (token == null) {
                logcat(TAG) { "the site has no clearance stored" }
            } else {
                logcat(TAG) { "the site has a clearance stored (len=${token.length})" }
            }
            runCatching { CookieManager.getInstance().flush() }
            token
        } catch (e: Throwable) {
            logcat(TAG) { "read failed: ${e.message}" }
            null
        } finally {
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
        }
    }

    private fun createWebView(): WebView = WebView(appCtx).apply {
        setDefaultSettings()
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = CHROME_MOBILE_USER_AGENT
        }
        webViewClient = WebViewClient()
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(this, true)
    }

    private suspend fun readStoredClearance(webView: WebView): String? {
        val raw = suspendCancellableCoroutine<String?> { cont ->
            webView.evaluateJavascript(JS_READ_CLEARANCE) { value -> cont.resume(value) }
        }
        val token = raw?.trim()?.removeSurrounding("\"")?.trim()
        if (token.isNullOrEmpty()) return null
        if (token.equals("null", ignoreCase = true) || token.equals("undefined", ignoreCase = true) || token == "{}") return null
        return token.takeIf { it.length >= 8 && it.none(Char::isWhitespace) }
    }
}

private const val JS_READ_CLEARANCE = """
    (function() {
        try {
            if (!window.outerWidth) Object.defineProperty(window, 'outerWidth', { get: () => window.innerWidth || 1080 });
            if (!window.outerHeight) Object.defineProperty(window, 'outerHeight', { get: () => window.innerHeight || 1920 });
            const local = window.localStorage.getItem('clearance');
            if (local) return local;
            const session = window.sessionStorage.getItem('clearance');
            if (session) return session;
            const match = /(?:^|;\s*)clearance=([^;]+)/.exec(document.cookie || '');
            return match ? decodeURIComponent(match[1]) : '';
        } catch (e) { return ''; }
    })();
"""
