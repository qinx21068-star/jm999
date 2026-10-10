package com.jmreader.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext

/** Short TTL/LRU cache with one bounded request per key and cancellation after the last reader leaves. */
internal class RequestCache<K, V>(
    private val scope: CoroutineScope,
    private val ttlMillis: Long,
    private val maxEntries: Int,
    private val cacheable: (V) -> Boolean = { true },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val requestTimeoutMillis: Long = 30_000,
) {
    private data class Entry<V>(val value: V, val expiresAt: Long)
    private class Pending<V> {
        lateinit var request: Deferred<V>
        var readers: Int = 0
    }
    private val mutex = Mutex()
    private val entries = LinkedHashMap<K, Entry<V>>(16, 0.75f, true)
    private val pending = mutableMapOf<K, Pending<V>>()

    init {
        require(ttlMillis > 0)
        require(maxEntries > 0)
        require(requestTimeoutMillis > 0)
    }

    /** A user refresh bypasses the cached value, preserving any existing in-flight request. */
    suspend fun refresh(key: K, loader: suspend () -> V): V {
        val prior = mutex.withLock { pending[key]?.request }
        if (prior != null) {
            try { prior.await() }
            catch (_: Exception) { coroutineContext.ensureActive() }
        }
        mutex.withLock { entries.remove(key) }
        return getOrLoad(key, loader)
    }

    suspend fun getOrLoad(key: K, loader: suspend () -> V): V {
        coroutineContext.ensureActive()
        val flight = mutex.withLock {
            val now = clock()
            entries[key]?.let { entry ->
                if (now < entry.expiresAt) return entry.value
                entries.remove(key)
            }
            val existing = pending[key]
            val request = existing ?: Pending<V>().also { created ->
                created.request = scope.async(start = CoroutineStart.LAZY) {
                    try {
                        val value = withTimeout(requestTimeoutMillis) { loader() }
                        if (cacheable(value)) mutex.withLock {
                            // A cancelled old generation must never populate a newer generation's cache.
                            if (pending[key] === created) {
                                entries[key] = Entry(value, clock() + ttlMillis)
                                while (entries.size > maxEntries) entries.remove(entries.keys.first())
                            }
                        }
                        value
                    } finally {
                        withContext(NonCancellable) {
                            mutex.withLock { if (pending[key] === created) pending.remove(key) }
                        }
                    }
                }
                pending[key] = created
            }
            request.readers++
            request.request.start()
            request
        }
        return try {
            flight.request.await()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    flight.readers--
                    if (flight.readers == 0 && pending[key] === flight) {
                        pending.remove(key)
                        flight.request.cancel()
                    }
                }
            }
        }
    }
}
