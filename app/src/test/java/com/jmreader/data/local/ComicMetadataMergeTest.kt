package com.jmreader.data.local

import com.jmreader.data.dto.ComicBriefDto
import org.junit.Assert.*
import org.junit.Test

class ComicMetadataMergeTest {
    @Test fun fillsDateWithoutReplacingCurrentMetadataFromAStaleSnapshot() {
        val current = ComicBriefDto(id = "123", name = "当前标题", author = "当前作者", tags = listOf("当前标签"))
        val incoming = current.copy(name = "旧标题", author = "旧作者", publishTime = "2026-10-01")
        val result = mergeComicMetadata(current, incoming)
        assertEquals("2026-10-01", result.publishTime)
        assertEquals(current.name, result.name)
        assertEquals(current.author, result.author)
        assertEquals(current.tags, result.tags)
    }

    @Test fun missingOrMismatchedDataCannotFabricateAPublishDate() {
        val current = ComicBriefDto(id = "123", name = "标题")
        assertNull(mergeComicMetadata(current, current.copy(publishTime = " ")).publishTime)
        assertEquals(current, mergeComicMetadata(current, current.copy(id = "456", publishTime = "2026-10-01")))
        assertTrue(current.needsMetadataEnrichment())
    }
}
