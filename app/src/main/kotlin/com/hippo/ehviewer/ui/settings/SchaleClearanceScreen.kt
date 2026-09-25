package com.hippo.ehviewer.ui.settings

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView as AndroidWebView
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.tarsin.tip

private const val JS_OUTER_SIZE_FIX = """
    if (!window.outerWidth) Object.defineProperty(window, 'outerWidth', { get: () => window.innerWidth || 1080 });
    if (!window.outerHeight) Object.defineProperty(window, 'outerHeight', { get: () => window.innerHeight || 1920 });
"""

private const val JS_OBSERVER_INJECTION = """
    (function() {
        if (!window.outerWidth) Object.defineProperty(window, 'outerWidth', { get: () => window.innerWidth || 1080 });
        if (!window.outerHeight) Object.defineProperty(window, 'outerHeight', { get: () => window.innerHeight || 1920 });

        if (window.__schaleObserverInstalled) return;
        window.__schaleObserverInstalled = true;

        function notifyApp(token) {
            if (token && typeof token === 'string' && token.length >= 8 && token !== 'null' && token !== '{}') {
                if (window.SchaleBridge) {
                    window.SchaleBridge.onClearanceToken(token);
                }
            }
        }

        try {
            const origSetItem = Storage.prototype.setItem;
            Storage.prototype.setItem = function(key, val) {
                origSetItem.apply(this, arguments);
                if (key === 'clearance') {
                    notifyApp(val);
                }
            };
        } catch(e) {}

        setInterval(function() {
            try {
                const token = window.localStorage.getItem('clearance');
                notifyApp(token);
            } catch(e) {}
        }, 500);
    })();
"""

class SchaleBridge(private val onTokenValid: (String) -> Unit) {
    @JavascriptInterface
    fun onClearanceToken(token: String?) {
        if (!token.isNullOrBlank()) {
            onTokenValid(token)
        }
    }
}

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.SchaleClearanceScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val coroutineScope = rememberCoroutineScope()
    val state = rememberWebViewState(url = EhUrl.HOST_SCHALE)
    val tokenHandled = remember { AtomicBoolean(false) }

    fun handleClearanceToken(raw: String?) {
        val token = raw?.trim()?.removeSurrounding("\"") ?: return
        if (SchaleEngine.isValidClearanceToken(token)) {
            if (tokenHandled.compareAndSet(false, true)) {
                Settings.schaleClearanceToken.value = token
                EhCookieStore.flush()
                coroutineScope.launch(Dispatchers.Main) {
                    tip(R.string.schale_verification_success)
                    navigator.popBackStack()
                }
            }
        }
    }

    val client = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: AndroidWebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view.evaluateJavascript(JS_OUTER_SIZE_FIX, null)
                view.evaluateJavascript(JS_OBSERVER_INJECTION, null)
            }

            override fun onPageFinished(view: AndroidWebView, url: String?) {
                super.onPageFinished(view, url)
                view.evaluateJavascript(JS_OUTER_SIZE_FIX, null)
                view.evaluateJavascript(JS_OBSERVER_INJECTION, null)
            }
        }
    }

    LaunchedEffect(Unit) {
        WebStorage.getInstance().deleteAllData()
        state.webView?.evaluateJavascript(
            "try { window.localStorage.removeItem('clearance'); } catch(e) {}",
            null,
        )
        while (isActive && !tokenHandled.get()) {
            delay(500)
            state.webView?.evaluateJavascript(
                "(function() { try { return window.localStorage.getItem('clearance') || ''; } catch(e) { return ''; } })()",
            ) { raw ->
                handleClearanceToken(raw)
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
                            state.webView?.evaluateJavascript(
                                "try { window.localStorage.removeItem('clearance'); window.location.reload(); } catch(e) {}",
                                null,
                            )
                        },
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                    }
                    IconButton(
                        onClick = {
                            state.webView?.evaluateJavascript(
                                "(function() { try { return window.localStorage.getItem('clearance') || ''; } catch(e) { return ''; } })()",
                            ) { raw ->
                                val token = raw?.trim()?.removeSurrounding("\"")
                                if (SchaleEngine.isValidClearanceToken(token)) {
                                    handleClearanceToken(raw)
                                } else {
                                    coroutineScope.launch(Dispatchers.Main) {
                                        tip(R.string.schale_verification_not_verified)
                                    }
                                }
                            }
                        },
                    ) {
                        Icon(imageVector = Icons.Default.Check, contentDescription = null)
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
                modifier = Modifier.fillMaxSize(),
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
                        SchaleBridge { token -> handleClearanceToken(token) },
                        "SchaleBridge",
                    )
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                },
            )
        }
    }
}
