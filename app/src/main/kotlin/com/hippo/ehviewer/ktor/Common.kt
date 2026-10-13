package com.hippo.ehviewer.ktor

import androidx.webkit.WebViewCompat
import com.ehviewer.core.network.EhCookieStore
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.userAgent
import io.ktor.util.appendIfNameAbsent
import splitties.init.appCtx

// It's safe to assume the WebView package will always be present as we require CookieManager anyway
private val WebViewVersion = WebViewCompat.getCurrentWebViewPackage(appCtx)!!.versionName!!.substringBefore('.')

val CHROME_MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$WebViewVersion.0.0.0 Mobile Safari/537.36"
private val CHROME_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$WebViewVersion.0.0.0 Safari/537.36"
private const val CHROME_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
private const val CHROME_ACCEPT_LANGUAGE = "en-US,en;q=0.9"

/** The brand list the WebView advertises through `navigator.userAgentData`, in the same order. */
private val CHROME_CLIENT_HINT_BRANDS = "\"Chromium\";v=\"$WebViewVersion\", \"Not=A?Brand\";v=\"24\", \"Google Chrome\";v=\"$WebViewVersion\""

/** The User-Agent the app sends right now, resolved exactly like [UserAgent] resolves it. */
val currentUserAgent: String
    get() = if (Settings.desktopSite.value && Settings.gallerySite.value != EhUrl.SITE_SCHALE) CHROME_USER_AGENT else CHROME_MOBILE_USER_AGENT

/** Hosts whose requests must be indistinguishable from the page's own XHRs (see [SchaleXhrHeaders]). */
private val SCHALE_XHR_HOSTS = setOf("api.schale.network", "auth.schale.network")

fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureCommon(redirect: Boolean = true) = apply {
    install(HttpCookies) {
        storage = EhCookieStore
    }
    install(HttpTimeout) {
        reset()
        requestTimeoutMillis = 10_000
    }
    install(UserAgent)
    install(SchaleXhrHeaders)
    defaultRequest {
        headers.appendIfNameAbsent(HttpHeaders.Accept, CHROME_ACCEPT)
        header(HttpHeaders.AcceptLanguage, CHROME_ACCEPT_LANGUAGE)
    }
    followRedirects = redirect
}

private val UserAgent = createClientPlugin("UserAgent") {
    onRequest { request, _ ->
        // Schale Network validates its clearance token against the exact User-Agent that solved
        // the Cloudflare challenge, so always use the WebView's mobile UA there.
        request.userAgent(currentUserAgent)
    }
}

/**
 * Makes Schale Network requests look exactly like the ones the site's own page makes.
 *
 * The site's edge decides whether a request is a browser by comparing its headers with what a
 * Chrome tab would send, and the page reaches the API with `fetch`/`XHR` — a cross-site request
 * that carries `Accept: */*`, the Chromium client hints and the `Sec-Fetch-*` metadata. A ktor
 * request claiming to be Chrome while sending a navigation `Accept` and no client hints reads as a
 * scripted client, which is met with a challenge the reader can only report as a rejected token.
 * This mirrors the page's own request shape so both clients are judged the same way.
 */
private val SchaleXhrHeaders = createClientPlugin("SchaleXhrHeaders") {
    onRequest { request, _ ->
        if (request.url.host !in SCHALE_XHR_HOSTS) return@onRequest
        // `fetch`/`XHR` default, and what the page itself sends: not a document navigation.
        request.headers.remove(HttpHeaders.Accept)
        request.header(HttpHeaders.Accept, "*/*")
        request.header("sec-ch-ua", CHROME_CLIENT_HINT_BRANDS)
        request.header("sec-ch-ua-mobile", "?1")
        request.header("sec-ch-ua-platform", "\"Android\"")
        // Cross-site subresource request: niyaniya.moe calling api/auth.schale.network.
        request.header("Sec-Fetch-Site", "cross-site")
        request.header("Sec-Fetch-Mode", "cors")
        request.header("Sec-Fetch-Dest", "empty")
    }
}

fun HttpTimeoutConfig.reset() = apply {
    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    connectTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
}
