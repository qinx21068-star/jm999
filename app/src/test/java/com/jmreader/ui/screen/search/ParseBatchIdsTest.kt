package com.jmreader.ui.screen.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v27.16：Search 批量 JM 号解析 [parseBatchIdsInput] 的单元测试。
 *
 * 规则：
 * - 1~18 位非全零纯数字，或大小写不敏感的 JM 前缀 + 1~18 位非全零数字；
 * - 归一化为纯数字并去重；
 * - 拆分 token 数 ≥ 2 才算批量，否则返回 null（普通搜索）。
 */
class ParseBatchIdsTest {

    @Test fun plainDigitsBatch() {
        assertEquals(listOf("123", "456"), parseBatchIdsInput("123 456"))
    }

    @Test fun jmPrefixNormalizedToDigits() {
        assertEquals(listOf("123", "456"), parseBatchIdsInput("JM123 jm456"))
    }

    @Test fun mixedCaseJmPrefix() {
        assertEquals(listOf("123", "456"), parseBatchIdsInput("jM123 Jm456"))
    }

    @Test fun supportsChineseAndAsciiSeparators() {
        assertEquals(listOf("1", "2", "3", "4"), parseBatchIdsInput("1,2，3。4;5".replace(";5", "")))
        assertEquals(listOf("1", "2", "3", "4"), parseBatchIdsInput("1 2，3。4"))
        assertEquals(listOf("1", "2"), parseBatchIdsInput("1；2"))
    }

    @Test fun deduplicatesPreservingOrder() {
        assertEquals(listOf("456", "123"), parseBatchIdsInput("456 123 456"))
    }

    @Test fun jmPrefixAndPlainFormCollapseToSameId() {
        assertEquals(listOf("123"), parseBatchIdsInput("JM123 123"))
    }

    @Test fun singleTokenIsNormalSearch() {
        assertNull(parseBatchIdsInput("123"))
        assertNull(parseBatchIdsInput("JM123"))
    }

    @Test fun englishPhraseIsNormalSearch() {
        assertNull(parseBatchIdsInput("naruto bleach"))
        assertNull(parseBatchIdsInput("hello world"))
    }

    @Test fun mixedWordsAndIdsIsNormalSearch() {
        assertNull(parseBatchIdsInput("JM123 hello"))
        assertNull(parseBatchIdsInput("123 bleach"))
    }

    @Test fun allZeroRejected() {
        assertNull(parseBatchIdsInput("0 123"))
        assertNull(parseBatchIdsInput("000 111"))
        assertNull(parseBatchIdsInput("JM000 JM111"))
    }

    @Test fun lengthBoundary() {
        val d18 = "123456789012345678"
        val d19 = "1234567890123456789"
        assertEquals(listOf(d18, "1"), parseBatchIdsInput("$d18 1"))
        assertNull(parseBatchIdsInput("$d19 1"))
    }

    @Test fun bareJmPrefixRejected() {
        assertNull(parseBatchIdsInput("JM 123"))
        assertNull(parseBatchIdsInput("JM jm"))
    }

    @Test fun blankInputRejected() {
        assertNull(parseBatchIdsInput(""))
        assertNull(parseBatchIdsInput("   "))
        assertNull(parseBatchIdsInput(" , ， "))
    }
}
