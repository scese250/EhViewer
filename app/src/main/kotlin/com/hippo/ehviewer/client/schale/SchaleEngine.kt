package com.hippo.ehviewer.client.schale

import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.model.GalleryCommentList
import com.ehviewer.core.model.GalleryDetail
import com.ehviewer.core.model.GalleryPreview
import com.ehviewer.core.model.GalleryTag
import com.ehviewer.core.model.GalleryTagGroup
import com.ehviewer.core.model.PowerStatus
import com.ehviewer.core.model.TagNamespace
import com.ehviewer.core.model.V1GalleryPreview
import com.ehviewer.core.model.VoteStatus
import com.ehviewer.core.util.logcat
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.ehRequest
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.client.parseAs
import com.hippo.ehviewer.client.parser.GalleryListResult
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

class SchaleTechnicalException(
    val stage: String,
    val url: String,
    val httpStatus: Int?,
    val httpDescription: String?,
    val token: String?,
    val responseBody: String?,
    val originalCause: Throwable? = null,
) : IOException(
    buildString {
        appendLine("[Error Técnico Schale Network]")
        appendLine("Etapa: $stage")
        if (httpStatus != null) {
            appendLine("HTTP Status: $httpStatus $httpDescription")
        }
        appendLine("URL: $url")
        appendLine("Token (len=${token?.length ?: 0}): $token")
        if (httpStatus == 403) {
            appendLine("Diagnóstico: El servidor de Schale rechazó el token (HTTP 403 Forbidden).")
            appendLine("Solución: El token expiró o es inválido. Ve a Configuración > EH > Verificación de Schale Network para resolver el captcha y renovarlo.")
        }
        if (!responseBody.isNullOrBlank()) {
            appendLine("Respuesta: ${responseBody.take(400)}")
        }
        if (originalCause != null) {
            appendLine("Causa: ${originalCause::class.simpleName}: ${originalCause.message}")
        }
    },
    originalCause,
)

/** Thrown when the Cloudflare clearance token is missing, expired or rejected by the server. */
class SchaleClearanceException(message: String) : IOException(message)

/** Thrown when an endpoint needs a Schale Network account session that is missing or expired. */
class SchaleAuthException(message: String) : IOException(message)

/**
 * The payload the site's own client keeps in localStorage["token"] after a successful login.
 * `expr` is the session expiry (a millisecond epoch); `next` (ignored here) is the refresh hint.
 */
@Serializable
private data class SchaleLocalSession(
    val session: String? = null,
    val refresh: String? = null,
    val expr: Long = 0L,
)

/** Some auth responses wrap the payload, so unwrap it when the flat parse finds no session. */
@Serializable
private data class SchaleLocalSessionEnvelope(
    val body: SchaleLocalSession? = null,
)

/** Page image URLs of a Schale gallery: chosen quality plus primary and fallback CDN variants. */
data class SchaleImageUrls(
    val quality: String,
    val primary: List<String>,
    val fallback: List<String>,
)

private const val TAG = "SchaleEngine"
private const val PAGE_SIZE = 40
private const val MAX_ATTEMPTS = 3

/** Rotate the session a little before its `expr` deadline instead of waiting for the first 403. */
private const val SESSION_REFRESH_MARGIN_MS = 10 * 60 * 1000L
private const val SCHALE_API_HOST = "api.schale.network"
private val PREFERRED_RESOLUTIONS = listOf("1280", "1600", "0", "980", "780")

private fun normalizeClearanceToken(raw: String?): String? {
    val token = raw?.trim()?.removeSurrounding("\"")?.trim()
    if (token.isNullOrEmpty()) return null
    if (token.equals("null", ignoreCase = true) || token.equals("undefined", ignoreCase = true) || token == "{}") return null
    return token
}

/**
 * Namespace prefixes the site's query language understands, taken from the live API (a term with an
 * unknown prefix is silently re-read as a `title` search, so the set matters).
 */
private val SCHALE_NAMESPACES = setOf(
    "tag", "artist", "group", "circle", "parody", "magazine", "character", "cosplayer",
    "uploader", "male", "female", "mixed", "language", "other", "reclass", "title",
)

/**
 * Values are matched exactly, so they must be anchored: `^value$`, quoted when they hold spaces,
 * `` `"^big penis$"` ``. This mirrors the site's own `String.prototype.wrap` (which also
 * lowercases; the server matches case-insensitively either way).
 */
private fun anchorTerm(value: String): String {
    val term = value.trim()
    if (term.isEmpty()) return term
    return if (term.any { it.isWhitespace() || it == '+' }) "\"^$term$\"".lowercase() else "^$term$".lowercase()
}

/**
 * The app's namespaces that Schale spells differently: EH's generic tags are Schale's `tag`, and
 * EH's `circle` is Schale's `group`. `location:`/`temp:` have no counterpart, so their tags are
 * searched as generic tags.
 */
private fun schaleNamespace(prefix: String): String = when (prefix) {
    "circle" -> "group"
    "other", "location", "temp" -> "tag"
    else -> prefix
}

/** True when a word filters a namespace (optionally negated). */
private fun startsNamespace(word: String): Boolean {
    val body = word.removePrefix("-").removePrefix("+")
    return body.indexOf(':') > 0
}

/**
 * Splits a query into terms, keeping `"quoted phrases"` together.
 *
 * A namespace term absorbs the plain words that follow it: the app sends tag names unquoted
 * (`tag:big penis`), which the API would otherwise read as two separate terms.
 */
private fun splitQuery(text: String): List<String> {
    val words = ArrayList<String>()
    val word = StringBuilder()
    var inQuotes = false
    for (ch in text) {
        when {
            ch == '"' -> {
                inQuotes = !inQuotes
                word.append(ch)
            }
            ch.isWhitespace() && !inQuotes -> {
                if (word.isNotEmpty()) {
                    words += word.toString()
                    word.clear()
                }
            }
            else -> word.append(ch)
        }
    }
    if (word.isNotEmpty()) words += word.toString()

    val terms = ArrayList<String>(words.size)
    for (current in words) {
        val previous = terms.lastOrNull()
        if (previous != null && startsNamespace(previous) && !startsNamespace(current) && !current.startsWith('"')) {
            terms[terms.lastIndex] = "$previous $current"
        } else {
            terms += current
        }
    }
    return terms
}

/** One term of the query language: `-` excludes, `ns:value` filters a namespace, `"…"` is a title. */
private fun normalizeTerm(raw: String): String {
    val term = raw.trim()
    if (term.isEmpty()) return term
    val excluded = term.startsWith('-')
    val body = term.removePrefix("-").removePrefix("+")
    if (body.isEmpty()) return body
    return if (excluded) "-${normalizeTermBody(body)}" else normalizeTermBody(body)
}

private fun normalizeTermBody(body: String): String {
    // A quoted phrase is what the app sends for an exact-title search (the detail screen's "similar"
    // button). The bare form is not valid for the API, so it always becomes a `title:` term.
    if (body.length > 2 && body.startsWith('"') && body.endsWith('"')) {
        return "title:${anchorTerm(body.substring(1, body.length - 1))}"
    }
    val colon = body.indexOf(':')
    // Plain text stays as typed: the server then matches it loosely against tag names.
    if (colon <= 0) return body
    val prefix = body.substring(0, colon).lowercase()
    // The app's own search box writes values pre-quoted (`uploader:"name"`), so unwrap first.
    val value = body.substring(colon + 1).removeSurrounding("\"")
    if (value.isBlank()) return body
    if (prefix !in SCHALE_NAMESPACES) {
        return "tag:${anchorTerm(value)}"
    }
    return "${schaleNamespace(prefix)}:${anchorTerm(value)}"
}

/**
 * Turns what the app's UI produced (tag names, `namespace:name` pairs, quoted titles, `-excluded`
 * terms) into the site's query syntax: `tag:big penis` becomes `tag:"^big penis$"`, which is what the
 * site's own tag links send. Plain words are left alone, so a plain search stays loose like the
 * site's search box.
 */
fun normalizeQuery(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty()) return text
    val terms = splitQuery(text).map(::normalizeTerm).filter { it.isNotBlank() }
    return if (terms.isEmpty()) text else terms.joinToString(" ")
}

/**
 * Outcome of exchanging a solved captcha for a clearance token.
 *
 * @param token the clearance when the server minted one
 * @param httpStatus the answer of the auth server; 429 means this connection used up its allowance
 * @param retryAfterSeconds the `Retry-After` the server sent, when it sent one
 */
data class ClearanceMintResult(
    val token: String?,
    val httpStatus: Int?,
    val retryAfterSeconds: Int?,
) {
    /** The auth server limits verifications per connection, so a 429 means "try again shortly". */
    val isRateLimited: Boolean
        get() = httpStatus == 429
}

/**
 * Engine for Schale Network (niyaniya.moe / api.schale.network) API calls.
 */
object SchaleEngine {

    /** Sort ids accepted by `GET /books?sort=` (the values the site itself uses). */
    object Sort {
        const val TITLE = 2
        const val PAGES = 3
        const val DATE = 4
        const val FAVORITED = 7
        const val VIEWS = 8
        const val FAVORITES = 9
    }

    private val _favoriteStates = MutableStateFlow<Map<Long, Boolean>>(emptyMap())

    /** Gallery id -> favorited, fed by list/detail responses and by favorite mutations. */
    val favoriteStates: StateFlow<Map<Long, Boolean>> = _favoriteStates.asStateFlow()

    fun sessionToken(): String? = normalizeClearanceToken(Settings.schaleSession.value)

    val isLoggedIn: Boolean get() = sessionToken() != null

    /**
     * Stores the session block the site keeps in localStorage["token"]:
     * `{"session": "…", "refresh": "…", "expr": <unix seconds>}`.
     *
     * @return true when a usable session was stored.
     */
    fun saveSessionFromJson(raw: String?): Boolean {
        val parsed = parseLocalSession(raw) ?: return false
        val session = normalizeClearanceToken(parsed.session) ?: return false
        val refresh = normalizeClearanceToken(parsed.refresh)
        Settings.schaleSession.value = session
        Settings.schaleSessionRefresh.value = refresh
        // The site keeps `expr` as a millisecond epoch; older payloads used seconds.
        Settings.schaleSessionExpiry.value = when {
            parsed.expr <= 0L -> 0L
            parsed.expr < 100_000_000_000L -> parsed.expr * 1000L
            else -> parsed.expr
        }
        logcat(TAG) { "session stored: session=${session.length} chars, refresh=${refresh?.length ?: 0} chars, expr=${parsed.expr}" }
        return true
    }

    /**
     * The session token inside the site's localStorage payload, or null when there is none.
     * Lets the login screen tell a session it already holds apart from a new one.
     */
    fun parseSessionToken(raw: String?): String? = parseLocalSession(raw)?.let { normalizeClearanceToken(it.session) }

    private fun parseLocalSession(raw: String?): SchaleLocalSession? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { text.parseAs<SchaleLocalSession>() }.getOrNull()
            ?: runCatching { text.parseAs<SchaleLocalSessionEnvelope>().body }.getOrNull()
    }

    fun clearSession() {
        Settings.schaleSession.value = null
        Settings.schaleSessionRefresh.value = null
        Settings.schaleSessionExpiry.value = 0L
        Settings.schaleUsername.value = null
        _favoriteStates.value = emptyMap()
    }

    /**
     * Account summary from `POST auth.schale.network/check`: username, role and the account's full
     * favorites map. The map is authoritative, so it replaces [favoriteStates] wholesale.
     *
     * @return the account, or null when there is no session or the server rejected it.
     */
    suspend fun fetchAccount(): SchaleAccount? {
        val session = sessionToken() ?: return null
        return try {
            val (status, body) = fetchRaw("${EhUrl.AUTH_SCHALE}/check", HttpMethod.Post, bearer = session)
            if (status != 200) {
                logcat(TAG) { "account check rejected (HTTP $status)" }
                null
            } else {
                val account = body.parseAs<SchaleAccount>()
                Settings.schaleUsername.value = account.username.takeIf { it.isNotEmpty() }
                _favoriteStates.value = account.favorites.mapNotNull { (id, value) ->
                    id.toLongOrNull()?.let { it to (value > 0) }
                }.toMap()
                logcat(TAG) { "account: '${account.username}' role=${account.role} favorites=${account.favorites.size}" }
                account.takeIf { it.isValid }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(TAG) { "account check failed: ${e.message}" }
            null
        }
    }

    /**
     * Signs out the way the site does: `POST auth.schale.network/logout?crt=…` with the refresh
     * token as Bearer credential (204 means done). The local session is dropped either way.
     */
    suspend fun logout() {
        val refresh = normalizeClearanceToken(Settings.schaleSessionRefresh.value)
        val crt = normalizeClearanceToken(Settings.schaleClearanceToken.value)
        if (refresh != null) {
            val url = "${EhUrl.AUTH_SCHALE}/logout" + (crt?.let { "?crt=$it" } ?: "")
            try {
                val (status, _) = fetchRaw(url, HttpMethod.Post, bearer = refresh)
                logcat(TAG) { "logout answered HTTP $status" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(TAG) { "logout failed: ${e.message}" }
            }
        }
        clearSession()
    }

    /**
     * Rotates the session with the refresh token the site stores
     * (`POST auth.schale.network/refresh`, which answers 201 with a fresh session block).
     *
     * @return true when a new session was stored.
     */
    suspend fun refreshSession(): Boolean {
        val refresh = normalizeClearanceToken(Settings.schaleSessionRefresh.value) ?: return false
        return try {
            val (status, body) = fetchRaw("${EhUrl.AUTH_SCHALE}/refresh", HttpMethod.Post, bearer = refresh)
            if (status in 200..299 && saveSessionFromJson(body)) {
                logcat(TAG) { "session refreshed (HTTP $status)" }
                true
            } else {
                logcat(TAG) { "session refresh rejected (HTTP $status)" }
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(TAG) { "session refresh failed: ${e.message}" }
            false
        }
    }

    /**
     * Fetch the manga list from Schale Network.
     *
     * @param page 1-indexed page number
     * @param keyword optional search in the site's own syntax (`tag:x`, `artist:x`, `-x` to exclude)
     * @param sort one of [Sort], or null for the site default (newest first)
     */
    suspend fun getSchaleGalleryList(page: Int = 1, keyword: String? = null, sort: Int? = null): GalleryListResult = fetchBooks(buildBooksUrl(page = page, keyword = keyword, sort = sort), "GET books (page=$page)")

    /**
     * Favorites of the signed-in account (same query parameters as the browse list). Needs both the
     * account session and a valid clearance token, because the endpoint is grouped with the
     * protected ones; the session is reported first so the user is pointed at the right setting.
     */
    suspend fun getSchaleFavorites(page: Int = 1, keyword: String? = null, sort: Int? = null): GalleryListResult {
        requireSessionToken()
        val crt = requireClearanceToken()
        return fetchBooks(buildFavoritesUrl(page = page, keyword = keyword, sort = sort, crt = crt), "GET books/favorites (page=$page)", authed = true)
    }

    suspend fun searchSchale(keyword: String, page: Int = 1, sort: Int? = null): GalleryListResult = fetchBooks(buildBooksUrl(page = page, keyword = keyword, sort = sort), "GET books search (page=$page)")

    suspend fun addFavorite(gid: Long, token: String) = mutateFavorite(gid, token, add = true)

    suspend fun removeFavorite(gid: Long, token: String) = mutateFavorite(gid, token, add = false)

    /**
     * Fetch detail and previews for a single Schale gallery entry.
     */
    suspend fun getSchaleGalleryDetail(id: Long, key: String): GalleryDetail {
        // Public endpoint — no crt token required
        val url = buildDetailUrl(id, key)
        // The session is optional here: it only adds the "favorites" flag for the signed-in account.
        val responseText = ehRequest(url, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
            applySessionAuth()
        }.executeSafely { resp ->
            checkPublicResponseStatus(resp)
            resp.bodyAsText()
        }
        val detail = responseText.parseAs<SchaleMangaDetail>()

        val thumbnails = detail.thumbnails
        val baseThumbUrl = thumbnails?.base?.trimEnd('/') ?: "https://$SCHALE_API_HOST"
        val thumbKey = thumbnails?.main?.let { "$baseThumbUrl/${it.path.trimStart('/')}" }
            ?: "https://$SCHALE_API_HOST/books/$id/$key/thumbnail"

        val previewList: List<GalleryPreview> = thumbnails?.entries?.mapIndexed { index, entry ->
            V1GalleryPreview(
                url = "$baseThumbUrl/${entry.path.trimStart('/')}",
                position = index,
                pToken = entry.path,
            )
        }.orEmpty()

        val tagGroups: List<GalleryTagGroup> = detail.tags.groupBy { it.namespace }.map { (nsId, tags) ->
            // Namespaces as published by the site: 0 Tag, 1 Artist, 2 Circle, 3 Parody, 4 Magazine,
            // 5 Character, 6 Cosplayer, 7 Uploader, 8 Male, 9 Female, 10 Mixed, 11 Language,
            // 12 Other, 13 Reclass. Only the ones without an EH counterpart fall back to Other.
            val ns = when (nsId) {
                1 -> TagNamespace.Artist
                2 -> TagNamespace.Group
                3 -> TagNamespace.Parody
                5 -> TagNamespace.Character
                6 -> TagNamespace.Cosplayer
                8 -> TagNamespace.Male
                9 -> TagNamespace.Female
                10 -> TagNamespace.Mixed
                11 -> TagNamespace.Language
                13 -> TagNamespace.Reclass
                else -> TagNamespace.Other
            }
            GalleryTagGroup(
                namespace = ns,
                tags = tags.map { GalleryTag(it.name, PowerStatus.Solid, VoteStatus.None) },
            )
        }

        val pageCount = if (previewList.isNotEmpty()) previewList.size else (detail.thumbnails?.entries?.size ?: 0)
        val baseInfo = BaseGalleryInfo(
            gid = id,
            token = key,
            title = detail.title,
            thumbKey = thumbKey,
            category = 0x4, // MANGA
            pages = pageCount,
            rating = -1f,
            posted = "",
        )

        // Only a signed-in request reports the flag; "absent" must not clear a known favorite.
        detail.favorites?.let { markFavorite(id, it) }
        return GalleryDetail(
            galleryInfo = baseInfo,
            tagGroups = tagGroups,
            comments = GalleryCommentList(emptyList(), false),
            previewList = previewList,
        )
    }

    /**
     * Fetch full-quality image URLs for a Schale gallery in page order (used by reader/spider).
     * Requires a valid Cloudflare clearance token and throws [SchaleClearanceException] when the
     * token is missing, expired or rejected; never falls back to lower quality.
     */
    suspend fun getSchaleImageUrls(id: Long, key: String): SchaleImageUrls = getProtectedImageUrls(id, key, requireClearanceToken())

    /**
     * Runs the same check the website uses for a stored clearance:
     * GET https://auth.schale.network/clearance with the token as Bearer credential.
     *
     * @return the HTTP status, or null when the request itself failed.
     */
    suspend fun validateClearanceToken(token: String): Int? = try {
        ehRequest("${EhUrl.AUTH_SCHALE}/clearance", EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
            method = HttpMethod.Get
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.Accept, "*/*")
        }.executeSafely { resp -> resp.status.value }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logcat(TAG) { "Clearance check failed: ${e.message}" }
        null
    }

    /**
     * Exchanges a solved captcha for a clearance, exactly the way the site does:
     * `POST auth.schale.network/clearance` with the captcha answer as bearer credential, which
     * answers 201 with the token in its body.
     *
     * The auth server allows only a handful of verifications per connection, so the status is
     * returned instead of thrown: the caller can wait out a 429 and try again with the same answer.
     */
    suspend fun mintClearance(captchaAnswer: String): ClearanceMintResult {
        val credential = normalizeClearanceToken(captchaAnswer)
            ?: return ClearanceMintResult(null, null, null)
        return try {
            var status: Int? = null
            var retryAfter: Int? = null
            val body = ehRequest("${EhUrl.AUTH_SCHALE}/clearance", EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
                method = HttpMethod.Post
                header(HttpHeaders.Authorization, "Bearer $credential")
                header(HttpHeaders.Accept, "*/*")
            }.executeSafely { resp ->
                status = resp.status.value
                retryAfter = resp.headers["Retry-After"]?.toIntOrNull()
                resp.bodyAsText()
            }
            val token = clearanceFromBody(body)
            if (status == 201 && token != null && isValidClearanceToken(token)) {
                Settings.schaleClearanceToken.value = token
                logcat(TAG) { "clearance minted from the captcha answer (len=${token.length})" }
                ClearanceMintResult(token, status, retryAfter)
            } else {
                logcat(TAG) { "clearance mint failed (HTTP ${status ?: "sin respuesta"}): ${body.take(160)}" }
                ClearanceMintResult(null, status, retryAfter)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(TAG) { "clearance mint failed: ${e.message}" }
            ClearanceMintResult(null, null, null)
        }
    }

    /** The mint endpoint answers with the token itself; a JSON wrapper around it is tolerated. */
    private fun clearanceFromBody(body: String?): String? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.startsWith("{")) return normalizeClearanceToken(text)
        val wrapped = Regex("\"(?:clearance|crt|token)\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
        return normalizeClearanceToken(wrapped)
    }

    /**
     * Full diagnostic: auth check, public list, protected detail POST and image data GET.
     * Reports the whole reader pipeline so a failure can be pinpointed in one run.
     */
    suspend fun testToken(token: String?): String {
        val credential = normalizeClearanceToken(token)
        if (credential == null || !isValidClearanceToken(credential)) {
            return "[Diagnóstico Schale]\nToken inválido o vacío en Settings: '$token'"
        }
        val report = StringBuilder()
        report.appendLine("[Diagnóstico Schale Network]")
        report.appendLine("Token (len=${credential.length}): ${credential.take(12)}…")
        report.appendLine("User-Agent: móvil (Chrome del WebView)\n")

        // Test 1: Auth check
        report.appendLine("--- 1. Servidor de Auth (${EhUrl.AUTH_SCHALE}/clearance) ---")
        try {
            val status = validateClearanceToken(credential)
            report.appendLine("HTTP Status: ${status ?: "sin respuesta"}")
            report.appendLine(if (status == 200) "Resultado: ÉXITO (Auth reconoció el token)" else "Resultado: FALLÓ")
        } catch (e: Throwable) {
            report.appendLine("Error al conectar con Auth: ${e::class.simpleName}: ${e.message}")
        }

        // Test 2: Public list
        report.appendLine("\n--- 2. Lista pública (GET /books) ---")
        var galleryId = 27643L
        var galleryKey = "f65c885cfa75"
        try {
            val listText = ehRequest(buildBooksUrl(page = 1, limit = 1), EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE)
                .executeSafely { resp ->
                    checkPublicResponseStatus(resp)
                    resp.bodyAsText()
                }
            val books = listText.parseAs<SchaleBooks>()
            val first = books.entries.firstOrNull()
            if (first != null) {
                galleryId = first.id.toLong()
                galleryKey = first.key
                report.appendLine("ÉXITO — total=${books.total}, primera: ${first.title}")
            } else {
                report.appendLine("FALLÓ — respuesta sin entradas")
            }
        } catch (e: Throwable) {
            report.appendLine("Error al conectar con la lista: ${e::class.simpleName}: ${e.message}")
        }
        report.appendLine("Galería de prueba: $galleryId/$galleryKey")

        // Test 3: Protected detail POST + Test 4: image data GET
        report.appendLine("\n--- 3. POST books/detail?crt= ---")
        var pageCount = -1
        try {
            val detailUrl = buildDetailUrlWithCrt(galleryId, galleryKey, credential)
            val (detailStatus, detailBody) = fetchRaw(detailUrl, HttpMethod.Post)
            report.appendLine("HTTP Status: $detailStatus")
            if (detailStatus in 200..299) {
                val data = detailBody.parseAs<SchaleMangaData>().data
                val chosenQuality = PREFERRED_RESOLUTIONS.firstOrNull { it in data } ?: data.keys.firstOrNull()
                val dataKey = chosenQuality?.let { data[it] }
                report.appendLine("Calidades disponibles: ${data.keys.joinToString().ifBlank { "ninguna" }}")
                if (chosenQuality == null || dataKey == null || dataKey.id == 0 || dataKey.key.isBlank()) {
                    report.appendLine("FALLÓ — sin calidad utilizable")
                } else {
                    report.appendLine("Calidad elegida: $chosenQuality (id=${dataKey.id})")
                    report.appendLine("\n--- 4. GET books/data ($chosenQuality) ---")
                    val dataUrl = buildImageDataUrl(galleryId, galleryKey, dataKey.id, dataKey.key, chosenQuality, credential)
                    val (dataStatus, dataBody) = fetchRaw(dataUrl, HttpMethod.Get)
                    report.appendLine("HTTP Status: $dataStatus")
                    if (dataStatus in 200..299) {
                        val info = dataBody.parseAs<SchaleImagesInfo>()
                        val base = info.base.trimEnd('/')
                        pageCount = info.entries.size
                        report.appendLine("Páginas: $pageCount")
                        report.appendLine("Base: $base")
                        report.appendLine("Fallback: ${info.fallback ?: "no"}")
                        report.appendLine("Primera: ${firstPageUrl(base, info.entries.firstOrNull()?.path, chosenQuality)}")
                        report.appendLine("Última: ${firstPageUrl(base, info.entries.lastOrNull()?.path, chosenQuality)}")
                    } else {
                        report.appendLine("Cuerpo: ${dataBody.take(300)}")
                    }
                }
            } else {
                report.appendLine("Cuerpo: ${detailBody.take(300)}")
            }
        } catch (e: Throwable) {
            report.appendLine("Error: ${e::class.simpleName}: ${e.message}")
        }

        // Test 5: the account endpoint, which needs the session as well as the clearance.
        report.appendLine("\n--- 5. GET books/favorites (sesión + verificación) ---")
        val session = sessionToken()
        if (session == null) {
            report.appendLine("Sin sesión guardada: los favoritos no se pueden probar.")
        } else {
            report.appendLine("Sesión: ${session.length} caracteres")
            try {
                val url = buildFavoritesUrl(page = 1, keyword = null, sort = null, crt = credential)
                val (favoriteStatus, favoriteBody) = fetchRaw(url, HttpMethod.Get, bearer = session)
                report.appendLine("HTTP Status: $favoriteStatus")
                report.appendLine("Cuerpo: ${favoriteBody.take(240).ifBlank { "(vacío)" }}")
                report.appendLine(if (favoriteStatus == 200) "Resultado: ÉXITO" else "Resultado: FALLÓ")
                if (favoriteStatus == 403) {
                    report.appendLine("Sesión en auth /check: ${if (probeSessionValid()) "válida" else "rechazada"}")
                    report.appendLine("Verificación en auth /clearance: ${validateClearanceToken(credential) ?: "sin respuesta"}")
                }
            } catch (e: Throwable) {
                report.appendLine("Error: ${e::class.simpleName}: ${e.message}")
            }
        }

        report.appendLine("\nResultado final: ${if (pageCount > 0) "LECTURA OK ($pageCount páginas)" else "LECTURA FALLÓ"}")
        return report.toString()
    }

    private fun firstPageUrl(base: String, path: String?, quality: String): String {
        if (path == null) return "—"
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return "$base/${path.trimStart('/')}?w=$quality"
    }

    private suspend fun getProtectedImageUrls(id: Long, key: String, token: String): SchaleImageUrls {
        val detailUrl = buildDetailUrlWithCrt(id, key, token)
        val mangaDataText = try {
            ehRequest(detailUrl, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
                method = HttpMethod.Post
                header(HttpHeaders.Accept, "*/*")
                applySessionAuth()
            }.executeSafely { resp ->
                val body = resp.bodyAsText()
                if (!resp.status.isSuccess()) {
                    throw protectedRequestError("POST books/detail", detailUrl, resp.status.value, resp.status.description, body, token)
                }
                body
            }
        } catch (e: SchaleClearanceException) {
            throw e
        } catch (e: SchaleTechnicalException) {
            throw e
        } catch (e: Throwable) {
            throw SchaleTechnicalException(
                stage = "POST books/detail (conexión)",
                url = detailUrl,
                httpStatus = null,
                httpDescription = null,
                token = token,
                responseBody = null,
                originalCause = e,
            )
        }

        val data = try {
            mangaDataText.parseAs<SchaleMangaData>().data
        } catch (e: Throwable) {
            throw SchaleTechnicalException(
                stage = "Parse JSON mangaData",
                url = detailUrl,
                httpStatus = 200,
                httpDescription = "OK",
                token = token,
                responseBody = mangaDataText,
                originalCause = e,
            )
        }

        val chosenQuality = PREFERRED_RESOLUTIONS.firstOrNull { it in data } ?: data.keys.firstOrNull()
        val dataKey = chosenQuality?.let { data[it] }
        if (chosenQuality == null || dataKey == null || dataKey.id == 0 || dataKey.key.isBlank()) {
            throw SchaleTechnicalException(
                stage = "Resolución de imágenes",
                url = detailUrl,
                httpStatus = null,
                httpDescription = null,
                token = token,
                responseBody = "Claves de calidad disponibles: ${data.keys.joinToString()}",
            )
        }

        val dataUrl = buildImageDataUrl(id, key, dataKey.id, dataKey.key, chosenQuality, token)
        val imagesText = try {
            ehRequest(dataUrl, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
                header(HttpHeaders.Accept, "*/*")
                applySessionAuth()
            }.executeSafely { resp ->
                val body = resp.bodyAsText()
                if (!resp.status.isSuccess()) {
                    throw protectedRequestError("GET books/data ($chosenQuality)", dataUrl, resp.status.value, resp.status.description, body, token)
                }
                body
            }
        } catch (e: SchaleClearanceException) {
            throw e
        } catch (e: SchaleTechnicalException) {
            throw e
        } catch (e: Throwable) {
            throw SchaleTechnicalException(
                stage = "GET books/data ($chosenQuality) (conexión)",
                url = dataUrl,
                httpStatus = null,
                httpDescription = null,
                token = token,
                responseBody = null,
                originalCause = e,
            )
        }

        val imagesInfo = try {
            imagesText.parseAs<SchaleImagesInfo>()
        } catch (e: Throwable) {
            throw SchaleTechnicalException(
                stage = "Parse JSON imagesInfo",
                url = dataUrl,
                httpStatus = 200,
                httpDescription = "OK",
                token = token,
                responseBody = imagesText,
                originalCause = e,
            )
        }

        val base = imagesInfo.base.trimEnd('/')
        val fallbackBase = imagesInfo.fallback?.trimEnd('/')
        val urls = imagesInfo.entries.map { firstPageUrl(base, it.path, chosenQuality) }
        val fallbackUrls = fallbackBase
            ?.takeIf { it.isNotBlank() }
            ?.let { host -> imagesInfo.entries.map { firstPageUrl(host, it.path, chosenQuality) } }
            .orEmpty()
        if (urls.isEmpty()) {
            throw SchaleTechnicalException(
                stage = "Resolución de imágenes",
                url = dataUrl,
                httpStatus = 200,
                httpDescription = "OK",
                token = token,
                responseBody = "La respuesta de books/data no contiene páginas",
            )
        }
        logcat(TAG) {
            "books/data $id/$key: ${urls.size} pages @ $chosenQuality, fallback=${fallbackUrls.isNotEmpty()} (base=$base)"
        }
        return SchaleImageUrls(chosenQuality, urls, fallbackUrls)
    }

    /**
     * A 403 on a clearance-protected endpoint means the server rejected the crt token.
     * We do NOT make an extra call to the auth server to "confirm" this: that diagnostic GET
     * went through OkHttp on a different TCP connection than the WebView used to solve the
     * captcha, so the auth server often returned 401/403 for a perfectly valid token, producing
     * a false "site rejected verification" error. Trust the 403 directly instead.
     */
    private fun protectedRequestError(
        stage: String,
        url: String,
        status: Int,
        description: String,
        body: String,
        token: String,
    ): Throwable {
        if (status == 403) {
            val preview = body.take(200).ifBlank { "(sin cuerpo)" }
            return SchaleClearanceException(
                "[Verificación expirada — HTTP 403]\n" +
                    "El servidor rechazó el token de verificación guardado.\n" +
                    "Respuesta del servidor: $preview\n\n" +
                    "Ve a Configuración > EH > Verificación de Schale Network y resuelve el captcha de nuevo.",
            )
        }
        return SchaleTechnicalException(
            stage = stage,
            url = url,
            httpStatus = status,
            httpDescription = description,
            token = token,
            responseBody = body,
        )
    }

    private suspend fun fetchRaw(url: String, httpMethod: HttpMethod, bearer: String? = null): Pair<Int, String> = ehRequest(url, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
        method = httpMethod
        header(HttpHeaders.Accept, "*/*")
        bearer?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }.executeSafely { resp -> resp.status.value to resp.bodyAsText() }

    /**
     * Retries transient failures only: network errors, 429 and 5xx. 4xx answers from the API are
     * definitive and are thrown right away.
     */
    private suspend fun <T> withRetries(stage: String, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val retryable = when (e) {
                    is SchaleClearanceException -> false
                    is SchaleAuthException -> false
                    is SchaleTechnicalException -> e.httpStatus == null || e.httpStatus == 429 || e.httpStatus >= 500
                    else -> true
                }
                logcat(TAG) { "$stage failed (attempt ${attempt + 1}/$MAX_ATTEMPTS): ${e.message}" }
                if (!retryable || attempt == MAX_ATTEMPTS - 1) throw e
                delay(750L * (attempt + 1))
                attempt++
            }
        }
    }

    fun isValidClearanceToken(token: String?): Boolean {
        val value = normalizeClearanceToken(token) ?: return false
        // The bound only rejects obvious rubbish (an error page, a JSON blob); the real length of a
        // clearance is set by the server, so it is not capped tightly.
        return value.length in 8..512 && value.none { it.isWhitespace() }
    }

    fun checkClearanceToken() {
        val token = Settings.schaleClearanceToken.value
        if (!isValidClearanceToken(token)) {
            throw SchaleClearanceException(
                "[Error de Configuración Schale]\nEl token de verificación no está configurado o es inválido.\n" +
                    "Token actual en Settings: '$token' (longitud: ${token?.length ?: 0})\n" +
                    "Ve a Configuración > EH > Verificación de Schale Network.",
            )
        }
    }

    // ---------- Shared request plumbing ----------

    private fun markFavorite(gid: Long, favorite: Boolean) {
        _favoriteStates.update { it + (gid to favorite) }
    }

    private fun sessionRequired() = SchaleAuthException(
        "[Cuenta requerida]\nSchale Network rechazó la sesión.\n" +
            "Inicia sesión en Configuración > EH > Cuenta de Schale Network para usar los favoritos.",
    )

    private fun clearanceRequired() = SchaleClearanceException(
        "[Verificación requerida]\nSchale Network rechazó el token de verificación (HTTP 403).\n" +
            "Ve a Configuración > EH > Verificación de Schale Network y resuelve el captcha para renovarlo \n" +
            "desde la conexión con la que vas a leer: la verificación queda atada a ella.",
    )

    /**
     * A 403 on an account-gated endpoint leaves it open whether the session or the clearance was
     * rejected. We check session existence locally (no network) and otherwise assume clearance,
     * including the actual server response body so the user sees what was said.
     * We do NOT call validateClearanceToken or probeSessionValid: those extra GETs went through
     * OkHttp on a different connection than the WebView, so they often mis-diagnosed a valid token.
     */
    private fun forbiddenReason(stage: String, url: String, body: String): IOException {
        if (sessionToken() == null) return sessionRequired()
        val clearance = normalizeClearanceToken(Settings.schaleClearanceToken.value)
        if (clearance == null) return clearanceRequired()
        // Session exists and clearance looks structurally valid; the 403 is most likely a
        // clearance expiry (the server validates it server-side). Show what the API said.
        val preview = body.take(200).ifBlank { "(sin cuerpo)" }
        return SchaleClearanceException(
            "[Verificación expirada — HTTP 403]\n" +
                "El servidor rechazó la verificación o la sesión guardada.\n" +
                "Respuesta del servidor: $preview\n\n" +
                "Ve a Configuración > EH > Verificación de Schale Network y resuelve el captcha de nuevo.\n" +
                "Si el problema persiste, cierra sesión y vuelve a iniciarla.",
        )
    }

    private suspend fun probeSessionValid(): Boolean {
        val session = sessionToken() ?: return false
        return try {
            val (status, _) = fetchRaw("${EhUrl.AUTH_SCHALE}/check", HttpMethod.Post, bearer = session)
            status == 200
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(TAG) { "session probe failed: ${e.message}" }
            false
        }
    }

    /**
     * The account session, rotated through the site's refresh token when `expr` says it is about to
     * expire. Reads/writes go through DataStore, so the rotated token is visible to every caller.
     */
    private suspend fun requireSessionToken(): String {
        val session = sessionToken() ?: throw sessionRequired()
        val expiry = Settings.schaleSessionExpiry.value
        if (expiry > 0L && System.currentTimeMillis() >= expiry - SESSION_REFRESH_MARGIN_MS) {
            logcat(TAG) { "session is near expiry (${(expiry - System.currentTimeMillis()) / 1000}s), refreshing" }
            refreshSession()
        }
        return sessionToken() ?: session
    }

    private fun requireClearanceToken(): String {
        val token = normalizeClearanceToken(Settings.schaleClearanceToken.value)
        if (token == null || !isValidClearanceToken(token)) {
            throw SchaleClearanceException(
                "[Verificación requerida]\nEl token de Schale Network no está configurado o no es válido.\n" +
                    "Ve a Configuración > EH > Verificación de Schale Network para resolver el captcha.",
            )
        }
        return token
    }

    private fun HttpRequestBuilder.applySessionAuth() {
        sessionToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /** Page of a `/books…` listing endpoint (browse, search or favorites). */
    private suspend fun fetchBooks(url: String, stage: String, authed: Boolean = false): GalleryListResult {
        val session = if (authed) requireSessionToken() else null
        val responseText = withRetries(stage) {
            ehRequest(url, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
                session?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }.executeSafely { resp ->
                val body = resp.bodyAsText()
                // A 403 on an account endpoint is either a dead session or a dead clearance;
                // retrying cannot help, so translate it into the actionable exception right away.
                if (authed && resp.status.value == 403) throw forbiddenReason(stage, url, body)
                checkPublicResponseStatus(resp)
                body
            }
        }
        val books = responseText.parseAs<SchaleBooks>()
        books.favorites.forEach { (id, favorite) -> id.toLongOrNull()?.let { markFavorite(it, favorite > 0) } }
        if (books.errors.isNotEmpty()) {
            // The server answers a query it could not match without `entries`/`total`, so this is an
            // empty result set rather than a failure. Log which terms it rejected and how it read
            // the query (`namespace` shows what a mistyped prefix was parsed as).
            val parsed = books.matches.joinToString { "${it.namespace}:${it.query}" }
            logcat(TAG) { "$stage -> no match for ${books.errors} (parsed as $parsed)" }
        }
        val galleryInfoList = books.entries.map { it.toBaseGalleryInfo() }
        val limit = books.limit.takeIf { it > 0 } ?: books.entries.size
        val hasMore = limit > 0 && books.page * limit < books.total
        val nextPage = if (hasMore) (books.page + 1).toString() else null
        val prevPage = if (books.page > 1) (books.page - 1).toString() else null
        logcat(TAG) { "$stage -> page=${books.page} limit=$limit total=${books.total} entries=${books.entries.size} next=$nextPage" }
        return GalleryListResult(prevPage, nextPage, ArrayList(galleryInfoList))
    }

    private suspend fun mutateFavorite(gid: Long, token: String, add: Boolean) {
        requireSessionToken()
        val crt = requireClearanceToken()
        val stage = if (add) "POST books/favorites" else "DELETE books/favorites"
        val url = URLBuilder(
            protocol = URLProtocol.HTTPS,
            host = SCHALE_API_HOST,
            pathSegments = listOf("books", "favorites", gid.toString(), token),
        ).apply {
            parameters.append("crt", crt)
        }.buildString()
        try {
            ehRequest(url, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
                method = if (add) HttpMethod.Post else HttpMethod.Delete
                applySessionAuth()
            }.executeSafely { resp ->
                val body = resp.bodyAsText()
                if (resp.status.value == 403) throw forbiddenReason(stage, url, body)
                if (!resp.status.isSuccess()) {
                    throw SchaleTechnicalException(
                        stage = stage,
                        url = url,
                        httpStatus = resp.status.value,
                        httpDescription = resp.status.description,
                        token = crt,
                        responseBody = body,
                    )
                }
            }
        } catch (e: SchaleAuthException) {
            throw e
        } catch (e: SchaleTechnicalException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw SchaleTechnicalException(
                stage = "$stage (conexión)",
                url = url,
                httpStatus = null,
                httpDescription = null,
                token = crt,
                responseBody = null,
                originalCause = e,
            )
        }
        markFavorite(gid, add)
        logcat(TAG) { "${if (add) "favorited" else "unfavorited"} $gid/$token" }
    }

    /** For public endpoints: any non-2xx is a generic IO error. */
    private fun checkPublicResponseStatus(resp: HttpResponse) {
        if (!resp.status.isSuccess()) {
            throw IOException("Schale API error: ${resp.status.value} ${resp.status.description}")
        }
    }

    // ---------- URL builders ----------

    // Public listing endpoint — no crt param
    private fun buildBooksUrl(page: Int, keyword: String? = null, sort: Int? = null, limit: Int = PAGE_SIZE): String = URLBuilder(
        protocol = URLProtocol.HTTPS,
        host = SCHALE_API_HOST,
        pathSegments = listOf("books"),
    ).apply {
        parameters.append("page", page.toString())
        parameters.append("limit", limit.toString())
        keyword?.takeIf { it.isNotBlank() }?.let { parameters.append("s", normalizeQuery(it)) }
        sort?.let { parameters.append("sort", it.toString()) }
    }.buildString()

    // Account endpoint — crt plus the session bearer token. Mirrors the site's own URL, which never
    // sends `limit` here (the API answers with its default page size).
    private fun buildFavoritesUrl(page: Int, keyword: String?, sort: Int?, crt: String): String = URLBuilder(
        protocol = URLProtocol.HTTPS,
        host = SCHALE_API_HOST,
        pathSegments = listOf("books", "favorites"),
    ).apply {
        parameters.append("page", page.toString())
        keyword?.takeIf { it.isNotBlank() }?.let { parameters.append("s", normalizeQuery(it)) }
        sort?.let { parameters.append("sort", it.toString()) }
        parameters.append("crt", crt)
    }.buildString()

    // Public endpoint (GET) — no crt param
    private fun buildDetailUrl(id: Long, key: String): String = URLBuilder(
        protocol = URLProtocol.HTTPS,
        host = SCHALE_API_HOST,
        pathSegments = listOf("books", "detail", id.toString(), key),
    ).buildString()

    // Protected endpoint (POST + GET data) — crt param required
    private fun buildDetailUrlWithCrt(id: Long, key: String, crt: String): String = URLBuilder(
        protocol = URLProtocol.HTTPS,
        host = SCHALE_API_HOST,
        pathSegments = listOf("books", "detail", id.toString(), key),
    ).apply {
        parameters.append("crt", crt)
    }.buildString()

    private fun buildImageDataUrl(id: Long, key: String, dataId: Int, pubKey: String, quality: String, crt: String): String = URLBuilder(
        protocol = URLProtocol.HTTPS,
        host = SCHALE_API_HOST,
        pathSegments = listOf("books", "data", id.toString(), key, dataId.toString(), pubKey, quality),
    ).apply {
        parameters.append("crt", crt)
    }.buildString()

    // ---------- Model conversions ----------

    private fun SchaleEntry.toBaseGalleryInfo(): BaseGalleryInfo {
        val fullThumbUrl = thumbnail.getFullUrl()
        return BaseGalleryInfo(
            gid = id.toLong(),
            token = key,
            title = title,
            pages = pages,
            thumbKey = fullThumbUrl,
            category = 0x4, // MANGA
            posted = "",
            rating = -1f,
        )
    }
}
