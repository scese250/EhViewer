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
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
import java.io.IOException

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

private const val PAGE_SIZE = 25
private const val SCHALE_API_HOST = "api.schale.network"

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
        val responseText = ehRequest(url, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE)
            .executeSafely { resp ->
                checkPublicResponseStatus(resp)
                resp.bodyAsText()
            }
        val books = responseText.parseAs<SchaleBooks>()
        val galleryInfoList = books.entries.map { it.toBaseGalleryInfo() }
        val hasMore = books.page * books.limit < books.total
        val nextPage = if (hasMore) (books.page + 1).toString() else null
        val prevPage = if (books.page > 1) (books.page - 1).toString() else null
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
     * Always requires a valid Cloudflare clearance token — no low-res fallback.
     * Prefers 1280p, then 1600p, then original; throws [SchaleClearanceException] if token is missing or expired.
     */
    suspend fun getSchaleImageUrls(id: Long, key: String): List<String> {
        val token = Settings.schaleClearanceToken.value
        if (!isValidClearanceToken(token)) {
            throw IOException(
                "[Error de Configuración Schale]\nEl token de verificación no está configurado o es inválido.\nToken actual en Settings: '$token' (longitud: ${token?.length ?: 0})\nVe a Configuración > EH > Verificación de Schale Network para obtener un token nuevo.",
            )
        }
        return getProtectedImageUrls(id, key, token!!)
    }

    suspend fun testToken(token: String?): String {
        if (!isValidClearanceToken(token)) {
            return "[Diagnóstico Schale]\nToken inválido o vacío en Settings: '$token'"
        }
        val nonNullToken = token!!
        val testUrl = buildDetailUrlWithCrt(27643, "f65c885cfa75", nonNullToken)
        val authUrl = "https://auth.schale.network/clearance"
        val report = StringBuilder()
        report.appendLine("[Diagnóstico Schale Network]")
        report.appendLine("Token (len=${nonNullToken.length}): $nonNullToken\n")

        // Test 1: Auth check
        report.appendLine("--- 1. Servidor de Auth ($authUrl) ---")
        try {
            val (authStatus, authBody, authHeaders) = ehRequest(authUrl, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
                method = HttpMethod.Get
                header(HttpHeaders.Authorization, "Bearer $nonNullToken")
                header(HttpHeaders.Accept, "*/*")
                header("Sec-Fetch-Dest", "empty")
                header("Sec-Fetch-Mode", "cors")
                header("Sec-Fetch-Site", "cross-site")
            }.executeSafely { resp ->
                Triple(
                    resp.status,
                    resp.bodyAsText(),
                    resp.headers.entries().joinToString("\n") { "  ${it.key}: ${it.value.joinToString(", ")}" },
                )
            }
            report.appendLine("HTTP Status: ${authStatus.value} ${authStatus.description}")
            report.appendLine("Resultado: ${if (authStatus.isSuccess()) "ÉXITO (Auth reconoció el token)" else "FALLÓ (${authStatus.value})"}")
            report.appendLine("Headers:\n$authHeaders")
            if (authBody.isNotBlank()) report.appendLine("Cuerpo: ${authBody.take(400)}")
        } catch (e: Throwable) {
            report.appendLine("Error al conectar con Auth: ${e::class.simpleName}: ${e.message}")
        }

        // Test 2: API detail check
        report.appendLine("\n--- 2. Petición POST a Books Detail ($testUrl) ---")
        try {
            val (apiStatus, apiBody, apiHeaders) = ehRequest(testUrl, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
                method = HttpMethod.Post
                header(HttpHeaders.Accept, "*/*")
                header("Sec-Fetch-Dest", "empty")
                header("Sec-Fetch-Mode", "cors")
                header("Sec-Fetch-Site", "cross-site")
            }.executeSafely { resp ->
                Triple(
                    resp.status,
                    resp.bodyAsText(),
                    resp.headers.entries().joinToString("\n") { "  ${it.key}: ${it.value.joinToString(", ")}" },
                )
            }
            report.appendLine("HTTP Status: ${apiStatus.value} ${apiStatus.description}")
            report.appendLine("Resultado: ${if (apiStatus.isSuccess()) "ÉXITO (API aceptó el token)" else "FALLÓ (${apiStatus.value})"}")
            report.appendLine("Headers:\n$apiHeaders")
            if (apiBody.isNotBlank()) report.appendLine("Cuerpo: ${apiBody.take(400)}")
        } catch (e: Throwable) {
            report.appendLine("Error al conectar con API: ${e::class.simpleName}: ${e.message}")
        }

        return report.toString()
    }

    private suspend fun getProtectedImageUrls(id: Long, key: String, token: String): List<String> {
        val detailUrl = buildDetailUrlWithCrt(id, key, token)
        val mangaDataText = try {
            ehRequest(detailUrl, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
                method = HttpMethod.Post
                header(HttpHeaders.Accept, "*/*")
                header("Sec-Fetch-Dest", "empty")
                header("Sec-Fetch-Mode", "cors")
                header("Sec-Fetch-Site", "cross-site")
            }.executeSafely { resp ->
                val body = resp.bodyAsText()
                if (!resp.status.isSuccess()) {
                    throw SchaleTechnicalException(
                        stage = "POST books/detail",
                        url = detailUrl,
                        httpStatus = resp.status.value,
                        httpDescription = resp.status.description,
                        token = token,
                        responseBody = body,
                    )
                }
                body
            }
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

        val mangaData = try {
            mangaDataText.parseAs<SchaleMangaData>()
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

        val data = mangaData.data
        val preferredResolutions = listOf("1280", "1600", "0", "980", "780")
        val chosenQuality = preferredResolutions.firstOrNull { it in data } ?: data.keys.firstOrNull()
        val dataKey = chosenQuality?.let { data[it] }

        if (chosenQuality != null && dataKey != null && dataKey.id != 0 && dataKey.key.isNotBlank()) {
            val dataUrl = buildImageDataUrl(id, key, dataKey.id, dataKey.key, chosenQuality, token)
            val imagesText = try {
                ehRequest(dataUrl, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
                    header(HttpHeaders.Accept, "*/*")
                    header("Sec-Fetch-Dest", "empty")
                    header("Sec-Fetch-Mode", "cors")
                    header("Sec-Fetch-Site", "cross-site")
                }.executeSafely { resp ->
                    val body = resp.bodyAsText()
                    if (!resp.status.isSuccess()) {
                        throw SchaleTechnicalException(
                            stage = "GET books/data ($chosenQuality)",
                            url = dataUrl,
                            httpStatus = resp.status.value,
                            httpDescription = resp.status.description,
                            token = token,
                            responseBody = body,
                        )
                    }
                    body
                }
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
            val urls = imagesInfo.entries.map { "$base/${it.path.trimStart('/')}" }
            if (urls.isNotEmpty()) return urls
        }

        throw SchaleTechnicalException(
            stage = "Resolución de imágenes",
            url = detailUrl,
            httpStatus = null,
            httpDescription = null,
            token = token,
            responseBody = "Claves de calidad disponibles: ${data.keys.joinToString()}",
        )
    }

    fun isValidClearanceToken(token: String?): Boolean = !token.isNullOrBlank() && token != "{}" && token != "null"

    fun checkClearanceToken() {
        val token = Settings.schaleClearanceToken.value
        if (!isValidClearanceToken(token)) {
            throw IOException(
                "[Error de Configuración Schale]\nEl token de verificación no está configurado o es inválido.\nToken actual en Settings: '$token' (longitud: ${token?.length ?: 0})\nVe a Configuración > EH > Verificación de Schale Network.",
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
    private fun buildBooksUrl(page: Int, cat: Int): String = URLBuilder(
        protocol = URLProtocol.HTTPS,
        host = SCHALE_API_HOST,
        pathSegments = listOf("books"),
    ).apply {
        parameters.append("page", page.toString())
        parameters.append("limit", PAGE_SIZE.toString())
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
