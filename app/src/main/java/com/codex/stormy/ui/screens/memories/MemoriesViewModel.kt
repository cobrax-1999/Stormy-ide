package com.codex.stormy.ui.screens.memories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codex.stormy.CodeXApplication
import com.codex.stormy.data.ai.memory.MemoryCategory
import com.codex.stormy.data.ai.memory.MemoryImportance
import com.codex.stormy.data.ai.memory.MemorySource
import com.codex.stormy.data.ai.memory.SemanticMemorySystem
import com.codex.stormy.data.repository.ProjectRepository
import com.codex.stormy.domain.model.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Memories screen
 */
data class MemoriesUiState(
    val projectMemories: List<ProjectMemoryState> = emptyList(),
    val selectedProject: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val editingMemory: EditingMemoryState? = null
)

/**
 * State representing a project's memories
 */
data class ProjectMemoryState(
    val projectId: String,
    val projectName: String,
    val memories: List<MemoryState> = emptyList()
)

/**
 * State representing a single memory entry
 */
data class MemoryState(
    val key: String,
    val value: String,
    val timestamp: Long,
    val category: MemoryCategory = MemoryCategory.GENERAL_NOTES
)

/**
 * State for editing a memory
 */
data class EditingMemoryState(
    val projectId: String,
    val key: String,
    val originalValue: String,
    val editedValue: String
)

/**
 * ViewModel for the Memories management screen
 * Handles loading, creating, editing, and deleting AI memories
 */
class MemoriesViewModel(
    private val semanticMemorySystem: SemanticMemorySystem,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MemoriesUiState())
    val uiState: StateFlow<MemoriesUiState> = _uiState.asStateFlow()

    init {
        loadMemories()
    }

    /**
     * Load all memories from all projects
     */
    private fun loadMemories() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Get all projects
                val projects = projectRepository.getAllProjects().first()

                // Load memories for each project
                val projectMemories = projects.map { project: Project ->
                    loadProjectMemories(project)
                }.filter { it.memories.isNotEmpty() || it.projectId == _uiState.value.selectedProject }
                 .sortedByDescending { projectMemory: ProjectMemoryState ->
                     projectMemory.memories.maxOfOrNull { it.timestamp } ?: 0L
                 }

                _uiState.update { state ->
                    state.copy(
                        projectMemories = projectMemories,
                        isLoading = false,
                        error = null
                    )
                }

            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        error = "Failed to load memories: ${e.message}"
                    )
                }
            }
        }
    }

    /**
     * Load memories for a specific project
     */
    private suspend fun loadProjectMemories(project: Project): ProjectMemoryState {
        val semanticMemories = semanticMemorySystem.getAllMemories(project.id)

        // Convert to MemoryState list
        val memories = semanticMemories.map { memory ->
            MemoryState(
                key = memory.key,
                value = memory.value,
                timestamp = memory.createdAt,
                category = memory.category
            )
        }.sortedByDescending { it.timestamp }

        return ProjectMemoryState(
            projectId = project.id,
            projectName = project.name,
            memories = memories
        )
    }

    // Helper for approximate timestamp is no longer needed as SemanticMemory has createdAt


    /**
     * Select a project to expand/collapse
     */
    fun selectProject(projectId: String?) {
        _uiState.update { it.copy(selectedProject = projectId) }
    }

    /**
     * Start editing a memory
     */
    fun startEditingMemory(projectId: String, memory: MemoryState) {
        _uiState.update { state ->
            state.copy(
                editingMemory = EditingMemoryState(
                    projectId = projectId,
                    key = memory.key,
                    originalValue = memory.value,
                    editedValue = memory.value
                )
            )
        }
    }

    /**
     * Cancel editing
     */
    fun cancelEditing() {
        _uiState.update { it.copy(editingMemory = null) }
    }

    /**
     * Save edited memory
     */
    fun saveMemoryEdit(key: String, newValue: String) {
        val editingMemory = _uiState.value.editingMemory ?: return

        viewModelScope.launch {
            try {
            try {
                // If the memory state has a category, use it. Otherwise default.
                // We'll search for the existing memory to find its category if possible
                val category = _uiState.value.editingMemory?.let { 
                     // This is simplified; ideally EditingMemoryState would store the category too.
                     // For now, assume GENERAL_NOTES or try to recall to match? 
                     // Since we are replacing based on Key, we might need the original category.
                     // But SemanticMemorySystem uses Category:Key as ID.
                     // So if we change the key, we might create a new memory.
                     // IMPORTANT: The UI assumes Key is the ID.
                     // Let's stick to GENERAL_NOTES for simplified UI editing or pass category if we can.
                     MemoryCategory.GENERAL_NOTES 
                } ?: MemoryCategory.GENERAL_NOTES

                // Note: SemanticMemorySystem needs Category to update the specific memory.
                // If we don't know the category, we might create a duplicate in GENERAL_NOTES.
                // Improvement: Fetch the memory first to get its category.
                
                // For this fix, let's look up the memory by key first to get its category?
                // Or better, let's assume we are just doing a basic save.
                // But wait, the list view knows the category.
                
                // Let's update `EditingMemoryState` too? No, let's keep it simple for now and rely on future improvements.
                // We'll try to find it in the current UI state to get the category.
                
                val currentProjectMemories = _uiState.value.projectMemories.find { it.projectId == editingMemory.projectId }
                val originalMemory = currentProjectMemories?.memories?.find { it.key == key }
                val targetCategory = originalMemory?.category ?: MemoryCategory.GENERAL_NOTES

                semanticMemorySystem.saveMemory(
                    projectId = editingMemory.projectId,
                    category = targetCategory,
                    key = key,
                    value = newValue,
                    importance = MemoryImportance.MEDIUM,
                    source = MemorySource.USER_EXPLICIT
                )
                _uiState.update { it.copy(editingMemory = null) }
                loadMemories()
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(error = "Failed to save memory: ${e.message}")
                }
            }
        }
    }

    /**
     * Add a new memory to the selected project
     */
    fun addMemory(key: String, value: String) {
        val projectId = _uiState.value.selectedProject ?: return

        viewModelScope.launch {
            try {
                semanticMemorySystem.saveMemory(
                    projectId = projectId,
                    category = MemoryCategory.GENERAL_NOTES,
                    key = key,
                    value = value,
                    importance = MemoryImportance.MEDIUM,
                    source = MemorySource.USER_EXPLICIT
                )
                loadMemories()
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(error = "Failed to add memory: ${e.message}")
                }
            }
        }
    }

    /**
     * Delete a memory
     */
    fun deleteMemory(projectId: String, key: String) {
        viewModelScope.launch {
            try {
                // Find category to delete
                val projectMemories = _uiState.value.projectMemories.find { it.projectId == projectId }
                val memory = projectMemories?.memories?.find { it.key == key }
                val category = memory?.category ?: MemoryCategory.GENERAL_NOTES
                
                semanticMemorySystem.deleteMemory(projectId, category, key)
                loadMemories()
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(error = "Failed to delete memory: ${e.message}")
                }
            }
        }
    }

    /**
     * Clear all memories for a project
     */
    fun clearProjectMemories(projectId: String) {
        viewModelScope.launch {
            try {
                semanticMemorySystem.clearProjectMemories(projectId)
                loadMemories()
            } catch (e: Exception) {
                _uiState.update { state ->
                    state.copy(error = "Failed to clear memories: ${e.message}")
                }
            }
        }
    }

    /**
     * Clear error message
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val application = CodeXApplication.getInstance()
                return MemoriesViewModel(
                    semanticMemorySystem = application.semanticMemorySystem,
                    projectRepository = application.projectRepository
                ) as T
            }
        }
    }
}
