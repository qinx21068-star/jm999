package com.jmreader.ui.screen.search

import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.PageResultDto
import com.jmreader.data.repository.Resource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A batch cursor includes the offset within a source page, so overflow is never discarded. */
internal class SearchBatchPager(
    private val batchSize: Int = 80,
    private val maxSourceRequests: Int = 16,
) {
    private data class Cursor(val sourcePage: Int = 1, val offset: Int = 0, val consumed: Long = 0)
    data class Batch(val items: List<ComicBriefDto>, val total: Int?, val endReached: Boolean)

    private val starts = mutableMapOf(1 to Cursor())
    private val batchIds = mutableMapOf<Int, Set<String>>()
    private val terminal = mutableSetOf<Int>()
    private var generation = 0L

    init {
        require(batchSize > 0)
        require(maxSourceRequests > 0)
    }

    fun reset() {
        generation++
        starts.clear()
        starts[1] = Cursor()
        batchIds.clear()
        terminal.clear()
    }

    fun loadedCountThrough(batch: Int): Int = batchIds.filterKeys { it <= batch }.values.flatten().toSet().size
    fun isTerminal(batch: Int): Boolean = batch in terminal

    suspend fun load(batch: Int, fetch: suspend (Int) -> Resource<PageResultDto>): Resource<Batch> {
        val snapshot = generation
        var cursor = starts[batch] ?: return Resource.Error("缺少上一批游标，请从首批重新加载")
        val result = linkedMapOf<String, ComicBriefDto>()
        var total: Int? = null
        var ended = false
        var requests = 0
        while (result.size < batchSize && requests++ < maxSourceRequests && !ended) {
            when (val response = fetch(cursor.sourcePage)) {
                is Resource.Error -> return response
                Resource.Loading -> return Resource.Error("搜索接口未返回结果")
                is Resource.Success -> {
                    total = response.data.total?.takeIf { it >= 0 } ?: total
                    val source = response.data.items
                    if (source.isEmpty()) {
                        ended = true
                        break
                    }
                    if (cursor.offset > source.size) return Resource.Error("搜索结果已变化，请刷新后重试")
                    var index = cursor.offset
                    var consumed = cursor.consumed
                    while (index < source.size && result.size < batchSize) {
                        val comic = source[index++]
                        consumed++
                        if (comic.id.isNotBlank()) result.putIfAbsent(comic.id, comic)
                    }
                    cursor = if (index < source.size) Cursor(cursor.sourcePage, index, consumed)
                        else Cursor(cursor.sourcePage + 1, 0, consumed)
                    ended = cursor.offset == 0 && total?.let { cursor.consumed >= it } == true
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (snapshot != generation) return Resource.Error("搜索条件已变化")
        // Commit only after the entire batch succeeds, including requests after the first source page.
        val oldNext = starts[batch + 1]
        if (oldNext != null && oldNext != cursor) {
            starts.keys.filter { it > batch }.forEach { starts.remove(it) }
            batchIds.keys.filter { it > batch }.forEach { batchIds.remove(it) }
            terminal.removeAll { it > batch }
        }
        starts[batch + 1] = cursor
        batchIds[batch] = result.keys.toSet()
        if (ended) terminal.add(batch) else terminal.remove(batch)
        return Resource.Success(Batch(result.values.toList(), total, ended))
    }
}
