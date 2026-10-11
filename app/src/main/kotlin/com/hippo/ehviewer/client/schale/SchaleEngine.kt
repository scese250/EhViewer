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

/** Page image URLs of a Schale gallery: chosen quality plus primary and fallback CDN variants. */
data class SchaleImageUrls(
    val quality: String,
    val primary: List<String>,
    val fallback: List<String>,
)

private const val TAG = "SchaleEngine"
private const val PAGE_SIZE = 40
private const val MAX_ATTEMPTS = 3
private const val SCHALE_API_HOST = "api.schale.network"
private val PREFERRED_RESOLUTIONS = listOf("1280", "1600", "0", "980", "780")

private fun normalizeClearanceToken(raw: String?): String? {
    val token = raw?.trim()?.removeSurrounding("\"")?.trim()
    if (token.isNullOrEmpty()) return null
    if (token.equals("null", ignoreCase = true) || token.equals("undefined", ignoreCase = true) || token == "{}") return null
    return token
}

/**
 * Engine for Schale Network (niyaniya.moe / api.schale.network) API calls.
 */
object SchaleEngine {

    /**
     * Fetch the manga list from Schale Network.
     *
     * @param page 1-indexed page number
     * @param cat  Category id (2 = manga is the default browse view)
     */
    suspend fun getSchaleGalleryList(page: Int = 1, cat: Int = 2): GalleryListResult {
        // Public endpoint — no crt token required
        val url = buildBooksUrl(page = page, cat = cat)
        val responseText = withRetries("GET books list (page=$page)") {
            ehRequest(url, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE)
                .executeSafely { resp ->
                    checkPublicResponseStatus(resp)
                    resp.bodyAsText()
                }
        }
        val books = responseText.parseAs<SchaleBooks>()
        val galleryInfoList = books.entries.map { it.toBaseGalleryInfo() }
        val limit = books.limit.takeIf { it > 0 } ?: books.entries.size
        val hasMore = limit > 0 && books.page * limit < books.total
        val nextPage = if (hasMore) (books.page + 1).toString() else null
        val prevPage = if (books.page > 1) (books.page - 1).toString() else null
        logcat(TAG) { "books page=${books.page} limit=$limit total=${books.total} entries=${books.entries.size} next=$nextPage" }
        return GalleryListResult(prevPage, nextPage, ArrayList(galleryInfoList))
    }

    /**
     * Fetch detail and previews for a single Schale gallery entry.
     */
    suspend fun getSchaleGalleryDetail(id: Long, key: String): GalleryDetail {
        // Public endpoint — no crt token required
        val url = buildDetailUrl(id, key)
        val responseText = ehRequest(url, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE)
            .executeSafely { resp ->
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
            val ns = when (nsId) {
                1 -> TagNamespace.Artist
                2 -> TagNamespace.Group
                3 -> TagNamespace.Parody
                8 -> TagNamespace.Male
                9 -> TagNamespace.Female
                10 -> TagNamespace.Mixed
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
    suspend fun getSchaleImageUrls(id: Long, key: String): SchaleImageUrls {
        val token = normalizeClearanceToken(Settings.schaleClearanceToken.value)
        if (token == null || !isValidClearanceToken(token)) {
            throw SchaleClearanceException(
                "[Verificación requerida]\nEl token de Schale Network no está configurado o no es válido.\n" +
                    "Ve a Configuración > EH > Verificación de Schale Network para resolver el captcha.",
            )
        }
        return getProtectedImageUrls(id, key, token)
    }

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
            val listText = ehRequest(buildBooksUrl(page = 1, cat = 2, limit = 1), EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE)
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
     * A 403 means the clearance token is no longer accepted: drop it so the user is asked to
     * re-verify, and return a short actionable error. Anything else stays a technical error.
     */
    private fun protectedRequestError(
        stage: String,
        url: String,
        status: Int,
        description: String,
        body: String,
        token: String,
    ): Throwable = if (status == 403) {
        Settings.schaleClearanceToken.value = null
        SchaleClearanceException(
            "[Verificación expirada]\nEl servidor de Schale rechazó el token (HTTP 403 Forbidden).\n" +
                "Ve a Configuración > EH > Verificación de Schale Network para re-verificar.",
        )
    } else {
        SchaleTechnicalException(
            stage = stage,
            url = url,
            httpStatus = status,
            httpDescription = description,
            token = token,
            responseBody = body,
        )
    }

    private suspend fun fetchRaw(url: String, httpMethod: HttpMethod): Pair<Int, String> = ehRequest(url, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
        method = httpMethod
        header(HttpHeaders.Accept, "*/*")
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
        return value.length in 8..128 && value.none { it.isWhitespace() }
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

    /** For public endpoints: any non-2xx is a generic IO error. */
    private fun checkPublicResponseStatus(resp: HttpResponse) {
        if (!resp.status.isSuccess()) {
            throw IOException("Schale API error: ${resp.status.value} ${resp.status.description}")
        }
    }

    // ---------- URL builders ----------

    // Public endpoint — no crt param
    private fun buildBooksUrl(page: Int, cat: Int, limit: Int = PAGE_SIZE): String = URLBuilder(
        protocol = URLProtocol.HTTPS,
        host = SCHALE_API_HOST,
        pathSegments = listOf("books"),
    ).apply {
        parameters.append("page", page.toString())
        parameters.append("limit", limit.toString())
        parameters.append("s", "cat:$cat")
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
