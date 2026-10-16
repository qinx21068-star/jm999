package com.jmreader.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jmreader.data.dto.ComicDetailDto
import com.jmreader.data.dto.JmCommentDto
import com.jmreader.ui.screen.forum.JmLinkedText

/** Shared compact presentation. Discussion ownership and work comments remain separate callers. */
@Composable
internal fun CompactComment(
    comment: JmCommentDto,
    onOpenComic: (String) -> Unit,
    modifier: Modifier = Modifier,
    attachment: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    var repliesExpanded by rememberSaveable(comment.cid, comment.content) { mutableStateOf(false) }
    val replies = remember(comment.replys) { flattenCommentReplies(comment.replys) }
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            UserAvatar(avatar = comment.photo, name = comment.nickname, sizeDp = 32)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(comment.nickname.ifBlank { "匿名" }, modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (comment.level > 0) {
                        Spacer(Modifier.width(6.dp))
                        Text("Lv.${comment.level}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (comment.addtime.isNotBlank()) Text(comment.addtime, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("评论", comment.content))
                Toast.makeText(context, "已复制评论内容", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "复制评论", modifier = Modifier.size(18.dp)) }
        }
        Column(Modifier.padding(start = 42.dp)) {
            Spacer(Modifier.height(6.dp))
            ExpandableCommentText(comment.content.ifBlank { "（无内容）" }, onOpenComic)
            if (attachment != null) {
                Spacer(Modifier.height(10.dp))
                attachment()
            }
            if (replies.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (if (repliesExpanded) replies else replies.take(2)).forEach { (reply, parentName) ->
                        Column {
                            val name = reply.nickname.ifBlank { "匿名" }
                            Text(if (parentName == null) name else "$name 回复 $parentName",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            ExpandableCommentText(reply.content.ifBlank { "（无内容）" }, onOpenComic, 4)
                            val meta = listOfNotNull(reply.addtime.takeIf { it.isNotBlank() },
                                reply.likes.takeIf { it > 0 }?.let { "♥ $it" },
                                reply.level.takeIf { it > 0 }?.let { "Lv.$it" }).joinToString(" · ")
                            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (comment.likes > 0 || replies.size > 2) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (comment.likes > 0) Text("♥ ${comment.likes}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (replies.size > 2) TextButton(onClick = { repliesExpanded = !repliesExpanded }) {
                        Text(if (repliesExpanded) "收起回复" else "展开其余 ${replies.size - 2} 条回复")
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(Modifier.padding(start = 42.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    }
}

/** Flatten server-returned reply trees without recursive composition or invented reply counts. */
internal fun flattenCommentReplies(replies: List<JmCommentDto>): List<Pair<JmCommentDto, String?>> {
    val result = mutableListOf<Pair<JmCommentDto, String?>>()
    val pending = java.util.ArrayDeque<Pair<JmCommentDto, String?>>()
    replies.asReversed().forEach { pending.addFirst(it to null) }
    while (pending.isNotEmpty()) {
        val entry = pending.removeFirst()
        result.add(entry)
        entry.first.replys.asReversed().forEach { pending.addFirst(it to entry.first.nickname.ifBlank { "匿名" }) }
    }
    return result
}

@Composable
private fun ExpandableCommentText(text: String, onOpenComic: (String) -> Unit, collapsedLines: Int = 6) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    SelectionContainer {
        JmLinkedText(text, onOpenComic, Modifier.fillMaxWidth(),
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
    }
    if (overflows || expanded) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
        Text(if (expanded) "收起正文" else "展开全文")
    }
}

/** One compact attachment; loading, missing cover and failure keep the same thumbnail footprint. */
@Composable
internal fun CompactCommentAttachment(
    comicId: String,
    title: String,
    preview: ComicDetailDto?,
    error: String?,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = "打开作品详情", onClick = onOpen).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(44.dp).height(60.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center) {
                if (!preview?.cover.isNullOrBlank()) AsyncImage(model = preview?.cover, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else if (preview == null && error == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.labelLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("JM$comicId  ›", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                val tags = preview?.tags.orEmpty().take(3).joinToString(" · ")
                if (tags.isNotBlank()) Text(tags, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (error != null) Text("预览暂不可用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (error != null) TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) { Text("重试预览") }
    }
}
