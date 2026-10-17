package com.jmreader.data.local

import android.content.Context
import android.net.Uri
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.FavoriteEntry
import com.jmreader.data.dto.FavoriteStoreData
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 旧版（`com.jmreader`，v1.2.0 及更早）没有导出功能，且 `allowBackup=false`，
 * 因此只能由用户自行取出其私有目录下的 JSON 文件后在这里导入。文件格式见
 * [FavoritesStore]（v1 裸数组 / v2 对象）、[HistoryStore]、[BrowseHistoryStore]。
 */
internal enum class LegacyKind { FAVORITES_V1, FAVORITES_V2, HISTORY, BROWSE, TEMPLATES, BACKUP, UNKNOWN }

/** 按内容判断旧版数据文件类型。纯函数，便于单元测试。 */
internal fun detectLegacyKind(text: String): LegacyKind {
    val t = text.trimStart()
    if (t.isEmpty()) return LegacyKind.UNKNOWN
    return try {
        when {
            t.startsWith("[") -> {
                val arr = JSONArray(t)
                val first = if (arr.length() > 0) arr.optJSONObject(0) else null
                when {
                    first == null -> LegacyKind.UNKNOWN
                    first.has("chapterId") || (first.has("page") && first.has("comic")) -> LegacyKind.HISTORY
                    first.has("updatedAt") && first.has("comic") -> LegacyKind.BROWSE
                    first.has("query") && first.has("order") -> LegacyKind.TEMPLATES
                    first.has("comic") -> LegacyKind.FAVORITES_V1 // 数组形式的 FavoriteEntry
                    first.has("id") && first.has("name") -> LegacyKind.FAVORITES_V1 // v1.2.0 的裸 ComicBriefDto 数组
                    else -> LegacyKind.UNKNOWN
                }
            }
            t.startsWith("{") -> {
                val obj = JSONObject(t)
                when {
                    obj.optString("format") == "jmreader-backup" -> LegacyKind.BACKUP
                    obj.has("entries") || obj.has("folders") -> LegacyKind.FAVORITES_V2
                    else -> LegacyKind.UNKNOWN
                }
            }
            else -> LegacyKind.UNKNOWN
        }
    } catch (_: Throwable) {
        LegacyKind.UNKNOWN
    }
}

/** 单个文件解析出的内容。 */
internal data class LegacyBundle(
    val favorites: List<FavoriteEntry> = emptyList(),
    val folders: List<com.jmreader.data.dto.FavoriteFolder> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val browse: List<BrowseEntry> = emptyList(),
    val templates: List<SearchTemplate> = emptyList(),
    val skipped: List<String> = emptyList(),
)

internal class LegacyDataImporter(private val container: AppContainer) {
    private val context: Context = container.applicationContext
    private val moshi = com.jmreader.data.api.NetworkFactory.moshi
    private val comicsAdapter = moshi.adapter<List<ComicBriefDto>>(
        Types.newParameterizedType(List::class.java, ComicBriefDto::class.java),
    )
    private val entriesAdapter = moshi.adapter<List<FavoriteEntry>>(
        Types.newParameterizedType(List::class.java, FavoriteEntry::class.java),
    )
    private val favoriteDataAdapter = moshi.adapter(FavoriteStoreData::class.java)
    private val historyAdapter = moshi.adapter<List<HistoryEntry>>(
        Types.newParameterizedType(List::class.java, HistoryEntry::class.java),
    )
    private val browseAdapter = moshi.adapter<List<BrowseEntry>>(
        Types.newParameterizedType(List::class.java, BrowseEntry::class.java),
    )
    private val templateAdapter = moshi.adapter<List<SearchTemplate>>(
        Types.newParameterizedType(List::class.java, SearchTemplate::class.java),
    )

    /** 读取并解析一个文件；无法识别时在 [LegacyBundle.skipped] 里记录原因。 */
    suspend fun read(uri: Uri, name: String): LegacyBundle = withContext(Dispatchers.IO) {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                require(bytes.size <= MAX_BYTES) { "文件超过 8MB" }
                bytes.toString(Charsets.UTF_8)
            }
        }.getOrNull() ?: return@withContext LegacyBundle(skipped = listOf("$name：无法读取"))

        try {
            when (detectLegacyKind(text)) {
                LegacyKind.FAVORITES_V1 -> {
                    val arr = JSONArray(text)
                    val hasWrapper = arr.length() > 0 && arr.optJSONObject(0)?.has("comic") == true
                    val favorites = if (hasWrapper) {
                        entriesAdapter.fromJson(text).orEmpty()
                    } else {
                        comicsAdapter.fromJson(text).orEmpty().map { FavoriteEntry(it, null) }
                    }
                    LegacyBundle(favorites = favorites.filter { it.comic.id.isNotBlank() })
                }
                LegacyKind.FAVORITES_V2 -> favoriteDataAdapter.fromJson(text)
                    ?.let { LegacyBundle(favorites = it.entries.filter { e -> e.comic.id.isNotBlank() }, folders = it.folders) }
                    ?: LegacyBundle(skipped = listOf("$name：收藏格式无效"))
                LegacyKind.HISTORY -> LegacyBundle(
                    history = historyAdapter.fromJson(text).orEmpty()
                        .filter { it.comic.id.isNotBlank() && it.chapterId.isNotBlank() },
                )
                LegacyKind.BROWSE -> LegacyBundle(
                    browse = browseAdapter.fromJson(text).orEmpty().filter { it.comic.id.isNotBlank() },
                )
                LegacyKind.TEMPLATES -> LegacyBundle(
                    templates = templateAdapter.fromJson(text).orEmpty().filter { it.query.isNotBlank() },
                )
                LegacyKind.BACKUP -> LegacyBundle(skipped = listOf("$name：这是新版备份，请用上方「导入备份」"))
                LegacyKind.UNKNOWN -> LegacyBundle(skipped = listOf("$name：无法识别的数据文件"))
            }
        } catch (e: Throwable) {
            LegacyBundle(skipped = listOf("$name：解析失败（${e.message ?: "格式不符"}）"))
        }
    }

    /** 合并导入。全部按 id 去重，不删除现有数据。 */
    suspend fun apply(bundle: LegacyBundle): String = withContext(Dispatchers.IO) {
        container.favoritesStore.ensureLoaded()
        container.historyStore.ensureLoaded()
        container.browseHistoryStore.ensureLoaded()
        container.searchTemplatesStore.ensureLoaded()

        var favAdded = 0
        if (bundle.favorites.isNotEmpty() || bundle.folders.isNotEmpty()) {
            val before = container.favoritesStore.entries.value.map { it.comic.id }.toSet()
            container.favoritesStore.importData(
                FavoriteStoreData(version = 2, folders = bundle.folders, entries = bundle.favorites),
                merge = true,
            )
            favAdded = container.favoritesStore.entries.value.count { it.comic.id !in before }
        }
        var historyAdded = 0
        if (bundle.history.isNotEmpty()) {
            val before = container.historyStore.items.value.map { it.comic.id to it.chapterId }.toSet()
            container.historyStore.importData(bundle.history, merge = true)
            historyAdded = container.historyStore.items.value.count { (it.comic.id to it.chapterId) !in before }
        }
        var browseAdded = 0
        if (bundle.browse.isNotEmpty()) {
            val before = container.browseHistoryStore.items.value.map { it.comic.id }.toSet()
            container.browseHistoryStore.importData(bundle.browse, merge = true)
            browseAdded = container.browseHistoryStore.items.value.count { it.comic.id !in before }
        }
        var templatesAdded = 0
        if (bundle.templates.isNotEmpty()) {
            val before = container.searchTemplatesStore.templates.value.size
            container.searchTemplatesStore.importData(bundle.templates, merge = true)
            templatesAdded = container.searchTemplatesStore.templates.value.size - before
        }

        buildString {
            append("导入完成：收藏 +$favAdded、阅读历史 +$historyAdded、浏览历史 +$browseAdded、模板 +$templatesAdded")
            if (bundle.skipped.isNotEmpty()) {
                append("\n跳过：")
                append(bundle.skipped.joinToString("；"))
            }
        }
    }

    companion object {
        private const val MAX_BYTES = 8 * 1024 * 1024
    }
}
