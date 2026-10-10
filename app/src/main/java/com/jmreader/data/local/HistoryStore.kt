package com.jmreader.data.local

import android.content.Context
import com.jmreader.data.dto.ComicBriefDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** 阅读历史：记录最近阅读的漫画与上次的章节/页码。 */
data class HistoryEntry(
    val comic: ComicBriefDto,
    val chapterId: String,
    val chapterTitle: String,
    val page: Int,
    val updatedAt: Long,
)

class HistoryStore(context: Context, moshi: Moshi, scope: CoroutineScope) {

    private val file = File(context.filesDir, "history.json")
    private val type = Types.newParameterizedType(List::class.java, HistoryEntry::class.java)
    private val adapter = moshi.adapter<List<HistoryEntry>>(type)

    private val _items = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val items: StateFlow<List<HistoryEntry>> = _items.asStateFlow()

    /** 串行化所有读写（含 load 与 upsert/clear/remove），避免竞态导致内存被旧值覆盖。 */
    private val mutex = Mutex()

    /** 加载完成信号。 [ensureLoaded] 挂起等待，避免在空初值上做"最近章节"判断。 */
    private val loaded = CompletableDeferred<Unit>()

    /** 异步加载，避免 App 启动时主线程同步 IO。 */
    init {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val loadedList = runCatching {
                    file.takeIf { it.exists() }?.readText()?.let { adapter.fromJson(it) } ?: emptyList()
                }.getOrDefault(emptyList())
                _items.value = loadedList.sortedByDescending { it.updatedAt }
            }
            loaded.complete(Unit)
        }
    }

    /** 挂起直到首次加载完成。 */
    suspend fun ensureLoaded() { loaded.await() }

    suspend fun upsert(comic: ComicBriefDto, chapterId: String, chapterTitle: String, page: Int) =
        withContext(Dispatchers.IO) {
            ensureLoaded()
            mutex.withLock {
                val list = _items.value.toMutableList()
                // 关键修复（Bug 34）：之前 removeAll { it.comic.id == comic.id } 会删除该漫画所有章节历史，
                // 只留最新一条。但 ReaderViewModel.load 按 (comicId, chapterId) 双键查进度，
                // 详情页"继续阅读"按 comicId 取最近章节。旧实现导致：
                // 读第1章到第5页 → 切第2章 → 第1章进度丢失 → 再点第1章从头开始。
                // 现在只删除同漫画同章节的旧记录，保留其他章节进度。
                list.removeAll { it.comic.id == comic.id && it.chapterId == chapterId }
                list.add(0, HistoryEntry(comic, chapterId, chapterTitle, page, System.currentTimeMillis()))
                // 先按全局更新时间排序，再限制每本漫画最多 20 条。
                // 旧实现先 groupBy 再 flatten，更新较新的条目可能被旧漫画分组顺序遮住，
                // 导致首页/详情页拿到的"最近阅读"不是实际最近的一条。
                val perComicCount = HashMap<String, Int>()
                val trimmed = list
                    .sortedByDescending { it.updatedAt }
                    .filter { entry ->
                        val count = perComicCount[entry.comic.id] ?: 0
                        if (count >= 20) false else {
                            perComicCount[entry.comic.id] = count + 1
                            true
                        }
                    }
                    .take(200)
                persist(trimmed)
                _items.value = trimmed
            }
        }

    suspend fun clear() = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            persist(emptyList())
            _items.value = emptyList()
        }
    }

    /** 删除一条章节进度；不误删同一本漫画其它章节的记录。 */
    suspend fun remove(comicId: String, chapterId: String? = null) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val list = _items.value.filterNot {
                it.comic.id == comicId && (chapterId == null || it.chapterId == chapterId)
            }
            persist(list)
            _items.value = list
        }
    }

    suspend fun importData(records: List<HistoryEntry>, merge: Boolean) = withContext(Dispatchers.IO) {
        ensureLoaded()
        mutex.withLock {
            val next = ((if (merge) _items.value else emptyList()) + records)
                .filter { it.comic.id.isNotBlank() && it.chapterId.isNotBlank() && it.page >= 0 }
                .sortedByDescending { it.updatedAt }
                .distinctBy { it.comic.id to it.chapterId }.take(200)
            persist(next)
            _items.value = next
        }
    }

    private fun persist(list: List<HistoryEntry>) {
        // 临时文件 + renameTo 原子替换，避免 load 读到写一半的 JSON
        runCatching {
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(adapter.toJson(list))
            if (!tmp.renameTo(file)) {
                file.writeText(adapter.toJson(list))
                tmp.delete()
            }
        }.getOrThrow()
    }
}
