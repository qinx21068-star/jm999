package com.jmreader.data.local

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** A saved search that can be reused from the search screen. */
@com.squareup.moshi.JsonClass(generateAdapter = false)
data class SearchTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val query: String = "",
    val order: String = "latest",
    val tag: String? = null,
)

/**
 * JSON-backed search templates. The file remains a plain JSON array so it can be
 * read by older tooling and changed without a schema migration.
 */
class SearchTemplatesStore(
    context: Context,
    moshi: Moshi,
    scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "search_templates.json")
    private val listType = Types.newParameterizedType(List::class.java, SearchTemplate::class.java)
    private val adapter = moshi.adapter<List<SearchTemplate>>(listType)
    private val mutex = Mutex()
    private val _templates = MutableStateFlow<List<SearchTemplate>>(emptyList())
    val templates: StateFlow<List<SearchTemplate>> = _templates.asStateFlow()

    private val loaded = kotlinx.coroutines.CompletableDeferred<Unit>()
    suspend fun ensureLoaded() { loaded.await() }

    suspend fun importData(records: List<SearchTemplate>, merge: Boolean) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val next = ((if (merge) _templates.value else emptyList()) + records)
                .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.query.isNotBlank() }
                .distinctBy { it.id }.take(MAX_TEMPLATES)
            writeFile(next)
            _templates.value = next
        }
    }

    init {
        scope.launch(Dispatchers.IO) {
            try {
                mutex.withLock { _templates.value = readFile() }
            } finally { loaded.complete(Unit) }
        }
    }

    suspend fun save(name: String, query: String, order: String, tag: String?): SearchTemplate? =
        withContext(Dispatchers.IO) {
            ensureLoaded()
            val cleanName = name.trim().replace('\n', ' ')
            val cleanQuery = query.trim().replace('\n', ' ')
            if (cleanName.isEmpty() || cleanQuery.isEmpty()) return@withContext null
            val template = SearchTemplate(
                name = cleanName,
                query = cleanQuery,
                order = order.trim().ifBlank { "latest" },
                tag = tag?.trim()?.takeIf { it.isNotEmpty() },
            )
            mutex.withLock {
                val updated = listOf(template) + _templates.value.filterNot {
                    it.name == template.name
                }
                val next = updated.take(MAX_TEMPLATES)
                writeFile(next)
                _templates.value = next
            }
            template
        }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val updated = _templates.value.filterNot { it.id == id }
            if (updated.size != _templates.value.size) {
                writeFile(updated)
                _templates.value = updated
            }
        }
    }

    private fun readFile(): List<SearchTemplate> {
        if (!file.exists() && !File(file.path + ".bak").exists()) return emptyList()
        return runCatching {
            android.util.AtomicFile(file).openRead().bufferedReader().use { adapter.fromJson(it.readText()).orEmpty() }
                .filter { it.id.isNotBlank() && it.name.isNotBlank() && it.query.isNotBlank() }
                .take(MAX_TEMPLATES)
        }.getOrElse {
            com.jmreader.core.Logger.w(
                "SearchTemplates",
                "读取模板失败: ${com.jmreader.core.Logger.brief(it)}",
            )
            emptyList()
        }
    }

    private fun writeFile(value: List<SearchTemplate>) {
        val atomic = android.util.AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(adapter.toJson(value).toByteArray()); atomic.finishWrite(output) }
        catch (e: Throwable) { atomic.failWrite(output); throw e }
    }

    private companion object {
        const val MAX_TEMPLATES = 50
    }
}
