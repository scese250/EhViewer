package com.hippo.ehviewer.ui.settings

import android.webkit.CookieManager
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ehviewer.core.i18n.R
import com.ehviewer.core.network.EhCookieStore
import com.google.accompanist.web.LoadingState
import com.google.accompanist.web.WebView
import com.google.accompanist.web.rememberWebViewState
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.ui.Screen
import com.hippo.ehviewer.ui.main.NavigationIcon
import com.hippo.ehviewer.util.setDefaultSettings
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import moe.tarsin.tip

@Destination<RootGraph>
@Composable
fun AnimatedVisibilityScope.SchaleClearanceScreen(navigator: DestinationsNavigator) = Screen(navigator) {
    val state = rememberWebViewState(url = EhUrl.HOST_SCHALE)

    fun handleClearanceToken(raw: String?) {
        val token = raw?.takeUnless { it == "null" || it.isBlank() }?.removeSurrounding("\"")
        if (!token.isNullOrBlank() && token != Settings.schaleClearanceToken.value) {
            Settings.schaleClearanceToken.value = token
            EhCookieStore.flush()
            tip(R.string.schale_verification_success)
            navigator.popBackStack()
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { !state.isLoading }.collect { hasFinished ->
            if (hasFinished) {
                state.webView?.evaluateJavascript("window.localStorage.getItem('clearance')") { raw ->
                    handleClearanceToken(raw)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1500)
            state.webView?.evaluateJavascript("window.localStorage.getItem('clearance')") { raw ->
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
                    IconButton(onClick = { state.webView?.reload() }) {
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
                modifier = Modifier.fillMaxSize(),
                onCreated = { webView ->
                    webView.setDefaultSettings()
                    with(webView.settings) {
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                },
            )
        }
    }
}
