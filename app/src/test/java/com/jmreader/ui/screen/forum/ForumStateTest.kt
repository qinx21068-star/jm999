package com.jmreader.ui.screen.forum

import com.jmreader.data.dto.JmCommentDto
import com.jmreader.data.dto.JmCommentPageDto
import org.junit.Assert.*
import org.junit.Test

class ForumStateTest {
    @Test fun successfulPageCommitsPageAndContentTogether() {
        val old = ForumUiState(comments = listOf(JmCommentDto(cid = "old")), page = 2, total = 27, loading = true)
        val next = old.received(3, JmCommentPageDto(listOf(JmCommentDto(cid = "new")), 27))
        assertEquals(3, next.page)
        assertEquals("new", next.comments.single().cid)
        assertFalse(next.loading)
        assertNull(next.error)
        assertEquals(3, next.totalPages)
    }

    @Test fun failedPageKeepsPreviousPageAndComments() {
        val old = ForumUiState(comments = listOf(JmCommentDto(cid = "old")), page = 2, total = 27)
        val failed = old.copy(loading = false, error = "network error")
        assertEquals(old.page, failed.page)
        assertEquals(old.comments, failed.comments)
    }

    @Test fun firstPageDeterminesPageSizeButShortLastPageDoesNotChangeIt() {
        val first = ForumUiState().received(1, JmCommentPageDto(List(20) { JmCommentDto(cid = "$it") }, 41))
        assertEquals(3, first.totalPages)
        val last = first.received(3, JmCommentPageDto(listOf(JmCommentDto(cid = "40")), 41))
        assertEquals(20, last.pageSize)
        assertEquals(3, last.totalPages)
    }

    @Test fun emptyAndLargeTotalsAreSafe() {
        assertEquals(1, ForumUiState().received(1, JmCommentPageDto()).totalPages)
        assertEquals(214748365, ForumUiState(total = Int.MAX_VALUE).totalPages)
    }

    @Test fun previewPrefersAidOtherwiseExplicitJmLink() {
        assertEquals("123", forumComicId(JmCommentDto(aid = "123", content = "JM456")))
        assertEquals("456", forumComicId(JmCommentDto(content = "推荐 jm456。")))
        assertEquals("789", forumComicId(JmCommentDto(aid = "0", content = "JM789")))
        assertNull(forumComicId(JmCommentDto(aid = "../album", content = "123456")))
        assertNull(forumComicId(JmCommentDto(content = "JM0")))
    }
}
