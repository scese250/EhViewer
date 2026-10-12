package com.hippo.ehviewer.ui.screen

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.serialization.saved
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import com.ehviewer.core.model.BaseGalleryInfo
import com.ehviewer.core.util.withIOContext
import com.hippo.ehviewer.Settings
import com.hippo.ehviewer.client.EhEngine
import com.hippo.ehviewer.client.EhUrl
import com.hippo.ehviewer.client.data.ListUrlBuilder
import com.hippo.ehviewer.client.data.ListUrlBuilder.Companion.MODE_TOPLIST
import com.hippo.ehviewer.client.schale.SchaleAuthException
import com.hippo.ehviewer.client.schale.SchaleEngine
import com.hippo.ehviewer.ui.tools.foldToLoadResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.tarsin.coroutines.runSuspendCatching

class GalleryListViewModel(lub: ListUrlBuilder, savedStateHandle: SavedStateHandle) : ViewModel() {
    private val mutex = Mutex()

    /**
     * Where the Schale list currently is. It is a numbered range (`page=N`) rather than an offset,
     * so the page step buttons need to know the current page and whether another one follows.
     */
    data class PagingPosition(val page: Int = 1, val hasMore: Boolean = false)

    private val _schalePosition = MutableStateFlow(PagingPosition())
    val schalePosition: StateFlow<PagingPosition> = _schalePosition.asStateFlow()

    val urlBuilder by savedStateHandle.saved(MutableStateSerializer()) {
        mutableStateOf(lub)
    }

    // Use a smaller prefetch distance to avoid prepending an additional page after jumping
    val data = Pager(PagingConfig(25, prefetchDistance = 20)) {
        object : PagingSource<String, BaseGalleryInfo>() {
            override fun getRefreshKey(state: PagingState<String, BaseGalleryInfo>): String? = null
            override suspend fun load(params: LoadParams<String>) = withIOContext {
                // Route to Schale Network if the current gallery site is SITE_SCHALE
                if (Settings.gallerySite.value == EhUrl.SITE_SCHALE) {
                    val builder = urlBuilder.value
                    // A refresh restarts without a key, so the page a jump asked for is kept there.
                    val page = params.key?.toIntOrNull() ?: builder.page
                    // The same box drives browse, search and the account's favorites for Schale. The
                    // uploader mode carries a bare name, so give it the namespace Schale expects.
                    val keyword = builder.keyword?.takeIf { it.isNotBlank() }
                        ?.let { if (builder.mode == ListUrlBuilder.MODE_UPLOADER) "uploader:\"$it\"" else it }
                    return@withIOContext runSuspendCatching {
                        when {
                            builder.mode == ListUrlBuilder.MODE_SCHALE_FAVORITES -> {
                                // Favorites live on the account, so ask the user to sign in first instead
                                // of failing later inside the request with a confusing server error.
                                if (!SchaleEngine.isLoggedIn) {
                                    throw SchaleAuthException(
                                        "[Cuenta requerida]\nLos favoritos son de tu cuenta de Schale Network.\n" +
                                            "Inicia sesión en Configuración > EH > Cuenta de Schale Network.",
                                    )
                                }
                                // Keeps the favorite buttons of this list in step with the account.
                                SchaleEngine.fetchAccount()
                                SchaleEngine.getSchaleFavorites(page = page, keyword = keyword)
                            }
                            keyword != null -> SchaleEngine.searchSchale(keyword = keyword, page = page)
                            else -> SchaleEngine.getSchaleGalleryList(page = page)
                        }
                    }.foldToLoadResult { result ->
                        _schalePosition.value = PagingPosition(page = page, hasMore = result.next != null)
                        LoadResult.Page(result.galleryInfoList, result.prev, result.next)
                    }
                }

                val urlBuilder = urlBuilder.value
                if (urlBuilder.mode == MODE_TOPLIST) {
                    // TODO: Since we know total pages, let pager support jump
                    val key: Int
                    val url = mutex.withLock {
                        with(urlBuilder) {
                            params.key?.let { page = it.toInt() }
                            key = page
                            build()
                        }
                    }
                    val prev = (key - 1).takeIf { it > 0 }
                    val next = (key + 1).takeIf { it <= TOPLIST_PAGES }
                    runSuspendCatching {
                        EhEngine.getGalleryList(url)
                    }.foldToLoadResult { result ->
                        LoadResult.Page(result.galleryInfoList, prev?.toString(), next?.toString())
                    }
                } else {
                    val url = mutex.withLock {
                        with(urlBuilder) {
                            when (params) {
                                is LoadParams.Prepend -> setIndex(params.key, isNext = false)
                                is LoadParams.Append -> setIndex(params.key, isNext = true)
                                is LoadParams.Refresh -> params.key?.let { setIndex(it, false) }
                            }
                            build()
                        }
                    }
                    runSuspendCatching {
                        EhEngine.getGalleryList(url)
                    }.foldToLoadResult { result ->
                        LoadResult.Page(result.galleryInfoList, result.prev, result.next)
                    }
                }
            }
        }
    }.flow.cachedIn(viewModelScope)
}
