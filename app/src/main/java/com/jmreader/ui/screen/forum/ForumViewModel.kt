package com.jmreader.ui.screen.forum

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jmreader.core.Logger
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.dto.JmCommentDto
import com.jmreader.data.dto.JmCommentPageDto
import com.jmreader.data.repository.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

internal data class ForumUiState(
    val comments: List<JmCommentDto> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val pageSize: Int = 10,
    val loading: Boolean = false,
    val error: String? = null,
    val previews: Map<String, ComicDetailDto> = emptyMap(),
    val previewErrors: Map<String, String> = emptyMap(),
) {
    val totalPages: Int get() = ((total.toLong() + pageSize - 1) / pageSize).toInt().coerceAtLeast(1)

    // Commit the requested page only after success. A failed page keeps the old page label/list.
    fun received(target: Int, result: JmCommentPageDto): ForumUiState = copy(
        comments = result.list,
        page = target,
        total = result.total.coerceAtLeast(0),
        pageSize = if (target == 1 && result.list.isNotEmpty()) result.list.size else pageSize,
        loading = false,
        error = null,
    )
}

/** Prefer the comment's album ID; otherwise preview the first explicit JM link. */
internal fun forumComicId(comment: JmCommentDto): String? {
    comment.aid?.trim()?.takeIf { it.matches(Regex("[0-9]{1,18}")) && it.any { c -> c != '0' } }?.let { return it }
    return Regex("(?i)(?<![a-z0-9_])JM([0-9]{1,18})(?![a-z0-9_])").find(comment.content)?.groupValues?.get(1)
        ?.takeIf { it.any { c -> c != '0' } }
}

/** Scoped to the forum back-stack entry: detail navigation does not discard comments/previews. */
internal class ForumViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ForumUiState())
    val state = _state.asStateFlow()
    private var pageJob: Job? = null
    private var previewJob: Job? = null
    private val previewLimit = Semaphore(3)
    private val previewInFlight = ConcurrentHashMap.newKeySet<String>()
    private val previewCache = ConcurrentHashMap<String, ComicDetailDto>()
    private var retryPage = 1
    var scrollIndex: Int = 0
        private set
    var scrollOffset: Int = 0
        private set

    init { loadPage(1) }

    fun saveScroll(page: Int, index: Int, offset: Int) {
        // Disposal of the previous page must not overwrite the new page's reset position.
        if (page != _state.value.page) return
        scrollIndex = index.coerceAtLeast(0)
        scrollOffset = offset.coerceAtLeast(0)
    }

    fun dismissError() { _state.update { it.copy(error = null) } }
    fun retry() = loadPage(retryPage)

    fun loadPage(target: Int) {
        if (target < 1 || _state.value.loading) return
        retryPage = target
        pageJob?.cancel()
        previewJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        pageJob = viewModelScope.launch {
            try {
                var result = container.repository.forum("manhua", null, null, target)
                if (result is Resource.Error) {
                    delay(800)
                    result = container.repository.forum("manhua", null, null, target)
                }
                when (val response = result) {
                    is Resource.Success -> {
                        if (target != _state.value.page) {
                            scrollIndex = 0
                            scrollOffset = 0
                        }
                        _state.update { it.received(target, response.data) }
                    }
                    is Resource.Error -> _state.update { it.copy(loading = false, error = response.message) }
                    Resource.Loading -> _state.update { it.copy(loading = false, error = "讨论接口未返回数据，请重试") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = Logger.brief(e)) }
            }
            // A cancelled page request does not start work for a stale page.
            previewJob = viewModelScope.launch { loadPagePreviews() }
        }
    }

    private suspend fun loadPagePreviews() = coroutineScope {
        _state.value.comments.mapNotNull(::forumComicId).distinct().forEach { id ->
            if (id !in _state.value.previews && id !in _state.value.previewErrors) {
                launch { loadPreview(id) }
            }
        }
    }

    fun retryPreview(id: String) {
        if (id !in _state.value.comments.mapNotNull(::forumComicId)) return
        _state.update { it.copy(previewErrors = it.previewErrors - id) }
        viewModelScope.launch { loadPreview(id) }
    }

    private suspend fun loadPreview(id: String) {
        if (!previewInFlight.add(id)) return
        try {
            previewLimit.withPermit {
                val result = previewCache[id]?.let { Resource.Success(it) }
                    ?: container.repository.comicDetail(id)
                when (result) {
                    is Resource.Success -> {
                        previewCache[id] = result.data
                        while (previewCache.size > 80) previewCache.remove(previewCache.keys.first())
                        _state.update { it.copy(previews = previewCache.toMap(), previewErrors = it.previewErrors - id) }
                    }
                    is Resource.Error -> _state.update { it.copy(previewErrors = (it.previewErrors + (id to result.message)).toList().takeLast(80).toMap()) }
                    Resource.Loading -> _state.update { it.copy(previewErrors = it.previewErrors + (id to "详情未返回，请重试")) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(previewErrors = it.previewErrors + (id to Logger.brief(e))) }
        } finally {
            previewInFlight.remove(id)
        }
    }
}

internal class ForumVMFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ForumViewModel(container) as T
}
