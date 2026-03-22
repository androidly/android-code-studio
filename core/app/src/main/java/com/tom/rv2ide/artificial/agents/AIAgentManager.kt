/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.tom.rv2ide.artificial.agents

import android.content.Context
import com.tom.rv2ide.artificial.agents.google.Gemini
import com.tom.rv2ide.artificial.agents.openai.OpenAI
import com.tom.rv2ide.artificial.agents.anthropic.Anthropic
import com.tom.rv2ide.artificial.agents.custom.CustomProviderAgent
import com.tom.rv2ide.artificial.agents.grok.Grok
import com.tom.rv2ide.artificial.agents.deepseek.DeepSeek
import com.tom.rv2ide.artificial.agents.local.LocalLLM
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.parser.SnippetParser
import com.tom.rv2ide.artificial.permissions.AIPermissionManager
import com.tom.rv2ide.artificial.project.awareness.ProjectData
import com.tom.rv2ide.artificial.secrets.ApiKey
import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolCallParser
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
import com.tom.rv2ide.artificial.tools.AIToolExecutor
import java.io.File
import kotlinx.coroutines.delay
import com.tom.rv2ide.artificial.dialogs.ProviderSwitchDialog
import kotlin.math.max
import kotlin.math.min

class AIAgentManager(private val context: Context) {

    companion object {
        private const val MAX_SESSION_TURNS = 12
        private const val MAX_SESSION_TEXT_CHARS = 1200
        private val LOOP_SENSITIVE_TOOL_NAMES = setOf(
            "build_project",
            "run_terminal_command",
            "replace_file_range"
        )
        private val sessionStore = LinkedHashMap<String, MutableList<AgentSessionTurn>>()
    }

    private val appContext = context.applicationContext
    private val sessionPersistenceStore = AIAgentSessionStore(appContext)
    private val snippetParser = SnippetParser()
    private val permissionManager = AIPermissionManager(appContext)
    private val toolExecutor = AIToolExecutor(
        context = appContext,
        projectRootProvider = { currentProjectRoot },
        writeFile = { filePath, content ->
            currentAgent?.writeFile(filePath, content) ?: FileWriteResult.Error("No agent initialized")
        },
        recordModification = { filePath, oldContent, newContent, success ->
            currentAgent?.recordModification(filePath, oldContent, newContent, success)
        }
    )
    private var currentProjectRoot: File? = null
    private var currentProviderId: String = Agents(appContext).getProvider()
    private var currentAgent: AIAgent? = null
    private var restoredPersistentSessionKey: String? = null
    private val providerSwitchDialog = ProviderSwitchDialog(appContext)
    init {
        Gemini.registerAgent()
        OpenAI.registerAgent()
        Anthropic.registerAgent()
        Grok.registerAgent()
        DeepSeek.registerAgent()
        LocalLLM.registerAgent()
        CustomProviderAgent.registerAgent()
        
        permissionManager.setFileWriteEnabled(true)
        permissionManager.setRequireConfirmation(false)
        
        if (!setProvider(currentProviderId)) {
            AIAgentRegistry.getAvailableProviders()
                .firstOrNull()
                ?.takeIf { it != currentProviderId }
                ?.let { fallbackProvider ->
                    currentProviderId = fallbackProvider
                    setProvider(fallbackProvider)
                }
        }
    }
    
    fun getCurrentAgent(): AIAgent? = currentAgent

    fun setProvider(providerId: String): Boolean {
        android.util.Log.d("AIAgentManager", "setProvider called with: $providerId")
        
        val factory = AIAgentRegistry.getFactory(providerId)
        if (factory == null) {
            android.util.Log.e("AIAgentManager", "No factory found for provider: $providerId")
            return false
        }
        
        if (!factory.hasValidApiKey()) {
            android.util.Log.e("AIAgentManager", "No valid API key for provider: $providerId")
            return false
        }
        
        currentProviderId = providerId
        currentAgent = factory.create(appContext)
        restoredPersistentSessionKey = null
        android.util.Log.d("AIAgentManager", "Agent created: ${currentAgent != null}")
        
        factory.getApiKey()?.let { apiKey ->
            android.util.Log.d("AIAgentManager", "Initializing agent with API key")
            currentAgent?.initialize(apiKey, appContext)
            currentAgent?.setContext(appContext)
            
            currentProjectRoot?.let { root ->
                val projectData = ProjectData(appContext)
                val projectTree = projectData.showProjectTree(root)
                currentAgent?.setProjectData(projectTree)
            }
            
            android.util.Log.d("AIAgentManager", "Agent initialized: ${currentAgent?.isInitialized()}")
        }

        restoreCurrentSessionState(force = true)
        
        return currentAgent?.isInitialized() ?: false
    }

    fun getCurrentProviderId(): String = currentProviderId

    fun getCurrentSessionStorageKey(): String = currentSessionKey()
    
    fun getCurrentProviderName(): String {
        return currentAgent?.providerName ?: "Unknown"
    }
    
    fun getAvailableProviders(): List<ProviderInfo> {
        return AIAgentRegistry.getAvailableProviders().mapNotNull { providerId ->
            val factory = AIAgentRegistry.getFactory(providerId)
            val agent = factory?.create(context)
            agent?.let {
                ProviderInfo(
                    id = it.providerId,
                    name = it.providerName,
                    isAvailable = factory.hasValidApiKey()
                )
            }
        }
    }

    fun setProjectRoot(projectPath: String): Boolean {
        val projectRoot = File(projectPath)
        if (!projectRoot.exists()) return false

        currentProjectRoot = projectRoot
        val projectData = ProjectData(appContext)
        val projectTree = projectData.showProjectTree(projectRoot)

        currentAgent?.setProjectData(projectTree)
        permissionManager.addAllowedDirectory(projectRoot.absolutePath)
        restoredPersistentSessionKey = null
        restoreCurrentSessionState(force = true)

        return true
    }

    fun clearConversation() {
        val sessionKey = currentSessionKey()
        currentAgent?.clearConversation()
        currentSessionTurns().clear()
        sessionPersistenceStore.clear(sessionKey)
        restoredPersistentSessionKey = sessionKey
    }

    private fun currentSessionTurns(): MutableList<AgentSessionTurn> {
        val key = currentSessionKey()
        return sessionStore.getOrPut(key) { mutableListOf() }
    }

    private fun currentSessionKey(): String {
        val projectKey = currentProjectRoot?.absolutePath ?: "__global__"
        return "$projectKey|${buildProviderSessionIdentity()}"
    }

    private fun buildProviderSessionIdentity(): String {
        val providerKey = currentAgent?.providerId ?: currentProviderId
        val fingerprint = currentPersistentConversationAgent()
            ?.persistentConversationFingerprint()
            ?.trim()
            .orEmpty()
        return if (fingerprint.isBlank()) {
            providerKey
        } else {
            "$providerKey|$fingerprint"
        }
    }

    private fun currentNativeToolAgent(): NativeToolCallingAgent? {
        return currentAgent as? NativeToolCallingAgent
    }

    private fun currentPersistentConversationAgent(): PersistentConversationAgent? {
        return currentAgent as? PersistentConversationAgent
    }

    private fun recordSessionTurn(role: AgentSessionRole, content: String) {
        val normalized = content.trim()
        if (normalized.isBlank()) {
            return
        }

        val sessionTurns = currentSessionTurns()
        sessionTurns += AgentSessionTurn(
            role = role,
            content = normalized.take(MAX_SESSION_TEXT_CHARS)
        )
        while (sessionTurns.size > MAX_SESSION_TURNS) {
            sessionTurns.removeAt(0)
        }
        persistCurrentSessionState()
    }

    private fun buildSessionContext(): String {
        val turns = currentSessionTurns().takeLast(MAX_SESSION_TURNS)
        if (turns.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("=== SESSION MEMORY ===")
            appendLine("Continue the same coding task unless the user clearly changes direction.")
            turns.forEachIndexed { index, turn ->
                append(index + 1)
                append(". ")
                append(turn.role.label)
                append(": ")
                appendLine(turn.content)
            }
        }.trim()
    }

    private fun summarizeAssistantResponse(
        response: String,
        summary: ModificationSummary? = null
    ): String {
        if (response.contains("FILE_TO_MODIFY:")) {
            return buildString {
                append("Prepared file changes")
                summary?.let {
                    append(": ${it.successfulFiles}/${it.totalFiles} files applied")
                    if (it.failedFiles > 0) {
                        append(", ${it.failedFiles} failed")
                    }
                }
            }
        }

        return response.trim().take(MAX_SESSION_TEXT_CHARS)
    }

    private fun summarizeToolResult(toolResult: AIToolExecutionResult): String {
        return buildString {
            append(toolResult.toolName)
            append(": ")
            append(toolResult.summary)
            toolResult.exitCode?.let { append(" (exit=$it)") }
            toolResult.executedCommand?.takeIf { it.isNotBlank() }?.let {
                append(" | cmd=")
                append(it.take(240))
            }
        }.take(MAX_SESSION_TEXT_CHARS)
    }

    private fun summarizeModificationResults(results: List<ModificationResult>): String {
        if (results.isEmpty()) {
            return ""
        }

        val fileSummary = results.joinToString(", ") { result ->
            val action = if (result.isNewFile) "created" else "updated"
            "${File(result.filePath).name} ($action)"
        }
        return "Changed files: $fileSummary".take(MAX_SESSION_TEXT_CHARS)
    }

    private fun isNonRetryableToolError(error: Throwable): Boolean {
        return error is ToolCallLoopException ||
            error is ToolProtocolException ||
            error is ToolExecutionDisabledException
    }

    suspend fun executeRequest(userRequest: String, callback: AIAgentCallback) {
        var success = false
        var providerSwitched = false

        restoreCurrentSessionState()
        currentAgent?.resetAttemptCount()
        recordSessionTurn(AgentSessionRole.USER, userRequest)
        callback.onProcessing("Analyzing your request...")

        while (!success && (currentAgent?.canRetry() == true)) {
            try {
                val currentAttempt = currentAgent?.getCurrentAttemptCount() ?: 0

                if (currentAttempt > 0 && !providerSwitched) {
                    callback.onRetry(currentAttempt, "Thinking differently...")
                    delay(1000)
                }

                val result = resolveAgentResponse(userRequest, callback)

                result.fold(
                    onSuccess = { resolvedResponse ->
                        val response = resolvedResponse.response
                        val modifications = resolvedResponse.toolModifications.toMutableList()

                        if (response.contains("FILE_TO_MODIFY:")) {
                            callback.onProcessing("Modifying files...")
                            modifications += processModifications(response, callback)
                        }

                        if (modifications.isNotEmpty()) {
                            val allSuccessful = modifications.all { it.writeResult is FileWriteResult.Success }

                            if (allSuccessful) {
                                val results = buildModificationResults(modifications)
                                val summary = createSummary(results)
                                recordSessionTurn(AgentSessionRole.ASSISTANT, summarizeAssistantResponse(response, summary))
                                recordSessionTurn(AgentSessionRole.CHANGE, summarizeModificationResults(results))
                                callback.onSuccess(response, results, summary)
                                success = true
                            } else {
                                callback.onProcessing("Some files failed. Retrying...")
                                currentAgent?.incrementAttemptCount()
                                delay(1500)
                            }
                        } else if (response.contains("FILE_TO_MODIFY:")) {
                            callback.onProcessing("No files were modified. Retrying...")
                            currentAgent?.incrementAttemptCount()
                            delay(1500)
                        } else {
                            val summary = ModificationSummary(0, 0, 0, 0, 0, emptyList())
                            recordSessionTurn(AgentSessionRole.ASSISTANT, summarizeAssistantResponse(response, summary))
                            callback.onTextResponse(response, summary)
                            success = true
                        }
                    },
                  onFailure = { error ->
                      android.util.Log.e("AIAgentManager", "Error occurred: ${error.message}", error)
                      
                      val shouldSwitchProvider = error is com.tom.rv2ide.artificial.exceptions.RateLimitException ||
                                                error is com.tom.rv2ide.artificial.exceptions.QuotaExceededException ||
                                                error is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException ||
                                                error is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException
                      
                      if (shouldSwitchProvider && !providerSwitched) {
                          val currentProviderName = currentAgent?.providerName ?: "Unknown"
                          val errorMsg = error.message ?: "Unknown error"
                          
                          if (providerSwitchDialog.isAutoSwitchEnabled()) {
                              val alternativeProvider = getAlternativeProvider()
                              if (alternativeProvider != null) {
                                  callback.onProcessing("⚠️ $currentProviderName: $errorMsg")
                                  callback.onProcessing("🔄 Auto-switching to another provider...")
                                  delay(1500)
                                  
                                  if (setProvider(alternativeProvider)) {
                                      providerSwitched = true
                                      currentAgent?.resetAttemptCount()
                                      
                                      val newProviderName = currentAgent?.providerName ?: "Unknown"
                                      callback.onProcessing("✅ Switched to $newProviderName")
                                  } else {
                                      val errorDisplay = formatErrorMessage(error)
                                      callback.onError("$errorDisplay\n\n❌ Failed to switch providers.")
                                      success = true
                                  }
                              } else {
                                  val errorDisplay = formatErrorMessage(error)
                                  callback.onError("$errorDisplay\n\n❌ No alternative providers available.")
                                  success = true
                              }
                          } else {
                              val errorDisplay = formatErrorMessage(error)
                              callback.onError("PROVIDER_SWITCH_REQUIRED::$errorDisplay")
                              success = true
                          }
                      } else if (
                          (currentAgent?.canRetry() == true) &&
                          !providerSwitched &&
                          !isNonRetryableToolError(error)
                      ) {
                          callback.onRetry(
                              currentAgent?.getCurrentAttemptCount() ?: 0,
                              "Error: ${error.message?.take(50) ?: "Unknown error"}. Retrying..."
                          )
                          currentAgent?.incrementAttemptCount()
                          delay(1500)
                      } else {
                          val errorDisplay = formatErrorMessage(error)
                          callback.onError(errorDisplay)
                          success = true
                      }
                  }
                )
            } catch (e: Exception) {
                android.util.Log.e("AIAgentManager", "Exception occurred: ${e.message}", e)
                
                if ((currentAgent?.canRetry() == true) && !isNonRetryableToolError(e)) {
                    callback.onRetry(
                        currentAgent?.getCurrentAttemptCount() ?: 0,
                        "Exception: ${e.message?.take(50) ?: "Unknown"}. Trying again..."
                    )
                    currentAgent?.incrementAttemptCount()
                    delay(1500)
                } else {
                    val errorDisplay = formatErrorMessage(e)
                    callback.onError(errorDisplay)
                    success = true
                }
            }
        }

        if (!success) {
          val attemptCount = currentAgent?.getCurrentAttemptCount() ?: 0
          val agentName = currentAgent?.providerName ?: "No agent initialized"
          callback.onError("Failed after $attemptCount attempts with $agentName.\n\nPlease check your API key and try again.")
          undoLastModification()
        }
    }

    private fun getAlternativeProvider(): String? {
        val availableProviders = AIAgentRegistry.getAvailableProviders()
        return availableProviders.firstOrNull { it != currentProviderId }
    }

    private fun restoreCurrentSessionState(force: Boolean = false) {
        val sessionKey = currentSessionKey()
        if (!force && restoredPersistentSessionKey == sessionKey) {
            return
        }

        val persisted = sessionPersistenceStore.load(sessionKey)
        val restoredTurns = when {
            persisted != null -> persisted.sessionTurns.mapNotNull(::restoreAgentSessionTurn)
            sessionStore.containsKey(sessionKey) -> sessionStore[sessionKey].orEmpty()
            else -> emptyList()
        }
        sessionStore[sessionKey] = restoredTurns.toMutableList()

        currentPersistentConversationAgent()?.let { agent ->
            val serializedState = persisted?.agentState?.trim().orEmpty()
            if (serializedState.isNotBlank()) {
                runCatching {
                    agent.importPersistentConversationState(serializedState)
                }.onFailure { error ->
                    android.util.Log.w(
                        "AIAgentManager",
                        "Failed to restore persistent conversation state for $sessionKey",
                        error
                    )
                    agent.clearConversation()
                    sessionStore[sessionKey] = mutableListOf()
                    sessionPersistenceStore.clear(sessionKey)
                }
            } else if (force || restoredPersistentSessionKey != sessionKey) {
                agent.clearConversation()
            }
        }

        restoredPersistentSessionKey = sessionKey
    }

    private fun persistCurrentSessionState() {
        val sessionKey = currentSessionKey()
        val turns = currentSessionTurns().map { turn ->
            PersistedAgentSessionTurn(
                role = turn.role.label,
                content = turn.content
            )
        }
        val agentState = currentPersistentConversationAgent()
            ?.exportPersistentConversationState()
            ?.takeIf { it.isNotBlank() }
        sessionPersistenceStore.save(
            sessionKey = sessionKey,
            sessionTurns = turns,
            agentState = agentState
        )
        restoredPersistentSessionKey = sessionKey
    }

    private suspend fun processModifications(
        response: String,
        callback: AIAgentCallback
    ): List<BaseFileModification> {
        val modifications = mutableListOf<BaseFileModification>()
        val parser = SnippetParser()

        if (response.contains("FILE_TO_MODIFY:")) {
            val lines = response.lines()
            var currentFile: String? = null
            val contentBuilder = StringBuilder()
            var inContent = false

            for (line in lines) {
                if (line.startsWith("FILE_TO_MODIFY:")) {
                    if (currentFile != null && contentBuilder.isNotEmpty()) {
                        val fileName = File(currentFile).name
                        callback.onFileModifying(currentFile, fileName)

                        val rawContent = contentBuilder.toString().trim()
                        val cleanedContent = parser.cleanFileContent(rawContent)
                        val previousContent = readCurrentFileContent(currentFile)

                        val writeResult = currentAgent?.writeFile(currentFile, cleanedContent)
                            ?: FileWriteResult.Error("No agent initialized")

                        val success = writeResult is FileWriteResult.Success
                        currentAgent?.recordModification(currentFile, previousContent, cleanedContent, success)

                        callback.onFileModified(currentFile, fileName, success)
                        delay(300)

                        modifications.add(
                            BaseFileModification(
                                filePath = currentFile,
                                content = cleanedContent,
                                writeResult = writeResult,
                                previousContent = previousContent
                            )
                        )
                    }

                    currentFile = line.substringAfter("FILE_TO_MODIFY:").trim()
                    contentBuilder.clear()
                    inContent = true
                } else if (inContent) {
                    contentBuilder.append(line).append("\n")
                }
            }

            if (currentFile != null && contentBuilder.isNotEmpty()) {
                val fileName = File(currentFile).name
                callback.onFileModifying(currentFile, fileName)

                val rawContent = contentBuilder.toString().trim()
                val cleanedContent = parser.cleanFileContent(rawContent)
                val previousContent = readCurrentFileContent(currentFile)

                val writeResult = currentAgent?.writeFile(currentFile, cleanedContent)
                    ?: FileWriteResult.Error("No agent initialized")

                val success = writeResult is FileWriteResult.Success
                currentAgent?.recordModification(currentFile, previousContent, cleanedContent, success)

                callback.onFileModified(currentFile, fileName, success)
                delay(300)

                modifications.add(
                    BaseFileModification(
                        filePath = currentFile,
                        content = cleanedContent,
                        writeResult = writeResult,
                        previousContent = previousContent
                    )
                )
            }
        }

        return modifications
    }

    private suspend fun resolveAgentResponse(
        userRequest: String,
        callback: AIAgentCallback
    ): Result<ResolvedAgentResponse> {
        currentNativeToolAgent()?.let { nativeAgent ->
            return resolveNativeToolAgentResponse(
                nativeAgent = nativeAgent,
                userRequest = userRequest,
                callback = callback
            )
        }

        val toolResults = mutableListOf<AIToolExecutionResult>()
        val toolModifications = mutableListOf<BaseFileModification>()
        val toolSignatureHistory = mutableListOf<String>()
        val toolOutcomeHistory = mutableListOf<ToolExecutionFingerprint>()
        var successfulFileChangeCount = 0

        while (true) {
            val context = buildToolContext(toolResults)
            val previewBuffer = StringBuilder()
            var assistantPreviewStarted = false
            var assistantPreviewSuppressed = false

            val result = currentAgent?.generateCodeStreaming(
                prompt = userRequest,
                context = context,
                language = "kotlin",
                projectStructure = null,
                listener = object : AIAgentStreamListener {
                    override fun onTextDelta(delta: String) {
                        if (delta.isEmpty()) {
                            return
                        }

                        previewBuffer.append(delta)
                        if (assistantPreviewSuppressed) {
                            return
                        }

                        if (!assistantPreviewStarted) {
                            val decision = resolveAssistantPreviewDecision(previewBuffer.toString())
                            when (decision) {
                                AssistantPreviewDecision.WAIT -> return
                                AssistantPreviewDecision.SUPPRESS -> {
                                    assistantPreviewSuppressed = true
                                    return
                                }
                                AssistantPreviewDecision.SHOW -> {
                                    assistantPreviewStarted = true
                                    callback.onAssistantTextStarted()
                                    callback.onAssistantTextDelta(previewBuffer.toString())
                                    previewBuffer.clear()
                                    return
                                }
                            }
                        }

                        callback.onAssistantTextDelta(delta)
                    }
                }
            ) ?: Result.failure(Exception("No agent initialized"))

            result.exceptionOrNull()?.let { return Result.failure(it) }
            val response = result.getOrNull() ?: return Result.failure(Exception("Empty AI response"))

            if (!assistantPreviewStarted && !assistantPreviewSuppressed) {
                when (resolveAssistantPreviewDecision(response, isFinalResponse = true)) {
                    AssistantPreviewDecision.SHOW -> {
                        callback.onAssistantTextStarted()
                        callback.onAssistantTextDelta(response)
                        assistantPreviewStarted = true
                    }
                    AssistantPreviewDecision.SUPPRESS,
                    AssistantPreviewDecision.WAIT -> Unit
                }
            }
            if (assistantPreviewStarted) {
                callback.onAssistantTextFinished(response)
            }

            val toolCalls = AIToolCallParser.parseToolCalls(response)
            if (toolCalls.isEmpty()) {
                return Result.success(
                    ResolvedAgentResponse(
                        response = response,
                        toolModifications = toolModifications.toList(),
                        toolResults = toolResults.toList()
                    )
                )
            }

            if (!permissionManager.isToolExecutionEnabled()) {
                return Result.failure(
                    ToolExecutionDisabledException(
                        "AI requested a tool, but AI Tool Execution is disabled. Enable it in AI preferences first."
                    )
                )
            }

            if (response.contains("FILE_TO_MODIFY:")) {
                return Result.failure(
                    ToolProtocolException(
                        "The model mixed TOOL_CALL and FILE_TO_MODIFY in one response. It must finish tool requests before sending edits."
                    )
                )
            }

            val toolSignature = buildToolCallSignature(toolCalls)
            detectToolLoop(toolSignatureHistory, toolSignature)?.let { loopMessage ->
                return Result.success(
                    finalizeToolRunWithCurrentContext(
                        userRequest = userRequest,
                        callback = callback,
                        toolResults = toolResults,
                        toolModifications = toolModifications,
                        loopReason = loopMessage
                    )
                )
            }
            toolSignatureHistory += toolSignature

            callback.onProcessing("Executing ${toolCalls.size} tool request(s)...")

            toolCalls.forEach { toolCall ->
                callback.onToolCallStarted(toolCall)
                callback.onProcessing(formatToolStartMessage(toolCall))
                val toolResult = toolExecutor.execute(toolCall) { chunk ->
                    callback.onToolCallOutput(toolCall, chunk)
                }
                detectToolResultLoop(
                    toolOutcomeHistory = toolOutcomeHistory,
                    toolResult = toolResult,
                    successfulFileChangeCount = successfulFileChangeCount
                )?.let { loopMessage ->
                    return Result.success(
                        finalizeToolRunWithCurrentContext(
                            userRequest = userRequest,
                            callback = callback,
                            toolResults = toolResults + toolResult,
                            toolModifications = toolModifications,
                            loopReason = loopMessage
                        )
                    )
                }
                toolResults += toolResult
                toolOutcomeHistory += buildToolExecutionFingerprint(toolResult, successfulFileChangeCount)
                recordSessionTurn(AgentSessionRole.TOOL, summarizeToolResult(toolResult))
                toolResult.fileChange?.let { fileChange ->
                    val fileName = File(fileChange.filePath).name
                    callback.onFileModifying(fileChange.filePath, fileName)
                    val writeSuccessful = fileChange.writeResult is FileWriteResult.Success
                    callback.onFileModified(fileChange.filePath, fileName, writeSuccessful)
                    if (writeSuccessful) {
                        successfulFileChangeCount += 1
                    }
                    toolModifications += BaseFileModification(
                        filePath = fileChange.filePath,
                        content = fileChange.newContent,
                        writeResult = fileChange.writeResult,
                        previousContent = fileChange.previousContent
                    )
                }
                callback.onToolCallCompleted(toolResult)
                callback.onProcessing(formatToolEndMessage(toolResult))
            }
        }
    }

    private suspend fun resolveNativeToolAgentResponse(
        nativeAgent: NativeToolCallingAgent,
        userRequest: String,
        callback: AIAgentCallback
    ): Result<ResolvedAgentResponse> {
        val toolResults = mutableListOf<AIToolExecutionResult>()
        val toolModifications = mutableListOf<BaseFileModification>()
        val toolSignatureHistory = mutableListOf<String>()
        val toolOutcomeHistory = mutableListOf<ToolExecutionFingerprint>()
        var successfulFileChangeCount = 0
        var pendingToolResults = emptyList<NativeToolResult>()

        var round = 0
        try {
            while (true) {
                val previewBuffer = StringBuilder()
                var assistantPreviewStarted = false
                var assistantPreviewSuppressed = false

                val result = if (round == 0) {
                    nativeAgent.startToolSessionTurn(
                        prompt = userRequest,
                        toolExecutionEnabled = permissionManager.isToolExecutionEnabled(),
                        listener = object : AIAgentStreamListener {
                            override fun onTextDelta(delta: String) {
                                if (delta.isEmpty()) {
                                    return
                                }

                                previewBuffer.append(delta)
                                if (assistantPreviewSuppressed) {
                                    return
                                }

                                if (!assistantPreviewStarted) {
                                    val decision = resolveNativeAssistantPreviewDecision(previewBuffer.toString())
                                    when (decision) {
                                        AssistantPreviewDecision.WAIT -> return
                                        AssistantPreviewDecision.SUPPRESS -> {
                                            assistantPreviewSuppressed = true
                                            return
                                        }
                                        AssistantPreviewDecision.SHOW -> {
                                            assistantPreviewStarted = true
                                            callback.onAssistantTextStarted()
                                            callback.onAssistantTextDelta(previewBuffer.toString())
                                            previewBuffer.clear()
                                            return
                                        }
                                    }
                                }

                                callback.onAssistantTextDelta(delta)
                            }
                        }
                    )
                } else {
                    nativeAgent.continueToolSessionTurn(
                        toolResults = pendingToolResults,
                        toolExecutionEnabled = permissionManager.isToolExecutionEnabled(),
                        listener = object : AIAgentStreamListener {
                            override fun onTextDelta(delta: String) {
                                if (delta.isEmpty()) {
                                    return
                                }

                                previewBuffer.append(delta)
                                if (assistantPreviewSuppressed) {
                                    return
                                }

                                if (!assistantPreviewStarted) {
                                    val decision = resolveNativeAssistantPreviewDecision(previewBuffer.toString())
                                    when (decision) {
                                        AssistantPreviewDecision.WAIT -> return
                                        AssistantPreviewDecision.SUPPRESS -> {
                                            assistantPreviewSuppressed = true
                                            return
                                        }
                                        AssistantPreviewDecision.SHOW -> {
                                            assistantPreviewStarted = true
                                            callback.onAssistantTextStarted()
                                            callback.onAssistantTextDelta(previewBuffer.toString())
                                            previewBuffer.clear()
                                            return
                                        }
                                    }
                                }

                                callback.onAssistantTextDelta(delta)
                            }
                        }
                    )
                }

                result.exceptionOrNull()?.let {
                    nativeAgent.abortActiveToolSessionTurn()
                    return Result.failure(it)
                }

                val response = result.getOrNull()
                    ?: run {
                        nativeAgent.abortActiveToolSessionTurn()
                        return Result.failure(Exception("Empty AI response"))
                    }

                if (!assistantPreviewStarted && !assistantPreviewSuppressed) {
                    when (resolveNativeAssistantPreviewDecision(response.assistantText, isFinalResponse = true)) {
                        AssistantPreviewDecision.SHOW -> {
                            callback.onAssistantTextStarted()
                            callback.onAssistantTextDelta(response.assistantText)
                            assistantPreviewStarted = true
                        }
                        AssistantPreviewDecision.SUPPRESS,
                        AssistantPreviewDecision.WAIT -> Unit
                    }
                }
                if (assistantPreviewStarted) {
                    callback.onAssistantTextFinished(response.assistantText)
                }

                val toolCalls = response.toolCalls
                if (toolCalls.isEmpty()) {
                    return Result.success(
                        ResolvedAgentResponse(
                            response = response.assistantText,
                            toolModifications = toolModifications.toList(),
                            toolResults = toolResults.toList()
                        )
                    )
                }

                if (!permissionManager.isToolExecutionEnabled()) {
                    nativeAgent.abortActiveToolSessionTurn()
                    return Result.failure(
                        ToolExecutionDisabledException(
                            "AI requested a tool, but AI Tool Execution is disabled. Enable it in AI preferences first."
                        )
                    )
                }

                val toolSignature = buildToolCallSignature(toolCalls)
                detectToolLoop(toolSignatureHistory, toolSignature)?.let { loopMessage ->
                    nativeAgent.abortActiveToolSessionTurn()
                    return Result.success(
                        finalizeToolRunWithCurrentContext(
                            userRequest = userRequest,
                            callback = callback,
                            toolResults = toolResults,
                            toolModifications = toolModifications,
                            loopReason = loopMessage
                        )
                    )
                }
                toolSignatureHistory += toolSignature

                callback.onProcessing("Executing ${toolCalls.size} tool request(s)...")

                val roundToolResults = mutableListOf<NativeToolResult>()
                toolCalls.forEachIndexed { index, toolCall ->
                    callback.onToolCallStarted(toolCall)
                    callback.onProcessing(formatToolStartMessage(toolCall))
                    val toolResult = toolExecutor.execute(toolCall) { chunk ->
                        callback.onToolCallOutput(toolCall, chunk)
                    }
                    detectToolResultLoop(
                        toolOutcomeHistory = toolOutcomeHistory,
                        toolResult = toolResult,
                        successfulFileChangeCount = successfulFileChangeCount
                    )?.let { loopMessage ->
                        nativeAgent.abortActiveToolSessionTurn()
                        return Result.success(
                            finalizeToolRunWithCurrentContext(
                                userRequest = userRequest,
                                callback = callback,
                                toolResults = toolResults + toolResult,
                                toolModifications = toolModifications,
                                loopReason = loopMessage
                            )
                        )
                    }
                    toolResults += toolResult
                    toolOutcomeHistory += buildToolExecutionFingerprint(toolResult, successfulFileChangeCount)
                    recordSessionTurn(AgentSessionRole.TOOL, summarizeToolResult(toolResult))
                    toolResult.fileChange?.let { fileChange ->
                        val fileName = File(fileChange.filePath).name
                        callback.onFileModifying(fileChange.filePath, fileName)
                        val writeSuccessful = fileChange.writeResult is FileWriteResult.Success
                        callback.onFileModified(fileChange.filePath, fileName, writeSuccessful)
                        if (writeSuccessful) {
                            successfulFileChangeCount += 1
                        }
                        toolModifications += BaseFileModification(
                            filePath = fileChange.filePath,
                            content = fileChange.newContent,
                            writeResult = fileChange.writeResult,
                            previousContent = fileChange.previousContent
                        )
                    }
                    callback.onToolCallCompleted(toolResult)
                    callback.onProcessing(formatToolEndMessage(toolResult))
                    roundToolResults += NativeToolResult(
                        toolCallId = toolCall.callId ?: "native_call_${round + 1}_${index + 1}",
                        toolName = toolCall.name,
                        output = toolResult.toToolResultPayload(),
                        isError = !toolResult.success
                    )
                }
                pendingToolResults = roundToolResults.toList()
                round += 1
            }
        } catch (error: Exception) {
            nativeAgent.abortActiveToolSessionTurn()
            return Result.failure(error)
        }
    }

    private fun buildToolCallSignature(toolCalls: List<AIToolCall>): String {
        return toolCalls.joinToString(separator = "||") { toolCall ->
            val normalizedArgs = toolCall.arguments.entries
                .sortedBy { it.key }
                .joinToString(separator = "&") { (key, value) ->
                    "$key=${normalizeToolArgument(value)}"
                }
            "${toolCall.name.lowercase()}::$normalizedArgs"
        }
    }

    private fun normalizeToolArgument(value: String): String {
        return value.trim().replace(Regex("\\s+"), " ")
    }

    private fun detectToolLoop(
        toolSignatureHistory: List<String>,
        nextSignature: String
    ): String? {
        val consecutiveRepeatCount = toolSignatureHistory
            .asReversed()
            .takeWhile { it == nextSignature }
            .size
        if (consecutiveRepeatCount >= 2) {
            return "The model repeated the same tool request three times in a row. Stopping to avoid a tool loop."
        }

        if (toolSignatureHistory.count { it == nextSignature } >= 2) {
            return "The model requested the same tool sequence three times in one run. Stopping to avoid a loop."
        }

        val extendedHistory = toolSignatureHistory + nextSignature
        if (extendedHistory.size >= 4) {
            val lastFour = extendedHistory.takeLast(4)
            if (lastFour[0] == lastFour[2] && lastFour[1] == lastFour[3]) {
                return "The model entered a repeating two-step tool loop. Stopping before it keeps alternating the same requests."
            }
        }

        return null
    }

    private fun detectToolResultLoop(
        toolOutcomeHistory: List<ToolExecutionFingerprint>,
        toolResult: AIToolExecutionResult,
        successfulFileChangeCount: Int
    ): String? {
        val current = buildToolExecutionFingerprint(toolResult, successfulFileChangeCount)
        if (toolResult.blockedBySafetyPolicy) {
            val blockedRepeatCount = toolOutcomeHistory.count { previous ->
                !previous.success &&
                    previous.toolName == current.toolName &&
                    previous.normalizedCommand == current.normalizedCommand &&
                    previous.failureFingerprint == current.failureFingerprint &&
                    previous.successfulFileChangeCount == successfulFileChangeCount
            }
            if (blockedRepeatCount >= 2) {
                val commandPreview = current.normalizedCommand
                    .takeIf { it.isNotBlank() }
                    ?.take(160)
                    ?.let { " [$it]" }
                    .orEmpty()
                return "The model kept retrying the same safety-blocked command$commandPreview without changing approach. Stopping before it burns more tool rounds."
            }
            return null
        }

        if (!current.success && current.toolName in LOOP_SENSITIVE_TOOL_NAMES) {
            val lastMatchingFailure = toolOutcomeHistory.lastOrNull { previous ->
                !previous.success &&
                    previous.toolName == current.toolName &&
                    previous.normalizedCommand == current.normalizedCommand &&
                    previous.failureFingerprint == current.failureFingerprint
            }
            if (lastMatchingFailure != null &&
                lastMatchingFailure.successfulFileChangeCount == successfulFileChangeCount
            ) {
                val toolLabel = when (current.toolName) {
                    "build_project" -> "build"
                    "run_terminal_command" -> "command"
                    else -> "tool"
                }
                val commandPreview = current.normalizedCommand
                    .takeIf { it.isNotBlank() }
                    ?.take(160)
                    ?.let { " [$it]" }
                    .orEmpty()
                return "The model repeated the same failing $toolLabel$commandPreview without changing any files. Stopping before it loops on the same failure again."
            }
        }

        if (current.toolName == "read_file_range" &&
            current.targetFile != null &&
            current.startLine != null &&
            current.endLine != null
        ) {
            val overlappingReadCount = toolOutcomeHistory.count { previous ->
                previous.toolName == "read_file_range" &&
                    previous.successfulFileChangeCount == successfulFileChangeCount &&
                    previous.targetFile == current.targetFile &&
                    previous.startLine != null &&
                    previous.endLine != null &&
                    rangesOverlapStrongly(
                        startA = previous.startLine,
                        endA = previous.endLine,
                        startB = current.startLine,
                        endB = current.endLine
                    )
            }
            if (overlappingReadCount >= 2) {
                val fileName = File(current.targetFile).name
                return "The model kept rereading overlapping ranges in $fileName without making changes. Stopping before it burns more tool rounds on the same file."
            }
        }

        return null
    }

    private fun buildToolExecutionFingerprint(
        toolResult: AIToolExecutionResult,
        successfulFileChangeCount: Int
    ): ToolExecutionFingerprint {
        val normalizedOutput = toolResult.output.replace("\r\n", "\n")
        val targetFile = extractToolField(normalizedOutput, "FILE")
            ?: toolResult.fileChange?.filePath
        val rangeText = extractToolField(normalizedOutput, "RANGE")
        val (startLine, endLine) = parseLineRange(rangeText)

        return ToolExecutionFingerprint(
            toolName = toolResult.toolName.lowercase(),
            success = toolResult.success,
            normalizedCommand = normalizeToolCommand(toolResult),
            failureFingerprint = buildFailureFingerprint(toolResult, normalizedOutput),
            targetFile = targetFile,
            startLine = startLine,
            endLine = endLine,
            successfulFileChangeCount = successfulFileChangeCount
        )
    }

    private fun normalizeToolCommand(toolResult: AIToolExecutionResult): String {
        val normalizedSummary = toolResult.summary.trim().replace(Regex("\\s+"), " ")
        val normalizedCommand = toolResult.executedCommand
            ?.trim()
            ?.replace(Regex("\\s+"), " ")
            .orEmpty()
        if (normalizedCommand.isBlank()) {
            return normalizedSummary
        }

        return when (toolResult.toolName.lowercase()) {
            "build_project" -> normalizedCommand
                .split(' ')
                .filterNot {
                    it in setOf("--console=plain", "--no-daemon", "--info", "--stacktrace")
                }
                .joinToString(" ")
            else -> normalizedCommand
        }
    }

    private fun buildFailureFingerprint(
        toolResult: AIToolExecutionResult,
        normalizedOutput: String
    ): String {
        if (toolResult.success) {
            return ""
        }

        if (toolResult.blockedBySafetyPolicy) {
            val reason = toolResult.summary
                .replace(Regex("\\s+"), " ")
                .trim()
            return "BLOCKED:$reason"
        }

        val lines = normalizedOutput.lines()
            .map(String::trim)
            .filter(String::isNotBlank)
        val errorLine = lines.firstOrNull { line ->
            line.startsWith("Execution failed for task") ||
                line.startsWith("e:") ||
                line.startsWith("error:") ||
                line.contains("FAILURE:", ignoreCase = true) ||
                line.contains("BUILD FAILED", ignoreCase = true) ||
                line.contains("Exception", ignoreCase = true)
        }
        val fallbackTail = lines.takeLast(3).joinToString(" | ")

        return (errorLine ?: fallbackTail.ifBlank { toolResult.summary })
            .replace(Regex("\\s+"), " ")
            .take(320)
    }

    private fun extractToolField(output: String, fieldName: String): String? {
        val match = Regex(
            pattern = "(?m)^${Regex.escape(fieldName)}:\\s*(.+)$"
        ).find(output) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseLineRange(rawRange: String?): Pair<Int?, Int?> {
        val match = rawRange
            ?.trim()
            ?.let { Regex("^(\\d+)-(\\d+)$").matchEntire(it) }
            ?: return null to null
        return match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
    }

    private fun rangesOverlapStrongly(
        startA: Int,
        endA: Int,
        startB: Int,
        endB: Int
    ): Boolean {
        val overlapStart = max(startA, startB)
        val overlapEnd = min(endA, endB)
        if (overlapEnd < overlapStart) {
            return false
        }
        val overlapSize = overlapEnd - overlapStart + 1
        val smallerRangeSize = min(endA - startA + 1, endB - startB + 1)
        return overlapSize * 100 >= smallerRangeSize * 70
    }

    private fun resolveAssistantPreviewDecision(
        content: String,
        isFinalResponse: Boolean = false
    ): AssistantPreviewDecision {
        val trimmedStart = content.trimStart()
        if (trimmedStart.startsWith("TOOL_CALL:") || trimmedStart.startsWith("FILE_TO_MODIFY:")) {
            return AssistantPreviewDecision.SUPPRESS
        }

        if (isFinalResponse) {
            return if (trimmedStart.isBlank()) {
                AssistantPreviewDecision.SUPPRESS
            } else {
                AssistantPreviewDecision.SHOW
            }
        }

        if (trimmedStart.isBlank()) {
            return AssistantPreviewDecision.WAIT
        }

        return if (trimmedStart.length >= 24 || trimmedStart.contains('\n')) {
            AssistantPreviewDecision.SHOW
        } else {
            AssistantPreviewDecision.WAIT
        }
    }

    private fun resolveNativeAssistantPreviewDecision(
        content: String,
        isFinalResponse: Boolean = false
    ): AssistantPreviewDecision {
        val trimmedStart = content.trimStart()

        if (isFinalResponse) {
            return if (trimmedStart.isBlank()) {
                AssistantPreviewDecision.SUPPRESS
            } else {
                AssistantPreviewDecision.SHOW
            }
        }

        if (trimmedStart.isBlank()) {
            return AssistantPreviewDecision.WAIT
        }

        return AssistantPreviewDecision.SHOW
    }

    private fun buildToolContext(toolResults: List<AIToolExecutionResult>): String {
        val toolExecutionEnabled = permissionManager.isToolExecutionEnabled()
        val sessionContext = buildSessionContext()

        return buildString {
            appendLine("=== AI TOOLING ===")
            appendLine("TOOL_EXECUTION_ENABLED: $toolExecutionEnabled")
            if (toolExecutionEnabled) {
                appendLine("You may request tools when you need to compile, inspect the environment, search the project, read focused file ranges, edit focused file ranges, run safe terminal commands, or install Termux packages.")
                appendLine("When you need a tool, output ONLY one or more TOOL_CALL blocks. Do NOT mix TOOL_CALL with FILE_TO_MODIFY or explanations.")
                appendLine()
                appendLine("Supported tools:")
                appendLine("TOOL_CALL: find_files")
                appendLine("PATTERN: *MainActivity*.kt")
                appendLine("MAX_RESULTS: 20")
                appendLine()
                appendLine("TOOL_CALL: search_project")
                appendLine("PATTERN: class MainActivity")
                appendLine("FILE_GLOB: *.kt")
                appendLine("MAX_RESULTS: 20")
                appendLine()
                appendLine("TOOL_CALL: read_file_range")
                appendLine("FILE: core/app/src/main/java/com/example/MainActivity.kt")
                appendLine("START_LINE: 120")
                appendLine("END_LINE: 220")
                appendLine()
                appendLine("TOOL_CALL: replace_file_range")
                appendLine("FILE: core/app/src/main/java/com/example/MainActivity.kt")
                appendLine("START_LINE: 140")
                appendLine("END_LINE: 165")
                appendLine("CONTENT:")
                appendLine("```kotlin")
                appendLine("// replacement block here")
                appendLine("```")
                appendLine()
                appendLine("TOOL_CALL: build_project")
                appendLine("TASKS: :app:assembleDebug")
                appendLine("ARGS: --stacktrace --info")
                appendLine("WORKDIR: PROJECT_ROOT")
                appendLine()
                appendLine("TOOL_CALL: run_terminal_command")
                appendLine("COMMAND: pkg install ripgrep -y")
                appendLine("WORKDIR: HOME")
                appendLine()
                appendLine("Tool rules:")
                appendLine("- Prefer find_files and search_project to locate the relevant file or symbol before reading content.")
                appendLine("- Prefer read_file_range and inspect only the relevant 50-200 lines, not an entire file, unless absolutely necessary.")
                appendLine("- Prefer replace_file_range for focused edits instead of rewriting a whole file.")
                appendLine("- Prefer build_project for any Gradle wrapper build or compile command.")
                appendLine("- Use run_terminal_command for safe non-build terminal commands such as pkg/apt/git/rg/ls/find/cat/head/tail/grep/python/cmake/curl/wget/stat/tree/du/df/ps/cp/mkdir/touch.")
                appendLine("- Use non-interactive flags when possible, for example pkg install -y.")
                appendLine("- Do not request dangerous commands, shell operators, pipes, redirects, or command chaining.")
                appendLine("- Never use 'cd ... && ...'. Put the directory in WORKDIR and keep COMMAND to a single command.")
                appendLine("- If a command is blocked by safety policy or not in the allowlist, do not retry it verbatim. Switch to another supported command or a different tool.")
                appendLine("- If you want to run gradle/gradlew, prefer build_project. Direct gradlew terminal commands may be rerouted as builds.")
                appendLine("- If the same build or command fails twice without any file changes, stop and explain the blocker instead of retrying the same failure.")
                appendLine("- After tool results are returned, either request another tool or produce FILE_TO_MODIFY blocks / final text.")
            } else {
                appendLine("Tool execution is disabled in settings.")
                appendLine("Do NOT emit TOOL_CALL blocks while tool execution is disabled.")
                appendLine("If tools are needed, tell the user to enable AI Tool Execution from AI preferences first.")
            }

            if (toolResults.isNotEmpty()) {
                appendLine()
                appendLine("=== TOOL RESULTS ===")
                toolResults.takeLast(8).forEachIndexed { index, result ->
                    appendLine(result.toContextBlock(index + 1))
                    appendLine()
                }
            }

            if (sessionContext.isNotBlank()) {
                appendLine()
                appendLine(sessionContext)
            }
        }.trim()
    }

    private suspend fun finalizeToolRunWithCurrentContext(
        userRequest: String,
        callback: AIAgentCallback,
        toolResults: List<AIToolExecutionResult>,
        toolModifications: List<BaseFileModification>,
        loopReason: String
    ): ResolvedAgentResponse {
        callback.onProcessing("Stopping repeated tool calls and producing a final answer from the current context...")

        val forcedResponse = attemptForcedFinalAnswer(
            userRequest = userRequest,
            callback = callback,
            toolResults = toolResults,
            toolModifications = toolModifications,
            loopReason = loopReason
        )?.trim()

        val finalResponse = forcedResponse
            ?.takeIf { it.isNotBlank() && !containsProtocolBlocks(it) }
            ?: buildForcedFinalFallback(
                toolResults = toolResults,
                toolModifications = toolModifications,
                loopReason = loopReason
            )

        return ResolvedAgentResponse(
            response = finalResponse,
            toolModifications = toolModifications.toList(),
            toolResults = toolResults.toList()
        )
    }

    private suspend fun attemptForcedFinalAnswer(
        userRequest: String,
        callback: AIAgentCallback,
        toolResults: List<AIToolExecutionResult>,
        toolModifications: List<BaseFileModification>,
        loopReason: String
    ): String? {
        val previewBuffer = StringBuilder()
        var assistantPreviewStarted = false
        var assistantPreviewSuppressed = false

        val result = currentAgent?.generateCodeStreaming(
            prompt = buildForcedFinalizationPrompt(userRequest, loopReason),
            context = buildForcedFinalizationContext(
                toolResults = toolResults,
                toolModifications = toolModifications,
                loopReason = loopReason
            ),
            language = "kotlin",
            projectStructure = null,
            listener = object : AIAgentStreamListener {
                override fun onTextDelta(delta: String) {
                    if (delta.isEmpty()) {
                        return
                    }

                    previewBuffer.append(delta)
                    if (assistantPreviewSuppressed) {
                        return
                    }

                    if (!assistantPreviewStarted) {
                        when (resolveAssistantPreviewDecision(previewBuffer.toString())) {
                            AssistantPreviewDecision.WAIT -> return
                            AssistantPreviewDecision.SUPPRESS -> {
                                assistantPreviewSuppressed = true
                                return
                            }
                            AssistantPreviewDecision.SHOW -> {
                                assistantPreviewStarted = true
                                callback.onAssistantTextStarted()
                                callback.onAssistantTextDelta(previewBuffer.toString())
                                previewBuffer.clear()
                                return
                            }
                        }
                    }

                    callback.onAssistantTextDelta(delta)
                }
            }
        ) ?: return null

        val response = result.getOrNull()?.trim().orEmpty()
        if (response.isBlank()) {
            return null
        }

        if (!assistantPreviewStarted && !assistantPreviewSuppressed) {
            when (resolveAssistantPreviewDecision(response, isFinalResponse = true)) {
                AssistantPreviewDecision.SHOW -> {
                    callback.onAssistantTextStarted()
                    callback.onAssistantTextDelta(response)
                    assistantPreviewStarted = true
                }
                AssistantPreviewDecision.SUPPRESS,
                AssistantPreviewDecision.WAIT -> Unit
            }
        }

        if (assistantPreviewStarted) {
            callback.onAssistantTextFinished(response)
        }

        return response
    }

    private fun buildForcedFinalizationPrompt(
        userRequest: String,
        loopReason: String
    ): String {
        return buildString {
            appendLine("The tool-assisted run for this request must end now.")
            appendLine("Original user request:")
            appendLine(userRequest.trim())
            appendLine()
            appendLine("Do not request tools.")
            appendLine("Do not emit TOOL_CALL blocks.")
            appendLine("Do not emit FILE_TO_MODIFY blocks.")
            appendLine("Write the final assistant reply to the user based only on the supplied context.")
            appendLine("If the work is blocked, explain the blocker and the best next step.")
            appendLine()
            append("Loop reason: ")
            append(loopReason.trim())
        }.trim()
    }

    private fun buildForcedFinalizationContext(
        toolResults: List<AIToolExecutionResult>,
        toolModifications: List<BaseFileModification>,
        loopReason: String
    ): String {
        val sessionContext = buildSessionContext()
        return buildString {
            appendLine("=== TOOL RUN FINALIZATION ===")
            appendLine("The previous tool run is being finalized without any more tool calls.")
            appendLine("Loop reason: $loopReason")
            appendLine("You must reply with final natural language only.")

            if (toolResults.isNotEmpty()) {
                appendLine()
                appendLine("=== RECENT TOOL RESULTS ===")
                toolResults.takeLast(8).forEachIndexed { index, result ->
                    append(index + 1)
                    append(". ")
                    appendLine(buildCompactToolResultSummary(result))
                }
            }

            if (toolModifications.isNotEmpty()) {
                appendLine()
                appendLine("=== APPLIED FILE CHANGES ===")
                toolModifications.takeLast(8).forEachIndexed { index, modification ->
                    val status = if (modification.writeResult is FileWriteResult.Success) "applied" else "failed"
                    append(index + 1)
                    append(". ")
                    append(File(modification.filePath).name)
                    append(" -> ")
                    append(status)
                    append(" (")
                    append(modification.filePath)
                    appendLine(")")
                }
            }

            if (sessionContext.isNotBlank()) {
                appendLine()
                appendLine(sessionContext)
            }
        }.trim()
    }

    private fun buildCompactToolResultSummary(toolResult: AIToolExecutionResult): String {
        val outputTail = toolResult.output
            .trim()
            .takeIf { it.isNotBlank() }
            ?.replace(Regex("\\s+"), " ")
            ?.takeLast(320)

        return buildString {
            append(toolResult.toolName)
            append(": ")
            append(if (toolResult.success) "success" else "failure")
            append(" | ")
            append(toolResult.summary)
            toolResult.executedCommand?.takeIf { it.isNotBlank() }?.let {
                append(" | cmd=")
                append(it.take(180))
            }
            toolResult.exitCode?.let {
                append(" | exit=")
                append(it)
            }
            outputTail?.let {
                append(" | output_tail=")
                append(it)
            }
        }
    }

    private fun buildForcedFinalFallback(
        toolResults: List<AIToolExecutionResult>,
        toolModifications: List<BaseFileModification>,
        loopReason: String
    ): String {
        return buildString {
            appendLine("I stopped requesting more tools to avoid repeating the same action.")
            appendLine()
            appendLine("Reason: $loopReason")

            if (toolModifications.isNotEmpty()) {
                appendLine()
                appendLine("Files touched in this run:")
                toolModifications.takeLast(8).forEach { modification ->
                    val status = if (modification.writeResult is FileWriteResult.Success) "applied" else "failed"
                    append("- ")
                    append(File(modification.filePath).name)
                    append(" (")
                    append(status)
                    appendLine(")")
                }
            }

            if (toolResults.isNotEmpty()) {
                appendLine()
                appendLine("Latest tool findings:")
                toolResults.takeLast(5).forEach { result ->
                    append("- ")
                    append(result.toolName)
                    append(": ")
                    appendLine(result.summary)
                }
            }

            appendLine()
            append("Tell me the next step if you want me to continue from this state.")
        }.trim()
    }

    private fun containsProtocolBlocks(text: String): Boolean {
        val normalized = text.trimStart()
        return normalized.startsWith("TOOL_CALL:") ||
            normalized.startsWith("FILE_TO_MODIFY:") ||
            "\nTOOL_CALL:" in normalized ||
            "\nFILE_TO_MODIFY:" in normalized
    }

    private fun formatToolStartMessage(toolCall: AIToolCall): String {
        val details = when (toolCall.name.lowercase()) {
            "find_files" -> toolCall.argument("pattern") ?: ""
            "search_project" -> toolCall.argument("pattern") ?: ""
            "read_file_range" -> listOfNotNull(
                toolCall.argument("file"),
                toolCall.argument("start_line")?.let { "lines $it-${toolCall.argument("end_line") ?: "?"}" }
            ).joinToString(" ")
            "replace_file_range" -> listOfNotNull(
                toolCall.argument("file"),
                toolCall.argument("start_line")?.let { "lines $it-${toolCall.argument("end_line") ?: "?"}" }
            ).joinToString(" ")
            "build_project" -> toolCall.argument("tasks") ?: ""
            "run_terminal_command" -> toolCall.argument("command") ?: ""
            else -> ""
        }
        val workdir = toolCall.argument("workdir")?.takeIf { it.isNotBlank() }

        return if (details.isBlank()) {
            buildString {
                append("Running tool: ${toolCall.name}")
                workdir?.let { append("\nWorkdir: $it") }
            }
        } else {
            buildString {
                append("Running tool: ${toolCall.name} [$details]")
                workdir?.let { append("\nWorkdir: $it") }
            }
        }
    }

    private fun formatToolEndMessage(toolResult: AIToolExecutionResult): String {
        val status = if (toolResult.success) "completed" else "failed"
        val exitCode = toolResult.exitCode?.let { " (exit=$it)" }.orEmpty()
        val outputPreview = toolResult.output
            .takeIf { it.isNotBlank() }
            ?.let(::buildToolOutputPreview)

        return buildString {
            append("Tool ${toolResult.toolName} $status$exitCode")
            append("\nSummary: ${toolResult.summary}")
            toolResult.executedCommand?.takeIf { it.isNotBlank() }?.let {
                append("\nCommand: $it")
            }
            toolResult.workingDirectory?.takeIf { it.isNotBlank() }?.let {
                append("\nWorkdir: $it")
            }
            outputPreview?.takeIf { it.isNotBlank() }?.let {
                append("\nOutput tail:\n")
                append(it)
            }
        }
    }

    private fun buildToolOutputPreview(output: String): String {
        val trimmed = output.trim()
        if (trimmed.isBlank()) {
            return ""
        }

        return if (trimmed.length > 1200) {
            trimmed.takeLast(1200)
        } else {
            trimmed
        }
    }

    private fun buildModificationResults(
        modifications: List<BaseFileModification>
    ): List<ModificationResult> {
        return modifications.map { modification ->
            val writeResult = modification.writeResult
            val success = writeResult is FileWriteResult.Success
            ModificationResult(
                filePath = modification.filePath,
                content = modification.content,
                previousContent = modification.previousContent,
                success = success,
                message = when (writeResult) {
                    is FileWriteResult.Success -> "Modified successfully"
                    is FileWriteResult.PermissionDenied -> writeResult.reason
                    is FileWriteResult.Error -> writeResult.message
                },
                isNewFile = modification.previousContent == null
            )
        }
    }

    private fun formatErrorMessage(error: Throwable): String {
        val errorMessage = error.message ?: "Unknown error occurred"
        val stackTrace = error.stackTraceToString().take(500)
        val providerName = currentAgent?.providerName ?: "Unknown"
        
        return when (error) {
            is ToolExecutionDisabledException ->
                "🧰 TOOL EXECUTION DISABLED\n\n${error.message}"
            is ToolProtocolException ->
                "⚠️ TOOL PROTOCOL ERROR\n\n${error.message}"
            is ToolCallLoopException ->
                "🔁 TOOL LOOP STOPPED\n\n${error.message}"
            is com.tom.rv2ide.artificial.exceptions.RateLimitException -> 
                "⚠️ RATE LIMIT EXCEEDED\n\nThe API rate limit has been exceeded.\nPlease wait a few minutes before trying again.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.QuotaExceededException -> 
                "⚠️ QUOTA EXCEEDED\n\nYour API quota has been exhausted.\nPlease check your billing or upgrade your plan.\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InsufficientBalanceException -> 
                "💳 INSUFFICIENT BALANCE\n\nYour account balance is too low to process this request.\nPlease add credits or upgrade your plan.\n\nProvider: $providerName\n\nDetails: $errorMessage"
            is com.tom.rv2ide.artificial.exceptions.InvalidApiKeyException -> 
                "❌ INVALID API KEY\n\nThe API key is invalid or expired.\nPlease update your API key in the configuration.\n\nDetails: $errorMessage"
            is java.net.UnknownHostException ->
                "🌐 NETWORK ERROR\n\nCould not connect to the API server.\nPlease check your internet connection.\n\nDetails: $errorMessage"
            is java.net.SocketTimeoutException ->
                "⏱️ TIMEOUT ERROR\n\nThe request took too long to complete.\nPlease try again.\n\nDetails: $errorMessage"
            is org.json.JSONException ->
                "📄 JSON PARSING ERROR\n\nFailed to parse API response.\nThe API may be experiencing issues.\n\nDetails: $errorMessage"
            else -> 
                "❌ ERROR OCCURRED\n\nProvider: $providerName\nError Type: ${error.javaClass.simpleName}\n\nMessage: $errorMessage\n\nStack Trace (first 500 chars):\n$stackTrace"
        }
    }

    fun showProviderErrorDialogFromFragment(
        activity: android.app.Activity,
        errorMessage: String,
        onProviderSelected: (String) -> Unit
    ) {
        val dialog = ProviderSwitchDialog(activity)
        val currentProviderName = currentAgent?.providerName ?: "Unknown"
        val availableProviders = getAvailableProviders()
            .filter { it.id != currentProviderId && it.isAvailable }
            .map { Pair(it.id, it.name) }
        
        dialog.showProviderErrorDialog(
            currentProviderName,
            errorMessage,
            availableProviders,
            onProviderSelected = { providerId ->
                setProvider(providerId)
                val agents = Agents(appContext)
                val availableModels = agents.getModelsForProvider(providerId)
                if (availableModels.isNotEmpty()) {
                    agents.setAgent(availableModels[0])
                }
                reinitializeWithSelectedModel()
                onProviderSelected(providerId)
            },
            onEnableAutoSwitch = {
                val alternativeProvider = getAlternativeProvider()
                if (alternativeProvider != null) {
                    setProvider(alternativeProvider)
                    val agents = Agents(appContext)
                    val availableModels = agents.getModelsForProvider(alternativeProvider)
                    if (availableModels.isNotEmpty()) {
                        agents.setAgent(availableModels[0])
                    }
                    reinitializeWithSelectedModel()
                }
            }
        )
    }
    
    fun isAutoSwitchEnabled(): Boolean {
        return providerSwitchDialog.isAutoSwitchEnabled()
    }
    
    fun setAutoSwitch(enabled: Boolean) {
        providerSwitchDialog.setAutoSwitch(enabled)
    }

    private fun createSummary(results: List<ModificationResult>): ModificationSummary {
        val successful = results.count { it.success }
        val failed = results.count { !it.success }
        val newFiles = results.count { it.isNewFile }
        val modifiedFiles = results.count { !it.isNewFile }

        val fileDetails = results.map { result ->
            FileDetail(
                fileName = File(result.filePath).name,
                filePath = result.filePath,
                status = if (result.success) FileStatus.SUCCESS else FileStatus.FAILED,
                changeType = if (result.isNewFile) ChangeType.CREATED else ChangeType.MODIFIED
            )
        }

        return ModificationSummary(
            totalFiles = results.size,
            successfulFiles = successful,
            failedFiles = failed,
            newFiles = newFiles,
            modifiedFiles = modifiedFiles,
            fileDetails = fileDetails
        )
    }

    private fun readCurrentFileContent(filePath: String): String? {
        return try {
            val file = File(filePath)
            if (file.exists() && file.isFile) {
                file.readText()
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun undoLastModification(): Boolean {
        return currentAgent?.undoLastModification() ?: false
    }

    fun reinitializeWithSelectedModel() {
        val factory = AIAgentRegistry.getFactory(currentProviderId)
        factory?.getApiKey()?.let { apiKey ->
            currentAgent?.reinitializeWithNewModel(apiKey, appContext)
        }
    }

    fun getCurrentModelName(): String {
        val agents = Agents(appContext)
        return agents.getAgent()
    }
    
    fun getConversationHistory(): List<UnifiedModificationAttempt> {
        return currentAgent?.getModificationHistory()?.map {
            UnifiedModificationAttempt(
                timestamp = it.timestamp,
                filePath = it.filePath,
                previousContent = it.previousContent,
                newContent = it.newContent,
                attemptNumber = it.attemptNumber,
                success = it.success
            )
        } ?: emptyList()
    }

    interface AIAgentCallback {
        fun onProcessing(message: String)
        fun onFileModifying(filePath: String, fileName: String)
        fun onFileModified(filePath: String, fileName: String, success: Boolean)
        fun onSuccess(response: String, modifications: List<ModificationResult>, summary: ModificationSummary)
        fun onTextResponse(response: String, summary: ModificationSummary)
        fun onError(message: String)
        fun onRetry(attemptNumber: Int, message: String)
        fun onAssistantTextStarted() {}
        fun onAssistantTextDelta(delta: String) {}
        fun onAssistantTextFinished(fullResponse: String) {}
        fun onToolCallStarted(toolCall: AIToolCall) {}
        fun onToolCallOutput(toolCall: AIToolCall, chunk: String) {}
        fun onToolCallCompleted(result: AIToolExecutionResult) {}
    }

    data class ModificationResult(
        val filePath: String,
        val content: String,
        val previousContent: String?,
        val success: Boolean,
        val message: String,
        val isNewFile: Boolean = false
    )

    data class ModificationSummary(
        val totalFiles: Int,
        val successfulFiles: Int,
        val failedFiles: Int,
        val newFiles: Int,
        val modifiedFiles: Int,
        val fileDetails: List<FileDetail>
    )

    data class FileDetail(
        val fileName: String,
        val filePath: String,
        val status: FileStatus,
        val changeType: ChangeType
    )

    enum class FileStatus { SUCCESS, FAILED }
    enum class ChangeType { CREATED, MODIFIED }
    
    data class ProviderInfo(
        val id: String,
        val name: String,
        val isAvailable: Boolean
    )
}

data class BaseFileModification(
    val filePath: String,
    val content: String,
    val writeResult: FileWriteResult,
    val previousContent: String?
)

data class ResolvedAgentResponse(
    val response: String,
    val toolModifications: List<BaseFileModification>,
    val toolResults: List<AIToolExecutionResult>
)

data class UnifiedModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)

private class ToolCallLoopException(message: String) : IllegalStateException(message)

private class ToolProtocolException(message: String) : IllegalStateException(message)

private class ToolExecutionDisabledException(message: String) : IllegalStateException(message)

private enum class AgentSessionRole(val label: String) {
    USER("USER"),
    ASSISTANT("ASSISTANT"),
    TOOL("TOOL"),
    CHANGE("CHANGE");

    companion object {
        fun fromLabel(label: String): AgentSessionRole? {
            return values().firstOrNull { it.label == label }
        }
    }
}

private data class AgentSessionTurn(
    val role: AgentSessionRole,
    val content: String
)

private data class ToolExecutionFingerprint(
    val toolName: String,
    val success: Boolean,
    val normalizedCommand: String,
    val failureFingerprint: String,
    val targetFile: String?,
    val startLine: Int?,
    val endLine: Int?,
    val successfulFileChangeCount: Int
)

private fun restoreAgentSessionTurn(turn: PersistedAgentSessionTurn): AgentSessionTurn? {
    val role = AgentSessionRole.fromLabel(turn.role) ?: return null
    return AgentSessionTurn(
        role = role,
        content = turn.content
    )
}

private enum class AssistantPreviewDecision {
    WAIT,
    SHOW,
    SUPPRESS
}
