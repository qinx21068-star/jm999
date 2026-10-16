package com.jmreader.ui.screen.forum

import androidx.compose.ui.graphics.Color
import com.jmreader.data.dto.JmCommentDto
import com.jmreader.ui.components.flattenCommentReplies
import org.junit.Assert.*
import org.junit.Test

class CompactCommentTest {
    @Test fun linksUseTheSameIdBoundsAsPreviewsWithoutChangingPlainText() {
        val text = "前缀 JM0 与 Jm123、jm123456789012345678；xJM123 JM12x 也不要截取"
        val result = linkifyJm(text, Color.Blue)
        assertEquals(text, result.text)
        assertEquals(listOf("123", "123456789012345678"),
            result.getStringAnnotations("JM_LINK", 0, result.length).map { it.item })
    }

    @Test fun allZeroIdsDoNotDuplicateSurroundingText() {
        val text = "abc JM00 后面 JM123 末尾 JM0"
        val result = linkifyJm(text, Color.Blue)
        assertEquals(text, result.text)
        assertEquals(listOf("123"), result.getStringAnnotations("JM_LINK", 0, result.length).map { it.item })
    }

    @Test fun nestedRepliesKeepOrderAndParentNames() {
        val child = JmCommentDto(cid = "child", nickname = "乙")
        val root = JmCommentDto(cid = "root", nickname = "甲", replys = listOf(child))
        val sibling = JmCommentDto(cid = "sibling", nickname = "丙")
        val result = flattenCommentReplies(listOf(root, sibling))
        assertEquals(listOf("root", "child", "sibling"), result.map { it.first.cid })
        assertEquals(listOf(null, "甲", null), result.map { it.second })
        assertTrue(flattenCommentReplies(emptyList()).isEmpty())
    }
}
