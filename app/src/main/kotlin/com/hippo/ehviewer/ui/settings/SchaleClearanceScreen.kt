package com.hippo.ehviewer.ui.settings

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView as AndroidWebView
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.util.logcat
import com.google.accompanist.web.AccompanistWebViewClient
import com.google.accompanist.web.LoadingState
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.schale.SchaleEngine
import com.hippo.ehviewer.ktor.CHROME_MOBILE_USER_AGENT
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.util.setDefaultSettings
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import moe.tarsin.tip

private const val POLL_INTERVAL_MS = 500L
private const val SLOW_HINT_TICKS = 30

/** The captcha answer is single use and short lived, so the app mints the clearance right away. */
private const val MINT_GRACE_MS = 2_000L

/** The auth server limits verifications per connection; wait out a 429 instead of giving up. */
private val MINT_RETRY_DELAYS_MS = longArrayOf(10_000L, 20_000L, 30_000L, 45_000L)

/** After this many 429s the answer is old enough that a fresh one is worth asking the page for. */
private const val FRESH_CAPTCHA_AT_ATTEMPT = 2

/**
 * Injected on every main-frame page load.
 * - Keeps the Turnstile widget renderable on WebViews that report no outer size.
 * - Reports the clearance however the site produces it: the storage write, the `crt` of a call the
 *   server accepted, or the body of the site's own `POST auth.schale.network/clearance`.
 * - Reports the captcha answer and the widget id, so the app can mint the verification itself (the
 *   auth server rate limits it per connection, and the site only tries once) and ask for a fresh
 *   captcha when an answer expires.
 */
private const val JS_OBSERVER_INJECTION = """
    (function () {
        try {
            if (!window.outerWidth) Object.defineProperty(window, 'outerWidth', { get: () => window.innerWidth || 1080 });
            if (!window.outerHeight) Object.defineProperty(window, 'outerHeight', { get: () => window.innerHeight || 1920 });
        } catch (e) {}

        function report(value) {
            try {
                const token = value === null || value === undefined ? '' : String(value).trim();
                if (token.length >= 8 && window.SchaleBridge) window.SchaleBridge.onClearanceToken(token);
            } catch (e) {}
        }

        function crtFrom(url) {
            try {
                const match = /[?&]crt=([^&]+)/.exec(String(url || ''));
                return match ? decodeURIComponent(match[1]) : null;
            } catch (e) { return null; }
        }

        function isClearanceCall(url) {
            return /\/clearance\b/.test(String(url || ''));
        }

        if (window.__schaleObserverInstalled !== true) {
            window.__schaleObserverInstalled = true;

            try {
                const originalSetItem = Storage.prototype.setItem;
                Storage.prototype.setItem = function (key, value) {
                    originalSetItem.apply(this, arguments);
                    if (key === 'clearance') report(value);
                };
            } catch (e) {}

            try {
                const originalOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function (method, url) {
                    try { this.__schaleUrl = url; } catch (e) {}
                    return originalOpen.apply(this, arguments);
                };
                const originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function () {
                    try {
                        const url = this.__schaleUrl;
                        this.addEventListener('load', function () {
                            try {
                                if (isClearanceCall(url) && this.status === 201) report(this.responseText);
                                else if (this.status >= 200 && this.status < 300) report(crtFrom(url));
                            } catch (e) {}
                        });
                    } catch (e) {}
                    return originalSend.apply(this, arguments);
                };
            } catch (e) {}

            try {
                const originalFetch = window.fetch;
                if (typeof originalFetch === 'function') {
                    window.fetch = function (input, init) {
                        const url = typeof input === 'string' ? input : (input && input.url) || '';
                        const result = originalFetch.apply(this, arguments);
                        try {
                            result.then(function (response) {
                                try {
                                    if (isClearanceCall(url) && response && response.status === 201) {
                                        response.clone().text().then(report);
                                    } else if (response && response.ok) {
                                        report(crtFrom(url));
                                    }
                                } catch (e) {}
                            });
                        } catch (e) {}
                        return result;
                    };
                }
            } catch (e) {}

            window.__schaleReadClearance = function () {
                try {
                    return window.localStorage.getItem('clearance') || '';
                } catch (e) { return ''; }
            };
        }

        var turnstileTries = 0;
        (function wrapTurnstile() {
            try {
                if (window.turnstile && window.turnstile.render && window.turnstile.__schaleWrapped !== true) {
                    const originalRender = window.turnstile.render;
                    window.turnstile.render = function (element, options) {
                        try {
                            if (options && typeof options.callback === 'function') {
                                const callback = options.callback;
                                options.callback = function (answer) {
                                    try {
                                        if (window.SchaleBridge) window.SchaleBridge.onCaptchaAnswer(String(answer));
                                    } catch (e) {}
                                    return callback.apply(this, arguments);
                                };
                            }
                        } catch (e) {}
                        const widget = originalRender.apply(this, arguments);
                        try { window.__schaleWidgetId = widget; } catch (e) {}
                        return widget;
                    };
                    window.turnstile.__schaleWrapped = true;
                }
            } catch (e) {}
            if (++turnstileTries < 120) setTimeout(wrapTurnstile, 500);
        })();

        window.__schaleFreshCaptcha = function () {
            try {
                if (window.turnstile && window.__schaleWidgetId !== undefined && window.__schaleWidgetId !== null) {
                    window.turnstile.reset(window.__schaleWidgetId);
                    return true;
                }
            } catch (e) {}
            return false;
        };
    })();
"""

private const val JS_READ_CLEARANCE = """
    (function () {
        try {
            if (window.__schaleReadClearance) return window.__schaleReadClearance();
            return window.localStorage.getItem('clearance') || '';
        } catch (e) { return ''; }
    })();
"""

// Only reloads the page. The clearance is never deleted from the site's own storage: the site
// re-checks it on load and asks for a new captcha by itself when it is no longer accepted.
private const val JS_RELOAD_PAGE = """
    (function () {
        window.location.reload();
    })();
"""

// Asks the page for a new captcha answer; falls back to a reload when the widget cannot be reset.
private const val JS_FRESH_CAPTCHA = """
    (function () {
        try {
            if (window.__schaleFreshCaptcha && window.__schaleFreshCaptcha()) return;
        } catch (e) {}
        try { window.location.reload(); } catch (e) {}
    })();
"""

private fun normalizeClearanceToken(raw: String?): String? {
    val token = raw?.trim()?.removeSurrounding("\"")?.trim()
    if (token.isNullOrEmpty()) return null
    if (token.equals("null", ignoreCase = true) || token.equals("undefined", ignoreCase = true) || token == "{}") return null
    return token
}

class SchaleBridge(
    private val onClearance: (String?) -> Unit,
    private val onCaptchaAnswer: (String?) -> Unit,
) {
    @JavascriptInterface
    fun onClearanceToken(token: String?) {
        onClearance(token)
    }

    @JavascriptInterface
    fun onCaptchaAnswer(answer: String?) {
        onCaptchaAnswer(answer)
    }
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.SchaleClearanceScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val state = rememberWebViewState(url = EhUrl.HOST_SCHALE)
    var captured by remember { mutableStateOf<String?>(null) }
    var captchaAnswer by remember { mutableStateOf("") }
    var captchaSeq by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("Esperando a que resuelvas el captcha…") }

    /** @return true when the value looked like a clearance token and was handed to the save step. */
    fun acceptToken(raw: String?): Boolean {
        val token = normalizeClearanceToken(raw) ?: return false
        if (captured == null) captured = token
        return true
    }

    // The site stores the clearance in localStorage right after Turnstile resolves, so poll it; the
    // injected observer also reports the token of a call the server accepted, which covers a slower
    // or unusual storage path.
    LaunchedEffect(Unit) {
        var ticks = 0
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            if (captured != null) break
            ticks++
            if (ticks == SLOW_HINT_TICKS) {
                statusText = "Aún no se detectó la verificación. Resuelve el captcha de la página."
            }
            val webView = state.webView ?: continue
            webView.evaluateJavascript(JS_READ_CLEARANCE) { result ->
                acceptToken(result)
            }
        }
    }

    // The auth server allows only a handful of verifications per connection and the site tries just
    // once, so the app asks for the clearance itself and keeps trying while the limit resets. This
    // is what makes a 429 (rate limited connection, typically a shared or mobile address) recover
    // instead of leaving the app without a token.
    LaunchedEffect(captchaSeq) {
        if (captchaSeq == 0) return@LaunchedEffect
        val answer = captchaAnswer
        statusText = "Captcha resuelto. Pidiendo la verificación…"
        delay(MINT_GRACE_MS)
        var attempt = 0
        while (isActive && captured == null) {
            val result = withContext(Dispatchers.IO) { SchaleEngine.mintClearance(answer) }
            if (result.token != null) {
                acceptToken(result.token)
                return@LaunchedEffect
            }
            logcat("SchaleClearance") { "mint attempt ${attempt + 1} -> HTTP ${result.httpStatus ?: "sin respuesta"}" }
            if (!result.isRateLimited) {
                statusText = "El captcha fue rechazado (HTTP ${result.httpStatus ?: "sin respuesta"}). Recarga la página y vuelve a resolverlo."
                return@LaunchedEffect
            }
            if (attempt >= MINT_RETRY_DELAYS_MS.size) {
                statusText = "Schale limita las verificaciones desde esta conexión (HTTP 429). " +
                    "Usa otra red —una VPN suele servir— o vuelve a intentarlo más tarde."
                return@LaunchedEffect
            }
            val waitMs = result.retryAfterSeconds?.let { (it * 1000L).coerceIn(5_000L, 120_000L) } ?: MINT_RETRY_DELAYS_MS[attempt]
            statusText = "Schale limita las verificaciones (HTTP 429). Reintentando en ${waitMs / 1000} s…"
            if (attempt == FRESH_CAPTCHA_AT_ATTEMPT) {
                state.webView?.evaluateJavascript(JS_FRESH_CAPTCHA, null)
            }
            delay(waitMs)
            attempt++
        }
    }

    LaunchedEffect(captured) {
        val token = captured ?: return@LaunchedEffect
        Settings.schaleClearanceToken.value = token
        EhCookieStore.flush()
        statusText = "Verificación guardada (len=${token.length})."
        tip("Verificación de Schale guardada")
        navigator.popBackStack()
    }

    val client = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: AndroidWebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.startsWith(EhUrl.HOST_SCHALE) == true) {
                    statusText = "Cargando niyaniya.moe…"
                    view.evaluateJavascript(JS_OBSERVER_INJECTION, null)
                }
            }

            override fun onPageFinished(view: AndroidWebView, url: String?) {
                super.onPageFinished(view, url)
                if (url?.startsWith(EhUrl.HOST_SCHALE) == true) {
                    if (captured == null) {
                        statusText = "Página cargada. Resuelve el captcha si no lo has hecho todavía."
                    }
                    view.evaluateJavascript(JS_OBSERVER_INJECTION, null)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.schale_verification_title)) },
                navigationIcon = { NavigationIcon() },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
        ) {
            val loadingState = state.loadingState
            if (loadingState is LoadingState.Loading) {
                LinearProgressIndicator(
                    progress = { loadingState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            WebView(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                client = client,
                onCreated = { webView ->
                    webView.setDefaultSettings()
                    with(webView.settings) {
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        userAgentString = CHROME_MOBILE_USER_AGENT
                    }
                    webView.addJavascriptInterface(
                        SchaleBridge(
                            onClearance = { token -> webView.post { acceptToken(token) } },
                            onCaptchaAnswer = { answer ->
                                webView.post {
                                    if (!answer.isNullOrBlank()) {
                                        captchaAnswer = answer
                                        captchaSeq++
                                    }
                                }
                            },
                        ),
                        "SchaleBridge",
                    )
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                },
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            TextButton(
                onClick = { state.webView?.evaluateJavascript(JS_RELOAD_PAGE, null) },
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            ) {
                Text(text = "Recargar página")
            }
        }
    }
}
