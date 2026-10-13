package com.hippo.ehviewer.client.schale

import kotlinx.serialization.Serializable

/**
 * Response from GET /books
 */
@Serializable
data class SchaleBooks(
    val entries: List<SchaleEntry> = emptyList(),
    val total: Int = 0,
    // Absent on the favorites endpoint, so keep 0 as "derive it from the page size".
    val limit: Int = 0,
    val page: Int = 1,
    // Account state of each listed gallery, only sent to signed-in requests.
    val favorites: Map<String, Int> = emptyMap(),
    /**
     * A query the server could not match at all is answered without `entries`/`total`:
     * `{"errors": {"none": ["tag:nosuchtag"]}, "matches": […]}`.
     */
    val errors: Map<String, List<String>> = emptyMap(),
    val matches: List<SchaleQueryMatch> = emptyList(),
)

/**
 * A parsed fragment of the `s=` query language, echoed back by the server. Kept for diagnostics:
 * it shows which namespace the server matched a term against (`title` for anything it did not
 * recognize).
 */
@Serializable
data class SchaleQueryMatch(
    val query: String = "",
    val namespace: String = "",
)

@Serializable
data class SchaleEntry(
    val id: Int,
    val key: String,
    val title: String,
    val pages: Int = 0,
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
    // Only sent for signed-in requests; null means "the server did not say", so keep the known state.
    val favorites: Boolean? = null,
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
 * Entry of GET /books/tags: a tag the site knows and how many galleries carry it. `namespace` is
 * only sent for the tags that are not plain ones (8 male, 9 female, 10 mixed, …), and the same name
 * appears once per namespace, so the pair — not the name — identifies a tag.
 */
@Serializable
data class SchaleTagInfo(
    val name: String,
    val count: Int = 0,
    val namespace: Int? = null,
)

/**
 * Response from POST https://auth.schale.network/check (Bearer = account session):
 * `{"favorites": {"<gid>": 1, …}, "role": 1, "username": "…"}`.
 */
@Serializable
data class SchaleAccount(
    val favorites: Map<String, Int> = emptyMap(),
    val role: Int = 0,
    val username: String = "",
) {
    /** The site treats an account as usable when it has a name and a role. */
    val isValid: Boolean get() = username.isNotEmpty() && role > 0
}

/**
 * Response from POST /books/detail/{id}/{key}
 */
@Serializable
data class SchaleMangaData(
    val data: Map<String, SchaleDataKey> = emptyMap(),
)

@Serializable
data class SchaleDataKey(
    val id: Int = 0,
    val key: String = "",
    val size: Long = 0L,
)

/**
 * Response from GET /books/data/{id}/{key}/{dataId}/{publicKey}/{quality}
 */
@Serializable
data class SchaleImagesInfo(
    val base: String = "",
    val fallback: String? = null,
    val entries: List<SchaleImagePath> = emptyList(),
)

@Serializable
data class SchaleImagePath(
    val path: String = "",
)
