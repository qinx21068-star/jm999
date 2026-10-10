@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.jmreader.ui.screen.search

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jmreader.data.AppContainer
import com.jmreader.data.local.SearchTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SearchTemplatePanel(container: AppContainer, query: String, order: String, tag: String?, onApply: (SearchTemplate) -> Unit) {
    val templates by container.searchTemplatesStore.templates.collectAsState()
    val scope = rememberCoroutineScope()
    var save by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        TextButton(enabled = query.isNotBlank(), onClick = { name = query.take(40); save = true }) { Text("保存搜索模板") }
        TextButton(onClick = { manage = !manage }) { Text(if (manage) "完成管理" else "模板 (${templates.size})") }
    }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        templates.forEach { template ->
            InputChip(
                selected = false,
                onClick = { if (!manage) onApply(template) },
                label = { Text(template.name) },
                trailingIcon = if (manage) { { TextButton(onClick = {
                    scope.launch {
                        try { container.searchTemplatesStore.remove(template.id) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Throwable) { message = "删除失败：${e.message}" }
                    }
                }, contentPadding = PaddingValues(0.dp)) { Text("删除") } } } else null,
            )
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp)) }
    if (save) AlertDialog(
        onDismissRequest = { save = false },
        title = { Text("命名搜索模板") },
        text = { Column {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("模板名称") })
            Text("$query · $order${tag?.let { " · $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            scope.launch {
                try { container.searchTemplatesStore.save(name, query, order, tag); save = false; message = "模板已保存" }
                catch (e: CancellationException) { throw e }
                catch (e: Throwable) { message = "保存失败：${e.message}" }
            }
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = { save = false }) { Text("取消") } },
    )
}
