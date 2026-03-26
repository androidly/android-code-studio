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
import com.tom.rv2ide.artificial.agents.external.ExternalEngineException
import com.tom.rv2ide.artificial.agents.external.ExternalEngineAgent
import com.tom.rv2ide.artificial.agents.local.LocalLLM
import com.tom.rv2ide.artificial.file.FileWriteResult
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

class AIAgentManager(private val context: Context) {

    companion object {
        private const val DEFAULT_CONVERSATION_SESSION_ID = "default"
    }

    private val appContext = context.applicationContext
    private val sessionPersistenceStore = AIAgentSessionStore(appContext)
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
    private val fileModificationProcessor = AIAgentFileModificationProcessor(
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
    private var currentConversationSessionId: String = DEFAULT_CONVERSATION_SESSION_ID
    private val sessionSupport = AIAgentSessionSupport(
        sessionPersistenceStore = sessionPersistenceStore,
        projectRootProvider = { currentProjectRoot },
        conversationSessionIdProvider = { currentConversationSessionId },
        providerSessionIdentityProvider = ::buildProviderSessionIdentity,
        persistentConversationAgentProvider = ::currentPersistentConversationAgent,
        logTag = "AIAgentManager"
    )
    private val responseResolver: AIAgentResponseResolver by lazy {
        AIAgentResponseResolver(
            host = object : AIAgentResponseResolverHost {
                override val currentAgent: AIAgent?
                    get() = this@AIAgentManager.currentAgent
                override val toolExecutor: AIToolExecutor
                    get() = this@AIAgentManager.toolExecutor
                override val permissionManager: AIPermissionManager
                    get() = this@AIAgentManager.permissionManager

                override fun currentNativeToolAgent(): NativeToolCallingAgent? {
                    return this@AIAgentManager.currentNativeToolAgent()
                }

                override fun buildSessionContext(): String {
                    return this@AIAgentManager.buildSessionContext()
                }

                override fun recordSessionTurn(role: AgentSessionRole, content: String) {
                    this@AIAgentManager.recordSessionTurn(role, content)
                }

                override fun summarizeToolResult(toolResult: AIToolExecutionResult): String {
                    return sessionSupport.summarizeToolResult(toolResult)
                }
            }
        )
    }
    private val providerSwitchDialog = ProviderSwitchDialog(appContext)
    init {
        Gemini.registerAgent()
        OpenAI.registerAgent()
        Anthropic.registerAgent()
        Grok.registerAgent()
        DeepSeek.registerAgent()
        LocalLLM.registerAgent()
        CustomProviderAgent.registerAgent()
        ExternalEngineAgent.registerAgent()
        
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
        currentAgent?.setConversationSessionId(currentConversationSessionId)
        sessionSupport.resetRestoredState()
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

    fun getCurrentSessionStorageKey(): String = sessionSupport.currentSessionKey()

    fun setConversationSessionId(sessionId: String) {
        val normalizedSessionId = sessionId.trim().ifBlank { DEFAULT_CONVERSATION_SESSION_ID }
        if (normalizedSessionId == currentConversationSessionId) {
            return
        }
        currentConversationSessionId = normalizedSessionId
        currentAgent?.setConversationSessionId(normalizedSessionId)
        sessionSupport.resetRestoredState()
        restoreCurrentSessionState(force = true)
    }
    
    fun getCurrentProviderName(): String {
        return currentAgent?.providerName ?: "Unknown"
    }

    fun syncSelectedProviderAndModel(): Boolean {
        val selectedProvider = Agents(appContext).getProvider()
        if (currentAgent == null || selectedProvider != currentProviderId) {
            return setProvider(selectedProvider)
        }

        reinitializeWithSelectedModel()
        currentAgent?.setConversationSessionId(currentConversationSessionId)
        currentAgent?.setContext(appContext)
        currentProjectRoot?.let { root ->
            val projectData = ProjectData(appContext)
            val projectTree = projectData.showProjectTree(root)
            currentAgent?.setProjectData(projectTree)
        }
        return currentAgent?.isInitialized() ?: false
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
        sessionSupport.resetRestoredState()
        restoreCurrentSessionState(force = true)

        return true
    }

    fun clearConversation() {
        currentAgent?.clearConversation()
        sessionSupport.clearCurrentSession()
    }

    private fun buildProviderSessionIdentity(): String {
        return AIAgentSessionCoordinator.buildProviderSessionIdentity(
            providerKey = currentAgent?.providerId ?: currentProviderId,
            persistentConversationFingerprint = currentPersistentConversationAgent()
                ?.persistentConversationFingerprint()
                .orEmpty()
        )
    }

    private fun currentNativeToolAgent(): NativeToolCallingAgent? {
        return currentAgent as? NativeToolCallingAgent
    }

    private fun currentPersistentConversationAgent(): PersistentConversationAgent? {
        return currentAgent as? PersistentConversationAgent
    }

    private fun recordSessionTurn(role: AgentSessionRole, content: String) {
        sessionSupport.recordSessionTurn(role, content)
    }

    private fun buildSessionContext(): String {
        return sessionSupport.buildSessionContext()
    }

    private fun isNonRetryableToolError(error: Throwable): Boolean {
        return error is ToolCallLoopException ||
            error is ToolProtocolException ||
            error is ToolExecutionDisabledException ||
            error is ExternalEngineException
    }

    suspend fun executeRequest(userRequest: String, callback: AIAgentCallback) {
        val requestRetrySupport = AIAgentRequestRetrySupport(
            callback = callback,
            currentProviderName = { currentAgent?.providerName ?: "Unknown" },
            currentAttemptCount = { currentAgent?.getCurrentAttemptCount() ?: 0 },
            canRetry = { currentAgent?.canRetry() == true },
            incrementAttemptCount = { currentAgent?.incrementAttemptCount() },
            resetAttemptCount = { currentAgent?.resetAttemptCount() },
            switchProvider = ::setProvider,
            findAlternativeProvider = ::getAlternativeProvider,
            isAutoSwitchEnabled = providerSwitchDialog::isAutoSwitchEnabled,
            isNonRetryableToolError = ::isNonRetryableToolError,
            formatErrorMessage = { error ->
                AIAgentErrorSupport.formatErrorMessage(
                    error = error,
                    providerName = currentAgent?.providerName ?: "Unknown"
                )
            }
        )

        restoreCurrentSessionState()
        currentAgent?.resetAttemptCount()
        recordSessionTurn(AgentSessionRole.USER, userRequest)
        callback.onProcessing("Analyzing your request...")

        while (requestRetrySupport.canContinue()) {
            try {
                requestRetrySupport.beforeAttempt()

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
                                val results = AIAgentModificationSupport.buildModificationResults(modifications)
                                val summary = AIAgentModificationSupport.createSummary(results)
                                recordSessionTurn(
                                    AgentSessionRole.ASSISTANT,
                                    sessionSupport.summarizeAssistantResponse(response, summary)
                                )
                                recordSessionTurn(
                                    AgentSessionRole.CHANGE,
                                    sessionSupport.summarizeModificationResults(results)
                                )
                                callback.onSuccess(response, results, summary)
                                requestRetrySupport.markCompleted()
                            } else {
                                requestRetrySupport.scheduleProcessingRetry("Some files failed. Retrying...")
                            }
                        } else if (response.contains("FILE_TO_MODIFY:")) {
                            requestRetrySupport.scheduleProcessingRetry("No files were modified. Retrying...")
                        } else {
                            val summary = ModificationSummary(0, 0, 0, 0, 0, emptyList())
                            recordSessionTurn(
                                AgentSessionRole.ASSISTANT,
                                sessionSupport.summarizeAssistantResponse(response, summary)
                            )
                            callback.onTextResponse(response, summary)
                            requestRetrySupport.markCompleted()
                        }
                    },
                    onFailure = { error ->
                        android.util.Log.e("AIAgentManager", "Error occurred: ${error.message}", error)
                        requestRetrySupport.handleResolutionFailure(error)
                    }
                )
            } catch (e: Exception) {
                android.util.Log.e("AIAgentManager", "Exception occurred: ${e.message}", e)
                requestRetrySupport.handleExecutionException(e)
            }
        }

        if (!requestRetrySupport.isCompleted) {
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
        sessionSupport.restoreCurrentSessionState(force)
    }

    private suspend fun processModifications(
        response: String,
        callback: AIAgentCallback
    ): List<BaseFileModification> {
        return fileModificationProcessor.process(response, callback)
    }

    private suspend fun resolveAgentResponse(
        userRequest: String,
        callback: AIAgentCallback
    ): Result<ResolvedAgentResponse> {
        return responseResolver.resolveAgentResponse(userRequest, callback)
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
