package com.codex.stormy.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.stormy.ui.components.MarkdownText
import com.codex.stormy.ui.components.diff.CollapsibleDiffView
import com.codex.stormy.ui.components.diff.DiffSummaryBadge
import com.codex.stormy.ui.theme.PoppinsFontFamily
import com.codex.stormy.utils.DiffUtils
import java.util.Locale

enum class AiActivityStatus {
    IDLE,
    THINKING,
    TYPING,
    CALLING_TOOL,
    WRITING_FILE,
    READING_FILE,
    EXECUTING
}

sealed class MessageContentBlock {
    data class ThinkingBlock(val content: String, val isActive: Boolean = false) : MessageContentBlock()
    data class TextBlock(val content: String) : MessageContentBlock()
    data class ToolCallBlock(
        val toolName: String,
        val status: ToolStatus,
        val output: String? = null,
        val filePath: String? = null,
        val isActive: Boolean = false,
        val additions: Int = 0,
        val deletions: Int = 0,
        val oldContent: String? = null,
        val newContent: String? = null
    ) : MessageContentBlock()

    data class CodeBlock(val code: String, val language: String?) : MessageContentBlock()
    data class ReasoningBlock(val content: String, val isActive: Boolean = false) : MessageContentBlock()
}

enum class ToolStatus {
    RUNNING, SUCCESS, ERROR
}

fun parseAiMessageContent(content: String, isStreaming: Boolean = false): List<MessageContentBlock> {
    if (content.isBlank()) return emptyList()

    val blocks = mutableListOf<MessageContentBlock>()
    val segments = splitByToolCalls(content)

    segments.forEachIndexed { index, segment ->
        when (segment.type) {
            SegmentType.TEXT -> {
                processTextSegment(
                    content = segment.content,
                    blocks = blocks,
                    isStreaming = isStreaming && index == segments.lastIndex
                )
            }

            SegmentType.TOOL_CALL -> {
                val output = segment.toolOutput?.takeIf { it.isNotBlank() }
                blocks.add(
                    MessageContentBlock.ToolCallBlock(
                        toolName = segment.toolName ?: "Unknown Tool",
                        status = segment.toolStatus ?: ToolStatus.RUNNING,
                        output = output,
                        filePath = extractFilePath(segment.toolName.orEmpty(), output.orEmpty()),
                        isActive = (segment.toolStatus ?: ToolStatus.RUNNING) == ToolStatus.RUNNING && isStreaming
                    )
                )
            }
        }
    }

    val cleaned = coalesceTextBlocks(blocks).filterNot { it is MessageContentBlock.TextBlock && it.content.isBlank() }
    return if (cleaned.isEmpty()) listOf(MessageContentBlock.TextBlock(content)) else cleaned
}

private enum class SegmentType { TEXT, TOOL_CALL }

private data class ContentSegment(
    val type: SegmentType,
    val content: String,
    val toolName: String? = null,
    val toolStatus: ToolStatus? = null,
    val toolOutput: String? = null
)

private fun splitByToolCalls(content: String): List<ContentSegment> {
    val headerRegex = Regex(
        pattern = """(?m)^\s*🔧\s*\*\*([^*]+)\*\*\s*\r?\n\s*(✅|❌|⏳)\s*""",
        options = setOf(RegexOption.MULTILINE)
    )

    val matches = headerRegex.findAll(content).toList()
    if (matches.isEmpty()) return listOf(ContentSegment(type = SegmentType.TEXT, content = content))

    val segments = mutableListOf<ContentSegment>()
    var cursor = 0

    for (i in matches.indices) {
        val match = matches[i]
        val toolStart = match.range.first
        val outputStart = match.range.last + 1
        val nextStart = matches.getOrNull(i + 1)?.range?.first ?: content.length

        if (toolStart > cursor) {
            segments.add(
                ContentSegment(
                    type = SegmentType.TEXT,
                    content = content.substring(cursor, toolStart)
                )
            )
        }

        val toolName = match.groupValues[1].trim()
        val statusEmoji = match.groupValues[2]
        val status = when (statusEmoji) {
            "✅" -> ToolStatus.SUCCESS
            "❌" -> ToolStatus.ERROR
            else -> ToolStatus.RUNNING
        }

        val rawOutput = content.substring(outputStart, nextStart)
        val output = rawOutput.trimEnd().trim('\n', '\r').ifBlank { null }

        segments.add(
            ContentSegment(
                type = SegmentType.TOOL_CALL,
                content = content.substring(toolStart, nextStart),
                toolName = toolName,
                toolStatus = status,
                toolOutput = output
            )
        )

        cursor = nextStart
    }

    if (cursor < content.length) {
        segments.add(
            ContentSegment(
                type = SegmentType.TEXT,
                content = content.substring(cursor)
            )
        )
    }

    return segments.filterNot { it.content.isBlank() && it.type == SegmentType.TEXT }
}

private fun processTextSegment(
    content: String,
    blocks: MutableList<MessageContentBlock>,
    isStreaming: Boolean
) {
    if (content.isBlank()) return

    val tokenRegex = Regex(
        pattern =
        """```([^\n\r`]*)\r?\n([\s\S]*?)```|<thinking>([\s\S]*?)</thinking>|<think>([\s\S]*?)</think>|<reasoning>([\s\S]*?)</reasoning>|<reason>([\s\S]*?)</reason>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    var cursor = 0
    for (match in tokenRegex.findAll(content)) {
        val start = match.range.first
        val endExclusive = match.range.last + 1

        if (start > cursor) {
            val text = content.substring(cursor, start)
            if (text.isNotBlank()) blocks.add(MessageContentBlock.TextBlock(text))
        }

        when {
            match.groupValues[2].isNotEmpty() || match.groupValues[1].isNotEmpty() -> {
                val language = match.groupValues[1].trim().ifBlank { null }
                val code = match.groupValues[2].trimEnd()
                blocks.add(MessageContentBlock.CodeBlock(code = code, language = language))
            }

            match.groupValues[3].isNotEmpty() -> {
                blocks.add(MessageContentBlock.ThinkingBlock(content = match.groupValues[3].trim(), isActive = false))
            }

            match.groupValues[4].isNotEmpty() -> {
                blocks.add(MessageContentBlock.ThinkingBlock(content = match.groupValues[4].trim(), isActive = false))
            }

            match.groupValues[5].isNotEmpty() -> {
                blocks.add(MessageContentBlock.ReasoningBlock(content = match.groupValues[5].trim(), isActive = false))
            }

            match.groupValues[6].isNotEmpty() -> {
                blocks.add(MessageContentBlock.ReasoningBlock(content = match.groupValues[6].trim(), isActive = false))
            }
        }

        cursor = endExclusive
    }

    if (cursor < content.length) {
        val tail = content.substring(cursor)

        if (isStreaming) {
            val open = findUnclosedReasoningOrThinking(tail)
            if (open != null) {
                val prefix = tail.substring(0, open.openTagStart)
                if (prefix.isNotBlank()) blocks.add(MessageContentBlock.TextBlock(prefix))

                val body = tail.substring(open.contentStart).trimEnd()
                when (open.kind) {
                    OpenKind.THINKING -> blocks.add(MessageContentBlock.ThinkingBlock(content = body.trim(), isActive = true))
                    OpenKind.REASONING -> blocks.add(MessageContentBlock.ReasoningBlock(content = body.trim(), isActive = true))
                }
                return
            }
        }

        if (tail.isNotBlank()) blocks.add(MessageContentBlock.TextBlock(tail))
    } else if (blocks.isEmpty() && content.isNotBlank()) {
        blocks.add(MessageContentBlock.TextBlock(content))
    }
}

private enum class OpenKind { THINKING, REASONING }

private data class UnclosedOpen(
    val kind: OpenKind,
    val openTagStart: Int,
    val contentStart: Int
)

private fun findUnclosedReasoningOrThinking(text: String): UnclosedOpen? {
    val lower = text.lowercase(Locale.ROOT)

    fun findOpen(kind: OpenKind, openTags: List<String>, closeTags: List<String>): UnclosedOpen? {
        val openIndex = openTags
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: return null

        val closeAfter = closeTags.any { tag ->
            val close = lower.indexOf(tag, startIndex = openIndex + 1)
            close >= 0
        }
        if (closeAfter) return null

        val openTag = openTags.first { lower.indexOf(it) == openIndex }
        return UnclosedOpen(kind = kind, openTagStart = openIndex, contentStart = openIndex + openTag.length)
    }

    val thinking = findOpen(
        kind = OpenKind.THINKING,
        openTags = listOf("<thinking>", "<think>"),
        closeTags = listOf("</thinking>", "</think>")
    )
    val reasoning = findOpen(
        kind = OpenKind.REASONING,
        openTags = listOf("<reasoning>", "<reason>"),
        closeTags = listOf("</reasoning>", "</reason>")
    )

    return listOfNotNull(thinking, reasoning).minByOrNull { it.openTagStart }
}

private fun extractFilePath(toolName: String, output: String): String? {
    val name = toolName.lowercase(Locale.ROOT)

    val fileToolKeys = listOf(
        "read_file", "write_file", "create_file", "delete_file", "patch_file", "edit_file",
        "read file", "write file", "create file", "delete file", "patch file", "edit file"
    )

    val isFileTool = fileToolKeys.any { key -> name.contains(key) }
    if (!isFileTool && output.isBlank()) return null

    val explicitPath = Regex("""(?im)^\s*(?:file|path)\s*[:=]\s*([^\s]+)\s*$""")
        .find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    if (explicitPath != null) return explicitPath

    val diffPath = Regex("""(?m)^\+\+\+\s+(?:b/)?([^\s]+)\s*$""")
        .find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    if (diffPath != null) return diffPath

    val generic = Regex("""(?i)(?:^|[\s"'`(])([a-z0-9_\-./\\]+?\.[a-z0-9]{1,10})(?:$|[\s"'`),.])""")
        .find(output)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.startsWith("http", ignoreCase = true) }

    return generic
}

private fun coalesceTextBlocks(blocks: List<MessageContentBlock>): List<MessageContentBlock> {
    if (blocks.isEmpty()) return blocks
    val out = ArrayList<MessageContentBlock>(blocks.size)

    for (b in blocks) {
        val last = out.lastOrNull()
        if (b is MessageContentBlock.TextBlock && last is MessageContentBlock.TextBlock) {
            out[out.lastIndex] = MessageContentBlock.TextBlock(last.content + b.content)
        } else {
            out.add(b)
        }
    }
    return out
}

@Composable
fun AiMessageContent(
    content: String,
    timestamp: String,
    isStreaming: Boolean = false,
    currentActivity: AiActivityStatus = AiActivityStatus.IDLE,
    modifier: Modifier = Modifier
) {
    val parsedBlocks = remember(content, isStreaming) {
        parseAiMessageContent(content, isStreaming)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(200)),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isStreaming) {
            LiveActivityIndicator(status = currentActivity)
        }

        parsedBlocks.forEachIndexed { index, block ->
            when (block) {
                is MessageContentBlock.ThinkingBlock -> {
                    ThinkingSection(
                        content = block.content,
                        isActive = block.isActive && isStreaming
                    )
                }

                is MessageContentBlock.ReasoningBlock -> {
                    ReasoningSection(
                        content = block.content,
                        isActive = block.isActive && isStreaming
                    )
                }

                is MessageContentBlock.ToolCallBlock -> {
                    ToolCallSection(
                        toolName = block.toolName,
                        status = block.status,
                        output = block.output,
                        filePath = block.filePath,
                        isActive = block.isActive && isStreaming,
                        additions = block.additions,
                        deletions = block.deletions,
                        oldContent = block.oldContent,
                        newContent = block.newContent
                    )
                }

                is MessageContentBlock.TextBlock -> {
                    ResponseSection(
                        content = block.content,
                        timestamp = if (index == parsedBlocks.lastIndex && !isStreaming) timestamp else null,
                        isStreaming = index == parsedBlocks.lastIndex && isStreaming
                    )
                }

                is MessageContentBlock.CodeBlock -> {
                    CodeSection(
                        code = block.code,
                        language = block.language
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveActivityIndicator(
    status: AiActivityStatus,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "activity_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    if (status == AiActivityStatus.IDLE) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha))
            )

            Icon(
                imageVector = when (status) {
                    AiActivityStatus.THINKING -> Icons.Outlined.Psychology
                    AiActivityStatus.TYPING -> Icons.Outlined.TextFields
                    AiActivityStatus.CALLING_TOOL -> Icons.Outlined.Build
                    AiActivityStatus.WRITING_FILE -> Icons.Outlined.Edit
                    AiActivityStatus.READING_FILE -> Icons.Outlined.Description
                    AiActivityStatus.EXECUTING -> Icons.Outlined.Code
                    else -> Icons.Outlined.AutoAwesome
                },
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Text(
                text = when (status) {
                    AiActivityStatus.THINKING -> "Thinking..."
                    AiActivityStatus.TYPING -> "Typing..."
                    AiActivityStatus.CALLING_TOOL -> "Calling tool..."
                    AiActivityStatus.WRITING_FILE -> "Writing file..."
                    AiActivityStatus.READING_FILE -> "Reading file..."
                    AiActivityStatus.EXECUTING -> "Executing..."
                    else -> "Processing..."
                },
                style = MaterialTheme.typography.labelMedium,
                fontFamily = PoppinsFontFamily,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun ReasoningSection(
    content: String,
    isActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "arrow_rotation"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
        tonalElevation = 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }

                Text(
                    text = "Reasoning",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )

                if (isActive) PulsingDots()

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationAngle),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    SelectionContainer {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = PoppinsFontFamily,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f),
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingSection(
    content: String,
    isActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(200),
        label = "arrow_rotation"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
        tonalElevation = 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Psychology,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }

                Text(
                    text = "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (isActive) PulsingDots()

                Spacer(modifier = Modifier.weight(1f))

                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(rotationAngle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    SelectionContainer {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = PoppinsFontFamily,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot_alpha_$index"
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary.copy(alpha = alpha))
            )
        }
    }
}

private data class UnifiedDiffSummary(val additions: Int, val deletions: Int)

private fun computeUnifiedDiffSummary(output: String): UnifiedDiffSummary? {
    val looksLikeDiff = output.contains("diff --git") ||
        output.contains("\n+++") || output.startsWith("+++ ") ||
        output.contains("\n--- ") || output.startsWith("--- ")

    if (!looksLikeDiff) return null

    var add = 0
    var del = 0
    for (line in output.lineSequence()) {
        if (line.startsWith("+++ ") || line.startsWith("--- ")) continue
        if (line.startsWith("+")) add++
        else if (line.startsWith("-")) del++
    }

    return if (add == 0 && del == 0) null else UnifiedDiffSummary(add, del)
}

@Composable
private fun ToolCallSection(
    toolName: String,
    status: ToolStatus,
    output: String?,
    filePath: String?,
    isActive: Boolean = false,
    additions: Int = 0,
    deletions: Int = 0,
    oldContent: String? = null,
    newContent: String? = null,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    val statusColor = when (status) {
        ToolStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        ToolStatus.ERROR -> MaterialTheme.colorScheme.error
        ToolStatus.RUNNING -> MaterialTheme.colorScheme.tertiary
    }

    val normalizedToolName = toolName.lowercase(Locale.ROOT)
    val isFileModificationTool = normalizedToolName.let { name ->
        name.contains("write") || name.contains("patch") ||
            name.contains("create") || name.contains("edit") ||
            name.contains("modify") || name.contains("update")
    }

    val hasDiffData = oldContent != null && newContent != null && oldContent != newContent
    val showDiffView = isFileModificationTool && hasDiffData && status == ToolStatus.SUCCESS

    val diffResult = remember(showDiffView, oldContent, newContent) {
        if (showDiffView && oldContent != null && newContent != null) {
            DiffUtils.computeDiff(oldContent, newContent)
        } else {
            null
        }
    }

    val outputDiffSummary = remember(output) {
        output?.let(::computeUnifiedDiffSummary)
    }

    val displayAdditions = when {
        additions > 0 -> additions
        diffResult != null -> diffResult.additions
        outputDiffSummary != null -> outputDiffSummary.additions
        else -> 0
    }

    val displayDeletions = when {
        deletions > 0 -> deletions
        diffResult != null -> diffResult.deletions
        outputDiffSummary != null -> outputDiffSummary.deletions
        else -> 0
    }

    val canExpand = !output.isNullOrBlank() || showDiffView

    val toolPulseTransition = rememberInfiniteTransition(label = "tool_pulse")
    val pulsingScale by toolPulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tool_scale"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = when (status) {
            ToolStatus.SUCCESS -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
            ToolStatus.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
            ToolStatus.RUNNING -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.15f)
        },
        tonalElevation = 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = canExpand) { isExpanded = !isExpanded }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = getToolIcon(toolName),
                    contentDescription = null,
                    modifier = Modifier
                        .size(16.dp)
                        .graphicsLayer {
                            val s = if (isActive) pulsingScale else 1f
                            scaleX = s
                            scaleY = s
                        },
                    tint = statusColor
                )

                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = PoppinsFontFamily,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                filePath?.let { path ->
                    val displayName = path.substringAfterLast('/').substringAfterLast('\\')
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(96.dp)
                    )
                }

                if (showDiffView || displayAdditions > 0 || displayDeletions > 0) {
                    DiffSummaryBadge(
                        additions = displayAdditions,
                        deletions = displayDeletions
                    )
                }

                Icon(
                    imageVector = when (status) {
                        ToolStatus.SUCCESS -> Icons.Outlined.CheckCircle
                        ToolStatus.ERROR -> Icons.Outlined.Error
                        ToolStatus.RUNNING -> Icons.Outlined.AutoAwesome
                    },
                    contentDescription = status.name,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor
                )

                if (canExpand) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(
                visible = isExpanded && canExpand,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )

                    if (showDiffView) {
                        CollapsibleDiffView(
                            filePath = filePath ?: "file",
                            oldContent = oldContent.orEmpty(),
                            newContent = newContent.orEmpty(),
                            toolName = toolName,
                            isSuccess = status == ToolStatus.SUCCESS,
                            modifier = Modifier.padding(8.dp),
                            initiallyExpanded = true
                        )
                    }

                    if (!output.isNullOrBlank() && (!showDiffView || output.isNotBlank())) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.5f))
                                .padding(10.dp)
                        ) {
                            SelectionContainer {
                                Text(
                                    text = output,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getToolIcon(toolName: String): ImageVector {
    val name = toolName.lowercase(Locale.ROOT)
    return when {
        name.contains("read") && name.contains("file") -> Icons.Outlined.Description
        name.contains("write") || name.contains("create") || name.contains("patch") -> Icons.Outlined.Edit
        name.contains("list") || name.contains("folder") -> Icons.Outlined.FolderOpen
        name.contains("delete") -> Icons.Outlined.Error
        name.contains("code") || name.contains("execute") -> Icons.Outlined.Code
        else -> Icons.Outlined.Build
    }
}

@Composable
private fun ResponseSection(
    content: String,
    timestamp: String?,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MarkdownText(
            markdown = content,
            modifier = Modifier.fillMaxWidth(),
            textColor = MaterialTheme.colorScheme.onSurface,
            linkColor = MaterialTheme.colorScheme.primary
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isStreaming) {
                StreamingIndicator()
            } else {
                Spacer(modifier = Modifier.width(1.dp))
            }

            timestamp?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = PoppinsFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun StreamingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "stream_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
        )
        Text(
            text = "Generating",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = PoppinsFontFamily,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun CodeSection(
    code: String,
    language: String?,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 0.dp
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language?.trim()?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: "CODE",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(code)) },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy code",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SelectionContainer {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}