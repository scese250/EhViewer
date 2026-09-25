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
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.ehRequest
import com.hippo.ehviewer.client.executeSafely
import com.hippo.ehviewer.client.parseAs
import com.hippo.ehviewer.client.parser.GalleryListResult
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol

class SchaleClearanceException(message: String) : Exception(message)

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
        checkClearanceToken()
        val url = buildBooksUrl(page = page, cat = cat)
        val responseText = ehRequest(url, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE)
            .executeSafely { resp ->
                checkResponseStatus(resp)
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
        checkClearanceToken()
        val url = buildDetailUrl(id, key)
        val responseText = ehRequest(url, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE)
            .executeSafely { resp ->
                checkResponseStatus(resp)
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

        val baseInfo = BaseGalleryInfo(
            gid = id,
            token = key,
            title = detail.title,
            thumbKey = thumbKey,
            category = 0x4, // MANGA
            pages = previewList.size,
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
     * Fetch full image URLs for a Schale gallery in page order (used by reader/spider).
     */
    suspend fun getSchaleImageUrls(id: Long, key: String): List<String> {
        checkClearanceToken()
        val detailUrl = buildDetailUrl(id, key)
        val mangaDataText = ehRequest(detailUrl, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE) {
            method = HttpMethod.Post
        }.executeSafely { resp ->
            checkResponseStatus(resp)
            resp.bodyAsText()
        }

        val mangaData = mangaDataText.parseAs<SchaleMangaData>()
        val data = mangaData.data
        val dataKey = data?.`1280` ?: data?.`1600` ?: data?.`0` ?: data?.`980` ?: data?.`780`
        val dataId = dataKey?.id
        val pubKey = dataKey?.key

        if (dataId != null && pubKey != null) {
            val realQuality = when (dataId) {
                data?.`1600`?.id -> "1600"
                data?.`1280`?.id -> "1280"
                data?.`980`?.id -> "980"
                data?.`780`?.id -> "780"
                else -> "0"
            }
            val dataUrl = buildImageDataUrl(id, key, dataId, pubKey, realQuality)
            val imagesText = ehRequest(dataUrl, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE)
                .executeSafely { resp ->
                    checkResponseStatus(resp)
                    resp.bodyAsText()
                }
            val imagesInfo = imagesText.parseAs<SchaleImagesInfo>()
            val base = imagesInfo.base.trimEnd('/')
            val urls = imagesInfo.entries.map { "$base/${it.path.trimStart('/')}?w=$realQuality" }
            if (urls.isNotEmpty()) return urls
        }

        // Fallback: fetch thumbnails entries from detail if image data endpoint is unavailable
        val getDetailText = ehRequest(detailUrl, EhUrl.REFERER_SCHALE, EhUrl.ORIGIN_SCHALE)
            .executeSafely { resp ->
                checkResponseStatus(resp)
                resp.bodyAsText()
            }
        val detail = getDetailText.parseAs<SchaleMangaDetail>()
        val base = detail.thumbnails?.base?.trimEnd('/') ?: "https://$SCHALE_API_HOST"
        return detail.thumbnails?.entries?.map { "$base/${it.path.trimStart('/')}" }.orEmpty()
    }

    private fun checkClearanceToken() {
        if (Settings.schaleClearanceToken.value.isNullOrBlank()) {
            throw SchaleClearanceException("Verificación de Cloudflare requerida. Ve a Configuración > EH > Verificación de Schale Network.")
        }
    }

    private fun checkResponseStatus(resp: HttpResponse) {
        if (resp.status == HttpStatusCode.BadRequest || resp.status == HttpStatusCode.Forbidden) {
            Settings.schaleClearanceToken.value = null
            throw SchaleClearanceException("La verificación de Cloudflare expiró. Por favor re-verifica en Configuración > EH > Verificación de Schale Network.")
        }
    }

    // ---------- URL builders ----------

    private fun buildBooksUrl(page: Int, cat: Int): String {
        return URLBuilder(
            protocol = URLProtocol.HTTPS,
            host = SCHALE_API_HOST,
            pathSegments = listOf("books"),
        ).apply {
            parameters.append("page", page.toString())
            parameters.append("limit", PAGE_SIZE.toString())
            parameters.append("s", "cat:$cat")
            appendClearanceToken()
        }.buildString()
    }

    private fun buildDetailUrl(id: Long, key: String): String {
        return URLBuilder(
            protocol = URLProtocol.HTTPS,
            host = SCHALE_API_HOST,
            pathSegments = listOf("books", "detail", id.toString(), key),
        ).apply {
            appendClearanceToken()
        }.buildString()
    }

    private fun buildImageDataUrl(id: Long, key: String, dataId: Int, pubKey: String, quality: String): String {
        return URLBuilder(
            protocol = URLProtocol.HTTPS,
            host = SCHALE_API_HOST,
            pathSegments = listOf("books", "data", id.toString(), key, dataId.toString(), pubKey, quality),
        ).apply {
            appendClearanceToken()
        }.buildString()
    }

    private fun URLBuilder.appendClearanceToken() {
        Settings.schaleClearanceToken.value?.let { crt ->
            if (crt.isNotBlank()) parameters.append("crt", crt)
        }
    }

    // ---------- Model conversions ----------

    private fun SchaleEntry.toBaseGalleryInfo(): BaseGalleryInfo {
        val fullThumbUrl = thumbnail.getFullUrl()
        return BaseGalleryInfo(
            gid = id.toLong(),
            token = key,
            title = title,
            thumbKey = fullThumbUrl,
            category = 0x4, // MANGA
            posted = "",
            rating = -1f,
        )
    }
}
