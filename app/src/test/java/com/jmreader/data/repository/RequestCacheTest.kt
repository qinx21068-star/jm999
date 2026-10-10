package com.jmreader.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class RequestCacheTest {
    @Test fun concurrentReadersShareOneRequestAndOneReaderCanCancel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val cache = RequestCache<String, String>(scope, 1000, 2)
        suspend fun load(): String { count.incrementAndGet(); entered.complete(Unit); finish.await(); return "chapter" }
        try {
            val first = async { cache.getOrLoad("123") { load() } }
            entered.await()
            val readers = List(8) { async(start = CoroutineStart.UNDISPATCHED) { cache.getOrLoad("123") { load() } } }
            first.cancelAndJoin()
            finish.complete(Unit)
            assertEquals(List(8) { "chapter" }, readers.awaitAll())
            assertEquals(1, count.get())
        } finally { scope.cancel() }
    }

    @Test fun lastReaderLeavingCancelsUnderlyingRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val cache = RequestCache<String, String>(scope, 1000, 2)
        try {
            val reader = async {
                cache.getOrLoad("image-list") {
                    entered.complete(Unit)
                    try { kotlinx.coroutines.awaitCancellation() }
                    finally { cancelled.complete(Unit) }
                }
            }
            entered.await()
            reader.cancelAndJoin()
            kotlinx.coroutines.withTimeout(2_000) { cancelled.await() }
        } finally { scope.cancel() }
    }

    @Test fun userRefreshReplacesCachedChapterDetail() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val cache = RequestCache<String, String>(scope, 1000, 2)
        try {
            assertEquals("old", cache.getOrLoad("id") { "old" })
            assertEquals("updated", cache.refresh("id") { "updated" })
            assertEquals("updated", cache.getOrLoad("id") { "unexpected" })
        } finally { scope.cancel() }
    }

    @Test fun errorsAreNotCachedAndEntriesExpire() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var clock = 0L
        val count = AtomicInteger()
        val cache = RequestCache<String, String>(scope, 1000, 2, cacheable = { it != "error" }, clock = { clock })
        try {
            assertEquals("error", cache.getOrLoad("id") { count.incrementAndGet(); "error" })
            assertEquals("ok", cache.getOrLoad("id") { count.incrementAndGet(); "ok" })
            assertEquals("ok", cache.getOrLoad("id") { count.incrementAndGet(); "unexpected" })
            clock = 1001
            assertEquals("new", cache.getOrLoad("id") { count.incrementAndGet(); "new" })
            assertEquals(3, count.get())
        } finally { scope.cancel() }
    }
}
