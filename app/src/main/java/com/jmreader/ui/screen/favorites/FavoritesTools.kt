@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jmreader.ui.screen.favorites

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.ComicBriefDto
import com.jmreader.data.repository.Resource
import com.jmreader.ui.nav.Routes
import androidx.navigation.NavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** Batch management stays in a dialog so the large card list keeps its existing fast path. */
@Composable
fun FavoriteBatchDialog(container: AppContainer, comics: List<ComicBriefDto>, onDismiss: () -> Unit, onResult: (String) -> Unit) {
    val folders by container.favoritesStore.folders.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var busy by remember { mutableStateOf(false) }
    var showMove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun action(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { onResult(block()) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { onResult("操作失败：${e.message}") }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("批量管理 · 已选 ${selected.size}") },
        text = {
            Column {
                Row {
                    TextButton(enabled = !busy, onClick = { selected = comics.map { it.id }.toSet() }) { Text("全选") }
                    TextButton(enabled = !busy, onClick = { selected = emptySet() }) { Text("清空选择") }
                }
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(comics, key = { it.id }) { comic ->
                        Row {
                            Checkbox(enabled = !busy, checked = comic.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + comic.id else selected - comic.id })
                            Text(comic.name.ifBlank { "JM${comic.id}" }, Modifier.weight(1f).padding(top = 10.dp), maxLines = 2)
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { showMove = true }) { Text("移动分组") }
                    TextButton(enabled = selected.isNotEmpty() && !busy, onClick = {
                        action {
                            var success = 0; var failed = 0
                            for (comic in comics.filter { it.id in selected }) {
                                when (val detail = container.repository.comicDetail(comic.id)) {
                                    is Resource.Success -> { container.downloadManager.enqueue(comic, detail.data); success++ }
                                    else -> failed++
                                }
                            }
                            "已加入 $success 本下载；$failed 本未能获取详情"
                        }
                    }) { Text("批量下载") }
                    TextButton(enabled = selected.isNotEmpty() && !busy, onClick = { confirmDelete = true }) { Text("删除收藏") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("完成") } },
    )
    if (showMove) AlertDialog(
        onDismissRequest = { showMove = false },
        title = { Text("移动到分组") },
        text = {
            LazyColumn(Modifier.heightIn(max = 240.dp)) {
                item { TextButton(onClick = { showMove = false; action { container.favoritesStore.moveMany(selected, null); "已移动到未分组" } }) { Text("未分组") } }
                item { TextButton(onClick = { showMove = false; action { container.favoritesStore.moveMany(selected, com.jmreader.data.local.FavoritesStore.READ_LATER_FOLDER_ID); "已移动到稍后再看" } }) { Text("稍后再看") } }
                items(folders, key = { it.id }) { folder -> TextButton(onClick = { showMove = false; action { container.favoritesStore.moveMany(selected, folder.id); "已移动到 ${folder.name}" } }) { Text(folder.name) } }
            }
        },
        confirmButton = { TextButton(onClick = { showMove = false }) { Text("取消") } },
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除 ${selected.size} 条本地收藏？") },
        text = { Text("漫画下载文件与阅读历史保留。") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; action { container.favoritesStore.removeMany(selected); selected = emptySet(); "已删除所选收藏" } }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
    )
}

@Composable
fun RandomReadingDialog(container: AppContainer, navController: NavController, onDismiss: () -> Unit) {
    val favorites by container.favoritesStore.items.collectAsState()
    val history by container.historyStore.items.collectAsState()
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(0) }
    var tag by remember { mutableStateOf("") }
    var unread by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("随机阅读") },
        text = { Column {
            Row {
                FilterChip(source == 0, { source = 0 }, { Text("本地收藏") })
                Spacer(Modifier.width(8.dp))
                FilterChip(source == 1, { source = 1 }, { Text("阅读历史") })
            }
            OutlinedTextField(tag, { tag = it }, enabled = !busy, label = { Text("指定标签（可留空）") })
            Row { Checkbox(unread, { unread = it }, enabled = !busy); Text("优先未读章节", Modifier.padding(top = 12.dp)) }
            Text(message, style = MaterialTheme.typography.bodySmall)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    container.favoritesStore.ensureLoaded(); container.historyStore.ensureLoaded()
                    val (tags, names, authors) = container.blockedTagsStore.allRules.first()
                    val rules = container.blockedTagsStore.normalizeRules(tags, names, authors)
                    val pool = (if (source == 0) favorites else history.map { it.comic }).distinctBy { it.id }.shuffled()
                    var target: Pair<String, String>? = null
                    for (comic in pool.take(10)) {
                        val detail = (withTimeoutOrNull(3_000) { container.repository.comicDetail(comic.id) } as? Resource.Success)?.data ?: continue
                        if (container.blockedTagsStore.isBlocked(detail.tags, detail.name, detail.author, rules)) continue
                        if (tag.isNotBlank() && detail.tags.none { it.equals(tag.trim(), true) }) continue
                        val read = history.filter { it.comic.id == comic.id }.map { it.chapterId }.toSet()
                        val choices = if (unread) detail.chapters.filter { it.id !in read } else detail.chapters
                        val chapter = choices.randomOrNull() ?: continue
                        target = comic.id to chapter.id
                        break
                    }
                    if (target != null) { navController.navigate(Routes.reader(target.first, target.second)); onDismiss() }
                    else message = "没有匹配的可读章节，或网络未能取得详情。"
                } catch (e: CancellationException) { throw e }
                catch (e: Throwable) { message = "随机阅读失败：${e.message}" }
                finally { busy = false }
            }
        }) { Text("抽一本") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
