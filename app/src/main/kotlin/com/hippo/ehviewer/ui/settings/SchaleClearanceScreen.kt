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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.hippo.ehviewer.client.parseAs
import com.hippo.ehviewer.client.schale.SchaleEngine
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
import kotlinx.coroutines.launch
import moe.tarsin.tip

private const val POLL_INTERVAL_MS = 500L
private const val SLOW_HINT_TICKS = 30

/**
 * How long the site's own mint gets before the app tries with the same answer. The answer is
 * single-use, so only one of the two POSTs can win: the site goes first and the app only mints
 * when the site's attempt produced nothing (see [JS_OBSERVER_INJECTION], which captures the
 * answer through every path the page offers).
 */
private const val SITE_MINT_TIMEOUT_MS = 8_000L

/**
 * Injected on every main-frame page load.
 * - Keeps the Turnstile widget renderable on WebViews that report no outer size.
 * - Reports the clearance however the site produces it: the storage write, the `crt` of a call the
 *   server accepted, or the body of the site's own `POST auth.schale.network/clearance`.
 * - Captures the captcha answer from every path that can see it — the `turnstile.render` callback
 *   (wrapped on a fast poll, best effort: a self-solving widget can fire it first), the widget's
 *   own `getResponse`, the hidden input Turnstile writes, and the `Authorization` header of the
 *   site's own POST. Nothing here writes to a page global, so the widget always initialises.
 * - Answers the embedded-browser signal the site guards its captcha with: the UA it is given has no
 *   `wv` token, and `navigator.webdriver` is answered the way a plain browser answers it.
 * - Reports a compact page state on every poll — the page, the Turnstile script, Cloudflare frames,
 *   captcha, clearance, and what the embedded-browser signals really said — so a stuck verification
 *   says what it is waiting for instead of looking frozen.
 */
private const val JS_OBSERVER_INJECTION = """
    (function () {
        try {
            if (!window.outerWidth) Object.defineProperty(window, 'outerWidth', { get: () => window.innerWidth || 1080 });
            if (!window.outerHeight) Object.defineProperty(window, 'outerHeight', { get: () => window.innerHeight || 1920 });
        } catch (e) {}

        // The site renders its captcha only when it believes it is not an embedded browser:
        // `/webview|wv/i.test(navigator.userAgent) || navigator.webdriver` guards the widget, so a
        // WebView that reports the automation flag never gets one. Our UA carries no such token, and
        // a plain (non-debuggable) WebView answers false already; answer it that way regardless and
        // remember what it really said, so the report can name the guard as the reason.
        try {
            if (window.__schaleWebdriver === undefined) window.__schaleWebdriver = !!window.navigator.webdriver;
        } catch (e) {}
        try {
            Object.defineProperty(Navigator.prototype, 'webdriver', {
                configurable: true,
                get: function () { return false; },
            });
        } catch (e) {}

        function report(value) {
            try {
                const token = value === null || value === undefined ? '' : String(value).trim();
                if (token.length >= 8 && window.SchaleBridge) window.SchaleBridge.onClearanceToken(token);
            } catch (e) {}
        }

        // The answer Cloudflare's widget produced, captured through every path that can see it.
        // The site renders the widget as soon as its script publishes `window.turnstile`, so the
        // assignment itself is intercepted below: re-wrapping `render` on a timer loses that race,
        // and losing it means the app never learns the answer at all.
        function reportAnswer(answer) {
            try {
                const text = answer === null || answer === undefined ? '' : String(answer).trim();
                if (!text || text === window.__schaleLastAnswer) return;
                window.__schaleLastAnswer = text;
                if (window.SchaleBridge) window.SchaleBridge.onCaptchaAnswer(text);
            } catch (e) {}
        }

        function wrapTurnstile(turnstile) {
            try {
                if (!turnstile || typeof turnstile.render !== 'function' || turnstile.__schaleWrapped === true) return;
                const originalRender = turnstile.render;
                turnstile.render = function (element, options) {
                    try {
                        if (options && typeof options.callback === 'function') {
                            const callback = options.callback;
                            options.callback = function (answer) {
                                try { reportAnswer(answer); } catch (e) {}
                                return callback.apply(this, arguments);
                            };
                        }
                    } catch (e) {}
                    const widget = originalRender.apply(this, arguments);
                    try { window.__schaleWidgetId = widget; } catch (e) {}
                    return widget;
                };
                turnstile.__schaleWrapped = true;
            } catch (e) {}
        }

        // The mint endpoint answers 201 with the clearance, but the site only stores what a 201
        // gave it and hides its captcha modal whether or not that succeeded, so a refused mint is
        // invisible. Report every answer so the app can say what the server actually replied.
        function reportClearanceCall(method, status, body) {
            try {
                const text = body === null || body === undefined ? '' : String(body);
                if (window.SchaleBridge) window.SchaleBridge.onClearanceCall(String(method || ''), String(status), text.slice(0, 400));
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

            // Do NOT redefine `window.turnstile` here. Creating it as an accessor made the widget
            // disappear in the app's WebView — `'turnstile' in window` was already true before the
            // loader ran, so the page never published the object. The page owns that global; the
            // answer is read back by the watch below without touching anything the page sets.

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
                    try {
                        this.__schaleUrl = url;
                        this.__schaleMethod = method;
                    } catch (e) {}
                    return originalOpen.apply(this, arguments);
                };
                const originalSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function () {
                    try {
                        const url = this.__schaleUrl;
                        // The site sends the single-use answer as `Authorization: Bearer <answer>`
                        // on its own POST, so that header is a capture path that cannot be missed.
                        if (isClearanceCall(url)) {
                            try {
                                const auth = this.getRequestHeader ? this.getRequestHeader('Authorization') : null;
                                if (auth) reportAnswer(String(auth).replace(/^Bearer\s+/i, ''));
                            } catch (e) {}
                        }
                        this.addEventListener('load', function () {
                            try {
                                if (isClearanceCall(url)) {
                                    // A 201 body IS the clearance, which is reported on its own path
                                    // and must not be echoed into the status line.
                                    reportClearanceCall(this.__schaleMethod, this.status, this.status === 201 ? '' : this.responseText);
                                    if (this.status === 201) report(this.responseText);
                                } else if (this.status >= 200 && this.status < 300) {
                                    report(crtFrom(url));
                                }
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
                        try {
                            if (isClearanceCall(url) && init && init.headers) {
                                const headers = init.headers;
                                const raw = typeof headers.get === 'function'
                                    ? headers.get('Authorization')
                                    : headers.Authorization || headers.authorization;
                                if (raw) reportAnswer(String(raw).replace(/^Bearer\s+/i, ''));
                            }
                        } catch (e) {}
                        const result = originalFetch.apply(this, arguments);
                        try {
                            result.then(function (response) {
                                try {
                                    if (!response) return;
                                    if (isClearanceCall(url)) {
                                        response.clone().text().then(function (text) {
                                            reportClearanceCall(init && init.method, String(response.status), response.status === 201 ? '' : text);
                                            if (response.status === 201) report(text);
                                        });
                                    } else if (response.ok) {
                                        report(crtFrom(url));
                                    }
                                } catch (e) {}
                            });
                        } catch (e) {}
                        return result;
                    };
                }
            } catch (e) {}
        }

        var turnstileTries = 0;
        (function pollTurnstile() {
            try { wrapTurnstile(window.turnstile); } catch (e) {}
            // Best effort: a self-solving widget can fire its callback the moment it is rendered,
            // before this poll reaches the object. The answer does not depend on the wrapper — the
            // watch below reads it back from Turnstile itself and from the hidden field.
            if (++turnstileTries < 600) setTimeout(pollTurnstile, 50);
        })();

        // Turnstile also exposes the answer it produced and writes it into a hidden input, so
        // neither of these depends on the site's own callback firing at all.
        function answerFromWidget() {
            try {
                if (window.turnstile && typeof window.turnstile.getResponse === 'function') {
                    return window.turnstile.getResponse(window.__schaleWidgetId) || '';
                }
            } catch (e) {}
            return '';
        }

        function answerFromInput() {
            try {
                const input = document.querySelector('input[name="cf-turnstile-response"]');
                return input && input.value ? input.value : '';
            } catch (e) { return ''; }
        }

        var answerWatches = 0;
        (function watchAnswer() {
            try {
                const answer = answerFromWidget() || answerFromInput();
                if (answer) reportAnswer(answer);
            } catch (e) {}
            if (++answerWatches < 3000) setTimeout(watchAnswer, 200);
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

        // Everything the screen needs to say what is happening, in one poll: the stored clearance,
        // the captcha answer, how many Cloudflare frames the page has and whether the Turnstile
        // field exists. Fields are joined with the ASCII unit separator (31).
        window.__schaleReadState = function () {
            var clearance = '';
            try { clearance = window.localStorage.getItem('clearance') || ''; } catch (e) {}
            var answer = window.__schaleLastAnswer || answerFromWidget() || answerFromInput() || '';
            if (answer) reportAnswer(answer);
            var frames = 0;
            try {
                frames = document.querySelectorAll('iframe[src*="challenges.cloudflare.com"]').length;
            } catch (e) {}
            var field = 0;
            try {
                if (document.querySelector('input[name="cf-turnstile-response"]')) field = 1;
            } catch (e) {}
            // Page identity and the two facts that decide whether the site renders a captcha at
            // all: the Turnstile script's presence and the automation/embedded-browser signals.
            var ready = 0;
            try { if (window.turnstile) ready = 1; } catch (e) {}
            var scripts = 0;
            try {
                scripts = document.querySelectorAll('script[src*="challenges.cloudflare.com"]').length;
            } catch (e) {}
            var where = '';
            var title = '';
            var cleanUa = 1;
            var webdriver = 0;
            try {
                where = window.location.host + window.location.pathname;
                title = (document.title || '').slice(0, 40);
                cleanUa = /webview|wv/i.test(String(window.navigator.userAgent)) ? 0 : 1;
                webdriver = window.__schaleWebdriver ? 1 : 0;
            } catch (e) {}
            return [clearance, answer, frames, field, ready, scripts, where, title, cleanUa, webdriver].join(String.fromCharCode(31));
        };
    })();
"""

private const val JS_READ_STATE = """
    (function () {
        try {
            if (window.__schaleReadState) return window.__schaleReadState();
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

/**
 * What the verification page reports on every poll: the clearance it has stored, the captcha answer
 * it was given, how many Cloudflare frames it has and whether the Turnstile field exists at all. A
 * screen that looks frozen can then say which of those it is waiting for.
 */
private data class PageState(
    val clearance: String?,
    val answer: String?,
    val frames: Int,
    val field: Boolean,
    val turnstile: Boolean,
    val scripts: Int,
    val where: String,
    val title: String,
    val cleanUa: Boolean,
    val webdriver: Boolean,
) {
    fun describe(): String = buildString {
        append(if (where.isNotEmpty()) where else "página desconocida")
        if (title.isNotEmpty()) append(" \"").append(title).append("\"")
        append(" · ua=")
        append(if (cleanUa) "limpio" else "con \"wv\"")
        append(" · webdriver=")
        append(if (webdriver) "sí" else "no")
        append(" · turnstile=")
        append(if (turnstile) "presente" else "ausente")
        append(" · scripts CF=")
        append(scripts)
        append(" · frames CF=")
        append(frames)
        append(" · campo=")
        append(if (field) "sí" else "no")
        append(" · captcha=")
        append(if (answer != null) "resuelta" else "pendiente")
        append(" · clearance=")
        append(if (clearance != null) "guardada" else "ninguna")
    }
}

/** Field separator of the single string [JS_READ_STATE] returns: the ASCII unit separator. */
private const val PAGE_STATE_SEPARATOR = '\u001f'

/**
 * Decodes what [JS_READ_STATE] returned. `evaluateJavascript` answers with the JSON encoding of the
 * JS value, so the string is unwrapped from its quotes; a bare value (a page where the state reader
 * is missing and only the clearance comes back) still yields a usable state.
 */
private fun parsePageState(raw: String?): PageState? {
    val encoded = raw?.takeIf { it.isNotBlank() && it != "null" } ?: return null
    val decoded = try {
        encoded.parseAs<String>()
    } catch (e: Throwable) {
        encoded.removeSurrounding("\"")
    }
    // limit = 10 keeps empty trailing fields (an empty title must not shrink the list).
    val parts = decoded.split(PAGE_STATE_SEPARATOR, limit = 10)
    if (parts.size < 10) {
        return PageState(normalizeClearanceToken(decoded), null, 0, false, false, 0, "", "", true, false)
    }
    return PageState(
        clearance = normalizeClearanceToken(parts[0]),
        answer = normalizeClearanceToken(parts[1]),
        frames = parts[2].trim().toIntOrNull() ?: 0,
        field = parts[3].trim() == "1",
        turnstile = parts[4].trim() == "1",
        scripts = parts[5].trim().toIntOrNull() ?: 0,
        where = parts[6],
        title = parts[7],
        cleanUa = parts[8].trim() != "0",
        webdriver = parts[9].trim() == "1",
    )
}

class SchaleBridge(
    private val onClearance: (String?) -> Unit,
    private val onCaptchaAnswer: (String?) -> Unit,
    private val onClearanceCall: (String?, String?, String?) -> Unit,
) {
    @JavascriptInterface
    fun onClearanceToken(token: String?) {
        onClearance(token)
    }

    @JavascriptInterface
    fun onCaptchaAnswer(answer: String?) {
        onCaptchaAnswer(answer)
    }

    @JavascriptInterface
    fun onClearanceCall(method: String?, status: String?, body: String?) {
        onClearanceCall(method, status, body)
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
    // A stored token is not a working token: only the server can say whether it still counts, so the
    // screen reports that verdict instead of asserting that the verification is active.
    var checking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
                    text = "Verificación guardada",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "La app guarda un token de Schale Network (${existingToken!!.length} caracteres). Guardado no es lo mismo que aceptado: " +
                        "sólo el servidor decide si sigue valiendo, y esa respuesta es la que explica que el lector vuelva a pedir verificación " +
                        "cuando ya habías resuelto el captcha.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = {
                        if (!checking) {
                            checking = true
                            checkResult = null
                            scope.launch {
                                val status = SchaleEngine.validateClearanceToken(existingToken!!)
                                checkResult = when (status) {
                                    200 -> "El servidor de auth acepta el token guardado."
                                    null -> "No hubo respuesta del servidor de auth (conexión)."
                                    else ->
                                        "El servidor de auth respondió HTTP $status a la comprobación. " +
                                            "Si es 403 con el token que el sitio sí acepta, el servidor está rechazando las peticiones de la app: " +
                                            "míralo en Configuración > EH > Diagnóstico de Schale."
                                }
                                checking = false
                            }
                        }
                    },
                    enabled = !checking,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text(text = if (checking) "Comprobando…" else "Comprobar contra el servidor")
                }
                checkResult?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                TextButton(
                    onClick = { navigator.popBackStack() },
                    modifier = Modifier.padding(top = 8.dp),
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

@Composable
context(activity: MainActivity)
private fun VerificationWebView(
    paddingValues: PaddingValues,
    existingToken: String?,
    onDone: () -> Unit,
) {
    val state = rememberWebViewState(url = EhUrl.HOST_SCHALE)
    var captured by remember { mutableStateOf<String?>(null) }
    var captchaSeq by remember { mutableStateOf(0) }
    // The answer the page was given, kept so the app can mint the clearance itself when the site's
    // own attempt produces nothing, and the outcome of every mint call the page made.
    var captchaAnswer by remember { mutableStateOf<String?>(null) }
    var mintReport by remember { mutableStateOf<String?>(null) }
    // A one-line answer to "is this screen still doing anything?", refreshed by every poll.
    var liveState by remember { mutableStateOf<String?>(null) }
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

    // Poll the page every 500 ms for three things: the clearance the site stores, the captcha
    // answer, and whether the widget rendered. The site's own JS mints and stores the clearance
    // after Turnstile solves — we read what the site wrote. The answer is read here as well because
    // it can arrive through any of the page-side capture paths; the app only mints with it when the
    // site's own POST produced nothing (the answer is single-use).
    LaunchedEffect(Unit) {
        var ticks = 0
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            if (captured != null) break
            ticks++
            if (ticks == SLOW_HINT_TICKS && captchaAnswer == null) {
                statusText = "Resuelve el captcha de la página para continuar."
            }
            val webView = state.webView ?: continue
            webView.evaluateJavascript(JS_READ_STATE) { result ->
                val page = parsePageState(result) ?: return@evaluateJavascript
                liveState = page.describe()
                // Only accept a token that differs from the one already stored, so that simply
                // loading the page (which echoes back the injected token) doesn't trigger a
                // redundant save when the user is just re-checking a still-valid token.
                val token = page.clearance
                if (token != null && token != existingToken) acceptToken(token)
                val answer = page.answer
                if (answer != null && answer != captchaAnswer) {
                    captchaAnswer = answer
                    captchaSeq++
                }
            }
        }
    }

    // After the captcha is solved, give the site up to SITE_MINT_TIMEOUT_MS to write a NEW
    // clearance to localStorage. When nothing new appears, its own POST did not produce one — the
    // site hides that: it stores only what a 201 returned and closes the modal either way. Mint
    // with the same single-use answer here instead, over the app's own connection, and report the
    // answer so the reason is visible instead of silent.
    LaunchedEffect(captchaSeq) {
        if (captchaSeq == 0) return@LaunchedEffect
        statusText = "Captcha resuelto. Esperando que el sitio complete la verificación…"
        var waited = 0L
        while (isActive && captured == null && waited < SITE_MINT_TIMEOUT_MS) {
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        if (captured != null || !isActive) return@LaunchedEffect
        val answer = captchaAnswer
        if (answer != null) {
            statusText = "El sitio no completó la verificación; probando desde la app…"
            val result = SchaleEngine.mintClearance(answer)
            mintReport = buildString {
                append("Mint desde la app: POST /clearance → HTTP ${result.httpStatus ?: "sin respuesta"}")
                result.retryAfterSeconds?.let { append(" (Retry-After ${it}s)") }
                // Only a failed mint has a body worth showing; a successful one is the clearance.
                if (result.token == null) {
                    result.body?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" · ${it.take(200)}") }
                }
            }
            val minted = result.token
            if (minted != null) {
                // Hand it to the site as well: its own reader waits for a clearance forever when
                // localStorage has none, which is why the reader never loads inside the WebView.
                state.webView?.evaluateJavascript(jsSeedClearance(minted), null)
                acceptToken(minted)
                return@LaunchedEffect
            }
            if (result.isRateLimited) {
                // Asking for another captcha here would burn another attempt against the same cap.
                statusText = "El servidor de verificación está limitando los intentos desde esta conexión" +
                    (result.retryAfterSeconds?.let { " (espera ${it}s)" } ?: "") +
                    ". Espera un poco antes de volver a intentarlo."
                return@LaunchedEffect
            }
        }
        statusText = "No se pudo completar la verificación ni desde el sitio ni desde la app. Resuelve el captcha otra vez."
        state.webView?.evaluateJavascript(JS_FRESH_CAPTCHA, null)
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
                                // Keep the answer and start the clock; the site POSTs it first, and
                                // the app only mints with it if the site's own attempt comes to
                                // nothing (a second use of a spent answer is refused by the server).
                                if (!answer.isNullOrBlank() && answer != captchaAnswer) {
                                    captchaAnswer = answer
                                    captchaSeq++
                                }
                            }
                        },
                        onClearanceCall = { method, status, body ->
                            webView.post {
                                mintReport = buildString {
                                    append("El sitio llamó a /clearance: ${method ?: "?"} → HTTP ${status ?: "?"}")
                                    body?.trim()?.takeIf { it.isNotEmpty() }?.let { append(" · ${it.take(200)}") }
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
        mintReport?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        liveState?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        TextButton(
            onClick = { state.webView?.evaluateJavascript(JS_RELOAD_PAGE, null) },
            modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
        ) {
            Text(text = "Recargar página")
        }
    }
}
