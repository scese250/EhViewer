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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.tarsin.tip

private const val POLL_INTERVAL_MS = 500L
private const val SLOW_HINT_TICKS = 40

/**
 * The clearance check never has to block the capture: the token is saved as soon as the site writes
 * it and this scope only logs whether the auth server accepts it.
 */
private val verificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Injected on every main-frame page load.
 * - Keeps the Turnstile widget renderable on WebViews that report no outer size.
 * - Mirrors localStorage.setItem('clearance', ...) into the app bridge as soon as the site stores
 *   the token. It never deletes an existing token; a fresh challenge is only forced on demand.
 */
private const val JS_OBSERVER_INJECTION = """
    (function() {
        if (!window.outerWidth) Object.defineProperty(window, 'outerWidth', { get: () => window.innerWidth || 1080 });
        if (!window.outerHeight) Object.defineProperty(window, 'outerHeight', { get: () => window.innerHeight || 1920 });

        if (window.__schaleObserverInstalled) return;
        window.__schaleObserverInstalled = true;

        try {
            const origSetItem = Storage.prototype.setItem;
            Storage.prototype.setItem = function(key, val) {
                origSetItem.apply(this, arguments);
                if (key === 'clearance' && val && val.length >= 10 && val !== 'null' && val !== '{}' && window.SchaleBridge) {
                    window.SchaleBridge.onClearanceToken(String(val));
                }
            };
        } catch(e) {}
    })();
"""

private const val JS_READ_CLEARANCE = """
    (function() {
        try { return window.localStorage.getItem('clearance') || ''; } catch(e) { return ''; }
    })();
"""

private const val JS_REFRESH_CLEARANCE = """
    (function() {
        try { window.localStorage.removeItem('clearance'); } catch(e) {}
        window.location.reload();
    })();
"""

private fun normalizeClearanceToken(raw: String?): String? {
    val token = raw?.trim()?.removeSurrounding("\"")?.trim()
    if (token.isNullOrEmpty()) return null
    if (token.equals("null", ignoreCase = true) || token.equals("undefined", ignoreCase = true) || token == "{}") return null
    return token
}

class SchaleBridge(private val onToken: (String?) -> Unit) {
    @JavascriptInterface
    fun onClearanceToken(token: String?) {
        onToken(token)
    }
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.SchaleClearanceScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val state = rememberWebViewState(url = EhUrl.HOST_SCHALE)
    val tokenHandled = remember { AtomicBoolean(false) }
    var pendingToken by remember { mutableStateOf<String?>(null) }
    var statusText by remember { mutableStateOf("Esperando a que resuelvas el captcha…") }
    var manualToken by remember { mutableStateOf("") }

    /** @return true when the value looked like a clearance token and was handed to the save step. */
    fun acceptToken(raw: String?): Boolean {
        val token = normalizeClearanceToken(raw) ?: return false
        if (tokenHandled.compareAndSet(false, true)) {
            pendingToken = token
        }
        return true
    }

    // The site stores the clearance in localStorage right after Turnstile resolves. Polling it is
    // more reliable than the injected setItem hook, which is not guaranteed to be installed before
    // the site writes the value (or to survive a late page load).
    LaunchedEffect(Unit) {
        var ticks = 0
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            if (pendingToken != null) break
            ticks++
            if (ticks == SLOW_HINT_TICKS) {
                statusText = "Aún no se detectó el token. Si el captcha ya se resolvió, pulsa ⟳ o pega el token abajo."
            }
            val webView = state.webView ?: continue
            webView.evaluateJavascript(JS_READ_CLEARANCE) { result ->
                acceptToken(result)
            }
        }
    }

    LaunchedEffect(pendingToken) {
        val token = pendingToken ?: return@LaunchedEffect
        Settings.schaleClearanceToken.value = token
        EhCookieStore.flush()
        statusText = "Token guardado (len=${token.length})"
        tip("Token de Schale guardado: ${token.take(8)}…")
        navigator.popBackStack()
        // Never blocks the save; the server check is reported through logcat only.
        verificationScope.launch {
            val httpStatus = SchaleEngine.validateClearanceToken(token)
            logcat("SchaleVerification") { "Clearance ${token.take(8)}… check -> ${httpStatus ?: "sin respuesta"}" }
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
                    statusText = "Página cargada. Resuelve el captcha si no lo has hecho todavía."
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
                actions = {
                    IconButton(
                        onClick = {
                            tokenHandled.set(false)
                            pendingToken = null
                            Settings.schaleClearanceToken.value = null
                            statusText = "Limpiando el token y recargando…"
                            state.webView?.evaluateJavascript(JS_REFRESH_CLEARANCE, null)
                        },
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    }
                },
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
                        SchaleBridge { token -> webView.post { acceptToken(token) } },
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = manualToken,
                    onValueChange = { manualToken = it },
                    label = { Text(text = "Token manual (localStorage.clearance)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = {
                        tokenHandled.set(false)
                        statusText = if (acceptToken(manualToken)) {
                            "Token manual aceptado. Guardando…"
                        } else {
                            "Token manual inválido: pégalo tal cual aparece en localStorage.clearance."
                        }
                    },
                ) {
                    Text(text = "Guardar")
                }
            }
        }
    }
}
