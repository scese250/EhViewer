package com.hippo.ehviewer.client.schale

import kotlinx.serialization.Serializable

/**
 * Response from GET /books
 */
@Serializable
data class SchaleBooks(
    val entries: List<SchaleEntry> = emptyList(),
    val total: Int = 0,
    val limit: Int = 25,
    val page: Int = 1,
)

@Serializable
data class SchaleEntry(
    val id: Int,
    val key: String,
    val title: String,
    val thumbnail: SchaleThumbnail = SchaleThumbnail(),
)

@Serializable
data class SchaleThumbnail(
    val path: String = "",
    val url: String? = null,
) {
    fun getFullUrl(base: String? = null): String {
        val p = url ?: path
        if (p.startsWith("http://") || p.startsWith("https://")) return p
        val b = base?.trimEnd('/') ?: "https://api.schale.network"
        return "$b/${p.trimStart('/')}"
    }
}

/**
 * Response from GET /books/detail/{id}/{key}
 */
@Serializable
data class SchaleMangaDetail(
    val id: Int = 0,
    val key: String = "",
    val title: String = "",
    val created_at: Long = 0L,
    val updated_at: Long? = null,
    val thumbnails: SchaleThumbnails? = null,
    val tags: List<SchaleTag> = emptyList(),
)

@Serializable
data class SchaleThumbnails(
    val base: String = "",
    val main: SchaleThumbnail? = null,
    val entries: List<SchaleThumbnail> = emptyList(),
)

@Serializable
data class SchaleTag(
    val name: String,
    val namespace: Int = 0,
)

/**
 * Response from POST /books/detail/{id}/{key}
 */
@Serializable
data class SchaleMangaData(
    val data: SchaleData? = null,
    val similar: List<SchaleEntry> = emptyList(),
)

@Serializable
data class SchaleData(
    val `0`: SchaleDataKey? = null,
    val `780`: SchaleDataKey? = null,
    val `980`: SchaleDataKey? = null,
    val `1280`: SchaleDataKey? = null,
    val `1600`: SchaleDataKey? = null,
)

@Serializable
data class SchaleDataKey(
    val id: Int? = null,
    val size: Double = 0.0,
    val key: String? = null,
)

/**
 * Response from GET /books/data/{id}/{key}/{dataId}/{publicKey}/{quality}
 */
@Serializable
data class SchaleImagesInfo(
    val base: String = "",
    val entries: List<SchaleImagePath> = emptyList(),
)

@Serializable
data class SchaleImagePath(
    val path: String = "",
)
