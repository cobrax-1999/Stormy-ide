package com.codex.stormy.data.ai.tools

import com.codex.stormy.data.ai.ToolCallResponse
import com.codex.stormy.data.ai.memory.MemoryCategory
import com.codex.stormy.data.ai.memory.MemoryImportance
import com.codex.stormy.data.ai.memory.MemorySource
import com.codex.stormy.data.ai.memory.SemanticMemorySystem
import com.codex.stormy.data.git.GitManager
import com.codex.stormy.data.git.GitOperationResult
import com.codex.stormy.data.repository.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException

/**
 * Todo item for task tracking
 */
@Serializable
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    var status: TodoStatus = TodoStatus.PENDING
)

@Serializable
enum class TodoStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED
}

/**
 * Result of asking user a question
 */
sealed class UserInputResult {
    data class Answered(val answer: String) : UserInputResult()
    data object Pending : UserInputResult()
}

/**
 * Callback interface for tools that require user interaction
 */
interface ToolInteractionCallback {
    suspend fun askUser(question: String, options: List<String>?): String?
    suspend fun onTaskFinished(summary: String)
    suspend fun onFileChanged(path: String, changeType: FileChangeType, oldContent: String?, newContent: String?)
    suspend fun onTodoCreated(todo: TodoItem)
    suspend fun onTodoUpdated(todo: TodoItem)
}

enum class FileChangeType {
    CREATED,
    MODIFIED,
    DELETED,
    RENAMED,
    COPIED,
    MOVED
}

/**
 * Executes tool calls from the AI agent
 */
class ToolExecutor(
    private val projectRepository: ProjectRepository,
    private val memoryStorage: MemoryStorage,
    private val gitManager: GitManager? = null,
    private val semanticMemorySystem: SemanticMemorySystem? = null
) {
    companion object {
        private const val TAG = "ToolExecutor"
    }

    // JSON parser with lenient settings for more robust parsing
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // Coroutine scope for background learning operations with proper supervisor job
    // This prevents one failed learning job from cancelling others and allows cleanup
    private val learningJob = SupervisorJob()
    private val learningScope = CoroutineScope(Dispatchers.IO + learningJob)

    // Timeout for auto-learning operations (prevent hanging)
    private val LEARNING_TIMEOUT_MS = 30_000L

    // Current project path for Git operations
    private var currentProjectPath: File? = null

    // Advanced tool executor for code analysis, batch ops, etc.
    private val advancedToolExecutor = AdvancedToolExecutor(projectRepository)

    // Flag to enable/disable argument validation (can be toggled for performance)
    var enableArgumentValidation: Boolean = true

    /**
     * Set the current project path for Git operations
     */
    fun setProjectPath(path: File) {
        currentProjectPath = path
        advancedToolExecutor.setProjectPath(path)
    }

    // Session-based todo storage
    // Session-based todo storage removed in favor of persistent storage
    // private val sessionTodos = mutableMapOf<String, MutableList<TodoItem>>()

    // Interaction callback for user input and notifications
    var interactionCallback: ToolInteractionCallback? = null

    /**
     * Execute a tool call and return the result
     */
    suspend fun execute(
        projectId: String,
        toolCall: ToolCallResponse
    ): ToolResult {
        return try {
            // Parse arguments with robust error handling
            val arguments = parseToolArguments(toolCall.function.arguments)
                ?: return ToolResult(
                    success = false,
                    output = "",
                    error = "Failed to parse tool arguments. The AI sent malformed JSON."
                )

            // Validate arguments if validation is enabled
            if (enableArgumentValidation) {
                val validationResult = ToolArgumentValidator.validate(
                    toolName = toolCall.function.name,
                    args = arguments,
                    projectRoot = currentProjectPath
                )
                if (!validationResult.isValid) {
                    android.util.Log.w(TAG, "Tool argument validation failed for ${toolCall.function.name}: ${validationResult.errors}")
                    return ToolResult(
                        success = false,
                        output = "",
                        error = "Invalid arguments: ${validationResult.errors.joinToString("; ")}"
                    )
                }
            }

            when (toolCall.function.name) {
                // File operations
                "read_file" -> executeReadFile(projectId, arguments)
                "write_file" -> executeWriteFile(projectId, arguments)
                "list_files" -> executeListFiles(projectId, arguments)
                "delete_file" -> executeDeleteFile(projectId, arguments)
                "create_folder" -> executeCreateFolder(projectId, arguments)
                "rename_file" -> executeRenameFile(projectId, arguments)
                "copy_file" -> executeCopyFile(projectId, arguments)
                "move_file" -> executeMoveFile(projectId, arguments)

                // Memory operations
                "save_memory" -> executeSaveMemory(projectId, arguments)
                "recall_memory" -> executeRecallMemory(projectId, arguments)
                "list_memories" -> executeListMemories(projectId)
                "delete_memory" -> executeDeleteMemory(projectId, arguments)
                "update_memory" -> executeUpdateMemory(projectId, arguments)

                // Search operations
                "search_files" -> executeSearchFiles(projectId, arguments)
                "search_replace" -> executeSearchReplace(projectId, arguments)
                "patch_file" -> executePatchFile(projectId, arguments)

                // Enhanced file operations
                "insert_at_line" -> executeInsertAtLine(projectId, arguments)
                "get_file_info" -> executeGetFileInfo(projectId, arguments)
                "regex_replace" -> executeRegexReplace(projectId, arguments)
                "append_to_file" -> executeAppendToFile(projectId, arguments)
                "prepend_to_file" -> executePrependToFile(projectId, arguments)

                // Todo operations
                "create_todo" -> executeCreateTodo(projectId, arguments)
                "update_todo" -> executeUpdateTodo(projectId, arguments)
                "list_todos" -> executeListTodos(projectId)

                // Agent control
                "ask_user" -> executeAskUser(arguments)
                "finish_task" -> executeFinishTask(arguments)

                // Git operations
                "git_status" -> executeGitStatus()
                "git_stage" -> executeGitStage(arguments)
                "git_unstage" -> executeGitUnstage(arguments)
                "git_commit" -> executeGitCommit(arguments)
                "git_push" -> executeGitPush(arguments)
                "git_pull" -> executeGitPull(arguments)
                "git_branch" -> executeGitBranch(arguments)
                "git_checkout" -> executeGitCheckout(arguments)
                "git_log" -> executeGitLog(arguments)
                "git_diff" -> executeGitDiff(arguments)
                "git_discard" -> executeGitDiscard(arguments)

                else -> {
                    // Try advanced tools executor
                    advancedToolExecutor.execute(projectId, toolCall.function.name, arguments)
                        ?: ToolResult(
                            success = false,
                            output = "",
                            error = "Unknown tool: ${toolCall.function.name}"
                        )
                }
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            ToolResult(
                success = false,
                output = "",
                error = "JSON parsing error: ${e.message?.take(100)}"
            )
        } catch (e: IllegalArgumentException) {
            ToolResult(
                success = false,
                output = "",
                error = "Invalid argument: ${e.message?.take(100)}"
            )
        } catch (e: java.io.IOException) {
            ToolResult(
                success = false,
                output = "",
                error = "File I/O error: ${e.message?.take(100)}"
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                output = "",
                error = "Error executing tool: ${e.message?.take(100)}"
            )
        }
    }

    /**
     * Robustly parse tool arguments from the AI's JSON string.
     * Uses production-grade state-machine based parser that properly handles
     * control characters inside strings without corrupting valid JSON.
     */
    private fun parseToolArguments(arguments: String): JsonObject? {
        return JsonParserUtils.parseToJsonObject(arguments)
    }

    // ==================== File Operations ====================

    private suspend fun executeReadFile(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path")
            ?: return ToolResult(false, "", "Missing required argument: path")

        return projectRepository.readFile(projectId, path)
            .fold(
                onSuccess = { content ->
                    ToolResult(true, content)
                },
                onFailure = { error ->
                    ToolResult(false, "", "Failed to read file: ${error.message}")
                }
            )
    }

    private suspend fun executeWriteFile(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path")
            ?: return ToolResult(false, "", "Missing required argument: path")
        val content = args.getStringArg("content")
            ?: return ToolResult(false, "", "Missing required argument: content")

        // Check if file exists and get old content for diff
        val oldContent = projectRepository.readFile(projectId, path).getOrNull()
        val isNewFile = oldContent == null

        return if (isNewFile) {
            projectRepository.createFile(projectId, path, content)
                .fold(
                    onSuccess = {
                        interactionCallback?.onFileChanged(path, FileChangeType.CREATED, null, content)
                        // Auto-learn from generated code in background
                        triggerAutoLearning(projectId, path, content)
                        ToolResult(true, "File created successfully: $path")
                    },
                    onFailure = {
                        // File might exist but was not readable, try write directly
                        projectRepository.writeFile(projectId, path, content)
                            .fold(
                                onSuccess = {
                                    interactionCallback?.onFileChanged(path, FileChangeType.MODIFIED, oldContent, content)
                                    triggerAutoLearning(projectId, path, content)
                                    ToolResult(true, "File written successfully: $path")
                                },
                                onFailure = { ToolResult(false, "", "Failed to write file: ${it.message}") }
                            )
                    }
                )
        } else {
            projectRepository.writeFile(projectId, path, content)
                .fold(
                    onSuccess = {
                        interactionCallback?.onFileChanged(path, FileChangeType.MODIFIED, oldContent, content)
                        // Auto-learn from generated code in background
                        triggerAutoLearning(projectId, path, content)
                        ToolResult(true, "File updated successfully: $path")
                    },
                    onFailure = { ToolResult(false, "", "Failed to write file: ${it.message}") }
                )
        }
    }

    /**
     * Trigger auto-learning from generated code in background.
     * Uses timeout to prevent hanging and proper error handling.
     */
    private fun triggerAutoLearning(projectId: String, path: String, content: String) {
        semanticMemorySystem?.let { memorySystem ->
            learningScope.launch {
                try {
                    withTimeout(LEARNING_TIMEOUT_MS) {
                        memorySystem.learnFromGeneratedCode(projectId, path, content)
                    }
                } catch (e: CancellationException) {
                    // Timeout or cancellation - log but don't propagate
                    android.util.Log.w("ToolExecutor", "Auto-learning timed out or cancelled for: $path")
                } catch (e: Exception) {
                    android.util.Log.e("ToolExecutor", "Auto-learning failed for: $path", e)
                }
            }
        }
    }

    /**
     * Cleanup resources - call this when the executor is no longer needed
     */
    fun destroy() {
        try {
            learningScope.cancel("ToolExecutor destroyed")
        } catch (e: Exception) {
            android.util.Log.w("ToolExecutor", "Error cancelling learning scope", e)
        }
    }

    private suspend fun executeListFiles(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path") ?: ""

        return try {
            val fileTree = projectRepository.getFileTree(projectId)
            val output = buildFileTreeString(fileTree, path)
            ToolResult(true, output.ifEmpty { "Directory is empty" })
        } catch (e: Exception) {
            ToolResult(false, "", "Failed to list files: ${e.message}")
        }
    }

    private fun buildFileTreeString(
        nodes: List<com.codex.stormy.domain.model.FileTreeNode>,
        basePath: String = "",
        indent: String = ""
    ): String {
        val sb = StringBuilder()

        for (node in nodes) {
            when (node) {
                is com.codex.stormy.domain.model.FileTreeNode.FileNode -> {
                    sb.appendLine("$indent📄 ${node.name}")
                }
                is com.codex.stormy.domain.model.FileTreeNode.FolderNode -> {
                    sb.appendLine("$indent📁 ${node.name}/")
                    sb.append(buildFileTreeString(node.children, node.path, "$indent  "))
                }
            }
        }

        return sb.toString()
    }

    private suspend fun executeDeleteFile(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path")
            ?: return ToolResult(false, "", "Missing required argument: path")

        // Get old content for reference
        val oldContent = projectRepository.readFile(projectId, path).getOrNull()

        return projectRepository.deleteFile(projectId, path)
            .fold(
                onSuccess = {
                    interactionCallback?.onFileChanged(path, FileChangeType.DELETED, oldContent, null)
                    ToolResult(true, "File deleted successfully: $path")
                },
                onFailure = { ToolResult(false, "", "Failed to delete file: ${it.message}") }
            )
    }

    private suspend fun executeCreateFolder(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path")
            ?: return ToolResult(false, "", "Missing required argument: path")

        return projectRepository.createFolder(projectId, path)
            .fold(
                onSuccess = { ToolResult(true, "Folder created successfully: $path") },
                onFailure = { ToolResult(false, "", "Failed to create folder: ${it.message}") }
            )
    }

    private suspend fun executeRenameFile(projectId: String, args: JsonObject): ToolResult {
        val oldPath = args.getPathArg("old_path")
            ?: return ToolResult(false, "", "Missing required argument: old_path")
        val newPath = args.getPathArg("new_path")
            ?: return ToolResult(false, "", "Missing required argument: new_path")

        return projectRepository.renameFile(projectId, oldPath, newPath)
            .fold(
                onSuccess = {
                    interactionCallback?.onFileChanged(oldPath, FileChangeType.RENAMED, null, newPath)
                    ToolResult(true, "File renamed from '$oldPath' to '$newPath'")
                },
                onFailure = { ToolResult(false, "", "Failed to rename file: ${it.message}") }
            )
    }

    private suspend fun executeCopyFile(projectId: String, args: JsonObject): ToolResult {
        val sourcePath = args.getPathArg("source_path")
            ?: return ToolResult(false, "", "Missing required argument: source_path")
        val destinationPath = args.getPathArg("destination_path")
            ?: return ToolResult(false, "", "Missing required argument: destination_path")

        return projectRepository.copyFile(projectId, sourcePath, destinationPath)
            .fold(
                onSuccess = {
                    interactionCallback?.onFileChanged(destinationPath, FileChangeType.COPIED, sourcePath, null)
                    ToolResult(true, "File copied from '$sourcePath' to '$destinationPath'")
                },
                onFailure = { ToolResult(false, "", "Failed to copy file: ${it.message}") }
            )
    }

    private suspend fun executeMoveFile(projectId: String, args: JsonObject): ToolResult {
        val sourcePath = args.getPathArg("source_path")
            ?: return ToolResult(false, "", "Missing required argument: source_path")
        val destinationPath = args.getPathArg("destination_path")
            ?: return ToolResult(false, "", "Missing required argument: destination_path")

        return projectRepository.moveFile(projectId, sourcePath, destinationPath)
            .fold(
                onSuccess = {
                    interactionCallback?.onFileChanged(sourcePath, FileChangeType.MOVED, null, destinationPath)
                    ToolResult(true, "File moved from '$sourcePath' to '$destinationPath'")
                },
                onFailure = { ToolResult(false, "", "Failed to move file: ${it.message}") }
            )
    }

    // ==================== Memory Operations ====================

    private suspend fun executeSaveMemory(projectId: String, args: JsonObject): ToolResult {
        val key = args.getStringArg("key")
            ?: return ToolResult(false, "", "Missing required argument: key")
        val value = args.getStringArg("value")
            ?: return ToolResult(false, "", "Missing required argument: value")
        val categoryStr = args.getStringArg("category")
        val importanceStr = args.getStringArg("importance")
        val tagsStr = args.getStringArg("tags")
        val relatedFilesStr = args.getStringArg("related_files")

        return try {
            // Use semantic memory system if available
            if (semanticMemorySystem != null) {
                val category = parseMemoryCategory(categoryStr)
                val importance = parseMemoryImportance(importanceStr)
                val tags = tagsStr?.split(",")?.map { it.trim() } ?: emptyList()
                val relatedFiles = relatedFilesStr?.split(",")?.map { it.trim() } ?: emptyList()

                semanticMemorySystem.saveMemory(
                    projectId = projectId,
                    category = category,
                    key = key,
                    value = value,
                    importance = importance,
                    source = MemorySource.AGENT,
                    tags = tags,
                    relatedFiles = relatedFiles
                )
                ToolResult(true, "Memory saved: $key (category: ${category.displayName})")
            } else {
                // Fallback to basic memory storage
                memoryStorage.save(projectId, key, value)
                ToolResult(true, "Memory saved: $key")
            }
        } catch (e: Exception) {
            ToolResult(false, "", "Failed to save memory: ${e.message}")
        }
    }

    private fun parseMemoryCategory(categoryStr: String?): MemoryCategory {
        if (categoryStr == null) return MemoryCategory.GENERAL_NOTES
        return when (categoryStr.uppercase().replace(" ", "_")) {
            "PROJECT_STRUCTURE", "STRUCTURE" -> MemoryCategory.PROJECT_STRUCTURE
            "CODING_PATTERNS", "PATTERNS", "CODE" -> MemoryCategory.CODING_PATTERNS
            "FRAMEWORK_CONFIG", "FRAMEWORK", "CONFIG" -> MemoryCategory.FRAMEWORK_CONFIG
            "USER_PREFERENCES", "PREFERENCES", "USER" -> MemoryCategory.USER_PREFERENCES
            "COMPONENT_KNOWLEDGE", "COMPONENTS", "COMPONENT" -> MemoryCategory.COMPONENT_KNOWLEDGE
            "STYLING_PATTERNS", "STYLING", "STYLES" -> MemoryCategory.STYLING_PATTERNS
            "ERROR_SOLUTIONS", "ERRORS", "SOLUTIONS" -> MemoryCategory.ERROR_SOLUTIONS
            "TASK_HISTORY", "TASKS", "HISTORY" -> MemoryCategory.TASK_HISTORY
            else -> MemoryCategory.GENERAL_NOTES
        }
    }

    private fun parseMemoryImportance(importanceStr: String?): MemoryImportance {
        if (importanceStr == null) return MemoryImportance.MEDIUM
        return when (importanceStr.uppercase()) {
            "CRITICAL", "HIGHEST" -> MemoryImportance.CRITICAL
            "HIGH", "IMPORTANT" -> MemoryImportance.HIGH
            "LOW", "MINOR" -> MemoryImportance.LOW
            else -> MemoryImportance.MEDIUM
        }
    }

    private suspend fun executeRecallMemory(projectId: String, args: JsonObject): ToolResult {
        val key = args.getStringArg("key")
            ?: return ToolResult(false, "", "Missing required argument: key")
        val categoryStr = args.getStringArg("category")

        return try {
            // Try semantic memory first
            if (semanticMemorySystem != null && categoryStr != null) {
                val category = parseMemoryCategory(categoryStr)
                val memory = semanticMemorySystem.recallMemory(projectId, category, key)
                if (memory != null) {
                    return ToolResult(true, "${memory.value} (confidence: ${(memory.confidence * 100).toInt()}%)")
                }
            }

            // Fallback to basic memory storage
            val value = memoryStorage.recall(projectId, key)
            if (value != null) {
                ToolResult(true, value)
            } else {
                ToolResult(true, "No memory found for key: $key")
            }
        } catch (e: Exception) {
            ToolResult(false, "", "Failed to recall memory: ${e.message}")
        }
    }

    private suspend fun executeListMemories(projectId: String): ToolResult {
        return try {
            // Use semantic memory system if available for richer output
            if (semanticMemorySystem != null) {
                val memories = semanticMemorySystem.getAllMemories(projectId)
                if (memories.isEmpty()) {
                    return ToolResult(true, "No memories saved for this project")
                }

                val output = buildString {
                    val grouped = memories.groupBy { it.category }
                    for ((category, categoryMemories) in grouped.entries.sortedByDescending { it.key.priority }) {
                        appendLine("\n${category.displayName}:")
                        for (memory in categoryMemories.take(10)) {
                            val confidenceIcon = when {
                                memory.confidence >= 0.8f -> "★"
                                memory.confidence >= 0.5f -> "☆"
                                else -> "○"
                            }
                            appendLine("  $confidenceIcon ${memory.key}: ${memory.value.take(100)}${if (memory.value.length > 100) "..." else ""}")
                        }
                    }
                }
                ToolResult(true, output)
            } else {
                // Fallback to basic memory storage
                val memories = memoryStorage.list(projectId)
                if (memories.isEmpty()) {
                    ToolResult(true, "No memories saved for this project")
                } else {
                    val output = memories.entries.joinToString("\n") { (key, value) ->
                        "• $key: $value"
                    }
                    ToolResult(true, output)
                }
            }
        } catch (e: Exception) {
            ToolResult(false, "", "Failed to list memories: ${e.message}")
        }
    }

    private suspend fun executeDeleteMemory(projectId: String, args: JsonObject): ToolResult {
        val key = args.getStringArg("key")
            ?: return ToolResult(false, "", "Missing required argument: key")
        val categoryStr = args.getStringArg("category")

        return try {
            // Try semantic memory deletion first
            if (semanticMemorySystem != null && categoryStr != null) {
                val category = parseMemoryCategory(categoryStr)
                val deleted = semanticMemorySystem.deleteMemory(projectId, category, key)
                if (deleted) {
                    return ToolResult(true, "Memory deleted: $key (from ${category.displayName})")
                }
            }

            // Fallback to basic memory storage
            val deleted = memoryStorage.delete(projectId, key)
            if (deleted) {
                ToolResult(true, "Memory deleted: $key")
            } else {
                ToolResult(true, "No memory found for key: $key")
            }
        } catch (e: Exception) {
            ToolResult(false, "", "Failed to delete memory: ${e.message}")
        }
    }

    private suspend fun executeUpdateMemory(projectId: String, args: JsonObject): ToolResult {
        val key = args.getStringArg("key")
            ?: return ToolResult(false, "", "Missing required argument: key")
        val value = args.getStringArg("value")
            ?: return ToolResult(false, "", "Missing required argument: value")
        val categoryStr = args.getStringArg("category")
        val importanceStr = args.getStringArg("importance")

        return try {
            // Use semantic memory system if available
            if (semanticMemorySystem != null) {
                val category = parseMemoryCategory(categoryStr)
                val importance = parseMemoryImportance(importanceStr)

                semanticMemorySystem.saveMemory(
                    projectId = projectId,
                    category = category,
                    key = key,
                    value = value,
                    importance = importance,
                    source = MemorySource.AGENT
                )
                ToolResult(true, "Memory updated: $key (category: ${category.displayName})")
            } else {
                // Fallback to basic memory storage
                val existing = memoryStorage.recall(projectId, key)
                memoryStorage.save(projectId, key, value)
                if (existing != null) {
                    ToolResult(true, "Memory updated: $key")
                } else {
                    ToolResult(true, "Memory created: $key")
                }
            }
        } catch (e: Exception) {
            ToolResult(false, "", "Failed to update memory: ${e.message}")
        }
    }

    // ==================== Search Operations ====================

    private suspend fun executeSearchFiles(projectId: String, args: JsonObject): ToolResult {
        val query = args.getStringArg("query")
            ?: return ToolResult(false, "", "Missing required argument: query")
        val filePattern = args.getStringArg("file_pattern")

        return try {
            val results = searchInProject(projectId, query, filePattern)
            if (results.isEmpty()) {
                ToolResult(true, "No matches found for: $query")
            } else {
                ToolResult(true, results)
            }
        } catch (e: Exception) {
            ToolResult(false, "", "Search failed: ${e.message}")
        }
    }

    private suspend fun searchInProject(
        projectId: String,
        query: String,
        filePattern: String?
    ): String {
        val fileTree = projectRepository.getFileTree(projectId)
        val results = StringBuilder()

        searchInNodes(projectId, fileTree, query, filePattern, results)

        return results.toString()
    }

    private suspend fun searchInNodes(
        projectId: String,
        nodes: List<com.codex.stormy.domain.model.FileTreeNode>,
        query: String,
        filePattern: String?,
        results: StringBuilder
    ) {
        for (node in nodes) {
            when (node) {
                is com.codex.stormy.domain.model.FileTreeNode.FileNode -> {
                    if (filePattern != null && !matchesPattern(node.name, filePattern)) {
                        continue
                    }

                    projectRepository.readFile(projectId, node.path)
                        .onSuccess { content ->
                            val lines = content.lines()
                            lines.forEachIndexed { index, line ->
                                if (line.contains(query, ignoreCase = true)) {
                                    results.appendLine("${node.path}:${index + 1}: ${line.trim()}")
                                }
                            }
                        }
                }
                is com.codex.stormy.domain.model.FileTreeNode.FolderNode -> {
                    searchInNodes(projectId, node.children, query, filePattern, results)
                }
            }
        }
    }

    private suspend fun executeSearchReplace(projectId: String, args: JsonObject): ToolResult {
        val search = args.getStringArg("search")
            ?: return ToolResult(false, "", "Missing required argument: search")
        val replace = args.getStringArg("replace")
            ?: return ToolResult(false, "", "Missing required argument: replace")
        val filePattern = args.getStringArg("file_pattern")
        val dryRun = args.getBooleanArg("dry_run", false)

        return projectRepository.searchAndReplace(projectId, search, replace, filePattern, dryRun)
            .fold(
                onSuccess = { result ->
                    if (result.filesModified == 0) {
                        ToolResult(true, "No matches found for: $search")
                    } else {
                        val action = if (dryRun) "Would replace" else "Replaced"
                        val output = buildString {
                            appendLine("$action ${"occurrence".pluralize(result.totalReplacements)} in ${"file".pluralize(result.filesModified)}:")
                            result.files.forEach { file ->
                                appendLine("  • ${file.path}: ${"replacement".pluralize(file.replacements)}")
                            }
                        }
                        ToolResult(true, output)
                    }
                },
                onFailure = { ToolResult(false, "", "Search and replace failed: ${it.message}") }
            )
    }

    private suspend fun executePatchFile(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path")
            ?: return ToolResult(false, "", "Missing required argument: path")
        val oldContent = args.getStringArg("old_content")
            ?: return ToolResult(false, "", "Missing required argument: old_content")
        val newContent = args.getStringArg("new_content")
            ?: return ToolResult(false, "", "Missing required argument: new_content")

        // Get file content before patch for diff
        val beforeContent = projectRepository.readFile(projectId, path).getOrNull()

        return projectRepository.patchFile(projectId, path, oldContent, newContent)
            .fold(
                onSuccess = {
                    val afterContent = projectRepository.readFile(projectId, path).getOrNull()
                    interactionCallback?.onFileChanged(path, FileChangeType.MODIFIED, beforeContent, afterContent)
                    ToolResult(true, "File patched successfully: $path")
                },
                onFailure = { ToolResult(false, "", "Failed to patch file: ${it.message}") }
            )
    }

    // ==================== Enhanced File Operations ====================

    private suspend fun executeInsertAtLine(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path")
            ?: return ToolResult(false, "", "Missing required argument: path")
        val lineNumber = args.getStringArg("line_number")?.toIntOrNull()
            ?: return ToolResult(false, "", "Missing or invalid argument: line_number")
        val content = args.getStringArg("content")
            ?: return ToolResult(false, "", "Missing required argument: content")

        return projectRepository.readFile(projectId, path)
            .fold(
                onSuccess = { existingContent ->
                    val lines = existingContent.lines().toMutableList()
                    val insertIndex = when {
                        lineNumber <= 0 -> 0
                        lineNumber > lines.size -> lines.size
                        else -> lineNumber - 1
                    }

                    // Insert content lines at the specified position
                    val contentLines = content.lines()
                    lines.addAll(insertIndex, contentLines)

                    val newContent = lines.joinToString("\n")
                    projectRepository.writeFile(projectId, path, newContent)
                        .fold(
                            onSuccess = {
                                interactionCallback?.onFileChanged(path, FileChangeType.MODIFIED, existingContent, newContent)
                                ToolResult(true, "Inserted ${contentLines.size} line(s) at line $lineNumber in $path")
                            },
                            onFailure = { ToolResult(false, "", "Failed to write file: ${it.message}") }
                        )
                },
                onFailure = {
                    // File doesn't exist, create it with content
                    projectRepository.createFile(projectId, path, content)
                        .fold(
                            onSuccess = {
                                interactionCallback?.onFileChanged(path, FileChangeType.CREATED, null, content)
                                ToolResult(true, "Created file $path with content")
                            },
                            onFailure = { ToolResult(false, "", "Failed to create file: ${it.message}") }
                        )
                }
            )
    }

    private suspend fun executeGetFileInfo(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path")
            ?: return ToolResult(false, "", "Missing required argument: path")

        val project = projectRepository.getProjectById(projectId)
            ?: return ToolResult(false, "", "Project not found")

        val file = File(project.rootPath, path)
        if (!file.exists()) {
            return ToolResult(false, "", "File not found: $path")
        }

        return try {
            val info = buildString {
                appendLine("File: $path")
                appendLine("Size: ${formatFileSize(file.length())}")
                appendLine("Extension: ${file.extension.ifEmpty { "(none)" }}")

                if (file.isFile) {
                    val content = file.readText()
                    val lineCount = content.lines().size
                    appendLine("Lines: $lineCount")
                    appendLine("Characters: ${content.length}")
                }

                val lastModified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(java.util.Date(file.lastModified()))
                appendLine("Last modified: $lastModified")
                appendLine("Type: ${if (file.isDirectory) "Directory" else "File"}")
                appendLine("Readable: ${file.canRead()}")
                appendLine("Writable: ${file.canWrite()}")
            }
            ToolResult(true, info)
        } catch (e: Exception) {
            ToolResult(false, "", "Failed to get file info: ${e.message}")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private suspend fun executeRegexReplace(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path")
            ?: return ToolResult(false, "", "Missing required argument: path")
        val pattern = args.getStringArg("pattern")
            ?: return ToolResult(false, "", "Missing required argument: pattern")
        val replacement = args.getStringArg("replacement")
            ?: return ToolResult(false, "", "Missing required argument: replacement")
        val flags = args.getStringArg("flags") ?: "g"

        return projectRepository.readFile(projectId, path)
            .fold(
                onSuccess = { existingContent ->
                    try {
                        val regexOptions = mutableSetOf<RegexOption>()
                        if (flags.contains("i")) regexOptions.add(RegexOption.IGNORE_CASE)
                        if (flags.contains("m")) regexOptions.add(RegexOption.MULTILINE)

                        val regex = Regex(pattern, regexOptions)
                        val matchCount = regex.findAll(existingContent).count()

                        if (matchCount == 0) {
                            return@fold ToolResult(true, "No matches found for pattern: $pattern")
                        }

                        val newContent = if (flags.contains("g") || !flags.contains("1")) {
                            regex.replace(existingContent, replacement)
                        } else {
                            regex.replaceFirst(existingContent, replacement)
                        }

                        projectRepository.writeFile(projectId, path, newContent)
                            .fold(
                                onSuccess = {
                                    interactionCallback?.onFileChanged(path, FileChangeType.MODIFIED, existingContent, newContent)
                                    ToolResult(true, "Replaced $matchCount match(es) in $path")
                                },
                                onFailure = { ToolResult(false, "", "Failed to write file: ${it.message}") }
                            )
                    } catch (e: Exception) {
                        ToolResult(false, "", "Invalid regex pattern: ${e.message}")
                    }
                },
                onFailure = { ToolResult(false, "", "Failed to read file: ${it.message}") }
            )
    }

    private suspend fun executeAppendToFile(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path")
            ?: return ToolResult(false, "", "Missing required argument: path")
        val content = args.getStringArg("content")
            ?: return ToolResult(false, "", "Missing required argument: content")

        val existingContent = projectRepository.readFile(projectId, path).getOrNull()
        val newContent = if (existingContent != null) {
            existingContent + content
        } else {
            content
        }

        return projectRepository.writeFile(projectId, path, newContent)
            .fold(
                onSuccess = {
                    val changeType = if (existingContent != null) FileChangeType.MODIFIED else FileChangeType.CREATED
                    interactionCallback?.onFileChanged(path, changeType, existingContent, newContent)
                    ToolResult(true, "Content appended to $path")
                },
                onFailure = { ToolResult(false, "", "Failed to append to file: ${it.message}") }
            )
    }

    private suspend fun executePrependToFile(projectId: String, args: JsonObject): ToolResult {
        val path = args.getPathArg("path")
            ?: return ToolResult(false, "", "Missing required argument: path")
        val content = args.getStringArg("content")
            ?: return ToolResult(false, "", "Missing required argument: content")

        val existingContent = projectRepository.readFile(projectId, path).getOrNull()
        val newContent = if (existingContent != null) {
            content + existingContent
        } else {
            content
        }

        return projectRepository.writeFile(projectId, path, newContent)
            .fold(
                onSuccess = {
                    val changeType = if (existingContent != null) FileChangeType.MODIFIED else FileChangeType.CREATED
                    interactionCallback?.onFileChanged(path, changeType, existingContent, newContent)
                    ToolResult(true, "Content prepended to $path")
                },
                onFailure = { ToolResult(false, "", "Failed to prepend to file: ${it.message}") }
            )
    }

    // ==================== Todo Operations ====================

    private suspend fun executeCreateTodo(projectId: String, args: JsonObject): ToolResult {
        val title = args.getStringArg("title")
            ?: return ToolResult(false, "", "Missing required argument: title")
        val description = args.getStringArg("description") ?: ""

        val todo = TodoItem(
            title = title,
            description = description
        )

        val todos = loadTodos(projectId).toMutableList()
        todos.add(todo)
        saveTodos(projectId, todos)

        // Notify callback about new todo
        interactionCallback?.onTodoCreated(todo)

        return ToolResult(true, "Todo created: [${todo.id.take(8)}] $title")
    }

    private suspend fun executeUpdateTodo(projectId: String, args: JsonObject): ToolResult {
        val todoId = args.getStringArg("todo_id")
            ?: return ToolResult(false, "", "Missing required argument: todo_id")
        val statusStr = args.getStringArg("status")
            ?: return ToolResult(false, "", "Missing required argument: status")

        val status = when (statusStr.lowercase()) {
            "pending" -> TodoStatus.PENDING
            "in_progress" -> TodoStatus.IN_PROGRESS
            "completed" -> TodoStatus.COMPLETED
            else -> return ToolResult(false, "", "Invalid status. Use: pending, in_progress, or completed")
        }

        val todos = loadTodos(projectId).toMutableList()
        val todo = todos.find { it.id.startsWith(todoId) || it.id == todoId }
            ?: return ToolResult(false, "", "Todo not found: $todoId")

        todo.status = status
        saveTodos(projectId, todos)

        // Notify callback about todo update
        interactionCallback?.onTodoUpdated(todo)

        return ToolResult(true, "Todo updated: [${todo.id.take(8)}] ${todo.title} -> $status")
    }

    private suspend fun executeListTodos(projectId: String): ToolResult {
        val todos = loadTodos(projectId)
        if (todos.isEmpty()) {
            return ToolResult(true, "No todos found. Use create_todo to add one.")
        }

        val output = buildString {
            appendLine("Current todos:")
            todos.forEach { todo ->
                val statusIcon = when (todo.status) {
                    TodoStatus.PENDING -> "⬜"
                    TodoStatus.IN_PROGRESS -> "🔄"
                    TodoStatus.COMPLETED -> "✅"
                }
                appendLine("$statusIcon [${todo.id.take(8)}] ${todo.title}")
                if (todo.description.isNotEmpty()) {
                    appendLine("   ${todo.description}")
                }
            }
        }

        return ToolResult(true, output)
    }

    private suspend fun loadTodos(projectId: String): List<TodoItem> {
        val path = ".codex/todos.json"
        return projectRepository.readFile(projectId, path).fold(
            onSuccess = { content ->
                try {
                    json.decodeFromString<List<TodoItem>>(content)
                } catch (e: Exception) {
                    emptyList()
                }
            },
            onFailure = { emptyList() }
        )
    }

    private suspend fun saveTodos(projectId: String, todos: List<TodoItem>) {
        val path = ".codex/todos.json"
        
        // Ensure directory exists
        projectRepository.createFolder(projectId, ".codex")
        
        val content = json.encodeToString(todos)
        projectRepository.writeFile(projectId, path, content)
    }

    // ==================== Agent Control ====================

    private suspend fun executeAskUser(args: JsonObject): ToolResult {
        val question = args.getStringArg("question")
            ?: return ToolResult(false, "", "Missing required argument: question")
        val optionsStr = args.getStringArg("options")
        val options = optionsStr?.split(",")?.map { it.trim() }

        val callback = interactionCallback
            ?: return ToolResult(true, "Question for user: $question" +
                (options?.let { "\nOptions: ${it.joinToString(", ")}" } ?: ""))

        val answer = callback.askUser(question, options)
        return if (answer != null) {
            ToolResult(true, "User response: $answer")
        } else {
            ToolResult(true, "Waiting for user response...")
        }
    }

    private suspend fun executeFinishTask(args: JsonObject): ToolResult {
        val summary = args.getStringArg("summary")
            ?: return ToolResult(false, "", "Missing required argument: summary")

        interactionCallback?.onTaskFinished(summary)
        return ToolResult(true, "Task completed: $summary")
    }

    // ==================== Git Operations ====================

    private fun checkGitAvailable(): ToolResult? {
        if (gitManager == null) {
            return ToolResult(false, "", "Git is not available")
        }
        if (currentProjectPath == null) {
            return ToolResult(false, "", "No project path set for Git operations")
        }
        return null
    }

    private suspend fun executeGitStatus(): ToolResult {
        checkGitAvailable()?.let { return it }

        return when (val result = gitManager!!.openRepository(currentProjectPath!!)) {
            is GitOperationResult.Success -> {
                val status = gitManager.status.value
                if (status == null || !status.isGitRepo) {
                    return ToolResult(true, "Not a Git repository")
                }

                val output = buildString {
                    appendLine("Branch: ${status.currentBranch.ifEmpty { "detached HEAD" }}")
                    if (status.hasRemote) {
                        appendLine("Remote: ${status.remoteUrl ?: "origin"}")
                        if (status.aheadCount > 0) appendLine("  ↑ ${"commit".pluralize(status.aheadCount)} ahead")
                        if (status.behindCount > 0) appendLine("  ↓ ${"commit".pluralize(status.behindCount)} behind")
                    }

                    val changedFiles = gitManager.changedFiles.value
                    val stagedFiles = changedFiles.filter { it.isStaged }
                    val unstagedFiles = changedFiles.filter { !it.isStaged }

                    if (stagedFiles.isNotEmpty()) {
                        appendLine("\nStaged changes (${stagedFiles.size}):")
                        stagedFiles.forEach { file ->
                            appendLine("  ${file.status.name[0]} ${file.path}")
                        }
                    }

                    if (unstagedFiles.isNotEmpty()) {
                        appendLine("\nUnstaged changes (${unstagedFiles.size}):")
                        unstagedFiles.forEach { file ->
                            appendLine("  ${file.status.name[0]} ${file.path}")
                        }
                    }

                    if (stagedFiles.isEmpty() && unstagedFiles.isEmpty()) {
                        appendLine("\nWorking tree clean")
                    }
                }
                ToolResult(true, output)
            }
            is GitOperationResult.Error -> {
                ToolResult(false, "", "Git error: ${result.message}")
            }
            is GitOperationResult.InProgress -> {
                ToolResult(true, "Git operation in progress...")
            }
        }
    }

    private suspend fun executeGitStage(args: JsonObject): ToolResult {
        checkGitAvailable()?.let { return it }

        val pathsArg = args.getStringArg("paths")
            ?: return ToolResult(false, "", "Missing required argument: paths")

        val result = if (pathsArg.lowercase() == "all") {
            gitManager!!.stageAll()
        } else {
            val paths = pathsArg.split(",").map { it.trim() }
            gitManager!!.stageFiles(paths)
        }

        return when (result) {
            is GitOperationResult.Success -> ToolResult(true, "Files staged successfully")
            is GitOperationResult.Error -> ToolResult(false, "", "Failed to stage: ${result.message}")
            is GitOperationResult.InProgress -> ToolResult(true, "Staging in progress...")
        }
    }

    private suspend fun executeGitUnstage(args: JsonObject): ToolResult {
        checkGitAvailable()?.let { return it }

        val pathsArg = args.getStringArg("paths")
            ?: return ToolResult(false, "", "Missing required argument: paths")

        val paths = pathsArg.split(",").map { it.trim() }
        return when (val result = gitManager!!.unstageFiles(paths)) {
            is GitOperationResult.Success -> ToolResult(true, "Files unstaged successfully")
            is GitOperationResult.Error -> ToolResult(false, "", "Failed to unstage: ${result.message}")
            is GitOperationResult.InProgress -> ToolResult(true, "Unstaging in progress...")
        }
    }

    private suspend fun executeGitCommit(args: JsonObject): ToolResult {
        checkGitAvailable()?.let { return it }

        val message = args.getStringArg("message")
            ?: return ToolResult(false, "", "Missing required argument: message")

        return when (val result = gitManager!!.commit(message)) {
            is GitOperationResult.Success -> {
                val commit = result.data
                ToolResult(true, "Commit created: ${commit.shortId} - ${commit.message}")
            }
            is GitOperationResult.Error -> ToolResult(false, "", "Failed to commit: ${result.message}")
            is GitOperationResult.InProgress -> ToolResult(true, "Committing...")
        }
    }

    private suspend fun executeGitPush(args: JsonObject): ToolResult {
        checkGitAvailable()?.let { return it }

        val remote = args.getStringArg("remote") ?: "origin"
        val setUpstream = args.getBooleanArg("set_upstream", false)

        return when (val result = gitManager!!.push(remote, null, setUpstream)) {
            is GitOperationResult.Success -> ToolResult(true, "Pushed successfully to $remote")
            is GitOperationResult.Error -> ToolResult(false, "", "Failed to push: ${result.message}")
            is GitOperationResult.InProgress -> ToolResult(true, "Pushing...")
        }
    }

    private suspend fun executeGitPull(args: JsonObject): ToolResult {
        checkGitAvailable()?.let { return it }

        val remote = args.getStringArg("remote") ?: "origin"
        val rebase = args.getBooleanArg("rebase", false)

        return when (val result = gitManager!!.pull(remote, null, rebase)) {
            is GitOperationResult.Success -> ToolResult(true, "Pulled successfully from $remote")
            is GitOperationResult.Error -> ToolResult(false, "", "Failed to pull: ${result.message}")
            is GitOperationResult.InProgress -> ToolResult(true, "Pulling...")
        }
    }

    private suspend fun executeGitBranch(args: JsonObject): ToolResult {
        checkGitAvailable()?.let { return it }

        val action = args.getStringArg("action")
            ?: return ToolResult(false, "", "Missing required argument: action")

        return when (action.lowercase()) {
            "list" -> {
                val branches = gitManager!!.branches.value
                if (branches.isEmpty()) {
                    ToolResult(true, "No branches found")
                } else {
                    val output = buildString {
                        appendLine("Branches:")
                        branches.filter { it.isLocal }.forEach { branch ->
                            val prefix = if (branch.isCurrent) "* " else "  "
                            append(prefix)
                            append(branch.name)
                            if (branch.aheadCount > 0 || branch.behindCount > 0) {
                                append(" [")
                                if (branch.aheadCount > 0) append("↑${branch.aheadCount}")
                                if (branch.behindCount > 0) append("↓${branch.behindCount}")
                                append("]")
                            }
                            appendLine()
                        }
                    }
                    ToolResult(true, output)
                }
            }
            "create" -> {
                val name = args.getStringArg("name")
                    ?: return ToolResult(false, "", "Branch name is required for create action")
                val checkout = args.getBooleanArg("checkout", false)

                when (val result = gitManager!!.createBranch(name, checkout)) {
                    is GitOperationResult.Success -> {
                        val msg = if (checkout) "Created and switched to branch '$name'" else "Created branch '$name'"
                        ToolResult(true, msg)
                    }
                    is GitOperationResult.Error -> ToolResult(false, "", "Failed to create branch: ${result.message}")
                    is GitOperationResult.InProgress -> ToolResult(true, "Creating branch...")
                }
            }
            "delete" -> {
                val name = args.getStringArg("name")
                    ?: return ToolResult(false, "", "Branch name is required for delete action")

                when (val result = gitManager!!.deleteBranch(name)) {
                    is GitOperationResult.Success -> ToolResult(true, "Deleted branch '$name'")
                    is GitOperationResult.Error -> ToolResult(false, "", "Failed to delete branch: ${result.message}")
                    is GitOperationResult.InProgress -> ToolResult(true, "Deleting branch...")
                }
            }
            else -> ToolResult(false, "", "Unknown action: $action. Use 'list', 'create', or 'delete'")
        }
    }

    private suspend fun executeGitCheckout(args: JsonObject): ToolResult {
        checkGitAvailable()?.let { return it }

        val branch = args.getStringArg("branch")
            ?: return ToolResult(false, "", "Missing required argument: branch")

        return when (val result = gitManager!!.checkout(branch)) {
            is GitOperationResult.Success -> ToolResult(true, "Switched to branch '$branch'")
            is GitOperationResult.Error -> ToolResult(false, "", "Failed to checkout: ${result.message}")
            is GitOperationResult.InProgress -> ToolResult(true, "Checking out...")
        }
    }

    private suspend fun executeGitLog(args: JsonObject): ToolResult {
        checkGitAvailable()?.let { return it }

        val count = args.getStringArg("count")?.toIntOrNull() ?: 10
        val commits = gitManager!!.commits.value.take(count)

        if (commits.isEmpty()) {
            return ToolResult(true, "No commits found")
        }

        val output = buildString {
            appendLine("Recent commits:")
            commits.forEach { commit ->
                appendLine("${commit.shortId} - ${commit.message}")
                appendLine("  Author: ${commit.authorName} <${commit.authorEmail}>")
                appendLine("  Date: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(commit.timestamp))}")
                appendLine()
            }
        }
        return ToolResult(true, output)
    }

    private suspend fun executeGitDiff(args: JsonObject): ToolResult {
        checkGitAvailable()?.let { return it }

        val path = args.getStringArg("path")
        val staged = args.getBooleanArg("staged", false)

        return if (path != null) {
            when (val result = gitManager!!.getFileDiff(path, staged)) {
                is GitOperationResult.Success -> {
                    val diff = result.data
                    if (diff.hunks.isEmpty()) {
                        ToolResult(true, "No changes in $path")
                    } else {
                        val output = buildString {
                            appendLine("Diff for $path:")
                            diff.hunks.forEach { hunk ->
                                appendLine("@@ -${hunk.oldStartLine},${hunk.oldLineCount} +${hunk.newStartLine},${hunk.newLineCount} @@")
                                hunk.lines.forEach { line ->
                                    appendLine(line)
                                }
                            }
                        }
                        ToolResult(true, output)
                    }
                }
                is GitOperationResult.Error -> ToolResult(false, "", "Failed to get diff: ${result.message}")
                is GitOperationResult.InProgress -> ToolResult(true, "Getting diff...")
            }
        } else {
            // Return summary of all changes
            val changedFiles = gitManager!!.changedFiles.value
            val targetFiles = if (staged) changedFiles.filter { it.isStaged } else changedFiles.filter { !it.isStaged }

            if (targetFiles.isEmpty()) {
                ToolResult(true, "No ${if (staged) "staged" else "unstaged"} changes")
            } else {
                val output = buildString {
                    appendLine("${if (staged) "Staged" else "Unstaged"} changes:")
                    targetFiles.forEach { file ->
                        appendLine("  ${file.status.name[0]} ${file.path}")
                    }
                }
                ToolResult(true, output)
            }
        }
    }

    private suspend fun executeGitDiscard(args: JsonObject): ToolResult {
        checkGitAvailable()?.let { return it }

        val pathsArg = args.getStringArg("paths")
            ?: return ToolResult(false, "", "Missing required argument: paths")

        val paths = pathsArg.split(",").map { it.trim() }
        return when (val result = gitManager!!.discardChanges(paths)) {
            is GitOperationResult.Success -> ToolResult(true, "Changes discarded for: ${paths.joinToString(", ")}")
            is GitOperationResult.Error -> ToolResult(false, "", "Failed to discard changes: ${result.message}")
            is GitOperationResult.InProgress -> ToolResult(true, "Discarding changes...")
        }
    }

    // ==================== Utility Functions ====================

    private fun matchesPattern(filename: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.matches(filename)
    }

    private fun String.pluralize(count: Int): String {
        return if (count == 1) "$count $this" else "$count ${this}s"
    }

    /**
     * Clear session todos for a project
     */
    fun clearSessionTodos(projectId: String) {
        sessionTodos.remove(projectId)
    }

    /**
     * Get all session todos for a project
     */
    fun getSessionTodos(projectId: String): List<TodoItem> {
        return sessionTodos[projectId]?.toList() ?: emptyList()
    }
}
