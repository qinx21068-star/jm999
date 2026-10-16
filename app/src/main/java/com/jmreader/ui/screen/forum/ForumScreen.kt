@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jmreader.ui.screen.forum

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.NavigateBefore
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.outlined.FirstPage
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.LastPage
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jmreader.data.AppContainer
import com.jmreader.data.dto.JmCommentDto
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.repository.Resource
import coil.compose.AsyncImage

/**
 * 讨论区：原生 Compose UI 渲染全局评论信息流。
 *
 * v27.9：从 HTML 抓取禁漫网页端 `/forum/`（JmWebFetcher + jm365.work 重定向获取"无 CF 域名"，
 * 不稳定——CF 拦截/换域/超时频繁，导致"根本加载不出来"）改为 /forum JSON API
 * （mode="manhua", aid=null → 全局评论流）。与 jasmine 客户端的 comments_screen 同款：
 * 展示所有本子的最新评论，每条带本子标题，点击可跳转该本子详情页。
 *
 * 数据来源：[com.jmreader.data.repository.JMRepository.forum]，
 * 走 reqApi 通道（token 鉴权 + AES 解密 + 域名轮换），与搜索/详情同款，已验证稳定。
 *
 * **JM 编号跳转**：评论正文里的 JM 编号用 [JmLinkedText] 高亮 + clickable，
 * 点击调 onOpenComic 跳 App 内详情页；评论卡片底部的本子标题也可点跳转。
 */
@Composable
fun ForumScreen(
    onBack: () -> Unit,
    onOpenComic: (String) -> Unit,
    container: AppContainer,
) {
    val vm: ForumViewModel = viewModel(factory = ForumVMFactory(container))
    val state by vm.state.collectAsState()
    val comments = state.comments
    val page = state.page
    val totalPages = state.totalPages
    val totalCount = state.total
    val loading = state.loading
    val error = state.error
    // key by committed page; a failed request retains both list and scroll position.
    val listState = key(page) { rememberLazyListState(vm.scrollIndex, vm.scrollOffset) }
    DisposableEffect(vm, listState, page) {
        onDispose {
            vm.saveScroll(page, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (totalCount > 0) "讨论区 ($totalCount)" else "讨论区",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.loadPage(page) }, enabled = !loading) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            if (comments.isNotEmpty()) {
                ForumListContent(
                    comments = comments,
                    previews = state.previews,
                    previewErrors = state.previewErrors,
                    onRetryPreview = vm::retryPreview,
                    listState = listState,
                    pagingEnabled = !loading,
                    page = page,
                    totalPages = totalPages,
                    onOpenComic = onOpenComic,
                    onPrev = { if (page > 1) vm.loadPage(page - 1) },
                    onNext = { if (page < totalPages) vm.loadPage(page + 1) },
                    onJumpToPage = { target -> if (target != page) vm.loadPage(target) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (loading) {
                LoadingShield(
                    onAbort = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (error != null) {
                val err = error
                if (err != null) {
                    ErrorBox(
                        message = err,
                        onRetry = vm::retry,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    EmptyBox("暂无讨论", Modifier.fillMaxSize())
                }
            } else {
                EmptyBox("暂无讨论", Modifier.fillMaxSize())
            }

            // 翻页时顶部进度条（保留旧评论可查看）
            if (loading && comments.isNotEmpty()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }

            // 翻页失败 banner
            val bannerErr = error
            if (bannerErr != null && comments.isNotEmpty()) {
                PageErrorBanner(
                    message = bannerErr,
                    onRetry = vm::retry,
                    onDismiss = vm::dismissError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }
        }
    }
}

/** 翻页失败时顶部叠加的 banner：保留旧评论可查看，提供"重试"和"忽略"按钮。 */
@Composable
private fun PageErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "翻页失败：$message",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onRetry) { Text("重试") }
        TextButton(onClick = onDismiss) { Text("忽略") }
    }
}

/**
 * 加载蒙层：加载期间显示的加载状态。
 * 提供"返回"按钮（加载失败或不想等时退出）。
 */
@Composable
internal fun LoadingShield(
    onAbort: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Text(
                "正在加载…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp),
            )
            TextButton(onClick = onAbort, modifier = Modifier.padding(top = 12.dp)) {
                Text("返回")
            }
        }
    }
}

// ============================ 原生评论列表 ============================

@Composable
private fun ForumListContent(
    comments: List<JmCommentDto>,
    previews: Map<String, ComicDetailDto>,
    previewErrors: Map<String, String>,
    onRetryPreview: (String) -> Unit,
    listState: LazyListState,
    pagingEnabled: Boolean,
    page: Int,
    totalPages: Int,
    onOpenComic: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onJumpToPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showJumpDialog by remember { mutableStateOf(false) }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(comments, key = { i, c -> "$i:${c.cid}" }) { _, c ->
            val aid = forumComicId(c)
            ForumCommentCard(
                comment = c,
                comicId = aid,
                preview = aid?.let { previews[it] },
                previewError = aid?.let { previewErrors[it] },
                onRetryPreview = { if (aid != null) onRetryPreview(aid) },
                onOpenComic = onOpenComic,
            )

        }
        // 分页栏：首页 / 上一页 / 页码(可点跳页) / 下一页 / 末页
        item(key = "pagination") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = { onJumpToPage(1) },
                    enabled = pagingEnabled && page > 1,
                ) { Icon(Icons.Outlined.FirstPage, contentDescription = "第一页") }
                IconButton(onClick = onPrev, enabled = pagingEnabled && page > 1) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateBefore, contentDescription = "上一页")
                }
                Text(
                    "第 $page / $totalPages 页",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = pagingEnabled) { showJumpDialog = true }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                IconButton(onClick = onNext, enabled = pagingEnabled && page < totalPages) {
                    Icon(Icons.AutoMirrored.Outlined.NavigateNext, contentDescription = "下一页")
                }
                IconButton(
                    onClick = { onJumpToPage(totalPages) },
                    enabled = pagingEnabled && page < totalPages,
                ) { Icon(Icons.Outlined.LastPage, contentDescription = "最后一页") }
            }
        }
    }

    if (showJumpDialog) {
        ForumPageJumpDialog(
            currentPage = page,
            totalPages = totalPages,
            onDismiss = { showJumpDialog = false },
            onJump = { target ->
                showJumpDialog = false
                onJumpToPage(target)
            },
        )
    }
}

@Composable
private fun ForumPageJumpDialog(
    currentPage: Int,
    totalPages: Int,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit,
) {
    var input by remember { mutableStateOf(currentPage.toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到页") },
        text = {
            Column {
                Text(
                    "共 $totalPages 页，当前第 $currentPage 页",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("页码") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                input.toIntOrNull()?.let { n ->
                    val target = n.coerceIn(1, totalPages)
                    onJump(target)
                }
            }) { Text("跳转") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ForumCommentCard(
    comment: JmCommentDto,
    comicId: String?,
    preview: ComicDetailDto?,
    previewError: String?,
    onRetryPreview: () -> Unit,
    onOpenComic: (String) -> Unit,
) {

    val ctx = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(
                        android.content.ClipData.newPlainText("comment", comment.content),
                    )
                    android.widget.Toast.makeText(ctx, "已复制评论内容", android.widget.Toast.LENGTH_SHORT).show()
                },
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = com.jmreader.ui.theme.LocalCardElevation.current),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.jmreader.ui.components.UserAvatar(
                    avatar = comment.photo,
                    name = comment.nickname,
                    sizeDp = 32,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            comment.nickname.ifBlank { "匿名" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (comment.level > 0) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Lv.${comment.level}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.secondaryContainer,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    val metaText = buildString {
                        if (comment.likes > 0) append("♥ ${comment.likes}")
                        if (comment.addtime.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(comment.addtime)
                        }
                    }
                    if (metaText.isNotBlank()) {
                        Text(
                            metaText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (comment.content.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(Modifier.height(10.dp))
                JmLinkedText(
                    text = comment.content,
                    onOpenComic = onOpenComic,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 本子标题：可点跳转该本子详情页（jasmine 同款行为）
            if (!comment.aid.isNullOrBlank() && comment.name.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                val aid = comment.aid
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                        .clickable { onOpenComic(aid) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        comment.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // 嵌套回复：内联展示（缩进 + 浅色背景）
            if (comment.replys.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                comment.replys.forEach { reply ->
                    ReplyItem(reply = reply, onOpenComic = onOpenComic)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        if (comicId != null) {
            ForumComicPreview(
                comicId = comicId,
                title = preview?.name?.takeIf { it.isNotBlank() } ?: comment.name,
                preview = preview,
                error = previewError,
                onRetry = onRetryPreview,
                onOpen = { onOpenComic(comicId) },
            )
        }
        }
    }
}

@Composable
private fun ForumComicPreview(
    comicId: String,
    title: String,
    preview: ComicDetailDto?,
    error: String?,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier.width(96.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpen).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (!preview?.cover.isNullOrBlank()) {
            AsyncImage(
                model = preview?.cover,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(116.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else if (preview == null && error == null) {
            Box(Modifier.fillMaxWidth().height(116.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text("JM$comicId", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        preview?.tags?.takeIf { it.isNotEmpty() }?.let { tags ->
            Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (error != null) {
            Text("预览加载失败", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text("重试预览") }
        }
    }
}

/** 嵌套回复项：缩进 + 浅色背景，与主评论区分。 */
@Composable
private fun ReplyItem(reply: JmCommentDto, onOpenComic: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.jmreader.ui.components.UserAvatar(
                avatar = reply.photo,
                name = reply.nickname,
                sizeDp = 24,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                reply.nickname.ifBlank { "匿名" },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (reply.addtime.isNotBlank()) {
                Text(
                    reply.addtime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (reply.content.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            JmLinkedText(
                text = reply.content,
                onOpenComic = onOpenComic,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ============================ JM 编号可点链接 ============================

internal fun linkifyJm(text: String, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString {
    val jmRegex = Regex("""\b(JM|jm)(\d{4,8})\b""")
    return buildAnnotatedString {
        var lastEnd = 0
        for (m in jmRegex.findAll(text)) {
            append(text.substring(lastEnd, m.range.first))
            val id = m.groupValues[2]
            pushStringAnnotation(tag = "JM_LINK", annotation = id)
            withStyle(SpanStyle(color = linkColor, fontWeight = FontWeight.SemiBold)) {
                append(m.value)
            }
            pop()
            lastEnd = m.range.last + 1
        }
        if (lastEnd < text.length) append(text.substring(lastEnd))
    }
}

@Composable
internal fun JmLinkedText(
    text: String,
    onOpenComic: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) {
        linkifyJm(text, linkColor)
    }
    androidx.compose.foundation.text.ClickableText(
        text = annotated,
        style = MaterialTheme.typography.bodyMedium.copy(
            lineHeight = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp),
            color = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations("JM_LINK", offset, offset)
                .firstOrNull()?.let { onOpenComic(it.item) }
        },
    )
}

// ============================ 通用组件 ============================

@Composable
private fun EmptyBox(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun ErrorBox(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("加载失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .padding(top = 8.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
            onRetry?.let {
                androidx.compose.material3.Button(onClick = it) { Text("重试") }
            }
            androidx.compose.material3.OutlinedButton(onClick = {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("error", message))
                android.widget.Toast.makeText(ctx, "错误已复制", android.widget.Toast.LENGTH_SHORT).show()
            }) { Text("复制错误") }
        }
    }
}
