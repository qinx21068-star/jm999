package com.jmreader.data.download

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.squareup.moshi.JsonClass
import com.jmreader.data.api.direct.executeCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import androidx.documentfile.provider.DocumentFile
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.repository.Resource
import com.jmreader.data.repository.proxiedImageUrl
import com.jmreader.core.CrashHandler
import com.jmreader.data.api.NetworkFactory
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus { QUEUED, DOWNLOADING, WAITING, PAUSED, COMPLETED, FAILED }

@JsonClass(generateAdapter = false)
internal data class PersistedDownload(
    val comic: ComicBriefDto,
    val detail: ComicDetailDto,
    val status: DownloadStatus,
    val progress: Int,
    val totalChapters: Int,
    val doneChapters: Int,
    val failedImages: Int,
    val skippedChapterIds: Set<String> = emptySet(),
)

data class DownloadTask(
    val comic: ComicBriefDto,
    val status: DownloadStatus,
    val progress: Int, // 0..100
    val totalChapters: Int,
    val doneChapters: Int,
    /** 失败的图片数（单图下载失败累计）。0=全部成功。 */
    val failedImages: Int = 0,
    val currentChapterId: String? = null,
    val currentChapterTitle: String? = null,
    val bytesPerSecond: Long = 0,
    val etaSeconds: Long? = null,
    val waitingReason: String? = null,
    val skippedChapterIds: Set<String> = emptySet(),
)

/**
 * 离线下载管理器。
 * 把整本漫画的每个章节图片下载到内部存储，离线阅读器优先读本地。
 *
 * 关键修复（曾经的问题）：
 * 1. **enqueue 去重清旧任务**：COMPLETED/FAILED 旧任务会被新任务顶替，
 *    避免 LazyColumn 同 key 崩溃。
 * 2. **remove 取消协程**：用 jobs Map 持有每本漫画的下载 Job，删除时 cancel，
 *    避免「假删除」（协程继续跑、写文件、占带宽）。
 * 3. **并发限制**：用 Semaphore(2) 限制同时下载的本数，避免 IP 封禁 + OOM。
 * 4. **单图失败计数**：runCatching 不再静默吞错，记录到 failedImages；
 *    失败比例 > 30% 时整本标 FAILED。
 * 5. **空章节处理**：chapters.isEmpty() 标 FAILED（而非假成功 COMPLETED）。
 * 6. **CancellationException 不吞**：协程取消正常传播，删除任务时能立即停止。
 */
class DownloadManager(
    private val context: Context,
    private val container: AppContainer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashHandler.coroutineHandler)
    // 下载器保留独立客户端：后端模式的图片 URL 必须请求后端代理。
    // 代理配置运行时同步更新，避免下载绕过用户的代理设置。
    @Volatile private var http: OkHttpClient = buildHttp(null)
    @Volatile private var currentProxy: String? = null

    private fun buildHttp(proxyStr: String?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        com.jmreader.data.api.NetworkFactory.parseProxy(proxyStr)?.let { (proxy, _) ->
            builder.proxy(proxy)
        }
        return builder.build()
    }

    fun applyProxy(proxyStr: String?) {
        if (proxyStr == currentProxy) return
        currentProxy = proxyStr
        val old = http
        http = buildHttp(proxyStr)
        scope.launch {
            runCatching {
                old.dispatcher.executorService.shutdown()
                old.connectionPool.evictAll()
            }
        }
    }

    /**
     * 并发下载数限制：同时只下 N 本，避免触发禁漫限流 + 避免内存峰值。
     *
     * v27.5：改为 @Volatile var，支持运行时通过 [setConcurrency] 调整。
     * - runDownload 进入时把当前 Semaphore 捕获到局部变量，整个下载过程使用同一个 Semaphore，
     *   避免 setConcurrency 切换 Semaphore 时 in-flight 任务的行为不一致
     * - setConcurrency 直接替换 semaphore 字段，新进入的 runDownload 用新 semaphore
     * - 已 in-flight 的任务仍用旧 semaphore（permit 数不变），完成后释放即可
     */
    @Volatile private var concurrencyLimit: Semaphore = Semaphore(2)

    /**
     * v27.5：运行时调整下载并发数。
     * 创建新的 Semaphore 替换旧的；in-flight 任务用旧 Semaphore 完成，新任务用新 Semaphore。
     */
    fun setConcurrency(n: Int) {
        val safe = n.coerceIn(1, 4)
        if (safe == _concurrencyMirror) return
        concurrencyLimit = Semaphore(safe)
        _concurrencyMirror = safe
        com.jmreader.core.Logger.i("Download", "并发数切换为 $safe（in-flight 任务用旧限制完成）")
    }

    /** 当前并发上限（仅用于 UI 显示）。 */
    fun currentConcurrency(): Int {
        // Semaphore 没有公开的 permit 数查询 API，单独存一份镜像更可靠
        return _concurrencyMirror
    }
    @Volatile private var _concurrencyMirror: Int = 2

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val taskFile = File(context.filesDir, "download_tasks.json")
    private val taskType = Types.newParameterizedType(List::class.java, PersistedDownload::class.java)
    private val taskAdapter = NetworkFactory.moshi.adapter<List<PersistedDownload>>(taskType)
    private var persistJob: Job? = null
    private val persistMutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private val skippedChapters = ConcurrentHashMap<String, Set<String>>()
    private val removals = mutableMapOf<String, Job>()
    private val touchedIds = ConcurrentHashMap.newKeySet<String>()
    private var restoring = false
    private val restoreMutex = Mutex()

    /** 每本漫画的下载 Job，remove 时 cancel。key=comicId。 */
    private val jobs = mutableMapOf<String, Job>()
    /** Generation token prevents an old cancelled job from updating a newly re-enqueued task. */
    private val runTokens = ConcurrentHashMap<String, String>()
    private val pausedIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * 缓存每本漫画的详情，供 [retry] 在 DownloadsScreen 直接重试使用，
     * 避免界面需要重新请求详情接口。
     * key=comicId，value=enqueue 时传入的 ComicDetailDto。
     */
    private val detailCache = ConcurrentHashMap<String, ComicDetailDto>()

    init {
        scope.launch {
            try { loadPersistedTasks() } finally { loaded.complete(Unit) }
        }
    }

    private suspend fun loadPersistedTasks() = withContext(Dispatchers.IO) {
        val records = runCatching {
            if (!taskFile.exists() && !File(taskFile.path + ".bak").exists()) emptyList() else AtomicFile(taskFile).openRead().bufferedReader().use { taskAdapter.fromJson(it.readText()).orEmpty() }
        }.getOrElse {
            com.jmreader.core.Logger.w("Download", "读取下载任务索引失败", it)
            emptyList()
        }
        val restored = records.map { record ->
            val status = if (record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.QUEUED || record.status == DownloadStatus.WAITING) {
                DownloadStatus.PAUSED
            } else record.status
            if (record.comic.id !in touchedIds) { detailCache.putIfAbsent(record.comic.id, record.detail); skippedChapters[record.comic.id] = record.skippedChapterIds }
            DownloadTask(
                comic = record.comic,
                status = status,
                progress = record.progress.coerceIn(0, 100),
                totalChapters = record.totalChapters,
                doneChapters = record.doneChapters,
                failedImages = record.failedImages, skippedChapterIds = record.skippedChapterIds,
            )
        }.filter { it.comic.id.matches(Regex("[A-Za-z0-9_-]{1,128}")) }.distinctBy { it.comic.id }.filterNot { it.comic.id in touchedIds }
        synchronized(jobs) {
            val eligible = restored.filterNot { it.comic.id in touchedIds }
            _tasks.update { current -> current + eligible.filterNot { task -> current.any { it.comic.id == task.comic.id } } }
        }
    }

    /** Persist a debounced snapshot; atomic rename prevents half-written task indexes. */
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            loaded.await()
            delay(250L)
            persistMutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching { writeIndex() }.onFailure { e ->
                        com.jmreader.core.Logger.w("Download", "写下载任务索引失败", e)
                    }
                }
            }
        }
    }

    private fun rootDir() = File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }

    // ---- v27.6 SAF 存储支持（用户在设置页选了 SAF 目录后，下载完成后复制到外部存储）----
    // 策略：下载仍写内部存储（快速、可靠、支持 .tmp 原子重命名），
    // 下载完成后若 SAF 启用则复制到 SAF 目录（用户可在文件管理器查看）。
    // listLocalFiles 优先读内部存储（快），内部存储无文件时回退读 SAF（App 数据被清后仍可读）。

    /** SAF 根目录 URI（从设置读取）。null=未配置 SAF，用内部存储。 */
    private val safRootUri: Uri?
        get() = container.settingsStore.currentSnapshot.downloadDirUri
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /** SAF 是否启用。 */
    private val isSafEnabled: Boolean get() = safRootUri != null

    /** 获取 SAF 根 DocumentFile。权限丢失时返回 null。 */
    private fun safRootDoc(): DocumentFile? = safRootUri?.let {
        runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull()
    }

    /** 获取/创建 SAF 下的漫画目录（仅写路径使用）。 */
    private fun safComicDir(comicId: String): DocumentFile? {
        val root = safRootDoc() ?: return null
        return runCatching { root.findFile(comicId) ?: root.createDirectory(comicId) }.getOrNull()
    }

    /** 读取已有 SAF 漫画目录；读路径不创建空目录。 */
    private fun existingSafComicDir(comicId: String): DocumentFile? =
        runCatching { safRootDoc()?.findFile(comicId) }.getOrNull()

    /**
     * v27.6：把内部存储中已下载的漫画复制到 SAF 目录。
     * 下载完成后调用，让用户能在文件管理器中查看下载的本子。
     * 失败静默（不影响下载状态），仅记日志。
     */
    private fun exportToSaf(comicId: String) {
        if (!isSafEnabled) return
        val srcComicDir = File(rootDir(), comicId)
        if (!srcComicDir.exists() || !srcComicDir.isDirectory) return
        val dstComicDir = safComicDir(comicId) ?: run {
            com.jmreader.core.Logger.w("Download", "SAF 导出失败：无法创建目录 $comicId（权限可能已丢失）")
            return
        }
        runCatching {
            srcComicDir.walkTopDown().forEach { srcFile ->
                if (!srcFile.isFile) return@forEach
                // 计算相对路径：comicId/chapterId/filename
                val relPath = srcFile.relativeTo(srcComicDir).path
                val parts = relPath.split(File.separator)
                if (parts.size < 2) return@forEach
                val chapterId = parts[0]
                val fileName = parts[1]
                // 跳过临时文件
                if (fileName.contains(".tmp")) return@forEach
                // 确保章节目录存在
                val chapterDoc = dstComicDir.findFile(chapterId) ?: dstComicDir.createDirectory(chapterId)
                if (chapterDoc == null) return@forEach
                // 跳过已存在的文件（避免重复复制）
                if (chapterDoc.findFile(fileName) != null) return@forEach
                // 复制文件
                val mimeType = if (fileName.endsWith(".expected")) "text/plain" else "image/jpeg"
                val dstFile = chapterDoc.createFile(mimeType, fileName) ?: return@forEach
                context.contentResolver.openOutputStream(dstFile.uri)?.use { os ->
                    srcFile.inputStream().use { it.copyTo(os) }
                }
            }
            val exportComplete = srcComicDir.listFiles()
                ?.filter { it.isDirectory }
                ?.all { srcChapter ->
                    val expectedImages = imageFilesOnly(srcChapter).size
                    val dstChapter = dstComicDir.findFile(srcChapter.name)
                    val copiedImages = dstChapter?.listFiles()
                        ?.count { it.isFile && it.name?.let { n -> !n.startsWith(".") && !n.contains(".tmp") } ?: false }
                    dstChapter != null && copiedImages == expectedImages
                } == true
            if (!exportComplete) error("SAF 导出校验失败：存在未完成章节")
            dstComicDir.findFile(".complete")?.delete()
            dstComicDir.createFile("text/plain", ".complete")?.let { marker ->
                context.contentResolver.openOutputStream(marker.uri)?.use { it.write("complete".toByteArray()) }
            }
            com.jmreader.core.Logger.i("Download", "SAF 导出完成: $comicId")
        }.onFailure { e ->
            com.jmreader.core.Logger.w("Download", "SAF 导出失败 $comicId: ${com.jmreader.core.Logger.brief(e)}")
        }
    }

    /**
     * v27.6：列出 SAF 中某章节的图片 URI 字符串。
     * 内部存储无文件时（如 App 数据被清）回退用此方法。
     * 返回 content:// URI 字符串列表，按文件名排序。
     */
    private fun listSafImages(comicId: String, chapterId: String): List<String> {
        val comicDir = existingSafComicDir(comicId) ?: return emptyList()
        val chapterDir = runCatching { comicDir.findFile(chapterId) }.getOrNull() ?: return emptyList()
        val completeMarker = comicDir.findFile(".complete") != null
        val expected = chapterDir.findFile(".expected")?.let { marker ->
            runCatching {
                context.contentResolver.openInputStream(marker.uri)?.bufferedReader()?.use { it.readText().trim().toInt() }
            }.getOrNull()
        }
        if (!completeMarker && expected == null) return emptyList()
        return runCatching {
            val files = chapterDir.listFiles()
                .filter { it.isFile && it.name?.let { n -> !n.startsWith(".") && !n.contains(".tmp") } ?: false }
                .sortedBy { it.name ?: "" }
            if (expected != null && files.size != expected) emptyList()
            else files.map { it.uri.toString() }
        }.getOrDefault(emptyList())
    }

    /** v27.6：删除 SAF 中的漫画目录。 */
    private fun removeSaf(comicId: String) {
        if (!isSafEnabled) return
        runCatching {
            existingSafComicDir(comicId)?.delete()
        }
    }

    /**
     * 章节目录（写入用）：不存在时创建。
     * 关键修复（Bug 31）：读路径（isChapterDownloaded / listLocalFiles）不能调用此函数，
     * 否则在线阅读每打开一个章节都会创建两级空目录，长期累积大量空目录污染存储。
     */
    fun chapterDir(comicId: String, chapterId: String): File =
        chapterDirForRead(comicId, chapterId).apply { if (!exists()) mkdirs() }

    /** 章节目录（读取用）：不创建目录。读路径用这个，避免空目录累积。 */
    private fun chapterDirForRead(comicId: String, chapterId: String): File =
        File(rootDir(), "$comicId/$chapterId")

    /**
     * 过滤出章节目录下的真实图片文件，排除标记文件和临时文件。
     * 关键修复（Bug 29）：之前 listFiles() 直接返回所有文件，包含：
     * - `.expected`（预期图片数标记文件，内容是数字文本）
     * - `.tmp`（下载未完成的临时文件，内容不完整）
     * 这两个文件会被阅读器当成图片加载 → Coil 解码失败 → 每个已下载章节首页/末页显示"加载失败"。
     * 同时 isChapterDownloaded 用未过滤的 files.size 校验，会把"10 张图 + .expected + .tmp = 12"
     * 误判为完整，导致残缺章节跳过重下。
     */
    private fun imageFilesOnly(dir: File): List<File> =
        dir.listFiles { f ->
            f.isFile && !f.name.startsWith(".") && !f.name.contains(".tmp")
        }?.toList() ?: emptyList()

    /**
     * 记录每个章节的预期图片数，用于校验下载完整性。
     * key="$comicId/$chapterId"，value=预期图片张数。
     *
     * 关键修复（持久化）：之前纯内存，进程重启后丢失，isChapterDownloaded 退化为
     * "目录非空即视为完成"，半成品章节（如 47 张图只下了 20 张）被误判为完整，
     * 离线阅读到第 20 页突然没图。现在用 ConcurrentHashMap + 章节目录下的
     * `.expected` 标记文件持久化，启动时回填。
     */
    private val expectedCounts = ConcurrentHashMap<String, Int>()

    init {
        // v27.5 性能修复：扫描已下载章节目录的 I/O 移到 IO 协程异步执行。
        // 之前在 init 块同步执行（主线程），下载越多冷启动越慢。
        // expectedCounts 在 isChapterDownloaded 调用时若还未加载完，会退化为"目录非空即视为完成"，
        // 加载完成后会自动修正，对用户无感（离线阅读通常在 App 启动几秒后才会发生）。
        scope.launch {
            runCatching {
                val root = rootDir()
                root.listFiles()?.forEach { comicDir ->
                    if (!comicDir.isDirectory) return@forEach
                    comicDir.listFiles()?.forEach { chDir ->
                        if (!chDir.isDirectory) return@forEach
                        val metaFile = File(chDir, ".expected")
                        if (metaFile.exists()) {
                            val n = metaFile.readText().trim().toIntOrNull()
                            if (n != null && n > 0) {
                                expectedCounts["${comicDir.name}/${chDir.name}"] = n
                            }
                        }
                        // v27.13：清理进程被杀时残留的 .tmp 文件
                        chDir.listFiles { f -> f.name.contains(".tmp") && runTokens[comicDir.name]?.let { token -> !f.name.endsWith(".tmp.$token") } != false }?.forEach { it.delete() }
                    }
                }
            }
        }
    }

    /**
     * 章节是否已完整下载：不仅看目录非空，还要校验实际文件数 == 预期图片数。
     * 半成品目录（下载中断留下部分图片）不会被误认为已完成。
     */
    fun isChapterDownloaded(comicId: String, chapterId: String): Boolean {
        val dir = chapterDirForRead(comicId, chapterId)
        val files = imageFilesOnly(dir)
        if (files.isEmpty()) return false
        // 优先用记录的预期数校验；无记录时（旧版本下载的）只看非空，保持兼容
        val expected = expectedCounts["$comicId/$chapterId"] ?: File(dir, ".expected").takeIf { it.exists() }?.let { runCatching { it.readText().trim().toIntOrNull() }.getOrNull() }
        return expected == null || files.size == expected
    }

    /**
     * 列出章节已下载的本地图片路径（按页码排序）。
     *
     * v27.6：返回 List<String> 而非 List<File>。
     * - 内部存储模式：返回文件绝对路径（如 "/data/.../downloads/comicId/chId/000.jpg"）
     * - SAF 回退模式（内部存储无文件但 SAF 有）：返回 content:// URI 字符串
     * ReaderImage 会根据前缀判断用 File 还是 Uri 加载。
     *
     * v27.5 性能修复：改为 suspend + withContext(IO)。
     * 之前是普通函数，调用方 ReaderViewModel.load() 在 viewModelScope.launch（默认 Main.immediate）
     * 内调用，导致每次打开章节都在主线程做：File.exists() + listFiles() + 排序。
     * 已下载章节有 30-50 个图片文件，低端机耗时 50-200ms，用户感知为"打开章节卡顿"。
     */
    suspend fun listLocalFiles(comicId: String, chapterId: String): List<String> =
        withContext(Dispatchers.IO) {
            val dir = chapterDirForRead(comicId, chapterId)
            val files = imageFilesOnly(dir)
            val expected = expectedCounts["$comicId/$chapterId"] ?: File(dir, ".expected").takeIf { it.exists() }?.let { runCatching { it.readText().trim().toIntOrNull() }.getOrNull() }
            // 仅在文件数齐全时返回，避免半成品目录被阅读器渲染成"只有几页的章节"
            if (expected != null && files.size != expected) {
                // v27.6：内部存储无完整文件，尝试从 SAF 读（App 数据被清后仍可读）
                val safFiles = listSafImages(comicId, chapterId)
                if (safFiles.isNotEmpty()) {
                    return@withContext safFiles
                }
                return@withContext emptyList()
            }
            if (files.isEmpty()) {
                // 内部存储完全无文件，尝试 SAF
                val safFiles = listSafImages(comicId, chapterId)
                return@withContext safFiles
            }
            files.sortedBy { it.nameWithoutExtension }.map { it.absolutePath }
        }

    /**
     * 加入下载队列。
     *
     * 去重策略：
     * - 已有 QUEUED/DOWNLOADING 任务：直接返回（不重复下）
     * - 已有 COMPLETED/FAILED 任务：从列表移除旧任务（避免 LazyColumn 同 key 崩溃），
     *   然后追加新任务。这允许用户「重新下载」已失败/已完成的本子。
     * - 同步取消旧任务的协程（如有）。
     */
    fun enqueue(comic: ComicBriefDto, detail: ComicDetailDto, onlyChapterIds: Set<String>? = null) {
        require(comic.id.matches(Regex("[A-Za-z0-9_-]{1,128}")) && detail.chapters.all { it.id.matches(Regex("[A-Za-z0-9_-]{1,128}")) }) { "漫画或章节 ID 无效" }
        synchronized(jobs) {
            check(!restoring) { "正在恢复数据，稍后再加入下载" }
            val current = _tasks.value.firstOrNull { it.comic.id == comic.id }
            if (current?.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING)) return
            touchedIds.add(comic.id)
            val previous = jobs[comic.id]
            previous?.cancel()
            val deletion = removals[comic.id]
            val token = java.util.UUID.randomUUID().toString()
            runTokens[comic.id] = token
            pausedIds.remove(comic.id)
            detailCache[comic.id] = detail
            val task = DownloadTask(comic, DownloadStatus.QUEUED, current?.progress ?: 0,
                detail.chapters.size, current?.doneChapters ?: 0,
                skippedChapterIds = skippedChapters[comic.id].orEmpty())
            _tasks.update { list -> list.filterNot { it.comic.id == comic.id } + task }
            jobs[comic.id] = scope.launch {
                loaded.await()
                previous?.join()
                deletion?.join()
                if (runTokens[comic.id] == token) runDownload(comic.id, detail, token, onlyChapterIds)
            }
            schedulePersist()
        }
    }

    private suspend fun runDownload(comicId: String, detail: ComicDetailDto, runToken: String, onlyChapterIds: Set<String>? = null) {
        // v27.5：捕获当前 Semaphore 到局部变量，整个下载过程使用同一个 Semaphore，
        // 避免 setConcurrency 切换 Semaphore 时 in-flight 任务行为不一致。
        val limiter = concurrencyLimit
        // 限制并发：最多 N 本同时下载（N 由设置控制，默认 2）
        limiter.withPermit {
            try {
                awaitConditions(comicId, runToken)
                runDownloadInner(comicId, detail, runToken, onlyChapterIds)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                updateIfCurrent(comicId, runToken) { it.copy(status = DownloadStatus.FAILED, waitingReason = e.message, bytesPerSecond = 0, etaSeconds = null) }
                com.jmreader.core.Logger.w("Download", "下载任务失败", e)
            } finally {
                if (runTokens[comicId] == runToken) runTokens.remove(comicId, runToken)
                // v27.5 稳定性加固（medium 修复）：协程被 cancel 时，下载中的 .tmp 文件
                // 可能残留（downloadFile/downloadAndDecode 是阻塞 I/O，cancel 只能在下一挂起点抛出）。
                // 这里清理整个漫画目录下的 .tmp 文件，避免累积占存储。
                // 注意：runDownloadInner 正常完成时所有 .tmp 都已 renameTo 到目标文件，此处删除空操作。
                runCatching {
                    val dir = File(rootDir(), comicId)
                    dir.walkTopDown()
                        .filter { it.isFile && it.name.endsWith(".tmp.$runToken") }
                        .forEach { it.delete() }
                }
            }
        }
    }

    private suspend fun runDownloadInner(comicId: String, detail: ComicDetailDto, runToken: String, onlyChapterIds: Set<String>? = null) {
        // v27.5：拆出 inner 让 runDownload 可以在 finally 中统一清理 .tmp 文件。
        // 原实现保留在 inner 中，调用方语义不变。
        // 任务已不在列表（被 remove 了）；协程取消由后续 isActive() 检查处理。
        if (runTokens[comicId] != runToken) return
        // 关键修复（Bug 16）：移除 Thread.currentThread().isInterrupted 检查——
        // Kotlin 协程 cancel() 不会设置 Thread.isInterrupted（除非用 runInterruptible），
        // 该检查永远 false，纯属无效代码，给读者造成"已处理取消"的错觉。
        if (_tasks.value.none { it.comic.id == comicId }) return

        val serverUrl = container.settingsStore.settings.first().serverUrl
        val useBackend = serverUrl.isNotBlank()
        val chapters = detail.chapters

        // 空章节：标 FAILED 而非假成功
        if (chapters.isEmpty()) {
            updateIfCurrent(comicId, runToken) { it.copy(status = DownloadStatus.FAILED, progress = 0) }
            com.jmreader.core.Logger.w("Download", "下载失败：$comicId 无章节")
            return
        }

        // 关键修复（Bug 17）：retry 时扫描已完整下载的章节作为初始 done，
        // 避免 UI 显示 "0/N 已完成" 误导用户以为从头重下。
        // 已完整下载的章节会在下面的 for 循环中跳过：不重复请求章节接口、不重复下图片。
        var done = chapters.count { it.id !in skippedChapters[comicId].orEmpty() && isChapterDownloaded(comicId, it.id) }
        var totalFailedImages = 0
        var totalImages = 0
        var downloadedBytes = 0L
        val rateStart = android.os.SystemClock.elapsedRealtime()

        for (ch in chapters) {
            // 协程取消（remove 触发）时立即停止
            if (!isActive() || comicId in pausedIds) return
            if (ch.id in skippedChapters[comicId].orEmpty() || (onlyChapterIds != null && ch.id !in onlyChapterIds)) continue
            awaitConditions(comicId, runToken)
            updateIfCurrent(comicId, runToken) { it.copy(currentChapterId = ch.id, currentChapterTitle = ch.title) }
            // 已完整下载的章节跳过（retry 场景）。
            // 注意：不能在这里 done++，已在初始 done 中计入。
            if (isChapterDownloaded(comicId, ch.id)) {
                updateIfCurrent(comicId, runToken) {
                    it.copy(
                        status = DownloadStatus.DOWNLOADING,
                        doneChapters = done,
                        progress = (done * 100 / chapters.size.coerceAtLeast(1)),
                    )
                }
                continue
            }
            updateIfCurrent(comicId, runToken) { it.copy(status = DownloadStatus.DOWNLOADING, doneChapters = done) }
            // 关键修复（Bug 18）：章节图片接口加 3 次指数退避重试（500ms, 1000ms）。
            // 之前单次网络抖动/限流 5xx 直接整本标 FAILED，下到第 9 章只因章节接口一次失败全功尽弃。
            // v27.5 稳定性加固（high 修复）：直连模式下 JMRepository.chapterImages 内部用 runCatching
            // 把异常吞成 Resource.Error，所以这里 try-catch 永远不进 catch 分支，重试形同虚设。
            // 现在改为：try-catch + res is Resource.Success 双重判断，任一失败都走重试。
            var res: com.jmreader.data.repository.Resource<com.jmreader.data.dto.ChapterImagesDto>? = null
            for (attempt in 0 until 3) {
                if (!isActive() || comicId in pausedIds) return
                try {
                    res = container.repository.chapterImages(ch.id)
                    if (res is Resource.Success) break
                    com.jmreader.core.Logger.w(
                        "Download",
                        "章节图片接口第 ${attempt + 1}/3 次返回错误 ${ch.id}: ${(res as? Resource.Error)?.message}",
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    com.jmreader.core.Logger.w(
                        "Download",
                        "章节图片接口第 ${attempt + 1}/3 次抛异常 ${ch.id}: ${com.jmreader.core.Logger.brief(e)}",
                    )
                }
                if (attempt < 2) kotlinx.coroutines.delay(500L shl attempt)
            }
            if (res == null || res !is Resource.Success) {
                com.jmreader.core.Logger.w("Download", "章节图片接口 3 次重试均失败 ${ch.id}")
                updateIfCurrent(comicId, runToken) { it.copy(status = DownloadStatus.FAILED) }
                return
            }
            when (res) {
                is Resource.Success -> {
                    val images = res.data.images
                    val scrambleId = res.data.scramble_id?.toLongOrNull() ?: 0L
                    val dir = chapterDir(comicId, ch.id)
                    // 空图片章：跳过但不算失败
                    if (images.isEmpty()) {
                        com.jmreader.core.Logger.w("Download", "章节 ${ch.id} 无图片")
                        done++
                        updateIfCurrent(comicId, runToken) {
                            it.copy(
                                doneChapters = done,
                                progress = (done * 100 / chapters.size.coerceAtLeast(1)),
                            )
                        }
                        continue
                    }
                    // 记录预期图片数，供 isChapterDownloaded/listLocalFiles 校验完整性
                    val expectedKey = "$comicId/${ch.id}"
                    expectedCounts[expectedKey] = images.size
                    // 关键修复：同步写 .expected 标记文件持久化预期图片数，
                    // 进程重启后 init 块扫描此文件回填 expectedCounts，
                    // 否则重启后半成品章节（如 47 张图只下了 20 张）被误判为完整。
                    //
                    // 关键修复（Bug 37）：之前 runCatching 静默吞异常，磁盘满/权限拒绝时
                    // .expected 没写成功但内存值已写入 → 当前会话正常，但重启后 init 扫不到
                    // .expected → expectedCounts 不含该 key → isChapterDownloaded 退化为
                    // "目录非空即完整"，残缺章节被误判完成、阅读器后半部分全是坏图、retry 也跳过。
                    // 现在写盘失败时回滚内存值并记日志，让 isChapterDownloaded 退化为
                    // "非空即完整"（旧版兼容行为），至少不会误判残缺为完整后还跳过重下。
                    val writeOk = runCatching {
                        File(dir, ".expected").writeText(images.size.toString())
                    }
                    if (writeOk.isFailure) {
                        com.jmreader.core.Logger.w(
                            "Download",
                            "写 .expected 失败 ${
                                com.jmreader.core.Logger.brief(
                                    writeOk.exceptionOrNull() ?: RuntimeException("unknown"),
                                )
                            }，回滚 expectedCounts",
                        )
                        expectedCounts.remove(expectedKey)
                    }
                    totalImages += images.size
                    // v27.5 性能修复：进度 emit 限频（≥3% 或最后一张才 emit）。
                    // 之前每张图都 update → _tasks.update 整列表 copy + emit，
                    // 10 章 × 40 张 = 400 次 emit，每次都触发 DownloadsScreen LazyColumn 重新 diff。
                    var lastEmitPct = -1
                    images.forEachIndexed { index, raw ->
                        if (!isActive() || comicId in pausedIds) return
                        awaitConditions(comicId, runToken)
                        if (ch.id in skippedChapters[comicId].orEmpty()) return@forEachIndexed
                        val file = File(dir, "%03d.jpg".format(index))
                        if (file.exists() && file.length() > 0) {
                            // 已存在且非空跳过，但仍推进进度条
                            val pct = ((done + (index + 1).toFloat() / images.size) / chapters.size * 100).toInt()
                            if (pct - lastEmitPct >= 3 || index == images.lastIndex) {
                                updateIfCurrent(comicId, runToken) { it.copy(progress = pct) }
                                lastEmitPct = pct
                            }
                            return@forEachIndexed
                        }
                        // 单图下载：失败计数而非吞掉
                        // 关键修复：用 .tmp 临时文件 + renameTo 原子落盘，
                        // 避免下载中断留下半成品文件被重试时误认为已完成
                        val tmp = File(dir, "%03d.jpg.tmp.%s".format(index, runToken))
                        val ok = try {
                            if (useBackend) {
                                downloadFile(proxiedImageUrl(serverUrl, raw), tmp)
                            } else {
                                downloadAndDecode(raw, scrambleId, tmp)
                            }
                            coroutineContext.ensureActive()
                            if (runTokens[comicId] != runToken) throw CancellationException("下载已停止")
                            // 下载成功后原子重命名到目标文件
                            if (tmp.exists() && tmp.length() > 0) {
                                file.delete()
                                if (!tmp.renameTo(file)) {
                                    // rename 失败：清理临时文件，标记本次失败
                                    tmp.delete()
                                    false
                                } else {
                                    true
                                }
                            } else {
                                tmp.delete()
                                false
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            com.jmreader.core.Logger.w("Download", "图片下载失败: ${com.jmreader.core.Logger.brief(e)}")
                            tmp.delete()
                            false
                        }
                        if (!ok) totalFailedImages++
                        else downloadedBytes += file.length()
                        val elapsed = (android.os.SystemClock.elapsedRealtime() - rateStart).coerceAtLeast(1)
                        val speed = downloadedBytes * 1000 / elapsed
                        val percentage = ((done + (index + 1).toFloat() / images.size) / chapters.size * 100).toInt()
                        val eta = if (speed > 0 && percentage > 0) elapsed / 1000 * (100 - percentage) / percentage else null
                        updateIfCurrent(comicId, runToken) { it.copy(bytesPerSecond = speed, etaSeconds = eta) }
                        val pct = ((done + (index + 1).toFloat() / images.size) / chapters.size * 100).toInt()
                        // v27.5 性能修复：≥3% 变化或最后一张才 emit，避免每图都 emit 整列表
                        if (pct - lastEmitPct >= 3 || index == images.lastIndex) {
                            updateIfCurrent(comicId, runToken) { it.copy(progress = pct, failedImages = totalFailedImages) }
                            lastEmitPct = pct
                        }
                    }
                }
                else -> {
                    updateIfCurrent(comicId, runToken) { it.copy(status = DownloadStatus.FAILED) }
                    return
                }
            }
            done++
            updateIfCurrent(comicId, runToken) {
                it.copy(
                    doneChapters = done,
                    progress = (done * 100 / chapters.size.coerceAtLeast(1)),
                )
            }
        }

        // 失败比例 > 30% → 标 FAILED
        val actualDone = chapters.count { it.id !in skippedChapters[comicId].orEmpty() && isChapterDownloaded(comicId, it.id) }
        val finalStatus = if (actualDone != chapters.size) DownloadStatus.FAILED else DownloadStatus.COMPLETED
        if (runTokens[comicId] == runToken) {
            updateIfCurrent(comicId, runToken) {
                it.copy(
                    status = finalStatus,
                    progress = actualDone * 100 / chapters.size, doneChapters = actualDone,
                    failedImages = totalFailedImages, bytesPerSecond = 0, etaSeconds = null, currentChapterId = null,
                )
            }
        }
        com.jmreader.core.Logger.i(
            "Download",
            "下载完成: $comicId status=$finalStatus failImg=$totalFailedImages/$totalImages",
        )
        // v27.6：下载完成后若 SAF 启用，复制到外部存储（用户可在文件管理器查看）
        if (finalStatus == DownloadStatus.COMPLETED) {
            exportToSaf(comicId)
        }
    }

    /** 协程是否仍活跃（未被 cancel）。必须在 suspend 上下文中调用。 */
    private suspend fun isActive(): Boolean = kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    private suspend fun downloadFile(url: String, target: File) {
        val req = Request.Builder().url(url).build()
        http.executeCancellable(req).use { resp ->
            if (!resp.isSuccessful) error("download ${resp.code}")
            // body 为 null 时抛异常，避免创建空文件被误认为下载成功
            val body = resp.body ?: error("空响应 body")
            target.outputStream().use { body.byteStream().copyTo(it) }
        }
    }

    /**
     * 直连模式：下载禁漫图片并解密分割图后落盘。
     * url 形如 https://cdn-msp.xxx/media/photos/{aid}/00047.webp?jm_sid=220980
     *
     * 关键修复（Bug 35）：之前无 GIF 判断，所有图片都走 decodeToBytes。
     * 但服务端对 GIF 不做切图，decodeToBytes 会：
     * 1) BitmapFactory.decodeByteArray 对 GIF 只解出第一帧；
     * 2) 若 num>0 还会把首帧错误切图 → 画面块状乱码；
     * 3) 最终 compress 成 JPEG → 动图变静图，动画完全丢失。
     * 现在与 JmImageFetcher.fetch 对齐：GIF 或 num==0 时原样落盘，保留动图。
     */
    private suspend fun downloadAndDecode(url: String, scrambleId: Long, target: File) {
        // 去掉所有 query 参数（jm_sid、v 等）拿到真实图片 URL
        val cleanUrl = url.substringBefore('?')
        val aid = com.jmreader.data.api.direct.JmImageDecoder.parseAidFromUrl(cleanUrl)
        val filename = com.jmreader.data.api.direct.JmImageDecoder.parseFileNameFromUrl(cleanUrl)
        val isGif = cleanUrl.endsWith(".gif", ignoreCase = true)
        val num = com.jmreader.data.api.direct.JmImageDecoder.getScrambleNum(scrambleId, aid, filename)

        // v27.13：图片 CDN 域名轮换重试，与 JmImageFetcher 一致。
        // 之前直接用 cleanUrl 单点下载，CDN 故障时整本下载失败。
        val domains = container.directClient.imageDomainList()
        val currentHost = container.directClient.currentImageDomain()
        val ordered = (listOf(currentHost) + domains).distinct()
        var lastErr: Throwable? = null

        for (host in ordered) {
            val tryUrl = replaceHost(cleanUrl, host)
            val req = Request.Builder()
                .url(tryUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 9; V1938CT Build/PQ3A.190705.11211812; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/91.0.4472.114 Safari/537.36")
                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                .header("X-Requested-With", "com.JMComic3.app")
                .header("Referer", "https://www.cdnaspa.club/")
                .build()
            try {
                http.executeCancellable(req).use { resp ->
                    if (!resp.isSuccessful) error("download ${resp.code}")
                    val bytes = resp.body?.bytes() ?: error("空响应")
                    // GIF / 无需分割：原样落盘，保留动图；否则解密分割图。
                    val decoded = if (isGif || num == 0) bytes
                    else com.jmreader.data.api.direct.JmImageDecoder.decodeToBytes(bytes, scrambleId, aid, filename)
                    target.writeBytes(decoded)
                    if (host != currentHost) container.directClient.selectImageDomain(host)
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                coroutineContext.ensureActive()
                lastErr = e
            }
        }
        throw lastErr ?: error("图片下载失败：无可用域名 $cleanUrl")
    }

    /** v27.13：替换 URL 的 host 部分，用于图片域名轮换。 */
    private fun replaceHost(url: String, newHost: String): String {
        val m = Regex("^(https?://)([^/]+)(/.*)?$").matchEntire(url) ?: return url
        return "${m.groupValues[1]}$newHost${m.groupValues[3]}"
    }

    internal suspend fun <T> withRestoreGate(block: suspend () -> T): T = restoreMutex.withLock {
        ensureLoaded()
        val old = synchronized(jobs) {
            restoring = true
            runTokens.clear()
            val list = jobs.values.toList() + removals.values.toList()
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            _tasks.update { tasks -> tasks.map { task ->
                if (task.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING))
                    task.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0, etaSeconds = null, waitingReason = null)
                else task
            } }
            list
        }
        try {
            old.forEach { it.join() }
            val result = block()
            flushIndex()
            result
        } finally { synchronized(jobs) { restoring = false } }
    }

    private suspend fun flushIndex() {
        persistJob?.cancel()
        loaded.await()
        persistMutex.withLock { writeIndex() }
    }

    private fun writeIndex() {
        val records = _tasks.value.mapNotNull { task ->
            detailCache[task.comic.id]?.let { detail ->
                PersistedDownload(task.comic, detail, task.status, task.progress, task.totalChapters,
                    task.doneChapters, task.failedImages, skippedChapters[task.comic.id].orEmpty())
            }
        }
        val atomic = AtomicFile(taskFile)
        val output = atomic.startWrite()
        try { output.write(taskAdapter.toJson(records).toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Throwable) { atomic.failWrite(output); throw e }
    }

    internal suspend fun exportRecords(): List<PersistedDownload> {
        ensureLoaded()
        return _tasks.value.mapNotNull { task ->
            detailCache[task.comic.id]?.let { detail ->
                PersistedDownload(task.comic, detail, task.status, task.progress, task.totalChapters,
                    task.doneChapters, task.failedImages, skippedChapters[task.comic.id].orEmpty())
            }
        }
    }

    internal suspend fun importRecords(records: List<PersistedDownload>, merge: Boolean) {
        ensureLoaded()
        // Stop existing writers before replacing the index, retaining their offline files.
        val active = synchronized(jobs) {
            runTokens.clear()
            jobs.values.toList().also { list -> list.forEach { it.cancel() }; jobs.clear() }
        }
        active.forEach { it.join() }
        synchronized(jobs) {
            val current = if (merge) _tasks.value else emptyList()
            if (!merge) { detailCache.clear(); skippedChapters.clear() }
            val accepted = records.filterNot { record -> current.any { it.comic.id == record.comic.id } }
            accepted.forEach { detailCache[it.comic.id] = it.detail; skippedChapters[it.comic.id] = it.skippedChapterIds }
            _tasks.value = current.map { task ->
                if (task.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING)) task.copy(status = DownloadStatus.PAUSED) else task
            } + accepted.map { record ->
                val complete = record.detail.chapters.isNotEmpty() && record.detail.chapters.all { isChapterDownloaded(record.comic.id, it.id) }
                DownloadTask(record.comic, if (complete) DownloadStatus.COMPLETED else DownloadStatus.PAUSED,
                    if (complete) 100 else 0, record.detail.chapters.size,
                    record.detail.chapters.count { isChapterDownloaded(record.comic.id, it.id) },
                    skippedChapterIds = record.skippedChapterIds)
            }
            schedulePersist()
        }
        flushIndex()
    }

    suspend fun ensureLoaded() { loaded.await() }

    /** Download policies are checked between images; a manual pause cancels the active call. */
    private suspend fun awaitConditions(comicId: String, token: String) {
        while (true) {
            coroutineContext.ensureActive()
            if (runTokens[comicId] != token) throw CancellationException("任务已停止")
            val device = container.deviceConditions.state.value
            val settings = container.settingsStore.currentSnapshot
            val reason = when {
                !device.online -> "等待网络连接"
                settings.downloadWifiOnly && !device.wifi -> "等待 Wi-Fi"
                settings.downloadChargingOnly && !device.charging -> "等待充电"
                else -> null
            }
            if (reason == null) {
                updateIfCurrent(comicId, token) { it.copy(status = DownloadStatus.DOWNLOADING, waitingReason = null) }
                return
            }
            updateIfCurrent(comicId, token) { it.copy(status = DownloadStatus.WAITING, waitingReason = reason, bytesPerSecond = 0, etaSeconds = null) }
            delay(1_000)
        }
    }

    fun chapters(comicId: String) = detailCache[comicId]?.chapters.orEmpty()

    /** Cancel this chapter only, keeping other chapters queued and any existing files. */
    fun cancelChapter(comicId: String, chapterId: String): Boolean = synchronized(jobs) {
        if (restoring) return false
        val task = _tasks.value.firstOrNull { it.comic.id == comicId } ?: return false
        if (chapters(comicId).none { it.id == chapterId }) return false
        skippedChapters[comicId] = skippedChapters[comicId].orEmpty() + chapterId
        update(comicId) { it.copy(skippedChapterIds = skippedChapters[comicId].orEmpty()) }
        if (task.currentChapterId == chapterId && task.status in setOf(DownloadStatus.DOWNLOADING, DownloadStatus.WAITING) && pause(comicId)) resume(task.comic)
        return true
    }

    fun retryChapter(comicId: String, chapterId: String): Boolean = synchronized(jobs) {
        if (restoring) return false
        val task = _tasks.value.firstOrNull { it.comic.id == comicId } ?: return false
        val detail = detailCache[comicId] ?: return false
        if (detail.chapters.none { it.id == chapterId }) return false
        skippedChapters[comicId] = skippedChapters[comicId].orEmpty() - chapterId
        // Retry scans completed files and only fetches missing pages.
        if (task.status in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING)) pause(comicId)
        enqueue(task.comic, detail, onlyChapterIds = setOf(chapterId))
        return true
    }

    private fun update(comicId: String, transform: (DownloadTask) -> DownloadTask) {
        // 原子 CAS 更新，避免并发覆盖
        _tasks.update { list -> list.map { if (it.comic.id == comicId) transform(it) else it } }
        schedulePersist()
    }

    private fun updateIfCurrent(
        comicId: String,
        runToken: String,
        transform: (DownloadTask) -> DownloadTask,
    ) {
        synchronized(jobs) {
            if (runTokens[comicId] == runToken) update(comicId, transform)
        }
    }

    /** 暂停下载，保留已下载文件和任务详情，稍后可继续。 */
    fun pause(comicId: String): Boolean = synchronized(jobs) {
        if (restoring) return false
        val task = _tasks.value.firstOrNull { it.comic.id == comicId } ?: return false
        if (task.status !in setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.WAITING)) return false
        pausedIds.add(comicId)
        runTokens.remove(comicId)
        jobs[comicId]?.cancel()
        update(comicId) { it.copy(status = DownloadStatus.PAUSED, bytesPerSecond = 0, etaSeconds = null, waitingReason = null) }
        true
    }


    /** 继续已暂停或进程重启后恢复的任务；已有文件会被跳过。 */
    fun resume(comic: ComicBriefDto): Boolean = synchronized(jobs) {
        if (restoring) return false
        val task = _tasks.value.firstOrNull { it.comic.id == comic.id } ?: return false
        if (task.status != DownloadStatus.PAUSED) return false
        val detail = detailCache[comic.id] ?: return false
        pausedIds.remove(comic.id)
        enqueue(comic, detail)
        return true
    }

    /**
     * 删除下载任务（含正在下载的）。
     * 关键修复：cancel 协程，避免「假删除」（协程继续跑、写文件、占带宽）。
     * 同时删除本地文件 + 详情缓存。
     *
     * 关键修复（Bug 36）：之前 deleteRecursively() 在调用方线程（主线程）同步执行。
     * 一本完整下载的本子可能有几百张图、几百 MB，递归删除耗时几百 ms～数秒；
     * 「清空全部」时 tasks.forEach 顺序同步删，10 本可能累计数秒主线程阻塞 → ANR。
     * 现在 IO 删除挪到 scope（Dispatchers.IO），主线程立即返回，UI 不卡顿。
     */
    fun remove(comicId: String) {
        synchronized(jobs) {
            if (restoring) return
            touchedIds.add(comicId)
            runTokens.remove(comicId)
            pausedIds.remove(comicId)
            skippedChapters.remove(comicId)
            val previous = jobs.remove(comicId)
            previous?.cancel()
            _tasks.update { list -> list.filterNot { it.comic.id == comicId } }
            detailCache.remove(comicId)
            removals[comicId] = scope.launch {
                previous?.join()
                runCatching { File(rootDir(), comicId).deleteRecursively() }
                removeSaf(comicId)
            }
            schedulePersist()
        }
    }


    /**
     * 重试已失败的任务：从失败处继续（已下载的章节图片文件保留，runDownload 会自动跳过）。
     *
     * 优先使用 enqueue 时缓存的 detail；若缓存已丢失（如进程重启），调用方需传入 detail。
     * @return true=已触发重试；false=任务不存在/状态非 FAILED/无 detail 无法重试
     */
    fun retry(comic: ComicBriefDto, detail: ComicDetailDto? = null): Boolean = synchronized(jobs) {
        if (restoring) return false
        // 仅对 FAILED 任务重试
        val existing = _tasks.value.firstOrNull { it.comic.id == comic.id } ?: return false
        if (existing.status != DownloadStatus.FAILED) return false
        val d = detail ?: detailCache[comic.id] ?: return false
        enqueue(comic, d)
        return true
    }
}
