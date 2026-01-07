package com.codex.stormy.ui.screens.editor.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codex.stormy.R
import com.codex.stormy.data.ai.AiModel
import com.codex.stormy.data.ai.tools.TodoItem
import com.codex.stormy.domain.model.ChatMessage
import com.codex.stormy.ui.components.DiffView
import com.codex.stormy.ui.components.TaskPlanningPanel
import com.codex.stormy.ui.components.chat.FileMentionPopup
import com.codex.stormy.ui.components.chat.ModelSelectorSheet
import com.codex.stormy.ui.components.chat.MentionItem
import com.codex.stormy.ui.components.chat.extractMentionQuery
import com.codex.stormy.ui.components.chat.replaceMentionInText
import com.codex.stormy.ui.components.message.AiMessageContent
import com.codex.stormy.domain.model.FileTreeNode
import com.codex.stormy.ui.components.toUiCodeChange
import com.codex.stormy.ui.theme.CodeXTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTab(
    messages: List<ChatMessage>,
    inputText: String,
    isLoading: Boolean,
    agentMode: Boolean,
    taskList: List<TodoItem> = emptyList(),
    currentModel: AiModel? = null,
    availableModels: List<AiModel> = emptyList(),
    fileTree: List<FileTreeNode> = emptyList(),
    onInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: (() -> Unit)? = null,
    onToggleAgentMode: () -> Unit,
    onModelChange: (AiModel) -> Unit = {},
    onRefreshModels: () -> Unit = {},
    onNavigateToModels: () -> Unit = {},
    isRefreshingModels: Boolean = false
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showModelSelector by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        // Task planning panel - show when there are active tasks
        AnimatedVisibility(
            visible = taskList.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            TaskPlanningPanel(tasks = taskList)
        }

        if (messages.isEmpty()) {
            WelcomeMessage(
                agentMode = agentMode,
                onToggleAgentMode = onToggleAgentMode,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(
                    items = messages,
                    key = { it.id }
                ) { message ->
                    ChatBubble(
                        message = message,
                        isStreaming = isLoading && message == messages.lastOrNull() && !message.isUser,
                        modifier = Modifier.animateItem()
                    )
                }

                if (isLoading && messages.lastOrNull()?.isUser == true) {
                    item {
                        TypingIndicator()
                    }
                }
            }
        }

        ChatInput(
            value = inputText,
            onValueChange = onInputChange,
            onSend = onSendMessage,
            onStop = onStopGeneration,
            isEnabled = !isLoading,
            isProcessing = isLoading,
            agentMode = agentMode,
            currentModel = currentModel,
            onModelClick = { showModelSelector = true },
            onToggleAgentMode = onToggleAgentMode,
            fileTree = fileTree,
            messages = messages,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
    }

    // Model selector bottom sheet
    if (showModelSelector) {
        ModelSelectorSheet(
            models = availableModels,
            selectedModel = currentModel,
            onModelSelected = { model ->
                onModelChange(model)
                scope.launch { sheetState.hide() }
                showModelSelector = false
            },
            onDismiss = { showModelSelector = false },
            onManageModels = {
                showModelSelector = false
                onNavigateToModels()
            },
            onRefresh = onRefreshModels,
            isRefreshing = isRefreshingModels,
            sheetState = sheetState
        )
    }
}

@Composable
private fun WelcomeMessage(
    agentMode: Boolean,
    onToggleAgentMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.SmartToy,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = context.getString(R.string.chat_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = context.getString(R.string.chat_welcome_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Agent mode toggle
        AgentModeToggle(
            agentMode = agentMode,
            onToggle = onToggleAgentMode
        )
    }
}

@Composable
private fun AgentModeToggle(
    agentMode: Boolean,
    onToggle: () -> Unit
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(24.dp)
            )
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Chat mode button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(
                    if (!agentMode) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
                )
                .clickable { if (agentMode) onToggle() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (!agentMode) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Chat",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (!agentMode) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Agent mode button
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(
                    if (agentMode) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow
                )
                .clickable { if (!agentMode) onToggle() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (agentMode) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Agent",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (agentMode) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * User message bubble with reduced font size and expandable text for long messages.
 * Font size reduced by 2sp from default bodyMedium (14sp -> 12sp)
 * Long messages (>300 chars) are collapsed with "Show more" option
 */
@Composable
private fun UserMessageBubble(
    content: String,
    timestamp: String,
    bubbleColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    // Threshold for collapsing long messages (in characters)
    val collapseThreshold = 300
    val isLongMessage = content.length > collapseThreshold
    var isExpanded by remember { mutableStateOf(false) }

    // Display text - truncated if long and not expanded
    val displayText = if (isLongMessage && !isExpanded) {
        content.take(collapseThreshold) + "..."
    } else {
        content
    }

    Box(
        modifier = modifier
            .widthIn(max = 320.dp)
            .clip(
                RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 4.dp
                )
            )
            .background(bubbleColor)
            .then(
                if (isLongMessage) {
                    Modifier.clickable { isExpanded = !isExpanded }
                } else {
                    Modifier
                }
            )
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = displayText,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,  // Reduced from 14sp (bodyMedium) to 12sp
                    lineHeight = 18.sp
                ),
                color = textColor,
                modifier = Modifier.animateContentSize()
            )

            // Show expand/collapse indicator for long messages
            if (isLongMessage) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isExpanded) "Show less" else "Show more",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = textColor.copy(alpha = 0.8f),
                    modifier = Modifier.align(Alignment.End)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp
                ),
                color = textColor.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}

@Composable
private fun ChatBubble(
    message: ChatMessage,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
    onApplyChange: ((String) -> Unit)? = null
) {
    val extendedColors = CodeXTheme.extendedColors

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (message.isUser) {
            // User message - compact bubble style
            if (message.content.isNotEmpty()) {
                UserMessageBubble(
                    content = message.content,
                    timestamp = message.formattedTime,
                    bubbleColor = extendedColors.chatUserBubble,
                    textColor = extendedColors.chatUserText
                )
            }
        } else {
            // AI message - professional IDE-style with structured content
            if (message.content.isNotEmpty()) {
                AiMessageContent(
                    content = message.content,
                    timestamp = message.formattedTime,
                    isStreaming = isStreaming,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Code changes as diff views
        message.codeChanges?.forEach { codeChange ->
            DiffView(
                codeChange = codeChange.toUiCodeChange(),
                onApply = onApplyChange?.let { { it(codeChange.filePath) } },
                modifier = Modifier.widthIn(max = 340.dp)
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CodeXTheme.extendedColors.chatAssistantBubble)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(index * 200)
                ),
                label = "dot_alpha_$index"
            )

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .graphicsLayer { this.alpha = alpha }
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun ContextStatsTooltip(
    usedTokens: Int,
    contextLimit: Int,
    modifier: Modifier = Modifier
) {
    val percentage = (usedTokens.toFloat() / contextLimit.coerceAtLeast(1)) * 100
    val remaining = (contextLimit - usedTokens).coerceAtLeast(0)

    Column(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${"%,d".format(usedTokens)} Tokens",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${percentage.toInt()}% Usage",
                style = MaterialTheme.typography.labelSmall,
                color = if (percentage > 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = (percentage / 100f).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(
                        if (percentage > 90) MaterialTheme.colorScheme.error 
                        else MaterialTheme.colorScheme.primary
                    )
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$0.00 Cost", // Placeholder for cost as we don't have pricing info yet
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Click to view context",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: (() -> Unit)?,
    isEnabled: Boolean,
    isProcessing: Boolean,
    agentMode: Boolean,
    currentModel: AiModel?,
    onModelClick: () -> Unit,
    onToggleAgentMode: () -> Unit,
    fileTree: List<FileTreeNode> = emptyList(),
    messages: List<ChatMessage> = emptyList(), // Added messages for context calc
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    
    // Estimate token usage (approx. 4 chars per token)
    val usedTokens = remember(messages, value) {
        (messages.sumOf { it.content.length } + value.length) / 4
    }
    
    val contextLimit = currentModel?.contextLength ?: 4096

    // @ mention state
    var textFieldValue by remember(value) {
        mutableStateOf(TextFieldValue(text = value, selection = TextRange(value.length)))
    }
    val mentionQuery = remember(textFieldValue) {
        extractMentionQuery(textFieldValue.text, textFieldValue.selection.start)
    }
    val showMentionPopup = mentionQuery != null
    
    var showContextStats by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // @ mention popup (above the input)
        FileMentionPopup(
            isVisible = showMentionPopup,
            query = mentionQuery ?: "",
            fileTree = fileTree,
            onSelectItem = { item ->
                val (newText, newCursor) = replaceMentionInText(
                    textFieldValue.text,
                    textFieldValue.selection.start,
                    item
                )
                textFieldValue = TextFieldValue(
                    text = newText,
                    selection = TextRange(newCursor)
                )
                onValueChange(newText)
            },
            onDismiss = { },
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Main Input Container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(16.dp)
                )
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(12.dp)
        ) {
            // Top: Input Field
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                if (textFieldValue.text.isEmpty()) {
                    Text(
                        text = "Ask anything... \"How do environment variables work here?\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
                
                androidx.compose.foundation.text.BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        textFieldValue = newValue
                        onValueChange(newValue.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(4.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    ),
                    maxLines = 10,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
                )
            }

            // Bottom: Toolbar (Tools, Model, Info, Send)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Left: Controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Build/Tools Trigger (Placeholder based on image logic if needed, else model selector)
                    // The image likely has a "Build" button or similar. Let's stick strictly to Model + Context for now
                    // keeping "Build" as a potential agent mode toggle or just label if user requested exact clone.
                    // User said: "redesign... exactly like tha attched image". Image suggests "Build" dropdown maybe?
                    // I will use the "Agent" mode toggle as "Build" if appropriate, or just keep Model Selector "MiniMax M2.1" style.
                    
                    // Mode Selector (Agent/Chat)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onToggleAgentMode)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = if (agentMode) "Agent" else "Chat",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Model Name (Clickable)
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onModelClick)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = currentModel?.name ?: "Select Model",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                         Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Right: Context & Actions
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Context Viewer Ring
                    Box(contentAlignment = Alignment.Center) {
                         Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .clickable { showContextStats = !showContextStats }
                        ) {
                             androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                                 val sweepAngle = (usedTokens.toFloat() / contextLimit.toFloat()) * 360f
                                 
                                 // Background track
                                 drawArc(
                                     color = androidx.compose.ui.graphics.Color.DarkGray.copy(alpha = 0.3f),
                                     startAngle = 0f,
                                     sweepAngle = 360f,
                                     useCenter = false,
                                     style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                                 )
                                 
                                 // Usage arc
                                 drawArc(
                                     color = androidx.compose.ui.graphics.Color.Gray, // Or a theme color
                                     startAngle = -90f,
                                     sweepAngle = sweepAngle,
                                     useCenter = false,
                                     style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx())
                                 )
                             }
                        }
                        
                        // Tooltip
                        androidx.compose.ui.window.Popup(
                            alignment = Alignment.TopEnd,
                            offset = androidx.compose.ui.unit.IntOffset(0, -150),
                            onDismissRequest = { showContextStats = false }
                        ) {
                             androidx.compose.animation.AnimatedVisibility(
                                 visible = showContextStats,
                                 enter = fadeIn() + scaleIn(),
                                 exit = fadeOut() + scaleOut()
                             ) {
                                 ContextStatsTooltip(
                                     usedTokens = usedTokens,
                                     contextLimit = contextLimit,
                                     modifier = Modifier.width(200.dp)
                                 )
                             }
                        }
                    }

                    // Image Input (Placeholder)
                    IconButton(
                        onClick = { /* TODO */ },
                        modifier = Modifier.size(32.dp),
                         colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                         Icon(
                            painter = androidx.compose.ui.res.painterResource(id = android.R.drawable.ic_menu_gallery),
                            contentDescription = "Image",
                            modifier = Modifier.size(20.dp)
                        )
                    }


                    // Stop/Send Button
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isProcessing) MaterialTheme.colorScheme.errorContainer 
                                else MaterialTheme.colorScheme.onSurface
                            )
                            .clickable(
                                enabled = (textFieldValue.text.isNotBlank() || isProcessing) && isEnabled,
                                onClick = {
                                    if (isProcessing) {
                                        onStop?.invoke()
                                    } else if (textFieldValue.text.isNotBlank()) {
                                        onSend()
                                        focusManager.clearFocus()
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isProcessing) {
                            Icon(
                                imageVector = Icons.Outlined.Stop,
                                contentDescription = "Stop",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send, // Or a specific arrow icon
                                contentDescription = "Send",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.surface
                            )
                        }
                    }
                }
            }
        }
    }
}


