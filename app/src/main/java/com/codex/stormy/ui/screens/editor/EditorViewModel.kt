package com.codex.stormy.ui.screens.editor

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.codex.stormy.CodeXApplication
import com.codex.stormy.data.ai.AiModel
import com.codex.stormy.data.ai.AssistantMessageWithToolCalls
import com.codex.stormy.data.ai.ChatRequestMessage
import com.codex.stormy.data.ai.StreamEvent
import com.codex.stormy.data.ai.ToolCallResponse
import com.codex.stormy.data.ai.context.ContextUsageLevel
import com.codex.stormy.data.ai.context.ContextWindowManager
import com.codex.stormy.data.ai.learning.UserPreferencesLearner
import com.codex.stormy.data.ai.memory.SemanticMemorySystem
import com.codex.stormy.data.ai.tools.FileChangeType
import com.codex.stormy.data.ai.tools.MemoryStorage
import com.codex.stormy.data.ai.tools.StormyTools
import com.codex.stormy.data.ai.tools.TodoItem
import com.codex.stormy.data.ai.tools.ToolExecutor
import com.codex.stormy.data.ai.tools.ToolInteractionCallback
import com.codex.stormy.data.ai.undo.UndoRedoManager
import com.codex.stormy.data.ai.undo.UndoRedoState
import com.codex.stormy.data.local.entity.MessageStatus
import com.codex.stormy.data.repository.AiModelRepository
import com.codex.stormy.data.repository.AiRepository
import com.codex.stormy.data.repository.ChatRepository
import com.codex.stormy.data.repository.PreferencesRepository
import com.codex.stormy.data.repository.ProjectRepository
import com.codex.stormy.domain.model.ChatMessage
import com.codex.stormy.domain.model.FileTreeNode
import com.codex.stormy.domain.model.Project
import com.codex.stormy.utils.FileLoadingStrategy
import com.codex.stormy.utils.FileSizeThresholds
import com.codex.stormy.utils.FileUtils
import com.codex.stormy.utils.LargeFileHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Represents an open file tab in the editor
 */
data class OpenFileTab(
    val path: String,
    val name: String,
    val extension: String,
    val isModified: Boolean = false,
    val content: String = "",
    val fileSize: Long = 0,
    val isReadOnly: Boolean = false,
    val isLargeFile: Boolean = false,
    val loadProgress: Float = 1f
)

data class EditorUiState(
    val project: Project? = null,
    val selectedTab: EditorTab = EditorTab.CHAT,
    val fileTree: List<FileTreeNode> = emptyList(),
    val expandedFolders: Set<String> = emptySet(),
    val openFiles: List<OpenFileTab> = emptyList(),
    val currentFileIndex: Int = -1,
    val currentFile: FileTreeNode.FileNode? = null,
    val fileContent: String = "",
    val isFileModified: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val chatInput: String = "",
    val isAiProcessing: Boolean = false,
    val showLineNumbers: Boolean = true,
    val wordWrap: Boolean = true,
    val fontSize: Float = 14f,
    val isLoading: Boolean = false,
    val error: String? = null,
    val agentMode: Boolean = true,
    // AI Model selection - null means no model selected (show "Select Model")
    val currentModel: AiModel? = null,
    val availableModels: List<AiModel> = emptyList(),
    // Context window management
    val contextTokenCount: Int = 0,
    val contextMaxTokens: Int = 8000,
    val contextUsageLevel: ContextUsageLevel = ContextUsageLevel.LOW,
    // Undo/Redo state
    val undoRedoState: UndoRedoState = UndoRedoState(),
    // Task planning
    val taskList: List<TodoItem> = emptyList(),
    // Large file handling
    val largeFileWarning: LargeFileWarning? = null,
    val fileLoadProgress: Float = 1f,
    val isCurrentFileReadOnly: Boolean = false
)

/**
 * Warning shown when opening large files
 */
data class LargeFileWarning(
    val filePath: String,
    val fileName: String,
    val fileSize: Long,
    val formattedSize: String,
    val lineCount: Long,
    val warningMessage: String,
    val estimatedLoadTime: String,
    val canEdit: Boolean,
    val suggestReadOnly: Boolean
)

class EditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val preferencesRepository: PreferencesRepository,
    private val aiRepository: AiRepository,
    private val chatRepository: ChatRepository,
    private val aiModelRepository: AiModelRepository,
    private val contextWindowManager: ContextWindowManager,
    private val userPreferencesLearner: UserPreferencesLearner,
    private val toolExecutor: ToolExecutor,
    private val memoryStorage: MemoryStorage,
    private val undoRedoManager: UndoRedoManager,
    private val semanticMemorySystem: SemanticMemorySystem
) : ViewModel() {

    private val projectId: String = savedStateHandle["projectId"] ?: ""

    private val _project = MutableStateFlow<Project?>(null)
    private val _selectedTab = MutableStateFlow(EditorTab.CHAT)
    private val _fileTree = MutableStateFlow<List<FileTreeNode>>(emptyList())
    private val _expandedFolders = MutableStateFlow<Set<String>>(emptySet())
    private val _openFiles = MutableStateFlow<List<OpenFileTab>>(emptyList())
    private val _currentFileIndex = MutableStateFlow(-1)
    private val _currentFile = MutableStateFlow<FileTreeNode.FileNode?>(null)
    private val _fileContent = MutableStateFlow("")
    private val _originalFileContent = MutableStateFlow("")
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _chatInput = MutableStateFlow("")
    private val _isAiProcessing = MutableStateFlow(false)
    private val _isLoading = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _agentMode = MutableStateFlow(true)
    private val _currentModel = MutableStateFlow<AiModel?>(null)
    private val _availableModels = MutableStateFlow<List<AiModel>>(emptyList())
    private val _messageHistory = mutableListOf<ChatRequestMessage>()
    private val _streamingContent = MutableStateFlow("")

    // Context window tracking
    private val _contextTokenCount = MutableStateFlow(0)
    private val _contextMaxTokens = MutableStateFlow(8000) // Default, will be updated when model is selected
    private val _contextUsageLevel = MutableStateFlow(ContextUsageLevel.LOW)

    // Task planning state
    private val _taskList = MutableStateFlow<List<TodoItem>>(emptyList())

    // Large file handling state
    private val _largeFileWarning = MutableStateFlow<LargeFileWarning?>(null)
    private val _fileLoadProgress = MutableStateFlow(1f)
    private val _isCurrentFileReadOnly = MutableStateFlow(false)

    // Agent loop state tracking
    private var _pendingToolCalls = mutableListOf<ToolCallResponse>()
    private var _shouldContinueAgentLoop = false
    private var _agentIterationCount = 0
    private var _taskCompleted = false

    // AI generation job for cancellation support
    private var _aiGenerationJob: Job? = null
    private var _isGenerationCancelled = false

    // Streaming update debouncing for performance
    private var _streamingUpdateJob: Job? = null
    private var _lastStreamingUpdate = 0L
    private var _pendingStreamingUpdate = false

    val uiState: StateFlow<EditorUiState> = combine(
        _project,
        _selectedTab,
        _fileTree,
        _expandedFolders,
        _openFiles,
        _currentFileIndex,
        _currentFile,
        _fileContent,
        _messages
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        EditorUiState(
            project = values[0] as Project?,
            selectedTab = values[1] as EditorTab,
            fileTree = values[2] as List<FileTreeNode>,
            expandedFolders = values[3] as Set<String>,
            openFiles = values[4] as List<OpenFileTab>,
            currentFileIndex = values[5] as Int,
            currentFile = values[6] as FileTreeNode.FileNode?,
            fileContent = values[7] as String,
            isFileModified = values[7] as String != _originalFileContent.value,
            messages = values[8] as List<ChatMessage>
        )
    }.combine(_chatInput) { state, chatInput ->
        state.copy(chatInput = chatInput)
    }.combine(_isAiProcessing) { state, isProcessing ->
        state.copy(isAiProcessing = isProcessing)
    }.combine(_agentMode) { state, agentMode ->
        state.copy(agentMode = agentMode)
    }.combine(_currentModel) { state, currentModel ->
        state.copy(currentModel = currentModel)
    }.combine(_availableModels) { state, availableModels ->
        state.copy(availableModels = availableModels)
    }.combine(preferencesRepository.lineNumbers) { state, lineNumbers ->
        state.copy(showLineNumbers = lineNumbers)
    }.combine(preferencesRepository.wordWrap) { state, wordWrap ->
        state.copy(wordWrap = wordWrap)
    }.combine(preferencesRepository.fontSize) { state, fontSize ->
        state.copy(fontSize = fontSize)
    }.combine(_contextTokenCount) { state, tokenCount ->
        state.copy(contextTokenCount = tokenCount)
    }.combine(_contextMaxTokens) { state, maxTokens ->
        state.copy(contextMaxTokens = maxTokens)
    }.combine(_contextUsageLevel) { state, usageLevel ->
        state.copy(contextUsageLevel = usageLevel)
    }.combine(undoRedoManager.state) { state, undoRedoState ->
        state.copy(undoRedoState = undoRedoState)
    }.combine(_taskList) { state, taskList ->
        state.copy(taskList = taskList)
    }.combine(_largeFileWarning) { state, warning ->
        state.copy(largeFileWarning = warning)
    }.combine(_fileLoadProgress) { state, progress ->
        state.copy(fileLoadProgress = progress)
    }.combine(_isCurrentFileReadOnly) { state, isReadOnly ->
        state.copy(isCurrentFileReadOnly = isReadOnly)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = EditorUiState()
    )

    // Debug logging state
    private val _debugLogsEnabled = MutableStateFlow(false)

    init {
        loadProject()
        loadChatHistory()
        loadAvailableModels()
        setupToolInteractionCallback()
        observeDebugLogsPreference()
    }

    private fun observeDebugLogsPreference() {
        viewModelScope.launch {
            preferencesRepository.debugLogsEnabled.collect { enabled ->
                _debugLogsEnabled.value = enabled
            }
        }
    }

    /**
     * Load available models from repository and resolve the initial model
     * Model resolution priority:
     * 1. Project's last used model (if exists)
     * 2. User's default model (if set)
     * 3. null (show "Select Model")
     */
    private fun loadAvailableModels() {
        viewModelScope.launch {
            // Observe enabled models from repository
            aiModelRepository.observeEnabledModels().collect { models ->
                _availableModels.value = models
            }
        }

        viewModelScope.launch {
            // Wait for project to load first
            _project.collect { project ->
                if (project != null && _currentModel.value == null) {
                    resolveInitialModel(project)
                }
            }
        }
    }

    /**
     * Resolve the initial model based on project and user preferences
     */
    private suspend fun resolveInitialModel(project: Project) {
        // Priority 1: Project's last used model
        if (!project.lastUsedModelId.isNullOrEmpty()) {
            val projectModel = aiModelRepository.getModelById(project.lastUsedModelId)
            if (projectModel != null) {
                _currentModel.value = projectModel
                updateContextMaxTokens(projectModel)
                return
            }
        }

        // Priority 2: User's global default model
        val defaultModelId = preferencesRepository.defaultModelId.first()
        if (defaultModelId.isNotEmpty()) {
            val defaultModel = aiModelRepository.getModelById(defaultModelId)
            if (defaultModel != null) {
                _currentModel.value = defaultModel
                updateContextMaxTokens(defaultModel)
                return
            }
        }

        // Priority 3: null (no model selected - user must select one)
        _currentModel.value = null
    }

    /**
     * Update context max tokens when model changes
     */
    private fun updateContextMaxTokens(model: AiModel?) {
        if (model != null) {
            _contextMaxTokens.value = contextWindowManager.getAvailableTokens(model)
        }
    }

    private fun loadProject() {
        viewModelScope.launch {
            _isLoading.value = true

            projectRepository.observeProjectById(projectId).collect { project ->
                _project.value = project
                if (project != null) {
                    loadFileTree()
                }
            }
        }
    }

    /**
     * Set up tool interaction callback for undo/redo tracking and task planning
     */
    private fun setupToolInteractionCallback() {
        toolExecutor.interactionCallback = object : ToolInteractionCallback {
            override suspend fun askUser(question: String, options: List<String>?): String? {
                // For now, we'll display the question in the chat
                // A full implementation would show a dialog and wait for user input
                return null
            }

            override suspend fun onTaskFinished(summary: String) {
                // Clear task list when task is finished
                _taskList.value = emptyList()
            }

            override suspend fun onFileChanged(
                path: String,
                changeType: FileChangeType,
                oldContent: String?,
                newContent: String?
            ) {
                // Record the change for undo/redo
                undoRedoManager.recordChange(
                    path = path,
                    changeType = changeType,
                    oldContent = oldContent,
                    newContent = newContent
                )
            }

            override suspend fun onTodoCreated(todo: TodoItem) {
                // Add the new todo to the task list
                _taskList.value = _taskList.value + todo
            }

            override suspend fun onTodoUpdated(todo: TodoItem) {
                // Update the todo in the task list
                _taskList.value = _taskList.value.map {
                    if (it.id == todo.id) todo else it
                }
            }
        }
    }

    /**
     * Load persisted chat history from database
     * Uses a simpler approach: database is always the source of truth when not streaming
     */
    private fun loadChatHistory() {
        viewModelScope.launch {
            chatRepository.getMessagesForProject(projectId).collect { dbMessages ->
                // Only update from database if we're not currently processing AI response
                // During AI processing, the in-memory state is managed by streaming logic
                if (!_isAiProcessing.value) {
                    // Database is the source of truth when not streaming
                    // Use distinctBy to prevent any duplicate IDs just in case
                    _messages.value = dbMessages.distinctBy { it.id }

                    // Rebuild message history for AI context
                    rebuildMessageHistoryFromMessages(_messages.value)
                }
            }
        }
    }

    /**
     * Rebuild the AI message history from persisted messages
     */
    private fun rebuildMessageHistoryFromMessages(messages: List<ChatMessage>) {
        _messageHistory.clear()
        for (message in messages) {
            when {
                message.isUser -> {
                    _messageHistory.add(aiRepository.createUserMessage(message.content))
                }
                message.isAssistant && message.status != MessageStatus.STREAMING -> {
                    _messageHistory.add(
                        ChatRequestMessage(
                            role = "assistant",
                            content = message.content
                        )
                    )
                }
            }
        }
    }

    private suspend fun loadFileTree() {
        val tree = projectRepository.getFileTree(projectId)
        _fileTree.value = tree
        _isLoading.value = false
        // Don't auto-open first file - let user choose from file tree
        // Chat tab is the default and should remain visible
    }

    private fun findFirstFile(nodes: List<FileTreeNode>): FileTreeNode.FileNode? {
        for (node in nodes) {
            when (node) {
                is FileTreeNode.FileNode -> return node
                is FileTreeNode.FolderNode -> {
                    findFirstFile(node.children)?.let { return it }
                }
            }
        }
        return null
    }

    fun selectTab(tab: EditorTab) {
        _selectedTab.value = tab
    }

    /**
     * Public method to refresh the file tree
     * Called when external changes are made (e.g., asset manager adds/deletes files)
     */
    fun refreshFileTree() {
        viewModelScope.launch {
            loadFileTree()
        }
    }

    fun toggleFolder(folderPath: String) {
        _expandedFolders.value = if (folderPath in _expandedFolders.value) {
            _expandedFolders.value - folderPath
        } else {
            _expandedFolders.value + folderPath
        }
    }

    fun openFile(relativePath: String) {
        viewModelScope.launch {
            saveCurrentFileIfModified()

            // Check if file is already open
            val existingIndex = _openFiles.value.indexOfFirst { it.path == relativePath }
            if (existingIndex >= 0) {
                // Switch to existing tab
                switchToFileTab(existingIndex)
                return@launch
            }

            val fileNode = findFileNode(relativePath)
            if (fileNode == null) {
                _error.value = "File not found: $relativePath"
                return@launch
            }

            // Check file size and determine loading strategy
            val project = _project.value ?: return@launch
            val file = File(project.rootPath, relativePath)
            val fileSize = file.length()
            val strategy = LargeFileHandler.determineStrategy(fileSize)

            when (strategy) {
                FileLoadingStrategy.UNSUPPORTED -> {
                    // File too large - show error
                    _error.value = "File is too large to open (${FileUtils.formatFileSize(fileSize)}). Maximum supported size is 10 MB."
                    return@launch
                }
                FileLoadingStrategy.READ_ONLY_PREVIEW -> {
                    // Show warning for very large files
                    val analysis = LargeFileHandler.analyzeFile(file)
                    _largeFileWarning.value = LargeFileWarning(
                        filePath = relativePath,
                        fileName = fileNode.name,
                        fileSize = fileSize,
                        formattedSize = analysis.sizeFormatted,
                        lineCount = analysis.lineCount,
                        warningMessage = analysis.warningMessage ?: "",
                        estimatedLoadTime = analysis.estimatedLoadTime,
                        canEdit = false,
                        suggestReadOnly = true
                    )
                    return@launch
                }
                FileLoadingStrategy.PAGINATED_LOAD -> {
                    // Show warning for large files
                    val analysis = LargeFileHandler.analyzeFile(file)
                    _largeFileWarning.value = LargeFileWarning(
                        filePath = relativePath,
                        fileName = fileNode.name,
                        fileSize = fileSize,
                        formattedSize = analysis.sizeFormatted,
                        lineCount = analysis.lineCount,
                        warningMessage = analysis.warningMessage ?: "",
                        estimatedLoadTime = analysis.estimatedLoadTime,
                        canEdit = true,
                        suggestReadOnly = false
                    )
                    return@launch
                }
                FileLoadingStrategy.CHUNKED_LOAD -> {
                    // Load with progress for medium-large files
                    openFileWithProgress(relativePath, fileNode, fileSize)
                }
                FileLoadingStrategy.FULL_LOAD -> {
                    // Normal loading for small files
                    openFileNormal(relativePath, fileNode, fileSize)
                }
            }
        }
    }

    /**
     * Open file with progress indicator for larger files
     */
    private suspend fun openFileWithProgress(
        relativePath: String,
        fileNode: FileTreeNode.FileNode,
        fileSize: Long
    ) {
        val project = _project.value ?: return
        val file = File(project.rootPath, relativePath)

        _fileLoadProgress.value = 0f

        LargeFileHandler.readFileWithProgress(file) { progress ->
            _fileLoadProgress.value = progress
        }.onSuccess { content ->
            val newTab = OpenFileTab(
                path = relativePath,
                name = fileNode.name,
                extension = fileNode.extension,
                isModified = false,
                content = content,
                fileSize = fileSize,
                isLargeFile = true
            )
            val updatedOpenFiles = _openFiles.value + newTab
            _openFiles.value = updatedOpenFiles
            _currentFileIndex.value = updatedOpenFiles.size - 1

            _fileContent.value = content
            _originalFileContent.value = content
            _currentFile.value = fileNode
            _fileLoadProgress.value = 1f
            _selectedTab.value = EditorTab.CODE
        }.onFailure { error ->
            _error.value = error.message
            _fileLoadProgress.value = 1f
        }
    }

    /**
     * Normal file loading for small files
     */
    private suspend fun openFileNormal(
        relativePath: String,
        fileNode: FileTreeNode.FileNode,
        fileSize: Long
    ) {
        projectRepository.readFile(projectId, relativePath)
            .onSuccess { content ->
                val newTab = OpenFileTab(
                    path = relativePath,
                    name = fileNode.name,
                    extension = fileNode.extension,
                    isModified = false,
                    content = content,
                    fileSize = fileSize
                )
                val updatedOpenFiles = _openFiles.value + newTab
                _openFiles.value = updatedOpenFiles
                _currentFileIndex.value = updatedOpenFiles.size - 1

                _fileContent.value = content
                _originalFileContent.value = content
                _currentFile.value = fileNode
                _selectedTab.value = EditorTab.CODE
            }
            .onFailure { error ->
                _error.value = error.message
            }
    }

    /**
     * Called when user confirms they want to open a large file
     */
    fun confirmOpenLargeFile(readOnly: Boolean = false) {
        val warning = _largeFileWarning.value ?: return
        _largeFileWarning.value = null

        viewModelScope.launch {
            val fileNode = findFileNode(warning.filePath) ?: return@launch

            if (readOnly) {
                // Open in read-only mode with preview
                openFileReadOnly(warning.filePath, fileNode, warning.fileSize)
            } else {
                // Open normally with progress
                openFileWithProgress(warning.filePath, fileNode, warning.fileSize)
            }
        }
    }

    /**
     * Open file in read-only mode (for very large files)
     */
    private suspend fun openFileReadOnly(
        relativePath: String,
        fileNode: FileTreeNode.FileNode,
        fileSize: Long
    ) {
        val project = _project.value ?: return
        val file = File(project.rootPath, relativePath)

        LargeFileHandler.readPreview(file).onSuccess { previewContent ->
            val newTab = OpenFileTab(
                path = relativePath,
                name = fileNode.name,
                extension = fileNode.extension,
                isModified = false,
                content = previewContent + "\n\n// ... (truncated - file too large to edit fully)",
                fileSize = fileSize,
                isReadOnly = true,
                isLargeFile = true
            )
            val updatedOpenFiles = _openFiles.value + newTab
            _openFiles.value = updatedOpenFiles
            _currentFileIndex.value = updatedOpenFiles.size - 1

            _fileContent.value = newTab.content
            _originalFileContent.value = newTab.content
            _currentFile.value = fileNode
            _isCurrentFileReadOnly.value = true
            _selectedTab.value = EditorTab.CODE
        }.onFailure { error ->
            _error.value = error.message
        }
    }

    /**
     * Dismiss large file warning without opening
     */
    fun dismissLargeFileWarning() {
        _largeFileWarning.value = null
    }

    fun switchToFileTab(index: Int) {
        if (index < 0 || index >= _openFiles.value.size) return

        viewModelScope.launch {
            saveCurrentFileIfModified()

            val targetFile = _openFiles.value[index]
            _currentFileIndex.value = index
            _fileContent.value = targetFile.content
            _originalFileContent.value = if (targetFile.isModified) "" else targetFile.content
            _currentFile.value = findFileNode(targetFile.path)
        }
    }

    fun closeFileTab(index: Int) {
        if (index < 0 || index >= _openFiles.value.size) return

        viewModelScope.launch {
            val closingFile = _openFiles.value[index]

            // Save if modified before closing
            if (closingFile.isModified) {
                projectRepository.writeFile(projectId, closingFile.path, closingFile.content)
            }

            val updatedOpenFiles = _openFiles.value.toMutableList()
            updatedOpenFiles.removeAt(index)
            _openFiles.value = updatedOpenFiles

            // Adjust current index
            when {
                updatedOpenFiles.isEmpty() -> {
                    _currentFileIndex.value = -1
                    _currentFile.value = null
                    _fileContent.value = ""
                    _originalFileContent.value = ""
                }
                index >= updatedOpenFiles.size -> {
                    switchToFileTab(updatedOpenFiles.size - 1)
                }
                index == _currentFileIndex.value -> {
                    switchToFileTab(index.coerceAtMost(updatedOpenFiles.size - 1))
                }
                index < _currentFileIndex.value -> {
                    _currentFileIndex.value = _currentFileIndex.value - 1
                }
            }
        }
    }

    fun closeOtherTabs(keepIndex: Int) {
        if (keepIndex < 0 || keepIndex >= _openFiles.value.size) return

        viewModelScope.launch {
            val keepFile = _openFiles.value[keepIndex]

            // Save all modified files before closing
            _openFiles.value.forEachIndexed { index, file ->
                if (index != keepIndex && file.isModified) {
                    projectRepository.writeFile(projectId, file.path, file.content)
                }
            }

            _openFiles.value = listOf(keepFile)
            _currentFileIndex.value = 0
        }
    }

    fun closeAllTabs() {
        viewModelScope.launch {
            // Save all modified files
            _openFiles.value.forEach { file ->
                if (file.isModified) {
                    projectRepository.writeFile(projectId, file.path, file.content)
                }
            }

            _openFiles.value = emptyList()
            _currentFileIndex.value = -1
            _currentFile.value = null
            _fileContent.value = ""
            _originalFileContent.value = ""
        }
    }

    private fun findFileNode(path: String): FileTreeNode.FileNode? {
        return findFileNodeInTree(_fileTree.value, path)
    }

    private fun findFileNodeInTree(nodes: List<FileTreeNode>, path: String): FileTreeNode.FileNode? {
        for (node in nodes) {
            when (node) {
                is FileTreeNode.FileNode -> {
                    if (node.path == path) return node
                }
                is FileTreeNode.FolderNode -> {
                    findFileNodeInTree(node.children, path)?.let { return it }
                }
            }
        }
        return null
    }

    fun updateFileContent(content: String) {
        _fileContent.value = content

        // Update the content in open files list
        val index = _currentFileIndex.value
        if (index >= 0 && index < _openFiles.value.size) {
            val isModified = content != _originalFileContent.value
            val updatedFiles = _openFiles.value.toMutableList()
            updatedFiles[index] = updatedFiles[index].copy(
                content = content,
                isModified = isModified
            )
            _openFiles.value = updatedFiles
        }
    }

    fun saveCurrentFile() {
        val currentFile = _currentFile.value ?: return
        val content = _fileContent.value

        viewModelScope.launch {
            projectRepository.writeFile(projectId, currentFile.path, content)
                .onSuccess {
                    _originalFileContent.value = content

                    // Update modified flag in open files
                    val index = _currentFileIndex.value
                    if (index >= 0 && index < _openFiles.value.size) {
                        val updatedFiles = _openFiles.value.toMutableList()
                        updatedFiles[index] = updatedFiles[index].copy(isModified = false)
                        _openFiles.value = updatedFiles
                    }
                }
                .onFailure { error ->
                    _error.value = error.message
                }
        }
    }

    private suspend fun saveCurrentFileIfModified() {
        if (_fileContent.value != _originalFileContent.value) {
            _currentFile.value?.let { file ->
                projectRepository.writeFile(projectId, file.path, _fileContent.value)
                    .onSuccess {
                        _originalFileContent.value = _fileContent.value

                        // Update modified flag in open files
                        val index = _currentFileIndex.value
                        if (index >= 0 && index < _openFiles.value.size) {
                            val updatedFiles = _openFiles.value.toMutableList()
                            updatedFiles[index] = updatedFiles[index].copy(isModified = false)
                            _openFiles.value = updatedFiles
                        }
                    }
            }
        }
    }

    fun createFile(parentPath: String, fileName: String) {
        viewModelScope.launch {
            val fullPath = if (parentPath.isEmpty()) fileName else "$parentPath/$fileName"
            projectRepository.createFile(projectId, fullPath)
                .onSuccess {
                    loadFileTree()
                    openFile(fullPath)
                }
                .onFailure { error ->
                    _error.value = error.message
                }
        }
    }

    fun createFolder(parentPath: String, folderName: String) {
        viewModelScope.launch {
            val fullPath = if (parentPath.isEmpty()) folderName else "$parentPath/$folderName"
            projectRepository.createFolder(projectId, fullPath)
                .onSuccess {
                    loadFileTree()
                    _expandedFolders.value = _expandedFolders.value + fullPath
                }
                .onFailure { error ->
                    _error.value = error.message
                }
        }
    }

    fun deleteFile(path: String) {
        viewModelScope.launch {
            projectRepository.deleteFile(projectId, path)
                .onSuccess {
                    // Close tab if open
                    val tabIndex = _openFiles.value.indexOfFirst { it.path == path }
                    if (tabIndex >= 0) {
                        val updatedFiles = _openFiles.value.toMutableList()
                        updatedFiles.removeAt(tabIndex)
                        _openFiles.value = updatedFiles

                        // Adjust current index
                        if (_currentFileIndex.value >= updatedFiles.size) {
                            _currentFileIndex.value = (updatedFiles.size - 1).coerceAtLeast(-1)
                        }
                        if (_currentFileIndex.value >= 0) {
                            switchToFileTab(_currentFileIndex.value)
                        } else {
                            _currentFile.value = null
                            _fileContent.value = ""
                            _originalFileContent.value = ""
                        }
                    }

                    loadFileTree()
                }
                .onFailure { error ->
                    _error.value = error.message
                }
        }
    }

    fun renameFile(oldPath: String, newName: String) {
        viewModelScope.launch {
            val parentPath = oldPath.substringBeforeLast("/", "")
            val newPath = if (parentPath.isEmpty()) newName else "$parentPath/$newName"

            projectRepository.renameFile(projectId, oldPath, newPath)
                .onSuccess {
                    // Update open tab if exists
                    val tabIndex = _openFiles.value.indexOfFirst { it.path == oldPath }
                    if (tabIndex >= 0) {
                        val updatedFiles = _openFiles.value.toMutableList()
                        val extension = newName.substringAfterLast(".", "")
                        updatedFiles[tabIndex] = updatedFiles[tabIndex].copy(
                            path = newPath,
                            name = newName,
                            extension = extension
                        )
                        _openFiles.value = updatedFiles

                        if (_currentFileIndex.value == tabIndex) {
                            _currentFile.value = findFileNode(newPath)
                        }
                    }

                    loadFileTree()
                }
                .onFailure { error ->
                    _error.value = error.message
                }
        }
    }

    /**
     * Import a file from device storage into the project
     * @param fileUri URI of the file to import
     * @param targetPath Target folder path within the project (empty string for root)
     */
    fun importFile(fileUri: Uri, targetPath: String) {
        viewModelScope.launch {
            projectRepository.importFileToProject(projectId, fileUri, targetPath)
                .onSuccess { importedPath ->
                    loadFileTree()
                    // Optionally open the imported file
                    openFile(importedPath)
                }
                .onFailure { error ->
                    _error.value = error.message ?: "Failed to import file"
                }
        }
    }

    /**
     * Import a folder from device storage into the project
     * @param folderUri URI of the folder to import (document tree URI)
     * @param targetPath Target folder path within the project (empty string for root)
     */
    fun importFolder(folderUri: Uri, targetPath: String) {
        viewModelScope.launch {
            projectRepository.importFolderToProject(projectId, folderUri, targetPath)
                .onSuccess { filesImported ->
                    loadFileTree()
                }
                .onFailure { error ->
                    _error.value = error.message ?: "Failed to import folder"
                }
        }
    }

    fun toggleAgentMode() {
        _agentMode.value = !_agentMode.value
    }

    fun updateChatInput(input: String) {
        _chatInput.value = input
    }

    fun sendMessage() {
        val content = _chatInput.value.trim()
        if (content.isEmpty() || _isAiProcessing.value) return

        val userMessage = ChatMessage.createUserMessage(projectId, content)
        _messages.value = _messages.value + userMessage
        _chatInput.value = ""
        _isAiProcessing.value = true

        // Reset agent loop state for new conversation turn
        _agentIterationCount = 0
        _taskCompleted = false
        _isGenerationCancelled = false
        _pendingToolCalls.clear()

        // Add to message history for AI context
        _messageHistory.add(aiRepository.createUserMessage(content))

        // Track the generation job for cancellation support
        _aiGenerationJob = viewModelScope.launch {
            // Save user message to database
            chatRepository.saveMessage(userMessage)

            // Auto-learn from user message (non-blocking)
            if (_agentMode.value) {
                try {
                    val currentFile = _currentFile.value?.path
                    semanticMemorySystem.learnFromUserMessage(projectId, content, currentFile)
                } catch (e: Exception) {
                    // Silently ignore learning errors - don't disrupt user experience
                }
            }

            sendAiRequest()
        }
    }

    /**
     * Stop the current AI generation/agent loop
     * This cancels any ongoing streaming and tool execution
     */
    fun stopGeneration() {
        if (!_isAiProcessing.value) return

        _isGenerationCancelled = true
        _aiGenerationJob?.cancel()
        _aiGenerationJob = null
        _streamingUpdateJob?.cancel()

        // Update the last message to show it was stopped
        val currentContent = _streamingContent.value
        if (currentContent.isNotEmpty()) {
            updateLastAssistantMessage(
                currentContent + "\n\n⏹️ Generation stopped by user.",
                MessageStatus.SENT
            )
        } else {
            // Remove empty assistant message if nothing was generated
            val currentMessages = _messages.value.toMutableList()
            if (currentMessages.isNotEmpty() && !currentMessages.last().isUser) {
                currentMessages.removeAt(currentMessages.lastIndex)
                _messages.value = currentMessages
            }
        }

        // Clean up state
        _isAiProcessing.value = false
        _taskCompleted = false
        _agentIterationCount = 0

        // Refresh file tree in a coroutine since loadFileTree is a suspend function
        viewModelScope.launch {
            loadFileTree()
        }
    }

    private suspend fun sendAiRequest() {
        // Check for cancellation at the start of each iteration
        if (_isGenerationCancelled) {
            return
        }
        currentCoroutineContext().ensureActive()

        val model = _currentModel.value
        val isAgentMode = _agentMode.value

        // Check if model is selected
        if (model == null) {
            updateLastAssistantMessage(
                "❌ Please select a model first before sending a message.",
                MessageStatus.ERROR
            )
            _isAiProcessing.value = false
            return
        }

        // Check iteration limit
        if (_agentIterationCount >= MAX_AGENT_ITERATIONS) {
            appendToLastAssistantMessage(
                "\n\n⚠️ Agent reached maximum iteration limit ($MAX_AGENT_ITERATIONS). Stopping to prevent infinite loop."
            )
            updateLastAssistantMessage(_streamingContent.value, MessageStatus.SENT)
            _isAiProcessing.value = false
            loadFileTree()
            return
        }

        _agentIterationCount++

        // Build system message with project context and memories
        val projectName = _project.value?.name ?: "Unknown Project"
        val currentFileName = _currentFile.value?.name ?: ""
        val currentFileContent = if (_fileContent.value.isNotEmpty()) {
            "\n\nCurrently open file ($currentFileName):\n```\n${_fileContent.value}\n```"
        } else ""

        // Get memory context if in agent mode
        val memoryContext = if (isAgentMode) {
            memoryStorage.getContextString(projectId)
        } else ""

        // Get semantic memory context for richer project knowledge
        val semanticMemoryContext = if (isAgentMode) {
            try {
                val currentFiles = _currentFile.value?.path?.let { listOf(it) } ?: emptyList()
                semanticMemorySystem.buildContextString(
                    projectId = projectId,
                    currentFiles = currentFiles,
                    queryTags = emptyList(),
                    maxTokens = 2000
                )
            } catch (e: Exception) {
                "" // Gracefully handle any memory system errors
            }
        } else ""

        // Get file tree for context
        val fileTreeContext = if (isAgentMode) {
            buildFileTreeContext()
        } else ""

        // Get user preferences context
        val preferencesContext = userPreferencesLearner.getPreferencesContext(projectId)

        val systemMessage = aiRepository.createSystemMessage(
            projectContext = "Project: $projectName$fileTreeContext$currentFileContent$memoryContext$semanticMemoryContext$preferencesContext"
        )

        // Optimize message history if needed for context window
        val optimizedHistory = if (contextWindowManager.needsOptimization(_messageHistory, model)) {
            contextWindowManager.optimizeMessages(_messageHistory, model, systemMessage)
        } else {
            _messageHistory.toList()
        }

        val messagesWithSystem = listOf(systemMessage) + optimizedHistory

        // Update context window stats
        updateContextStats(messagesWithSystem)

        // Only create new assistant message if this is the first iteration
        if (_agentIterationCount == 1) {
            _streamingContent.value = ""
            val assistantMessage = ChatMessage.createAssistantMessage(
                projectId = projectId,
                content = "",
                status = MessageStatus.STREAMING
            )
            _messages.value = _messages.value + assistantMessage
        }

        // Get tools based on mode
        val tools = if (isAgentMode) StormyTools.getAllTools() else null

        // Track tool calls for this iteration
        var currentToolCalls = listOf<ToolCallResponse>()
        var hasToolCalls = false
        var finishedWithToolCalls = false


        // Log the request
        logDebugToProject(
            "REQUEST",
            """
            Model: ${model.id}
            
            --- System Message ---
            ${systemMessage.content}
            
            --- Conversation History ---
            ${optimizedHistory.joinToString("\n\n") { "${it.role}: ${it.content}" }}
            """.trimIndent()
        )

        try {
            aiRepository.streamChat(
                model = model,
                messages = messagesWithSystem,
                tools = tools,
                temperature = 0.7f
            ).collect { event ->
                // Check for cancellation on each event
                if (_isGenerationCancelled) {
                    return@collect
                }
                currentCoroutineContext().ensureActive()

                when (event) {
                    is StreamEvent.Started -> {
                        // Streaming started
                    }
                    is StreamEvent.ContentDelta -> {
                        // Handle content delta - may contain inline <think> tags from some models
                        val currentContent = _streamingContent.value
                        val hasUnclosedThinkTag = hasUnclosedThinkingTag(currentContent)
                        val deltaContainsCloseTag = event.content.lowercase().contains("</think>")
                        val deltaContainsOpenTag = event.content.lowercase().contains("<think>")

                        if (hasUnclosedThinkTag && !deltaContainsCloseTag && !deltaContainsOpenTag && event.content.isNotEmpty()) {
                            // We have an open think block from ReasoningDelta, and this is regular content
                            // Close the think block before adding regular content
                            _streamingContent.value += "</think>\n${event.content}"
                        } else {
                            // Either no open think tag, or the delta contains tags that will handle closure
                            _streamingContent.value += event.content
                        }
                        // Use debounced update for better performance
                        debouncedStreamingUpdate()
                    }
                    is StreamEvent.ReasoningDelta -> {
                        // Handle reasoning for thinking models - wrap in <think> tags for proper parsing
                        val currentContent = _streamingContent.value
                        val hasOpenThinkTag = hasUnclosedThinkingTag(currentContent)

                        if (!hasOpenThinkTag && event.reasoning.isNotEmpty()) {
                            // Start a new think block
                            _streamingContent.value += "<think>${event.reasoning}"
                        } else if (hasOpenThinkTag) {
                            // Continue the existing think block
                            _streamingContent.value += event.reasoning
                        }
                        // Use debounced update for better performance
                        debouncedStreamingUpdate()
                    }
                    is StreamEvent.ToolCalls -> {
                        // Store tool calls for processing after stream completes
                        currentToolCalls = event.toolCalls
                        hasToolCalls = true
                    }
                    is StreamEvent.FinishReason -> {
                        // Track if finished due to tool calls
                        finishedWithToolCalls = event.reason == "tool_calls"
                    }
                    is StreamEvent.Error -> {
                        // Don't show error if generation was cancelled
                        if (!_isGenerationCancelled) {
                            updateLastAssistantMessage(
                                _streamingContent.value + "\n\n❌ Error: ${event.message}",
                                MessageStatus.ERROR
                            )
                            _isAiProcessing.value = false
                        }
                    }
                    is StreamEvent.Completed -> {
                        // Skip completion handling if cancelled
                        if (_isGenerationCancelled) {
                            return@collect
                        }

                        // Handle completion based on whether we have tool calls
                        if (hasToolCalls && currentToolCalls.isNotEmpty()) {
                            // Process tool calls and continue the loop
                            val shouldContinue = handleToolCalls(currentToolCalls)

                            if (shouldContinue && _agentMode.value && !_taskCompleted && !_isGenerationCancelled) {
                                // Continue the agentic loop
                                sendAiRequest()
                            } else {
                                // Task completed or agent stopped
                                updateLastAssistantMessage(_streamingContent.value, MessageStatus.SENT)
                                _isAiProcessing.value = false
                                loadFileTree()
                            }
                        } else {
                            // No tool calls - conversation turn complete
                            val finalContent = _streamingContent.value
                            updateLastAssistantMessage(finalContent, MessageStatus.SENT)

                            // Log the response
                            logDebugToProject(
                                "RESPONSE",
                                """
                                Content: $finalContent
                                """.trimIndent()
                            )

                            // Add assistant response to history (without tool calls)
                            if (finalContent.isNotEmpty()) {
                                _messageHistory.add(
                                    ChatRequestMessage(
                                        role = "assistant",
                                        content = finalContent
                                    )
                                )
                            }

                            _isAiProcessing.value = false
                            loadFileTree()
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // Cancellation is expected when user stops generation - don't treat as error
            // State cleanup is handled by stopGeneration()
            throw e // Re-throw to properly cancel the coroutine
        } catch (e: Exception) {
            // Don't show error if generation was cancelled
            if (!_isGenerationCancelled) {
                updateLastAssistantMessage(
                    _streamingContent.value + "\n\n❌ Failed to connect to AI: ${e.message}",
                    MessageStatus.ERROR
                )
                _isAiProcessing.value = false
            }
        }
    }

    /**
     * Build a compact file tree context string for the AI
     */
    private fun buildFileTreeContext(): String {
        val tree = _fileTree.value
        if (tree.isEmpty()) return ""

        val sb = StringBuilder("\n\nProject file structure:\n")
        buildFileTreeString(tree, sb, 0)
        return sb.toString()
    }

    private fun buildFileTreeString(nodes: List<FileTreeNode>, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        for (node in nodes) {
            when (node) {
                is FileTreeNode.FileNode -> {
                    sb.append("$indent- ${node.name}\n")
                }
                is FileTreeNode.FolderNode -> {
                    sb.append("$indent📁 ${node.name}/\n")
                    buildFileTreeString(node.children, sb, depth + 1)
                }
            }
        }
    }

    /**
     * Update context window statistics for UI display
     */
    private fun updateContextStats(messages: List<ChatRequestMessage>) {
        val model = _currentModel.value ?: return
        val currentTokens = contextWindowManager.estimateTotalTokens(messages)
        val maxTokens = contextWindowManager.getAvailableTokens(model)
        val usage = currentTokens.toFloat() / maxTokens

        _contextTokenCount.value = currentTokens
        _contextMaxTokens.value = maxTokens
        _contextUsageLevel.value = when {
            usage < 0.5f -> ContextUsageLevel.LOW
            usage < 0.75f -> ContextUsageLevel.MEDIUM
            usage < 0.9f -> ContextUsageLevel.HIGH
            else -> ContextUsageLevel.CRITICAL
        }
    }

    /**
     * Handle tool calls and return whether the agent should continue
     */
    private suspend fun handleToolCalls(toolCalls: List<ToolCallResponse>): Boolean {
        val toolResults = StringBuilder()
        var shouldContinue = true

        // First, add the assistant message with tool calls to history
        val currentContent = _streamingContent.value
        _messageHistory.add(
            AssistantMessageWithToolCalls(
                content = if (currentContent.isNotEmpty()) currentContent else null,
                toolCalls = toolCalls
            ).toChatRequestMessage()
        )

        // Begin a change group for undo/redo
        val toolNames = toolCalls.map { it.function.name }.distinct().joinToString(", ")
        undoRedoManager.beginChangeGroup("AI: $toolNames")

        for (toolCall in toolCalls) {
            val toolName = toolCall.function.name
            val result = toolExecutor.execute(projectId, toolCall)

            // Add visual feedback in the UI
            toolResults.append("\n\n🔧 **${formatToolName(toolName)}**\n")
            if (result.success) {
                val output = result.output.take(500) // Truncate long outputs in UI
                if (result.output.length > 500) {
                    toolResults.append("✅ ${output}...")
                } else {
                    toolResults.append("✅ $output")
                }
            } else {
                toolResults.append("❌ ${result.error}")
            }

            // Add tool result to message history for AI context
            _messageHistory.add(
                aiRepository.createToolResultMessage(
                    toolCallId = toolCall.id,
                    result = if (result.success) result.output else "Error: ${result.error}"
                )
            )

            // Check if this is a finish_task tool call
            if (toolName == "finish_task") {
                _taskCompleted = true
                shouldContinue = false
            }

            // Check if this is an ask_user tool call (requires user input)
            if (toolName == "ask_user") {
                shouldContinue = false
            }
        }

        // End the change group for undo/redo
        undoRedoManager.endChangeGroup()

        // Append tool results to streaming content
        _streamingContent.value += toolResults.toString()
        updateLastAssistantMessage(_streamingContent.value, MessageStatus.STREAMING)

        // Refresh file tree to reflect any changes
        loadFileTree()

        return shouldContinue
    }

    /**
     * Format tool name for display (convert snake_case to Title Case)
     */
    private fun formatToolName(name: String): String {
        return name.split("_").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    /**
     * Append text to the last assistant message
     */
    private fun appendToLastAssistantMessage(text: String) {
        _streamingContent.value += text
        updateLastAssistantMessage(_streamingContent.value, MessageStatus.STREAMING)
    }

    /**
     * Debounced update for streaming content to reduce UI lag.
     * Updates are batched and applied at most every STREAMING_UPDATE_INTERVAL_MS.
     */
    private fun debouncedStreamingUpdate() {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastUpdate = currentTime - _lastStreamingUpdate

        if (timeSinceLastUpdate >= STREAMING_UPDATE_INTERVAL_MS) {
            // Enough time has passed, update immediately
            _lastStreamingUpdate = currentTime
            updateLastAssistantMessageDirect(_streamingContent.value, MessageStatus.STREAMING)
        } else if (!_pendingStreamingUpdate) {
            // Schedule a delayed update
            _pendingStreamingUpdate = true
            _streamingUpdateJob?.cancel()
            _streamingUpdateJob = viewModelScope.launch {
                delay(STREAMING_UPDATE_INTERVAL_MS - timeSinceLastUpdate)
                _pendingStreamingUpdate = false
                _lastStreamingUpdate = System.currentTimeMillis()
                updateLastAssistantMessageDirect(_streamingContent.value, MessageStatus.STREAMING)
            }
        }
        // If pendingStreamingUpdate is true, an update is already scheduled
    }

    /**
     * Flush any pending streaming updates immediately
     */
    private fun flushStreamingUpdate() {
        _streamingUpdateJob?.cancel()
        _pendingStreamingUpdate = false
        _lastStreamingUpdate = System.currentTimeMillis()
    }

    private fun updateLastAssistantMessage(content: String, status: MessageStatus) {
        // For non-streaming updates, flush any pending and update directly
        if (status != MessageStatus.STREAMING) {
            flushStreamingUpdate()
        }
        updateLastAssistantMessageDirect(content, status)
    }

    private fun updateLastAssistantMessageDirect(content: String, status: MessageStatus) {
        val currentMessages = _messages.value.toMutableList()
        if (currentMessages.isNotEmpty()) {
            val lastMessage = currentMessages.last()
            if (!lastMessage.isUser) {
                // Sanitize content when saving (not streaming)
                val sanitizedContent = if (status != MessageStatus.STREAMING) {
                    sanitizeAiResponseContent(content)
                } else {
                    content
                }

                val updatedMessage = lastMessage.copy(
                    content = sanitizedContent,
                    status = status
                )
                currentMessages[currentMessages.lastIndex] = updatedMessage
                _messages.value = currentMessages

                // Persist to database when message is complete (not streaming)
                if (status != MessageStatus.STREAMING) {
                    viewModelScope.launch {
                        chatRepository.saveMessage(updatedMessage)
                    }
                }
            }
        }
    }

    /**
     * Check if content has any unclosed thinking/reasoning tags
     * Supports multiple tag formats used by different AI providers
     */
    private fun hasUnclosedThinkingTag(content: String): Boolean {
        val lowerContent = content.lowercase()
        val tagPairs = listOf(
            "<think>" to "</think>",
            "<thinking>" to "</thinking>",
            "<reasoning>" to "</reasoning>",
            "<reason>" to "</reason>"
        )

        for ((openTag, closeTag) in tagPairs) {
            val openCount = lowerContent.split(openTag).size - 1
            val closeCount = lowerContent.split(closeTag).size - 1
            if (openCount > closeCount) {
                return true
            }
        }
        return false
    }

    /**
     * Sanitize AI response content to ensure proper tag closure and remove polluted content
     * This handles various thinking/reasoning tag formats from different AI providers
     */
    private fun sanitizeAiResponseContent(content: String): String {
        var result = content

        // List of thinking/reasoning tag pairs to process
        val tagPairs = listOf(
            "<think>" to "</think>",
            "<thinking>" to "</thinking>",
            "<reasoning>" to "</reasoning>",
            "<reason>" to "</reason>"
        )

        // Close any unclosed thinking tags
        for ((openTag, closeTag) in tagPairs) {
            val openCount = result.lowercase().split(openTag.lowercase()).size - 1
            val closeCount = result.lowercase().split(closeTag.lowercase()).size - 1

            if (openCount > closeCount) {
                // Add missing closing tags
                repeat(openCount - closeCount) {
                    result = "$result$closeTag"
                }
            }
        }

        // Normalize all thinking tag variants to <think></think> for consistent parsing
        result = result
            .replace(Regex("<thinking>", RegexOption.IGNORE_CASE), "<think>")
            .replace(Regex("</thinking>", RegexOption.IGNORE_CASE), "</think>")
            .replace(Regex("<reasoning>", RegexOption.IGNORE_CASE), "<think>")
            .replace(Regex("</reasoning>", RegexOption.IGNORE_CASE), "</think>")
            .replace(Regex("<reason>", RegexOption.IGNORE_CASE), "<think>")
            .replace(Regex("</reason>", RegexOption.IGNORE_CASE), "</think>")

        // Remove any empty thinking blocks
        result = result.replace(Regex("<think>\\s*</think>", RegexOption.IGNORE_CASE), "")

        // Clean up excessive whitespace but preserve paragraph breaks
        result = result
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        return result
    }

    fun clearChat() {
        _messages.value = emptyList()
        _messageHistory.clear()
        _streamingContent.value = ""

        // Clear persisted messages
        viewModelScope.launch {
            chatRepository.clearChatHistory(projectId)
        }
    }

    /**
     * Export chat history to markdown file
     */
    fun exportChatHistory(onResult: (Result<java.io.File>) -> Unit) {
        val projectName = _project.value?.name ?: "Unknown"
        viewModelScope.launch {
            val result = chatRepository.exportToMarkdown(projectId, projectName)
            onResult(result)
        }
    }

    fun setModel(model: AiModel) {
        _currentModel.value = model
        updateContextMaxTokens(model)

        // Persist the last used model for this project
        viewModelScope.launch {
            projectRepository.updateLastUsedModelId(projectId, model.id)
            aiModelRepository.recordModelUsage(model.id)
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Undo the last AI change
     */
    fun undo() {
        viewModelScope.launch {
            undoRedoManager.undo(projectId)
                .onSuccess { message ->
                    // Refresh file tree and current file
                    loadFileTree()
                    _currentFile.value?.let { file ->
                        projectRepository.readFile(projectId, file.path)
                            .onSuccess { content ->
                                _fileContent.value = content
                                _originalFileContent.value = content
                            }
                    }
                }
                .onFailure { error ->
                    _error.value = error.message
                }
        }
    }

    /**
     * Redo the last undone AI change
     */
    fun redo() {
        viewModelScope.launch {
            undoRedoManager.redo(projectId)
                .onSuccess { message ->
                    // Refresh file tree and current file
                    loadFileTree()
                    _currentFile.value?.let { file ->
                        projectRepository.readFile(projectId, file.path)
                            .onSuccess { content ->
                                _fileContent.value = content
                                _originalFileContent.value = content
                            }
                    }
                }
                .onFailure { error ->
                    _error.value = error.message
                }
        }
    }

    /**
     * Clear undo/redo history
     */
    fun clearUndoHistory() {
        undoRedoManager.clearHistory()
    }

    /**
     * Handle AI code edit request from text selection in the code editor
     * Creates a specialized prompt for the AI to edit the selected code
     */
    fun handleAiCodeEdit(request: com.codex.stormy.ui.screens.editor.code.AiCodeEditRequest) {
        // Switch to chat tab to show the AI response
        _selectedTab.value = EditorTab.CHAT

        // Create a specialized prompt for code editing
        val prompt = buildString {
            append("Please help me edit the following code from **${request.fileName}** ")
            append("(lines ${request.startLine + 1}-${request.endLine + 1}):\n\n")
            append("```\n${request.selectedText}\n```\n\n")
            append("What changes would you like me to make to this code?")
        }

        // Set the chat input with the prompt
        _chatInput.value = prompt
    }

    companion object {
        private const val MAX_AGENT_ITERATIONS = 25 // Prevent infinite loops
        private const val STREAMING_UPDATE_INTERVAL_MS = 50L // Debounce interval for streaming updates (20 FPS)

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = CodeXApplication.getInstance()
                val savedStateHandle = extras.createSavedStateHandle()
                return EditorViewModel(
                    savedStateHandle = savedStateHandle,
                    projectRepository = application.projectRepository,
                    preferencesRepository = application.preferencesRepository,
                    aiRepository = application.aiRepository,
                    chatRepository = application.chatRepository,
                    aiModelRepository = application.aiModelRepository,
                    contextWindowManager = application.contextWindowManager,
                    userPreferencesLearner = application.userPreferencesLearner,
                    toolExecutor = application.toolExecutor,
                    memoryStorage = application.memoryStorage,
                    undoRedoManager = application.undoRedoManager,
                    semanticMemorySystem = application.semanticMemorySystem
                ) as T
            }
        }
    /**
     * Helper to log debug info to .codex/logs.txt if enabled
     */
    private fun logDebugToProject(title: String, content: String) {
        if (!_debugLogsEnabled.value) return
        
        val project = _project.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val logsDir = File(project.rootPath, ".codex")
                if (!logsDir.exists()) logsDir.mkdirs()
                
                val logFile = File(logsDir, "logs.txt")
                val timestamp = java.time.LocalDateTime.now().toString()
                
                val output = buildString {
                    append("\n")
                    append("=".repeat(20))
                    append(" $title [$timestamp] ")
                    append("=".repeat(20))
                    append("\n")
                    append(content)
                    append("\n")
                }
                
                logFile.appendText(output)
            } catch (e: Exception) {
                // Ignore logging errors to prevent detailed crashes
            }
        }
    }
}
