package com.jmreader.ui.screen.search

import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.PageResultDto
import com.jmreader.data.repository.Resource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SearchBatchPagerTest {
    private fun item(id: Int) = ComicBriefDto(id = id.toString(), name = "Comic $id")
    private fun success(result: Resource<SearchBatchPager.Batch>) = (result as Resource.Success).data

    @Test fun carriesRemainderFromOversizedSourcePage() = runBlocking {
        val pager = SearchBatchPager()
        val fetch: suspend (Int) -> Resource<PageResultDto> = { page ->
            Resource.Success(PageResultDto(page, 200, List(100) { item((page - 1) * 100 + it) }))
        }
        val first = success(pager.load(1, fetch))
        val second = success(pager.load(2, fetch))
        val third = success(pager.load(3, fetch))
        assertEquals((0..79).map(Int::toString), first.items.map { it.id })
        assertEquals((80..159).map(Int::toString), second.items.map { it.id })
        assertEquals((160..199).map(Int::toString), third.items.map { it.id })
        assertTrue(third.endReached)
        assertEquals(200, pager.loadedCountThrough(3))
    }

    @Test fun aggregatesTwentyPerPageAndHonorsUnknownTotal() = runBlocking {
        val pager = SearchBatchPager()
        val fetch: suspend (Int) -> Resource<PageResultDto> = { page ->
            Resource.Success(PageResultDto(page, null, if (page <= 5) List(20) { item((page - 1) * 20 + it) } else emptyList()))
        }
        assertEquals(80, success(pager.load(1, fetch)).items.size)
        val last = success(pager.load(2, fetch))
        assertEquals(20, last.items.size)
        assertTrue(last.endReached)
        assertEquals(100, pager.loadedCountThrough(2))
    }

    @Test fun failedBatchDoesNotCommitOrSkipPreviouslyFetchedRecords() = runBlocking {
        val pager = SearchBatchPager(batchSize = 3)
        var fail = true
        val fetch: suspend (Int) -> Resource<PageResultDto> = { page ->
            if (page == 2 && fail) Resource.Error("temporary")
            else Resource.Success(PageResultDto(page, 5, when (page) {
                1 -> listOf(item(1), item(2))
                2 -> listOf(item(3), item(4))
                3 -> listOf(item(5))
                else -> emptyList()
            }))
        }
        assertTrue(pager.load(1, fetch) is Resource.Error)
        assertEquals(0, pager.loadedCountThrough(1))
        assertTrue(pager.load(2, fetch) is Resource.Error)
        fail = false
        assertEquals(listOf("1", "2", "3"), success(pager.load(1, fetch)).items.map { it.id })
        assertEquals(listOf("4", "5"), success(pager.load(2, fetch)).items.map { it.id })
    }

    @Test fun resetDuringRequestCannotCommitOldQueryCursor() = runBlocking {
        val pager = SearchBatchPager()
        val response = pager.load(1) { page ->
            pager.reset()
            Resource.Success(PageResultDto(page, 1, listOf(item(1))))
        }
        assertTrue(response is Resource.Error)
        assertEquals(0, pager.loadedCountThrough(1))
    }
}
