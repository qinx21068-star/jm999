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
