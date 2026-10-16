@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jmreader.core.Logger
import com.jmreader.data.AppContainer
import com.jmreader.data.api.direct.ApiDomainPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个域名的测速/选择状态。
 */
data class DomainItem(
    val host: String,
    val latency: Long? = null,        // ms；null 表示未测或失败
    val error: String? = null,        // 非 null 表示失败原因
    val testing: Boolean = false,
    val isCurrent: Boolean = false,
    val isCustom: Boolean = false,    // 用户自定义（可删）
    val verifiedAtMillis: Long? = null,
)

class DomainViewModel(private val container: AppContainer) : ViewModel() {

    private val _items = mutableStateListOf<DomainItem>()
    val items: List<DomainItem> get() = _items

    private val _candidates = mutableStateListOf<DomainItem>()
    val candidates: List<DomainItem> get() = _candidates

    private val _events = MutableSharedFlow<String>()
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val builtin: List<String> get() = container.directClient.apiDomainList()

    private var autoTestStarted = false
    var verifyingCandidates by mutableStateOf(false)
        private set

    init { reload(autoTest = true) }

    /**
     * v27.5 稳定性加固：所有用户操作都用 launchSafe 包裹，IO 异常不会让进程崩溃。
     * DataStore 写盘、OkHttp 网络、socket 异常都可能抛 IOException，必须兜底。
     */
    private fun launchSafe(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            com.jmreader.core.Logger.w("Domain", "操作失败: ${com.jmreader.core.Logger.brief(e)}")
            _events.emit("操作失败：${e.message ?: "未知错误"}")
        }
    }

    /**
     * 重新加载域名列表。DataStore 读取放到协程里，避免在主线程 runBlocking 造成 ANR。
     * 内置域名池读取（directClient.apiDomainList/currentDomain）是内存操作，可留在主线程。
     */
    fun reload(autoTest: Boolean = false) {
        val current = container.directClient.currentDomain()
        launchSafe {
            val custom = container.settingsStore.settings.first().customApiDomains
            val all = (builtin + custom).distinct()
            _items.clear()
            all.forEach { host ->
                _items.add(DomainItem(
                    host = host,
                    isCurrent = host == current,
                    isCustom = custom.contains(host),
                ))
            }
            if (autoTest && !autoTestStarted) {
                autoTestStarted = true
                testAllInternal()
            }
        }
    }

    private suspend fun testAllInternal() {
        _items.indices.forEach { i -> _items[i] = _items[i].copy(testing = true, error = null, latency = null) }
        val hosts = _items.toList().map { it.host }
        val sem = Semaphore(4)
        val results: List<Pair<String, Pair<Long?, String?>>> = withContext(Dispatchers.IO) {
            hosts.map { host ->
                async { sem.withPermit { host to container.directClient.testDomain(host) } }
            }.map { it.await() }
        }
        for ((host, pair) in results) {
            val i = _items.indexOfFirst { it.host == host }
            if (i >= 0) _items[i] = _items[i].copy(testing = false, latency = pair.first, error = pair.second)
        }
        val ok = _items.count { it.error == null && it.latency != null }
        _events.emit("测速完成：$ok/${_items.size} 可用")
    }

    /** 测速单个域名。 */
    fun testOne(host: String) = launchSafe {
        val idx = _items.indexOfFirst { it.host == host }
        if (idx < 0) return@launchSafe
        _items[idx] = _items[idx].copy(testing = true, error = null, latency = null)
        val (lat, err) = withContext(Dispatchers.IO) { container.directClient.testDomain(host) }
        val i2 = _items.indexOfFirst { it.host == host }
        if (i2 >= 0) {
            _items[i2] = _items[i2].copy(testing = false, latency = lat, error = err)
        }
    }

    /** 测速全部（并发，限制并发到 4 避免触发限流/耗尽 socket）。 */
    fun testAll() = launchSafe { testAllInternal() }

    /** 选中某域名为当前使用。 */
    fun select(host: String) = launchSafe {
        val check = container.directClient.testDomain(host)
        if (check.first == null || check.second != null) {
            _events.emit("该线路本次验证失败，未切换：${check.second ?: "未返回延迟"}")
            return@launchSafe
        }
        container.directClient.selectDomain(host)
        _items.indices.forEach { i ->
            _items[i] = _items[i].copy(isCurrent = _items[i].host == host)
        }
        _events.emit("已切换到 $host")
    }

    /** 添加自定义域名。 */
    fun addCustom(host: String) = launchSafe {
        // 规整：去掉协议前缀（大小写不敏感）和末尾斜杠
        val h = host.trim()
            .replace(Regex("(?i)^https?://"), "")
            .trimEnd('/')
            .trim()
        if (h.isBlank()) {
            _events.emit("域名不能为空")
            return@launchSafe
        }
        val normalized = ApiDomainPolicy.normalize(h)
        if (normalized == null) {
            _events.emit("请输入有效的公网 API 域名")
            return@launchSafe
        }
        if (_items.any { ApiDomainPolicy.normalize(it.host) == normalized }) {
            _events.emit("域名已存在：$normalized")
            return@launchSafe
        }
        val check = container.directClient.testDomain(normalized)
        if (check.first == null || check.second != null) {
            _events.emit("本次 API 验证失败，未添加：${check.second ?: "未返回延迟"}")
            return@launchSafe
        }
        val custom = container.settingsStore.settings.first().customApiDomains + normalized
        if (_items.any { ApiDomainPolicy.normalize(it.host) == normalized }) {
            _events.emit("域名已存在：$normalized")
            return@launchSafe
        }
        container.settingsStore.setCustomApiDomains(custom)
        container.directClient.mergeCustomDomains(custom.toList())
        _items.add(0, DomainItem(
            host = normalized, isCustom = true, latency = check.first,
            verifiedAtMillis = System.currentTimeMillis(),
        ))
        _candidates.removeAll { it.host == normalized }
        _events.emit("已添加本次 API 验证通过的域名：$normalized")
    }

    /** 删除自定义域名（内置不可删）。同步移除运行时域名池中的对应项。 */
    fun removeCustom(host: String) = launchSafe {
        val custom = container.settingsStore.settings.first().customApiDomains - host
        container.settingsStore.setCustomApiDomains(custom)
        container.directClient.removeDomain(host)   // 同步运行时域名池，否则被删域名仍可能被选中
        _items.removeAll { it.host == host }
        // 关键：若删除的恰好是当前域名，directClient.removeDomain 已自动把 domainIndex
        // 指向新的域名，但 UI 上 isCurrent 标记需要重新同步，否则旧的"当前"标记会消失而无新标记。
        val newCurrent = container.directClient.currentDomain()
        _items.indices.forEach { i ->
            _items[i] = _items[i].copy(isCurrent = _items[i].host == newCurrent)
        }
        _events.emit("已删除 $host")
    }

    /** Re-fetch and validate on the device; only this check's successful results are shown. */
    fun refreshCandidates() {
        if (verifyingCandidates) return
        verifyingCandidates = true
        _candidates.clear()
        launchSafe {
            try {
                val hosts = container.directClient.fetchApiDomainCandidates()
                if (hosts.isEmpty()) {
                    _events.emit("域名服务未返回有效候选，已保留当前线路")
                    return@launchSafe
                }
                val verified = container.directClient.verifyApiDomains(hosts)
                val checkedAt = System.currentTimeMillis()
                verified.forEach { (host, latency, error) ->
                    val index = _items.indexOfFirst { it.host.equals(host, ignoreCase = true) }
                    if (index >= 0) _items[index] = _items[index].copy(
                        latency = latency, error = error,
                        verifiedAtMillis = if (error == null) checkedAt else null,
                    )
                }
                verified.filter { it.second != null && it.third == null }
                    .sortedBy { it.second }
                    .forEach { (host, latency, _) ->
                        _candidates.add(DomainItem(host = host, latency = latency, verifiedAtMillis = checkedAt))
                    }
                _events.emit("本次 API 验证通过：${_candidates.size}/${hosts.size}；结果仅代表当前网络")
            } finally {
                verifyingCandidates = false
            }
        }
    }

    fun addVerifiedCandidate(host: String) {
        if (_candidates.none { it.host == host }) return
        // Revalidate before persisting; a displayed candidate may already have expired.
        addCustom(host)
    }

    /** The refresh action previews validated candidates instead of silently changing the pool. */
    fun refreshFromServer() = refreshCandidates()

}

class DomainVMFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = DomainViewModel(container) as T
}

@Composable
fun DomainManageScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: DomainViewModel = viewModel(factory = DomainVMFactory(container))
    val items = vm.items
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    var showAdd by remember { mutableStateOf(false) }
    var newHost by remember { mutableStateOf("") }
    val anyTesting = vm.verifyingCandidates || items.any { it.testing }
    // 删除确认：记录待删除的域名，确认后才真正删除
    var deleteTarget by remember { mutableStateOf<DomainItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API 域名管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refreshFromServer() }, enabled = !anyTesting) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "拉取最新域名")
                    }
                    IconButton(onClick = { showAdd = true; newHost = "" }, enabled = !anyTesting) {
                        Icon(Icons.Outlined.Add, contentDescription = "添加域名")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Button(
                    onClick = { vm.testAll() },
                    enabled = !anyTesting,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (anyTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp).width(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("测速中…")
                    } else {
                        Icon(Icons.Outlined.Speed, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("全部测速")
                    }
                }
            }
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            item {
                Text(
                    "点底部「全部测速」检测每个域名的延迟与可用性；点某行的勾设为当前域名。" +
                    "若全部失败，可点右上角刷新从服务器拉最新域名，或手动添加。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(enabled = !anyTesting, onClick = { vm.refreshCandidates() }) {
                    Text(if (vm.verifyingCandidates) "正在验证候选…" else "验证并查看最新域名候选")
                }
            }
            itemsIndexed(vm.candidates, key = { i, item -> "candidate_${item.host.lowercase()}_$i" }) { _, candidate ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(candidate.host, style = MaterialTheme.typography.bodyMedium)
                            Text("本次 API 验证通过 · ${candidate.latency} ms", style = MaterialTheme.typography.labelSmall)
                            candidate.verifiedAtMillis?.let { checkedAt ->
                                Text(
                                    "检查时间：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(checkedAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }

                        }
                        val alreadyAdded = items.any { it.host.equals(candidate.host, ignoreCase = true) }
                        TextButton(enabled = !anyTesting && !alreadyAdded, onClick = { vm.addVerifiedCandidate(candidate.host) }) {
                            Text(if (alreadyAdded) "已在池中" else "验证并添加")
                        }

                    }
                }
            }
            itemsIndexed(items, key = { i, item -> item.host.lowercase().ifBlank { "idx_$i" } }) { _, item ->
                // v27.5 性能修复：lambda 用 remember 缓存
                val onTest = remember(item, vm) { { vm.testOne(item.host); Unit } }
                val onSelect = remember(item, vm) { { vm.select(item.host); Unit } }
                val onRemove = if (item.isCustom) {
                    remember(item) { { deleteTarget = item } }
                } else null
                DomainRow(
                    item = item,
                    onTest = onTest,
                    onSelect = onSelect,
                    onRemove = onRemove,
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false; newHost = "" },
            title = { Text("添加自定义域名") },
            text = {
                OutlinedTextField(
                    value = newHost,
                    onValueChange = { newHost = it },
                    singleLine = true,
                    placeholder = { Text("www.cdnhjk.net") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newHost.isNotBlank()) {
                        vm.addCustom(newHost)
                        newHost = ""
                    }
                    showAdd = false
                }) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false; newHost = "" }) { Text("取消") }
            },
        )
    }

    // 删除域名二次确认：删除当前生效域名会静默切换到其它域名，需告知用户
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除域名") },
            text = {
                Text(
                    buildString {
                        append("确认删除「${target.host}」？\n\n")
                        if (target.isCurrent) {
                            append("这是当前生效的域名，删除后将自动切换到其它可用域名。\n\n")
                        }
                        append("此操作不可撤销。")
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val host = target.host
                    deleteTarget = null
                    vm.removeCustom(host)
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun DomainRow(
    item: DomainItem,
    onTest: () -> Unit,
    onSelect: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isCurrent) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.host,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (item.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    if (item.isCurrent) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) { Text("当前", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall) }
                    }
                    if (item.isCustom) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) { Text("自定义", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                when {
                    item.testing -> Text("测速中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    item.error != null -> Text("失败：${item.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    item.latency != null -> Text("${item.latency} ms", style = MaterialTheme.typography.bodySmall, color = if (item.latency < 500) Color(0xFF2E7D32) else Color(0xFFEF6C00))
                    else -> Text("未测速", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = onTest) {
                Icon(Icons.Outlined.Speed, contentDescription = "测速")
            }
            IconButton(onClick = onSelect) {
                Icon(Icons.Outlined.Check, contentDescription = "设为当前")
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除")
                }
            }
        }
    }
}
