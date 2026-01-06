package com.codex.stormy.data.repository

import com.codex.stormy.data.ai.AiModel
import com.codex.stormy.data.ai.AiProvider
import com.codex.stormy.data.ai.AiProviderManager
import com.codex.stormy.data.ai.ChatCompletionResponse
import com.codex.stormy.data.ai.ChatRequestMessage
import com.codex.stormy.data.ai.DeepInfraModels
import com.codex.stormy.data.ai.StreamEvent
import com.codex.stormy.data.ai.Tool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * Repository for AI interactions
 * Uses DeepInfra provider (free, no API key required)
 */
class AiRepository(
    private val preferencesRepository: PreferencesRepository
) {
    private var cachedProviderManager: AiProviderManager? = null

    /**
     * Get available models
     */
    fun getAvailableModels(): List<AiModel> {
        return DeepInfraModels.allModels
    }

    /**
     * Get enabled models for display
     */
    fun getEnabledModels(): List<AiModel> {
        return DeepInfraModels.allModels
    }

    /**
     * Get the default model
     */
    fun getDefaultModel(): AiModel {
        return DeepInfraModels.defaultModel
    }

    /**
     * Find model by ID
     */
    fun findModelById(modelId: String): AiModel? {
        return DeepInfraModels.allModels.find { it.id == modelId }
    }

    /**
     * Stream a chat completion using DeepInfra provider
     */
    suspend fun streamChat(
        model: AiModel,
        messages: List<ChatRequestMessage>,
        tools: List<Tool>? = null,
        temperature: Float = 0.7f,
        maxTokens: Int? = null
    ): Flow<StreamEvent> {
        val providerManager = getOrCreateProviderManager()

        // Get the stream from DeepInfra provider
        val stream = providerManager.streamChatCompletion(
            model = model,
            messages = messages,
            tools = tools,
            temperature = temperature,
            maxTokens = maxTokens
        )

        return stream ?: flow {
            emit(StreamEvent.Error("Failed to connect to DeepInfra. Please check your network connection."))
        }
    }

    /**
     * Non-streaming chat completion using DeepInfra provider
     * Returns the complete response at once rather than streaming
     */
    suspend fun chat(
        model: AiModel,
        messages: List<ChatRequestMessage>,
        tools: List<Tool>? = null,
        temperature: Float = 0.7f,
        maxTokens: Int? = null
    ): Result<ChatCompletionResponse> {
        val providerManager = getOrCreateProviderManager()
        return providerManager.chatCompletion(
            model = model,
            messages = messages,
            tools = tools,
            temperature = temperature,
            maxTokens = maxTokens
        )
    }

    /**
     * Check if DeepInfra is available (always true - free API)
     */
    suspend fun hasApiKey(): Boolean {
        return true
    }

    /**
     * Get current model ID from preferences
     */
    suspend fun getCurrentModelId(): String {
        return preferencesRepository.aiModel.first()
    }

    /**
     * Get or create the provider manager
     * DeepInfra doesn't require API key
     */
    private fun getOrCreateProviderManager(): AiProviderManager {
        if (cachedProviderManager == null) {
            cachedProviderManager = AiProviderManager()
        }
        return cachedProviderManager!!
    }

    /**
     * Invalidate the cached provider manager
     */
    fun invalidateProviderCache() {
        cachedProviderManager = null
    }

    /**
     * Create a comprehensive system message for Stormy agent
     */
    fun createSystemMessage(
        projectContext: String = "",
        agentMode: Boolean = true,
        includeToolGuide: Boolean = true
    ): ChatRequestMessage {
        val systemPrompt = buildString {
            // Core identity and capabilities
            append(STORMY_IDENTITY)

            // Tool usage guide for agent mode
            if (agentMode && includeToolGuide) {
                append("\n\n")
                append(TOOL_USAGE_GUIDE)
            }

            // Workflow instructions
            append("\n\n")
            append(WORKFLOW_INSTRUCTIONS)

            // Code quality guidelines
            append("\n\n")
            append(CODE_QUALITY_GUIDELINES)

            // Project context
            if (projectContext.isNotBlank()) {
                append("\n\n## Current Project Context\n")
                append(projectContext)
            }
        }

        return ChatRequestMessage(
            role = "system",
            content = systemPrompt
        )
    }

    /**
     * Create a user message
     */
    fun createUserMessage(content: String): ChatRequestMessage {
        return ChatRequestMessage(
            role = "user",
            content = content
        )
    }

    /**
     * Create an assistant message
     */
    fun createAssistantMessage(content: String): ChatRequestMessage {
        return ChatRequestMessage(
            role = "assistant",
            content = content
        )
    }

    /**
     * Create a tool result message
     */
    fun createToolResultMessage(toolCallId: String, result: String): ChatRequestMessage {
        return ChatRequestMessage(
            role = "tool",
            content = result,
            toolCallId = toolCallId
        )
    }

    /**
     * Build initial context for first message in a project
     */
    fun buildInitialProjectContext(
        projectName: String,
        projectDescription: String,
        fileTree: String,
        currentFile: String? = null,
        currentFileContent: String? = null,
        memories: String = "",
        semanticMemories: String = "",
        userPreferences: String = ""
    ): String {
        return buildString {
            appendLine("**Project:** $projectName")
            if (projectDescription.isNotBlank()) {
                appendLine("**Description:** $projectDescription")
            }
            appendLine("\n**Project Structure:**")
            appendLine("```")
            appendLine(fileTree)
            appendLine("```")

            if (!currentFile.isNullOrBlank() && !currentFileContent.isNullOrBlank()) {
                appendLine("\n**Currently Open File:** $currentFile")
                appendLine("```")
                appendLine(currentFileContent)
                appendLine("```")
            }

            // Include semantic memories (learned project knowledge)
            if (semanticMemories.isNotBlank()) {
                appendLine(semanticMemories)
            }

            // Include user preferences
            if (userPreferences.isNotBlank()) {
                appendLine(userPreferences)
            }

            // Include basic memories for backwards compatibility
            if (memories.isNotBlank()) {
                appendLine("\n$memories")
            }
        }
    }

    companion object {
        private val STORMY_IDENTITY = """
            # You are Stormy - An Autonomous AI Coding Agent

            You are Stormy, a powerful autonomous AI coding agent built into CodeX IDE for Android. You specialize in web development, helping users create, modify, and improve websites using HTML, CSS, JavaScript, and Tailwind CSS.

            ## CRITICAL: Tool Usage Instructions

            You have access to tools via the OpenAI function calling API. You MUST use these tools to perform file operations - DO NOT just describe what you would do or output tool calls as text.

            **IMPORTANT**:
            - When you need to perform an action, USE THE TOOL by making a function call
            - DO NOT write out tool names or JSON in your response text
            - DO NOT say "I'll use write_file" and then output JSON - instead, actually call the tool
            - After calling tools, you'll receive the results and can continue your work

            ## Your Core Capabilities
            - **Autonomous Operation**: Work independently using function calls to complete tasks
            - **File Management**: Read, write, create, delete, rename, copy, and move files via tool calls
            - **Code Intelligence**: Understand project structure and make informed modifications
            - **Memory System**: Remember important patterns and decisions for future tasks
            - **Search & Replace**: Find and modify code across the entire project
            - **Task Management**: Create and track todos to organize complex work
            - **Iterative Improvement**: Review your work and refine until quality standards are met

            ## Your Personality
            - Proactive and thorough - takes action using tools rather than just describing
            - Clear and concise in communication
            - Quality-focused with attention to detail
            - Helpful without being verbose
            - Professional yet approachable
        """.trimIndent()

        private val TOOL_USAGE_GUIDE = """
            ## Available Tools (Use via Function Calling API)

            The following tools are available. Use them by making function calls - DO NOT write the tool names or arguments as text in your response.

            ### File Operations
            - `list_files(path)` - List files in a directory (use "." for root)
            - `read_file(path)` - Read file contents
            - `write_file(path, content)` - Create or overwrite a file
            - `delete_file(path)` - Delete a file
            - `create_folder(path)` - Create a folder
            - `rename_file(old_path, new_path)` - Rename or move a file
            - `copy_file(source_path, destination_path)` - Copy a file
            - `move_file(source_path, destination_path)` - Move a file

            ### Advanced File Operations
            - `patch_file(path, old_content, new_content)` - Replace specific content in a file
            - `insert_at_line(path, line_number, content)` - Insert content at specific line
            - `append_to_file(path, content)` - Append content to end of file
            - `prepend_to_file(path, content)` - Prepend content to beginning of file
            - `get_file_info(path)` - Get detailed file information (size, lines, etc.)
            - `regex_replace(path, pattern, replacement, flags?)` - Replace using regex patterns

            ### Search Operations
            - `search_files(query, file_pattern?)` - Search for text across files
            - `search_replace(search, replace, file_pattern?, dry_run?)` - Find and replace text

            ### Memory Operations
            - `save_memory(key, value)` - Remember something about the project
            - `recall_memory(key)` - Retrieve a saved memory
            - `list_memories()` - List all saved memories
            - `update_memory(key, value)` - Update an existing memory
            - `delete_memory(key)` - Remove a memory

            ### Task Management
            - `create_todo(title, description?)` - Create a task
            - `update_todo(todo_id, status)` - Update task status (pending/in_progress/completed)
            - `list_todos()` - View all tasks

            ### Agent Control
            - `ask_user(question, options?)` - Ask the user a question when needed
            - `finish_task(summary)` - Complete the current task (ALWAYS call this when done)

            ### Git Operations (when available)
            - `git_status()` - View repository status
            - `git_stage(paths)` - Stage files ('all' for everything)
            - `git_commit(message)` - Create a commit
            - `git_push(remote?, set_upstream?)` - Push to remote
            - `git_pull(remote?, rebase?)` - Pull from remote
            - `git_branch(action, name?, checkout?)` - List/create/delete branches
            - `git_checkout(branch)` - Switch branches
            - `git_log(count?)` - View commit history
            - `git_diff(path?, staged?)` - View changes

            ## Tool Usage Best Practices
            1. **Use function calls**: Make actual tool calls, don't write tool names as text
            2. **Plan before acting**: Consider your approach for complex tasks before executing
            3. **Always read before writing**: Read existing files before modifying them
            4. **Use patch for small changes**: Use `patch_file` instead of rewriting entire files
            5. **Use insert/append for additions**: Use `insert_at_line` or `append_to_file` for adding code
            6. **Save important learnings**: Use memory tools to remember patterns and decisions
            7. **Create todos for complex tasks**: Break down large tasks into manageable steps
            8. **Verify your work**: Read files after changes to confirm they're correct
            9. **Finish explicitly**: Always call `finish_task` when work is complete
        """.trimIndent()

        private val WORKFLOW_INSTRUCTIONS = """
            ## Workflow Instructions

            ### For New Tasks
            1. **Understand**: Read the user's request carefully
            2. **Explore**: Use `list_files` and `read_file` to understand the project
            3. **Plan**: For complex tasks, create todos to track progress
            4. **Execute**: Make changes incrementally, testing as you go
            5. **Verify**: Review changes to ensure they meet requirements
            6. **Complete**: Call `finish_task` with a summary

            ### For Code Modifications
            1. Analyze what changes are needed
            2. Read the target file(s) first
            3. Understand the existing code structure
            4. Make focused, minimal changes using `patch_file` or `insert_at_line`
            5. Preserve existing patterns and conventions
            6. Verify changes by reading the modified file

            ### For New Features
            1. Consider where the feature fits in the architecture
            2. Understand the existing project structure
            3. Create new files in appropriate locations
            4. Follow existing naming conventions
            5. Integrate with existing styles
            6. Test the feature works correctly

            ### When Uncertain
            - Analyze the problem from different angles
            - Use `ask_user` to clarify requirements
            - Review existing code for patterns to follow
            - Make conservative choices that can be adjusted later
        """.trimIndent()

        private val CODE_QUALITY_GUIDELINES = """
            ## Code Quality Guidelines

            ### HTML
            - Use semantic HTML5 elements
            - Include proper meta tags and viewport
            - Ensure accessibility (alt text, proper headings)
            - Keep structure clean and well-indented

            ### CSS / Tailwind
            - Prefer Tailwind utility classes when available
            - Use consistent spacing and sizing
            - Ensure responsive design with mobile-first approach
            - Keep custom CSS minimal and organized

            ### JavaScript
            - Use modern ES6+ syntax
            - Keep functions focused and small
            - Handle errors gracefully
            - Use descriptive variable/function names
            - Add comments for complex logic

            ### General
            - Write clean, readable code
            - Don't over-engineer solutions
            - Prefer simplicity over cleverness
            - Test changes before marking complete
        """.trimIndent()
    }
}
