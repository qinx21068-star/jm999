@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jmreader.data.AppContainer
import com.jmreader.data.local.BackupManager
import com.jmreader.data.local.BackupPreview
import com.jmreader.data.local.LegacyBundle
import com.jmreader.data.local.LegacyDataImporter
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BackupTools(container: AppContainer) {
    val manager = remember(container) { BackupManager(container) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<BackupPreview?>(null) }
    fun action(block: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try { message = block() }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { message = "操作失败：${e.message ?: "未知错误"}" }
            finally { busy = false }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) action { manager.export(uri); "备份已导出" }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) action { preview = manager.preview(uri); "备份校验通过，请确认导入方式" }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("本地数据备份", style = MaterialTheme.typography.titleMedium)
        Text("导出收藏、阅读/浏览历史、搜索模板、屏蔽规则、设置及下载索引。图片和登录信息不随备份转移。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = { export.launch("一根葱-backup-${System.currentTimeMillis()}.json") }) { Text("导出备份") }
            OutlinedButton(enabled = !busy, onClick = { restore.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("导入备份") }
        }
        TextButton(enabled = !busy, onClick = { action { preview = manager.recoveryPreview(); "已读取上次导入前的快照" } }) { Text("恢复导入前快照") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("从旧版导入（com.jmreader）", style = MaterialTheme.typography.titleMedium)
        Text(
            "旧版没有导出功能，需要先取出它的数据文件。支持 favorites.json、history.json、" +
                "browse_history.json、search_templates.json；按 id 去重合并，不删除现有数据。",
            style = MaterialTheme.typography.bodySmall,
        )
        val importer = remember(container) { LegacyDataImporter(container) }
        var legacy by remember { mutableStateOf<LegacyBundle?>(null) }
        var legacyNames by remember { mutableStateOf("") }
        var showLegacyHelp by remember { mutableStateOf(false) }
        val legacyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) action {
                var merged = LegacyBundle()
                val names = mutableListOf<String>()
                for (uri in uris) {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "文件"
                    val one = importer.read(uri, name)
                    merged = merged.copy(
                        favorites = merged.favorites + one.favorites,
                        folders = merged.folders + one.folders,
                        history = merged.history + one.history,
                        browse = merged.browse + one.browse,
                        templates = merged.templates + one.templates,
                        skipped = merged.skipped + one.skipped,
                    )
                    names += name
                }
                legacy = merged
                legacyNames = names.joinToString("、")
                "已读取：$legacyNames。确认无误后点「合并导入」。"
            }
        }
        OutlinedButton(
            enabled = !busy,
            onClick = { legacyPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        ) { Text("选择旧版数据文件") }
        legacy?.let { bundle ->
            Text(
                "待导入：收藏 ${bundle.favorites.size}、阅读历史 ${bundle.history.size}、" +
                    "浏览历史 ${bundle.browse.size}、模板 ${bundle.templates.size}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (bundle.skipped.isNotEmpty()) {
                Text("跳过：" + bundle.skipped.joinToString("；"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(enabled = !busy, onClick = { action { val result = importer.apply(bundle); legacy = null; result } }) { Text("合并导入") }
        }
        TextButton(onClick = { showLegacyHelp = !showLegacyHelp }) {
            Text(if (showLegacyHelp) "收起提取方法" else "如何取出旧版文件？")
        }
        if (showLegacyHelp) {
            Text(
                "需要 root 或 ADB（旧版 allowBackup=false，无法用云备份/ADB 备份）。\n" +
                    "有 root 的文件管理器：进入 /data/data/com.jmreader/files/，复制 favorites.json、" +
                    "history.json、browse_history.json、search_templates.json。\n\n" +
                    "或连接电脑执行：\n" +
                    "adb shell su -c 'cat /data/data/com.jmreader/files/favorites.json' > favorites.json",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    preview?.let { data ->
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("导入预览") },
            text = { Text(data.summary + "\n\n合并保留当前同 ID 数据与设置；覆盖替换各列表及可迁移设置。导入前自动保存本地恢复快照。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { action { manager.restore(data, true); preview = null; "已合并导入" } }) { Text("合并") } },
            dismissButton = {
                Row {
                    TextButton(enabled = !busy, onClick = { preview = null }) { Text("取消") }
                    TextButton(enabled = !busy, onClick = { action { manager.restore(data, false); preview = null; "已覆盖导入；目录授权保持当前设备设置" } }) { Text("覆盖") }
                }
            },
        )
    }
}
