@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.jmreader.ui.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jmreader.data.AppContainer
import com.jmreader.data.api.direct.executeCancellable
import com.jmreader.data.repository.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import okhttp3.Request

@Composable
fun FilterRuleTester(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val rulesEnabled by container.blockedTagsStore.enabled.collectAsState(initial = true)
    var id by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("输入手工资料，或读取一个漫画 ID 的真实资料后测试。") }
    var busy by remember { mutableStateOf(false) }
    fun test(load: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                if (load) {
                    val comicId = id.trim().removePrefix("JM").removePrefix("jm")
                    require(comicId.isNotBlank()) { "请输入漫画 ID" }
                    when (val detail = withTimeout(20_000) { container.repository.comicDetail(comicId) }) {
                        is Resource.Success -> {
                            title = detail.data.name
                            author = detail.data.author.orEmpty()
                            tags = detail.data.tags.joinToString(", ")
                        }
                        is Resource.Error -> error(detail.message)
                        Resource.Loading -> error("资料尚未就绪")
                    }
                }
                val rules = container.blockedTagsStore.configuredRules.first()
                val inputTags = tags.split(',', '，', '\n').map { it.trim() }.filter { it.isNotBlank() }
                val hits = withContext(Dispatchers.Default) {
                    container.blockedTagsStore.matchedRuleLabels(inputTags, title, author, rules)
                }
                result = if (hits.isEmpty()) "这组资料未命中规则。手工资料不代表服务器的完整标签。" else "命中规则：\n" + hits.joinToString("\n")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) { result = "测试超时，请检查网络后重试" } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { result = "无法验证：${e.message}" }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("过滤规则测试器") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Switch(rulesEnabled, onCheckedChange = { enabled -> scope.launch { container.blockedTagsStore.setEnabled(enabled) } })
                    Text("启用列表过滤", Modifier.padding(start = 8.dp, top = 12.dp))
                }
                if (!rulesEnabled) Text("规则暂时不作用于列表，测试仍按保存的规则判断。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(id, { id = it }, label = { Text("漫画 ID") }, singleLine = true)
                OutlinedButton(enabled = !busy, onClick = { test(true) }) { Text("读取真实资料并测试") }
                OutlinedTextField(title, { title = it }, label = { Text("标题") })
                OutlinedTextField(author, { author = it }, label = { Text("作者") })
                OutlinedTextField(tags, { tags = it }, label = { Text("标签，逗号分隔") })
                Text(result, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { test(false) }) { Text("测试手工资料") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
fun NetworkDiagnosticsDialog(container: AppContainer, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by container.settingsStore.settings.collectAsState()
    var comicId by remember { mutableStateOf("") }
    var report by remember { mutableStateOf("选择接口或图片测试。图片测速读取真实封面的前 64 KB，不代表完整图片吞吐。") }
    var busy by remember { mutableStateOf(false) }
    fun run(images: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                report = withTimeout(25_000) {
                    withContext(Dispatchers.IO) {
                        val started = android.os.SystemClock.elapsedRealtime()
                        if (!images) {
                            val (ok, message) = container.healthCheck()
                            "${if (ok) "接口正常" else "接口失败"} · ${android.os.SystemClock.elapsedRealtime() - started} ms\n$message"
                        } else {
                            val id = comicId.trim().removePrefix("JM").removePrefix("jm")
                            require(id.isNotBlank()) { "图片测试需要漫画 ID" }
                            val detail = container.repository.comicDetail(id)
                            val cover = (detail as? Resource.Success)?.data?.cover
                                ?: error((detail as? Resource.Error)?.message ?: "此漫画没有可用封面")
                            val imageUrl = if (settings.serverUrl.isBlank()) cover else com.jmreader.data.repository.proxiedImageUrl(settings.serverUrl, cover)
                            val imageStart = android.os.SystemClock.elapsedRealtime()
                            container.directClient.http.executeCancellable(Request.Builder().url(imageUrl).build()).use { response ->
                                require(response.isSuccessful) { "图片 HTTP ${response.code}" }
                                val stream = response.body?.byteStream() ?: error("图片响应为空")
                                val buffer = ByteArray(8192)
                                var bytes = 0
                                while (bytes < 65_536) {
                                    val n = stream.read(buffer, 0, minOf(buffer.size, 65_536 - bytes))
                                    if (n < 0) break
                                    bytes += n
                                }
                                require(bytes > 0) { "图片无数据" }
                                "图片链路正常 · ${android.os.SystemClock.elapsedRealtime() - imageStart} ms\n收到 $bytes 字节 · ${response.request.url.host}\n这是封面链路测试；正文图片有独立 CDN 轮换。"
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) { report = "诊断超时，请检查网络或代理后重试" } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { report = "测试失败：${e.message}\n可以检查代理，或在域名管理里重新测速选择线路。" }
            finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网络诊断中心") },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模式：${if (settings.serverUrl.isBlank()) "直连" else "后端"}\n代理：${if (settings.proxy.isNullOrBlank()) "未配置" else "已配置"}\n接口：${container.directClient.currentDomain()}\n图片 CDN：${container.directClient.currentImageDomain()}")
                OutlinedTextField(comicId, { comicId = it }, label = { Text("图片测试的漫画 ID") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = !busy, onClick = { run(false) }) { Text("接口测试") }
                    OutlinedButton(enabled = !busy, onClick = { run(true) }) { Text("图片测试") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(report, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
