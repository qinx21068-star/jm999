package com.jmreader.data.local

import android.content.Context
import android.util.AtomicFile
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@JsonClass(generateAdapter = false)
data class FavoriteUpdate(val comicId: String, val knownChapterIds: Set<String>, val hasUpdate: Boolean = false, val checkedAt: Long = 0)

/** Explicit checks compare chapter IDs; the first check establishes a baseline. */
class FavoriteUpdatesStore(context: Context, moshi: Moshi, scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, "favorite_updates.json"))
    private val adapter = moshi.adapter<List<FavoriteUpdate>>(Types.newParameterizedType(List::class.java, FavoriteUpdate::class.java))
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private val _items = MutableStateFlow<List<FavoriteUpdate>>(emptyList())
    val items = _items.asStateFlow()
    init {
        scope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    _items.value = runCatching { file.openRead().bufferedReader().use { adapter.fromJson(it.readText()).orEmpty() } }.getOrDefault(emptyList())
                }
            } finally { loaded.complete(Unit) }
        }
    }
    suspend fun check(comicId: String, chapters: Set<String>) = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            val old = _items.value.firstOrNull { it.comicId == comicId }
            val next = FavoriteUpdate(comicId, old?.knownChapterIds ?: chapters,
                old != null && (chapters - old.knownChapterIds).isNotEmpty(), System.currentTimeMillis())
            write(_items.value.filterNot { it.comicId == comicId } + next)
        }
    }
    suspend fun markRead(comicId: String, chapters: Set<String>) = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock { write(_items.value.filterNot { it.comicId == comicId } + FavoriteUpdate(comicId, chapters, false, System.currentTimeMillis())) }
    }
    private fun write(value: List<FavoriteUpdate>) {
        val output = file.startWrite()
        try { output.write(adapter.toJson(value).toByteArray()); file.finishWrite(output); _items.value = value }
        catch (e: Throwable) { file.failWrite(output); throw e }
    }
}
