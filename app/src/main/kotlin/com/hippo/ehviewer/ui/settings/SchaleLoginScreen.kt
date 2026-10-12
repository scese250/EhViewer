package com.hippo.ehviewer.ui.settings

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView as AndroidWebView
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ehviewer.core.network.EhCookieStore
import com.ehviewer.core.util.launchIO
import com.ehviewer.core.util.logcat
import com.google.accompanist.web.AccompanistWebViewClient
import com.google.accompanist.web.LoadingState
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.schale.SchaleEngine
import com.hippo.ehviewer.collectAsState
import com.hippo.ehviewer.ktor.CHROME_MOBILE_USER_AGENT
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.util.setDefaultSettings
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.tarsin.tip
import org.json.JSONTokener

private const val POLL_INTERVAL_MS = 1000L

/**
 * The site renders its login modal (and the Turnstile widget inside it) only for real browser
 * engines, so the account is signed in on the real page and the app captures the session block the
 * site writes to localStorage["token"]: `{"session": …, "refresh": …, "expr": …}`.
 */
private const val JS_PAGE_SHIM = """
    (function() {
        if (!window.outerWidth) Object.defineProperty(window, 'outerWidth', { get: () => window.innerWidth || 1080 });
        if (!window.outerHeight) Object.defineProperty(window, 'outerHeight', { get: () => window.innerHeight || 1920 });
    })();
"""

private const val JS_READ_TOKEN = """
    (function() {
        try { return window.localStorage.getItem('token') || ''; } catch(e) { return ''; }
    })();
"""

private const val JS_READ_CLEARANCE = """
    (function() {
        try { return window.localStorage.getItem('clearance') || ''; } catch(e) { return ''; }
    })();
"""

/**
 * Drops the site's own session token and reloads. Cookies are deliberately left alone: the
 * Cloudflare clearance cookie is what keeps the site usable (and re-solving it is painful), so only
 * the localStorage entry that holds the account session is removed.
 */
private const val JS_CLEAR_TOKEN = """
    (function() {
        try { window.localStorage.removeItem('token'); } catch(e) {}
        window.location.reload();
    })();
"""

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.SchaleLoginScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val state = rememberWebViewState(url = EhUrl.HOST_SCHALE)
    // Collected rather than read through the engine so composition never blocks on DataStore.
    val session by Settings.schaleSession.collectAsState()
    val username by Settings.schaleUsername.collectAsState()
    var stored by remember { mutableStateOf(session) }
    val loggedIn = !session.isNullOrBlank()
    var statusText by remember {
        mutableStateOf(
            if (loggedIn) {
                "Sesión activa. Toca «Login» en la página si quieres cambiar de cuenta."
            } else {
                "Toca «Login» en la página, entra con tu cuenta y la app guardará la sesión sola."
            },
        )
    }
    var captured by remember { mutableStateOf<String?>(null) }

    // Refresh the account summary (username and the favorites map) whenever the screen opens.
    LaunchedEffect(Unit) {
        if (loggedIn) {
            runCatching { SchaleEngine.fetchAccount() }
                .onSuccess { account ->
                    statusText = account?.let { "Sesión activa como ${it.username}." }
                        ?: "La sesión guardada ya no es válida. Inicia sesión otra vez."
                }
        }
    }

    // The site stores the session right after a successful login (and reloads the page).
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(POLL_INTERVAL_MS)
            if (captured != null) continue
            val webView = state.webView ?: continue
            webView.evaluateJavascript(JS_READ_TOKEN) { result ->
                val raw = result?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                // Only pick up a session the app does not hold yet, so signing out and back in works
                // without reloading this screen.
                val pending = SchaleEngine.parseSessionToken(raw)
                if (pending != null && pending != stored) {
                    captured = raw
                }
            }
            // The site mints its own Cloudflare verification while the user is here, so keep the app's
            // copy in step with it; the reader then keeps working without a manual re-verification.
            webView.evaluateJavascript(JS_READ_CLEARANCE) { result ->
                val clearance = result?.let { runCatching { JSONTokener(it).nextValue() as? String }.getOrNull() }
                if (clearance != null && SchaleEngine.isValidClearanceToken(clearance)) {
                    launchIO {
                        val stored = Settings.schaleClearanceToken.value
                        if (clearance != stored && SchaleEngine.validateClearanceToken(clearance) == 200) {
                            Settings.schaleClearanceToken.value = clearance
                            EhCookieStore.flush()
                            logcat("SchaleLogin") { "clearance taken from the site (${clearance.length} chars)" }
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(captured) {
        val raw = captured ?: return@LaunchedEffect
        if (!SchaleEngine.saveSessionFromJson(raw)) {
            logcat("SchaleLogin") { "Unusable session payload (${raw.length} chars)" }
            statusText = "La página devolvió una sesión ilegible. Vuelve a intentarlo."
            captured = null
            return@LaunchedEffect
        }
        statusText = "Sesión guardada. Consultando la cuenta…"
        val account = runCatching { SchaleEngine.fetchAccount() }.getOrNull()
        statusText = account?.let { "Sesión iniciada como ${it.username} (${it.favorites.size} favoritos)." }
            ?: "Sesión guardada, pero la cuenta no respondió. Comprueba tu conexión."
        tip("Sesión de Schale Network guardada")
        stored = SchaleEngine.parseSessionToken(raw)
        captured = null
        navigator.popBackStack()
    }

    val client = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: AndroidWebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url?.startsWith(EhUrl.HOST_SCHALE) == true) {
                    statusText = "Cargando niyaniya.moe…"
                    view.evaluateJavascript(JS_PAGE_SHIM, null)
                }
            }

            override fun onPageFinished(view: AndroidWebView, url: String?) {
                super.onPageFinished(view, url)
                if (url?.startsWith(EhUrl.HOST_SCHALE) == true) {
                    // Only report loading while it is actually loading; the site answers slowly, so
                    // the hint matters once the page is up.
                    statusText = if (captured != null) {
                        "Sesión detectada, guardando…"
                    } else {
                        "Página lista. Toca «Login»: el sitio tarda en responder, espera sin salir."
                    }
                    view.evaluateJavascript(JS_PAGE_SHIM, null)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = username?.let { "Cuenta: $it" } ?: "Cuenta de Schale Network")
                },
                navigationIcon = { NavigationIcon() },
                actions = {
                    if (loggedIn) {
                        IconButton(
                            onClick = {
                                // Local sign-out only: the site's own session and cookies stay intact,
                                // so nothing has to be re-verified against Cloudflare.
                                SchaleEngine.clearSession()
                                statusText = "Sesión cerrada en la app. La cuenta del sitio sigue abierta."
                                state.webView?.evaluateJavascript(JS_CLEAR_TOKEN, null)
                            },
                        ) {
                            Icon(imageVector = Icons.Default.Logout, contentDescription = null)
                        }
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
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                },
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
