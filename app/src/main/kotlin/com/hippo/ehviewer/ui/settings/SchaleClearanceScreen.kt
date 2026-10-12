package com.hippo.ehviewer.ui.settings

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView as AndroidWebView
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.EhCookieStore
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
private const val CAPTCHA_FALLBACK_MS = 6_000L

/**
 * Injected on every main-frame page load.
 * - Keeps the Turnstile widget renderable on WebViews that report no outer size.
 * - Watches every way the site can produce the clearance: the storage write, the `crt` parameter of
 *   a call the server accepted, the answer of `POST auth.schale.network/clearance`, and the captcha
 *   answer itself. Nothing depends on the site finishing its own bookkeeping.
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
                    const local = window.localStorage.getItem('clearance');
                    if (local) return local;
                    const session = window.sessionStorage.getItem('clearance');
                    if (session) return session;
                    const match = /(?:^|;\s*)clearance=([^;]+)/.exec(document.cookie || '');
                    return match ? decodeURIComponent(match[1]) : '';
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
                                    try { if (window.SchaleBridge) window.SchaleBridge.onCaptchaAnswer(String(answer)); } catch (e) {}
                                    return callback.apply(this, arguments);
                                };
                            }
                        } catch (e) {}
                        return originalRender.apply(this, arguments);
                    };
                    window.turnstile.__schaleWrapped = true;
                }
            } catch (e) {}
            if (++turnstileTries < 120) setTimeout(wrapTurnstile, 500);
        })();
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
    var storedToken by remember { mutableStateOf(Settings.schaleClearanceToken.value) }
    var captchaAnswer by remember { mutableStateOf("") }
    var captchaSeq by remember { mutableStateOf(0) }
    var statusText by remember { mutableStateOf("Esperando a que resuelvas el captcha…") }

    /** @return true when the value looked like a clearance token and was handed to the save step. */
    fun acceptToken(raw: String?): Boolean {
        val token = normalizeClearanceToken(raw) ?: return false
        if (captured == null) captured = token
        return true
    }

    fun readFromSite() {
        val webView = state.webView
        if (webView == null) {
            statusText = "La página todavía no está lista. Espera un momento e inténtalo otra vez."
            return
        }
        statusText = "Leyendo la verificación que guardó el sitio…"
        webView.evaluateJavascript(JS_READ_CLEARANCE) { result ->
            if (!acceptToken(result)) {
                statusText = "El sitio todavía no tiene una verificación guardada. Resuelve el captcha de la página."
            }
        }
    }

    // The site stores the clearance in localStorage, but the app does not depend on that: the
    // injected observer also reports the token of a call the server accepted and the answer of the
    // clearance call. Polling stays as the last safety net.
    LaunchedEffect(Unit) {
        var ticks = 0
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            if (captured != null) break
            ticks++
            if (ticks == SLOW_HINT_TICKS) {
                statusText = "Aún no se detectó la verificación. Si ya resolviste el captcha, pulsa «Leer del sitio»."
            }
            val webView = state.webView ?: continue
            webView.evaluateJavascript(JS_READ_CLEARANCE) { result -> acceptToken(result) }
        }
    }

    LaunchedEffect(captured) {
        val token = captured ?: return@LaunchedEffect
        val changed = Settings.schaleClearanceToken.value != token
        Settings.schaleClearanceToken.value = token
        storedToken = token
        EhCookieStore.flush()
        statusText = "Verificación guardada (len=${token.length}). Comprobando con el servidor…"
        val httpStatus = withContext(Dispatchers.IO) { SchaleEngine.validateClearanceToken(token) }
        when {
            httpStatus == 200 && changed -> {
                tip("Verificación de Schale guardada")
                navigator.popBackStack()
            }
            httpStatus == 200 -> statusText = "La verificación ya estaba guardada y el servidor la acepta."
            httpStatus == null -> statusText = "Guardada (len=${token.length}), pero el servidor no respondió. Revisa tu conexión."
            else -> {
                statusText = "El servidor rechazó la verificación (HTTP $httpStatus). Resuelve el captcha de la página."
                captured = null
            }
        }
    }

    // The site exchanges the captcha answer for a clearance itself; if that call does not land, the
    // app makes the same call so the verification the user just passed is not lost.
    LaunchedEffect(captchaSeq) {
        if (captchaSeq == 0) return@LaunchedEffect
        statusText = "Captcha resuelto. Obteniendo la verificación…"
        delay(CAPTCHA_FALLBACK_MS)
        if (captured != null) return@LaunchedEffect
        val answer = captchaAnswer
        val minted = withContext(Dispatchers.IO) { SchaleEngine.createClearance(answer) }
        if (minted == null) {
            statusText = "El sitio no guardó la verificación. Pulsa «Recargar página» e inténtalo otra vez."
        } else {
            // The engine already stored it, so this path finishes here instead of going through the
            // save step, which would find the token unchanged.
            storedToken = minted
            EhCookieStore.flush()
            statusText = "Verificación del captcha guardada (len=${minted.length})."
            tip("Verificación de Schale guardada")
            navigator.popBackStack()
        }
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
            Text(
                text = "Guardado en la app: " + (storedToken?.let { "sí (len=${it.length})" } ?: "no"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = { readFromSite() }) {
                    Text(text = "Leer del sitio")
                }
                TextButton(onClick = { state.webView?.reload() }) {
                    Text(text = "Recargar página")
                }
            }
        }
    }
}
