package com.jmreader.data.local

import android.net.Uri
import com.jmreader.data.AppContainer
import com.jmreader.data.api.NetworkFactory
import com.jmreader.data.dto.FavoriteStoreData
import com.jmreader.data.download.PersistedDownload
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Validated metadata-only backup. Images and device-local URI grants are not portable. */
data class BackupPreview internal constructor(
    internal val root: JSONObject,
    val favorites: Int,
    val history: Int,
    val browseHistory: Int,
    val downloads: Int,
    val templates: Int,
) {
    val summary: String get() = "收藏 $favorites 条 · 阅读历史 $history 条\n浏览历史 $browseHistory 条 · 下载索引 $downloads 条\n搜索模板 $templates 条 · 外观与阅读设置\n离线图片、登录凭证、应用锁密码和目录授权不包含在备份中。"
}

class BackupManager(private val container: AppContainer) {
    private val context = container.applicationContext
    private val moshi = NetworkFactory.moshi
    private val favoriteAdapter = moshi.adapter(FavoriteStoreData::class.java)
    private val historyAdapter = moshi.adapter<List<HistoryEntry>>(Types.newParameterizedType(List::class.java, HistoryEntry::class.java))
    private val browseAdapter = moshi.adapter<List<BrowseEntry>>(Types.newParameterizedType(List::class.java, BrowseEntry::class.java))
    private val downloadAdapter = moshi.adapter<List<PersistedDownload>>(Types.newParameterizedType(List::class.java, PersistedDownload::class.java))
    private val templateAdapter = moshi.adapter<List<SearchTemplate>>(Types.newParameterizedType(List::class.java, SearchTemplate::class.java))
    private val mutex = Mutex()

    private suspend fun snapshot(): JSONObject {
        container.favoritesStore.ensureLoaded()
        container.historyStore.ensureLoaded()
        container.browseHistoryStore.ensureLoaded()
        container.downloadManager.ensureLoaded()
        container.searchTemplatesStore.ensureLoaded()
        val rules = container.blockedTagsStore.configuredRules.first()
        val favorites = FavoriteStoreData(2, container.favoritesStore.folders.value, container.favoritesStore.entries.value)
        return JSONObject().put("format", "jmreader-backup").put("version", 1)
            .put("createdAt", System.currentTimeMillis())
            .put("favorites", JSONObject(favoriteAdapter.toJson(favorites)))
            .put("history", JSONArray(historyAdapter.toJson(container.historyStore.items.value)))
            .put("browseHistory", JSONArray(browseAdapter.toJson(container.browseHistoryStore.items.value)))
            .put("downloads", JSONArray(downloadAdapter.toJson(container.downloadManager.exportRecords())))
            .put("templates", JSONArray(templateAdapter.toJson(container.searchTemplatesStore.templates.value)))
            .put("searchHistory", JSONArray(container.searchHistoryStore.allTerms.first()))
            .put("settings", container.settingsStore.exportPortable())
            .put("blockedRules", JSONObject().put("tags", JSONArray(rules.first.toList())).put("names", JSONArray(rules.second.toList())).put("authors", JSONArray(rules.third.toList())))
    }

    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val text = snapshot().toString(2)
            val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("无法写入备份文件")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
    }

    suspend fun recoveryPreview(): BackupPreview = withContext(Dispatchers.IO) {
        val file = android.util.AtomicFile(java.io.File(context.filesDir, "before_restore.json"))
        validate(file.openRead().bufferedReader().use { JSONObject(it.readText()) })
    }

    suspend fun preview(uri: Uri): BackupPreview = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: error("无法打开备份文件")
        val bytes = input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                require(output.size() + n <= 10 * 1024 * 1024) { "备份文件超过 10 MB" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        validate(JSONObject(bytes.toString(Charsets.UTF_8)))
    }

    private fun safeSegment(id: String): Boolean = id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && id != "." && id != ".."

    private fun validate(root: JSONObject): BackupPreview {
        require(root.optString("format") == "jmreader-backup" && root.optInt("version") == 1) { "不是支持的备份格式" }
        val favorite = favoriteAdapter.fromJson(root.getJSONObject("favorites").toString()) ?: error("收藏数据无效")
        val history = historyAdapter.fromJson(root.getJSONArray("history").toString()) ?: error("阅读历史无效")
        val browse = browseAdapter.fromJson(root.getJSONArray("browseHistory").toString()) ?: error("浏览历史无效")
        val downloads = downloadAdapter.fromJson(root.getJSONArray("downloads").toString()) ?: error("下载索引无效")
        val templates = templateAdapter.fromJson(root.getJSONArray("templates").toString()) ?: error("搜索模板无效")
        require(favorite.entries.size <= 50_000 && favorite.folders.size <= 1000 && downloads.size <= 5000) { "备份条目过多" }
        require(history.all { it.page >= 0 && it.comic.id.isNotBlank() && it.chapterId.isNotBlank() }) { "历史页码或 ID 无效" }
        require(downloads.all { record -> safeSegment(record.comic.id) && record.detail.chapters.size <= 10_000 && record.detail.chapters.all { safeSegment(it.id) } }) { "下载任务 ID 或章节无效" }
        root.getJSONArray("searchHistory").let { terms -> (0 until terms.length()).forEach { terms.getString(it) } }
        container.settingsStore.validatePortable(root.getJSONObject("settings"))
        val rules = root.optJSONObject("blockedRules")
        if (rules != null) for (kind in listOf("tags", "names", "authors")) {
            val array = rules.getJSONArray(kind); require(array.length() <= 10_000)
            (0 until array.length()).forEach { require(array.getString(it).length <= 200) }
        }
        return BackupPreview(root, favorite.entries.size, history.size, browse.size, downloads.size, templates.size)
    }

    suspend fun restore(preview: BackupPreview, merge: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            container.downloadManager.withRestoreGate {
            // Validate all sections again before the first mutation, and save a local recovery snapshot.
            val root = validate(preview.root).root
            val old = snapshot()
            val recovery = android.util.AtomicFile(java.io.File(context.filesDir, "before_restore.json"))
            val output = recovery.startWrite()
            try { output.write(old.toString().toByteArray()); recovery.finishWrite(output) }
            catch (e: Throwable) { recovery.failWrite(output); throw e }
            try {
                apply(root, merge)
            } catch (e: Throwable) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { apply(old, false) }.onFailure {
                        com.jmreader.core.Logger.e("Backup", "恢复回滚失败，保留 before_restore.json", it)
                    }
                }
                throw e
            }
            }
        }
    }

    private suspend fun apply(root: JSONObject, merge: Boolean) {
        container.favoritesStore.importData(favoriteAdapter.fromJson(root.getJSONObject("favorites").toString())!!, merge)
        container.historyStore.importData(historyAdapter.fromJson(root.getJSONArray("history").toString())!!, merge)
        container.browseHistoryStore.importData(browseAdapter.fromJson(root.getJSONArray("browseHistory").toString())!!, merge)
        container.searchTemplatesStore.importData(templateAdapter.fromJson(root.getJSONArray("templates").toString())!!, merge)
        container.downloadManager.importRecords(downloadAdapter.fromJson(root.getJSONArray("downloads").toString())!!, merge)
        val terms = root.getJSONArray("searchHistory")
        container.searchHistoryStore.importTerms((0 until terms.length()).map { terms.getString(it) }, merge)
        root.optJSONObject("blockedRules")?.let { rules ->
            fun values(key: String): Set<String> = rules.getJSONArray(key).let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
            container.blockedTagsStore.importRules(Triple(values("tags"), values("names"), values("authors")), merge)
        }
        // Merge preserves existing preferences; replacement restores portable preferences only.
        if (!merge) {
            container.settingsStore.importPortable(root.getJSONObject("settings"), replace = true)
            container.rebuildApi()
        }
    }
}


