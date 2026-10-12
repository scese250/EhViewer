package com.hippo.ehviewer.ui.settings

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView as AndroidWebView
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import com.hippo.ehviewer.ktor.CHROME_MOBILE_USER_AGENT
import com.hippo.ehviewer.ui.MainActivity
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.util.setDefaultSettings
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.tarsin.tip

private const val POLL_INTERVAL_MS = 500L
private const val SLOW_HINT_TICKS = 30

/**
 * How long to wait for the site to mint and store its own clearance before we give up and
 * ask the user to try again. The site mints the clearance itself — we must NOT try to mint
 * with the same single-use Turnstile token or we will race the site's request and one of the
 * two POSTs will get a 401 (token already consumed).
 */
private const val SITE_MINT_TIMEOUT_MS = 15_000L

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
    // Read the saved token once on entry. If it exists we show the "already verified" screen
    // and only open the WebView when the user explicitly requests a re-verify — the same way a
    // browser never shows the captcha again just because you open a new tab.
    val existingToken = remember { normalizeClearanceToken(Settings.schaleClearanceToken.value) }
    var showWebView by remember { mutableStateOf(existingToken == null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.schale_verification_title)) },
                navigationIcon = { NavigationIcon() },
            )
        },
    ) { paddingValues ->
        if (!showWebView) {
            // ── Already-verified screen ──────────────────────────────────────────────────
            // The WebView is NOT created here, so the site never loads and no captcha appears.
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
            ) {
                Text(
                    text = "✓ Verificación guardada",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Tu verificación de Schale Network está activa (longitud del token: ${existingToken!!.length} caracteres). " +
                        "No necesitas resolver el captcha de nuevo a menos que el lector te diga que la verificación expiró.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = { navigator.popBackStack() },
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(text = "Volver")
                }
                TextButton(
                    onClick = { showWebView = true },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(text = "Volver a verificar (sólo si tienes problemas)")
                }
            }
        } else {
            // ── WebView verification screen ──────────────────────────────────────────────
            // When re-verifying, we inject the saved token into localStorage BEFORE the site's
            // scripts run. The site reads localStorage["clearance"] on load and checks it
            // against its auth server — if valid, no captcha widget appears, exactly like a
            // browser whose tab already has the clearance stored.
            VerificationWebView(
                paddingValues = paddingValues,
                existingToken = existingToken,
                onDone = { navigator.popBackStack() },
            )
        }
    }
}

/** The JS snippet that pre-seeds localStorage with our saved token before the page reads it. */
private fun jsSeedClearance(token: String): String {
    // Escape single quotes and backslashes so the token is safe inside a JS string literal.
    val safe = token.replace("\\", "\\\\").replace("'", "\\'")
    return "(function(){try{localStorage.setItem('clearance','$safe');}catch(e){}})();"
}

context(activity: MainActivity)
@Composable
private fun VerificationWebView(
    paddingValues: PaddingValues,
    existingToken: String?,
    onDone: () -> Unit,
) {
    val state = rememberWebViewState(url = EhUrl.HOST_SCHALE)
    var captured by remember { mutableStateOf<String?>(null) }
    var captchaSeq by remember { mutableStateOf(0) }
    var statusText by remember {
        mutableStateOf(
            if (existingToken != null) {
                "Cargando el sitio — si tu verificación sigue siendo válida el captcha no aparecerá."
            } else {
                "Esperando a que resuelvas el captcha…"
            },
        )
    }

    fun acceptToken(raw: String?): Boolean {
        val token = normalizeClearanceToken(raw) ?: return false
        if (captured == null) captured = token
        return true
    }

    // Poll localStorage every 500 ms. The site's own JS mints and stores the clearance after
    // Turnstile solves — we read what the site wrote. We must NOT POST the token ourselves:
    // it is single-use and the site already uses it; a second POST gets HTTP 401.
    LaunchedEffect(Unit) {
        var ticks = 0
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            if (captured != null) break
            ticks++
            if (ticks == SLOW_HINT_TICKS) {
                statusText = "Resuelve el captcha de la página para continuar."
            }
            val webView = state.webView ?: continue
            webView.evaluateJavascript(JS_READ_CLEARANCE) { result ->
                // Only accept a token that differs from the one already stored, so that simply
                // loading the page (which echoes back the injected token) doesn't trigger a
                // redundant save when the user is just re-checking a still-valid token.
                val token = normalizeClearanceToken(result) ?: return@evaluateJavascript
                if (token != existingToken) acceptToken(token)
            }
        }
    }

    // After the captcha is solved, give the site up to SITE_MINT_TIMEOUT_MS to write a NEW
    // clearance to localStorage. If nothing new appears, its POST failed — reset the widget.
    LaunchedEffect(captchaSeq) {
        if (captchaSeq == 0) return@LaunchedEffect
        statusText = "Captcha resuelto. Esperando que el sitio complete la verificación…"
        var waited = 0L
        while (isActive && captured == null && waited < SITE_MINT_TIMEOUT_MS) {
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        if (captured == null && isActive) {
            statusText = "El sitio no pudo completar la verificación. Resuelve el captcha de nuevo."
            state.webView?.evaluateJavascript(JS_FRESH_CAPTCHA, null)
        }
    }

    LaunchedEffect(captured) {
        val token = captured ?: return@LaunchedEffect
        Settings.schaleClearanceToken.value = token
        EhCookieStore.flush()
        statusText = "Verificación guardada (len=${token.length})."
        tip("Verificación de Schale guardada")
        onDone()
    }

    val client = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: AndroidWebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.startsWith(EhUrl.HOST_SCHALE) == true) {
                    statusText = "Cargando niyaniya.moe…"
                    // Inject the saved token into localStorage as early as possible so the
                    // site finds it when its scripts run — no captcha if the token is valid.
                    existingToken?.let { view.evaluateJavascript(jsSeedClearance(it), null) }
                    view.evaluateJavascript(JS_OBSERVER_INJECTION, null)
                }
            }

            override fun onPageFinished(view: AndroidWebView, url: String?) {
                super.onPageFinished(view, url)
                if (url?.startsWith(EhUrl.HOST_SCHALE) == true) {
                    // Re-inject on finish as well: some SPA frameworks re-read localStorage
                    // after hydration, so a second injection makes the token more likely to
                    // be in place when the site's auth check runs.
                    existingToken?.let { view.evaluateJavascript(jsSeedClearance(it), null) }
                    if (captured == null) {
                        statusText = if (existingToken != null) {
                            "Página cargada. Si no ves captcha, tu verificación sigue siendo válida — puedes volver."
                        } else {
                            "Página cargada. Resuelve el captcha para continuar."
                        }
                    }
                    view.evaluateJavascript(JS_OBSERVER_INJECTION, null)
                }
            }
        }
    }

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
                                // We do not call mintClearance — the site POSTs the single-use
                                // Turnstile token itself. We just start the timeout clock.
                                if (!answer.isNullOrBlank()) captchaSeq++
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
