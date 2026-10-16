package com.jmreader.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.local.BlockMode
import com.jmreader.data.repository.Resource
import com.jmreader.ui.components.ComicGridCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class HomeRecommendationState(
    val items: List<ComicBriefDto> = emptyList(),
    val loading: Boolean = false,
    val coverHiddenIds: Set<String> = emptySet(),
    val error: String? = null,
)

/** Requests survive detail navigation; input changes cancel and clear stale recommendations. */
internal class HomeRecommendationViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeRecommendationState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                container.settingsStore.settings.map { it.homeTagRecommendations to it.blockMode }.distinctUntilChanged(),
                container.searchTemplatesStore.templates.map { templates ->
                    templates.mapNotNull { it.tag?.trim()?.takeIf(String::isNotBlank) }
                        .distinctBy { it.lowercase() }
                }.distinctUntilChanged(),
                container.blockedTagsStore.allRules,
            ) { settings, tags, rules -> Triple(settings, tags, rules) }
                .distinctUntilChanged()
                .collectLatest { (settings, savedTags, configured) ->
                    _state.value = HomeRecommendationState()
                    if (!settings.first || savedTags.isEmpty()) return@collectLatest
                    _state.value = HomeRecommendationState(loading = true)
                    try {
                        val rules = container.blockedTagsStore.normalizeRules(configured.first, configured.second, configured.third)
                        val tags = savedTags.shuffled().take(4)
                        val pages = coroutineScope {
                            tags.map { tag ->
                                async { container.repository.search(tag, 1, "latest") }
                            }.awaitAll()
                        }
                        if (pages.all { it is Resource.Error }) error("所有模板搜索均失败")
                        // Interleave sources so one large result set cannot crowd out other tags.
                        val groups = pages.map { (it as? Resource.Success)?.data?.items.orEmpty().shuffled() }
                        val candidates = buildList<ComicBriefDto> {
                            val longest = groups.maxOfOrNull { it.size } ?: 0
                            for (index in 0 until longest) groups.forEach { group -> group.getOrNull(index)?.let(::add) }
                        }.filter { it.id.isNotBlank() }.distinctBy { it.id }.take(16)
                        val limit = Semaphore(4)
                        val enriched = coroutineScope {
                            candidates.map { comic ->
                                async {
                                    limit.withPermit {
                                        val result = withTimeoutOrNull(8_000L) { container.repository.comicDetail(comic.id) }
                                        val detail = (result as? Resource.Success)?.data
                                        if (detail == null) {
                                            comic.takeIf { rules.isEmpty() }
                                        } else {
                                            comic.copy(
                                                name = detail.name.ifBlank { comic.name },
                                                author = detail.author?.takeIf(String::isNotBlank) ?: comic.author,
                                                tags = detail.tags.ifEmpty { comic.tags },
                                                cover = detail.cover?.takeIf(String::isNotBlank) ?: comic.cover,
                                                likes = detail.likes ?: comic.likes,
                                                views = detail.views ?: comic.views,
                                                publishTime = detail.publishTime?.takeIf(String::isNotBlank) ?: comic.publishTime,
                                            )
                                        }
                                    }
                                }
                            }.awaitAll().filterNotNull()
                        }
                        _state.value = withContext(Dispatchers.Default) {
                            val hiddenCovers = mutableSetOf<String>()
                            val visible = enriched.filter { comic ->
                                if (rules.tags.isNotEmpty() && comic.tags.isEmpty()) return@filter false
                                if (rules.authors.isNotEmpty() && comic.author.isNullOrBlank()) return@filter false
                                val blocked = container.blockedTagsStore.isBlocked(comic.tags, comic.name, comic.author, rules)
                                if (blocked && settings.second == BlockMode.COVER_ONLY) hiddenCovers.add(comic.id)
                                !blocked || settings.second == BlockMode.COVER_ONLY
                            }.take(12)
                            HomeRecommendationState(
                                items = visible,
                                coverHiddenIds = hiddenCovers.intersect(visible.mapTo(HashSet()) { it.id }),
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        com.jmreader.core.Logger.w("HomeRecommendations", "推荐加载失败: ${com.jmreader.core.Logger.brief(e)}")
                        _state.value = HomeRecommendationState(error = "模板推荐暂时加载失败")
                    }
                }
        }
    }
}

internal class HomeRecommendationVmFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeRecommendationViewModel(container) as T
}

@Composable
internal fun TemplateRecommendationRow(
    items: List<ComicBriefDto>,
    loading: Boolean,
    coverHiddenIds: Set<String>,
    error: String?,
    onClick: (ComicBriefDto) -> Unit,
) {
    if (items.isEmpty() && !loading && error == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text("模板标签推荐", style = MaterialTheme.typography.titleSmall)
        when {
            loading -> CircularProgressIndicator(Modifier.size(22.dp))
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            else -> LazyRow(contentPadding = PaddingValues(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { comic ->
                    ComicGridCard(
                        item = comic,
                        onClick = { onClick(comic) },
                        coverHidden = comic.id in coverHiddenIds,
                        modifier = Modifier.width(132.dp),
                    )
                }
            }
        }
    }
}
