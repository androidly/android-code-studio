package com.tom.rv2ide.artificial.agents.custom

import android.content.Context
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.AIAgentStreamListener
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.agents.NativeToolCallingAgent
import com.tom.rv2ide.artificial.agents.NativeToolResult
import com.tom.rv2ide.artificial.agents.NativeToolTurnResponse
import com.tom.rv2ide.artificial.agents.PersistentConversationAgent
import com.tom.rv2ide.artificial.agents.addBoundedModificationAttempt
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.rules.WritingRules
import com.tom.rv2ide.artificial.tools.AIToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class CustomProviderAgent : AIAgent, NativeToolCallingAgent, PersistentConversationAgent {

    private var apiKey: String? = null
    private var baseUrl: String = ""
    private var selectedModel: String = ""
    private var apiType: CustomProviderApiType = CustomProviderApiType.OPENAI_CHAT

    private val writingRules = WritingRules.Instructions()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private var projectTreeResult: ProjectTreeResult? = null
    private var fileWriter: AIFileWriter? = null
    private var agents: Agents? = null
    private val conversationHistory = mutableListOf<CustomConversationMessage>()
    private val committedToolConversation = mutableListOf<CustomProviderTurn>()
    private val activeToolTurn = mutableListOf<CustomProviderTurn>()
    private val condensedNativeConversation = mutableListOf<String>()
    private val modificationHistory = mutableListOf<ModificationAttempt>()
    private val responsesContinuationState = CustomProviderResponsesContinuationState()
    private var currentAttemptCount = 0
    private var toolCallSequence = 0
    private var strictNativeToolSchemasEnabled = true
    private var responsesContinuationEnabledForSession = true
    private var initializationFingerprint: String? = null
    private val maxRetryAttempts = 3
    private val defaultContextWindowTokens = 400000
    private val defaultInputWindowTokens = 272000
    private val defaultReservedCompactionTokens = 20000
    private val defaultMaxGenerationTokens = 128000
    private val minCommittedNativeTurnsToKeep = 8
    private val nativeCompressionBatchSize = 8
    private val maxCondensedNativeEntries = 10
    private val conversationCompactionConfig = CustomProviderConversationCompactionConfig(
        defaultContextWindowTokens = defaultContextWindowTokens,
        defaultInputWindowTokens = defaultInputWindowTokens,
        defaultReservedCompactionTokens = defaultReservedCompactionTokens,
        defaultMaxGenerationTokens = defaultMaxGenerationTokens,
        minCommittedNativeTurnsToKeep = minCommittedNativeTurnsToKeep,
        nativeCompressionBatchSize = nativeCompressionBatchSize,
        maxCondensedNativeEntries = maxCondensedNativeEntries
    )
    private val nativeResponseParser = CustomProviderNativeResponseParser(
        apiTypeProvider = { apiType },
        nextToolCallId = ::nextToolCallId,
        normalizeJsonObjectString = ::normalizeJsonObjectString
    )
    private val requestExecutor = CustomProviderRequestExecutor(
        httpClient = httpClient,
        apiTypeProvider = { apiType },
        applyLearningOutcome = ::applyCompatibilityLearningOutcome
    )
    private val requestCoordinator = CustomProviderRequestCoordinator(
        requestExecutor = requestExecutor,
        nativeResponseParser = nativeResponseParser,
        apiTypeProvider = { apiType },
        apiKeyProvider = { apiKey },
        baseUrlProvider = { baseUrl },
        selectedModelProvider = { selectedModel },
        textSystemPromptProvider = writingRules::useThis,
        strictNativeToolSchemasEnabledProvider = { strictNativeToolSchemasEnabled },
        responsesContinuationStateProvider = { responsesContinuationState },
        responsesContinuationEnabledForSessionProvider = { responsesContinuationEnabledForSession },
        defaultMaxGenerationTokens = defaultMaxGenerationTokens,
        nativeSystemPromptProvider = ::nativeSystemPrompt,
        nativeConversationProvider = ::nativeConversation
    )

    override val providerId = "custom"
    override val providerName = "Custom Provider"

    companion object {
        fun registerAgent() {
            AIAgentRegistry.register("custom", object : AIAgentRegistry.AgentFactory {
                override fun create(context: Context): AIAgent {
                    return CustomProviderAgent()
                }

                override fun hasValidApiKey(): Boolean {
                    return CustomProviderConfig.hasValidConfig()
                }

                override fun getApiKey(): String? {
                    return CustomProviderConfig.getApiKey().takeIf { it.isNotBlank() }
                }
            })
        }
    }

    override fun initialize(apiKey: String, context: Context) {
        this.apiKey = apiKey
        agents = Agents(context)

        val settings = CustomProviderConfig.getSettings()
        baseUrl = settings.normalizedBaseUrl
        apiType = settings.apiType

        val savedModel = if (agents?.getProvider() == providerId) {
            agents?.getAgent().orEmpty()
        } else {
            ""
        }

        selectedModel = savedModel.ifBlank { settings.modelId }

        if (baseUrl.isBlank() || this.apiKey.isNullOrBlank() || selectedModel.isBlank()) {
            throw IllegalStateException("Custom provider is not fully configured")
        }

        val nextFingerprint = CustomProviderCompatibilitySupport.buildInitializationFingerprint(
            apiKey = this.apiKey.orEmpty(),
            normalizedBaseUrl = baseUrl,
            modelId = selectedModel,
            apiType = apiType
        )
        if (initializationFingerprint == null) {
            resetProviderCompatibilityState()
        }
        if (initializationFingerprint != null && initializationFingerprint != nextFingerprint) {
            clearConversation()
        }
        initializationFingerprint = nextFingerprint
    }

    override fun reinitializeWithNewModel(apiKey: String, context: Context) {
        initialize(apiKey, context)
    }

    override fun setContext(context: Context) {
        fileWriter = AIFileWriter(context)
    }

    override fun setProjectData(projectTreeResult: ProjectTreeResult) {
        this.projectTreeResult = projectTreeResult
    }

    override fun clearConversation() {
        conversationHistory.clear()
        committedToolConversation.clear()
        activeToolTurn.clear()
        condensedNativeConversation.clear()
        modificationHistory.clear()
        currentAttemptCount = 0
        toolCallSequence = 0
        resetProviderCompatibilityState()
        resetNativeResponsesContinuationState()
    }

    override fun recordModification(filePath: String, oldContent: String?, newContent: String, success: Boolean) {
        modificationHistory.addBoundedModificationAttempt(
            ModificationAttempt(
                timestamp = System.currentTimeMillis(),
                filePath = filePath,
                previousContent = oldContent,
                newContent = newContent,
                attemptNumber = currentAttemptCount,
                success = success
            )
        )
    }

    override fun undoLastModification(): Boolean {
        if (modificationHistory.isEmpty()) {
            return false
        }

        val lastModification = modificationHistory.lastOrNull { it.success } ?: return false
        if (lastModification.previousContent != null) {
            val result = writeFile(lastModification.filePath, lastModification.previousContent)
            if (result is FileWriteResult.Success) {
                modificationHistory.removeAt(modificationHistory.lastIndexOf(lastModification))
                return true
            }
            return false
        }

        return try {
            File(lastModification.filePath).delete()
            modificationHistory.removeAt(modificationHistory.lastIndexOf(lastModification))
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun getModificationHistory(): List<ModificationAttempt> {
        return modificationHistory.toList()
    }

    override fun resetAttemptCount() {
        currentAttemptCount = 0
    }

    override fun incrementAttemptCount() {
        currentAttemptCount++
    }

    override fun getCurrentAttemptCount(): Int = currentAttemptCount

    override fun canRetry(): Boolean = currentAttemptCount < maxRetryAttempts

    override suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(generateResponse(prompt, context, null))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override suspend fun generateCodeStreaming(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?,
        listener: AIAgentStreamListener
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(generateResponse(prompt, context, listener))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    override fun writeFile(filePath: String, content: String): FileWriteResult {
        val writer = fileWriter ?: return FileWriteResult.Error("File writer not initialized")
        return writer.writeFile(filePath, content, createBackup = true)
    }

    override fun isInitialized(): Boolean {
        return !apiKey.isNullOrBlank() && baseUrl.isNotBlank() && selectedModel.isNotBlank()
    }

    override fun persistentConversationFingerprint(): String {
        return initializationFingerprint.orEmpty()
    }

    override fun exportPersistentConversationState(): String {
        val persistedRequestSignature = responsesContinuationState.lastRequestSignature
            ?.takeIf { activeToolTurn.isEmpty() }
        val persistedResponseId = responsesContinuationState.lastResponseId
            ?.takeIf { activeToolTurn.isEmpty() }
        return CustomProviderPersistentStateSupport.exportState(
            conversationHistory = conversationHistory,
            committedToolConversation = committedToolConversation,
            condensedNativeConversation = condensedNativeConversation,
            toolCallSequence = toolCallSequence,
            lastNativeResponsesRequestSignature = persistedRequestSignature,
            lastNativeResponsesResponseId = persistedResponseId
        )
    }

    override fun importPersistentConversationState(serializedState: String) {
        clearConversation()
        val state = CustomProviderPersistentStateSupport.importState(serializedState)
        conversationHistory += state.conversationHistory
        committedToolConversation += state.committedToolConversation
        condensedNativeConversation += state.condensedNativeConversation
        toolCallSequence = state.toolCallSequence
        responsesContinuationState.lastRequestSignature = state.lastNativeResponsesRequestSignature
        responsesContinuationState.lastResponseId = state.lastNativeResponsesResponseId
        CustomProviderResponsesContinuationSupport.restoreConversationKeys(
            state = responsesContinuationState,
            currentConversation = nativeConversation()
        )
    }

    override suspend fun startToolSessionTurn(
        prompt: String,
        toolExecutionEnabled: Boolean,
        listener: AIAgentStreamListener
    ): Result<NativeToolTurnResponse> = withContext(Dispatchers.IO) {
        try {
            activeToolTurn.clear()
            activeToolTurn += CustomProviderTurn.User(nativeUserTurn(prompt))
            autoCompactNativeConversationIfNeeded(toolExecutionEnabled)
            val response = requestCoordinator.callProviderNative(toolExecutionEnabled, listener)
            activeToolTurn += CustomProviderTurn.Assistant(
                text = response.assistantText,
                toolCalls = response.toolCalls
            )
            rememberNativeResponsesContinuationState(
                toolExecutionEnabled = toolExecutionEnabled,
                responseId = response.responseId
            )
            if (response.toolCalls.isEmpty()) {
                commitActiveToolTurn(toolExecutionEnabled)
            }
            Result.success(response.toExternalResponse())
        } catch (error: Exception) {
            abortActiveToolSessionTurn()
            Result.failure(error)
        }
    }

    override suspend fun continueToolSessionTurn(
        toolResults: List<NativeToolResult>,
        toolExecutionEnabled: Boolean,
        listener: AIAgentStreamListener
    ): Result<NativeToolTurnResponse> = withContext(Dispatchers.IO) {
        try {
            if (activeToolTurn.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("No active tool session turn to continue")
                )
            }

            activeToolTurn += toolResults.map { toolResult ->
                CustomProviderTurn.ToolResult(
                    toolCallId = toolResult.toolCallId,
                    toolName = toolResult.toolName,
                    output = toolResult.output,
                    isError = toolResult.isError
                )
            }

            autoCompactNativeConversationIfNeeded(toolExecutionEnabled)
            val response = requestCoordinator.callProviderNative(toolExecutionEnabled, listener)
            activeToolTurn += CustomProviderTurn.Assistant(
                text = response.assistantText,
                toolCalls = response.toolCalls
            )
            rememberNativeResponsesContinuationState(
                toolExecutionEnabled = toolExecutionEnabled,
                responseId = response.responseId
            )
            if (response.toolCalls.isEmpty()) {
                commitActiveToolTurn(toolExecutionEnabled)
            }
            Result.success(response.toExternalResponse())
        } catch (error: Exception) {
            abortActiveToolSessionTurn()
            Result.failure(error)
        }
    }

    override fun abortActiveToolSessionTurn() {
        if (activeToolTurn.isNotEmpty()) {
            resetNativeResponsesContinuationState()
        }
        activeToolTurn.clear()
    }

    private fun generateResponse(
        prompt: String,
        context: String?,
        listener: AIAgentStreamListener?
    ): String {
        if (!isInitialized()) {
            throw IllegalStateException("Custom provider not initialized")
        }

        val fullPrompt = CustomProviderPromptContextSupport.buildPrompt(
            prompt = prompt,
            context = context,
            projectTreeResult = projectTreeResult,
            conversationHistory = conversationHistory,
            modificationHistory = modificationHistory,
            currentAttemptCount = currentAttemptCount,
            maxRetryAttempts = maxRetryAttempts
        )
        val responseText = requestCoordinator.callProvider(fullPrompt, listener)
        if (responseText.isBlank()) {
            throw Exception("Empty response from custom provider")
        }

        conversationHistory.add(CustomConversationMessage("user", prompt))
        conversationHistory.add(CustomConversationMessage("assistant", responseText))
        CustomProviderConversationCompactionSupport.trimConversationHistory(conversationHistory)
        listener?.onCompleted(responseText)
        return responseText
    }

    private fun resetNativeResponsesContinuationState() {
        CustomProviderResponsesContinuationSupport.clear(responsesContinuationState)
    }

    private fun resetProviderCompatibilityState() {
        val defaults = CustomProviderCompatibilitySupport.defaultFlags(baseUrl)
        strictNativeToolSchemasEnabled = defaults.strictToolSchemasEnabled
        responsesContinuationEnabledForSession = defaults.responsesContinuationEnabled
    }

    private fun rememberNativeResponsesContinuationState(
        toolExecutionEnabled: Boolean,
        responseId: String?
    ) {
        CustomProviderResponsesContinuationSupport.rememberSuccessfulResponse(
            state = responsesContinuationState,
            responseId = responseId,
            currentConversation = nativeConversation(),
            selectedModel = selectedModel,
            toolExecutionEnabled = toolExecutionEnabled,
            systemPrompt = nativeSystemPrompt(toolExecutionEnabled),
            apiType = apiType,
            responsesContinuationEnabledForSession = responsesContinuationEnabledForSession
        )
    }

    private fun applyCompatibilityLearningOutcome(
        learningOutcome: CustomProviderCompatibilityLearningOutcome
    ) {
        if (learningOutcome.disableStrictToolSchemas) {
            strictNativeToolSchemasEnabled = false
        }
        if (learningOutcome.disableResponsesContinuation) {
            responsesContinuationEnabledForSession = false
            resetNativeResponsesContinuationState()
        }
    }

    private fun commitActiveToolTurn(toolExecutionEnabled: Boolean) {
        if (activeToolTurn.isEmpty()) {
            return
        }
        committedToolConversation += activeToolTurn
        activeToolTurn.clear()
        autoCompactNativeConversationIfNeeded(toolExecutionEnabled)
    }

    private fun nextToolCallId(): String {
        toolCallSequence += 1
        return "acs_call_$toolCallSequence"
    }

    private fun nativeUserTurn(prompt: String): String {
        return CustomProviderPromptContextSupport.buildNativeUserTurn(
            prompt = prompt,
            modificationHistory = modificationHistory,
            currentAttemptCount = currentAttemptCount,
            maxRetryAttempts = maxRetryAttempts
        )
    }

    private fun nativeSystemPrompt(toolExecutionEnabled: Boolean): String {
        return CustomProviderPromptContextSupport.buildNativeSystemPrompt(
            toolExecutionEnabled = toolExecutionEnabled,
            projectTreeResult = projectTreeResult
        )
    }

    private fun nativeConversation(): List<CustomProviderTurn> {
        return CustomProviderPromptContextSupport.currentNativeConversation(
            committedToolConversation = committedToolConversation,
            activeToolTurn = activeToolTurn,
            condensedNativeConversation = condensedNativeConversation
        )
    }

    private fun autoCompactNativeConversationIfNeeded(toolExecutionEnabled: Boolean) {
        CustomProviderPromptContextSupport.autoCompactNativeConversationIfNeeded(
            toolExecutionEnabled = toolExecutionEnabled,
            committedToolConversation = committedToolConversation,
            activeToolTurn = activeToolTurn,
            condensedNativeConversation = condensedNativeConversation,
            config = conversationCompactionConfig,
            projectTreeResult = projectTreeResult,
            onCompacted = ::resetNativeResponsesContinuationState
        )
    }

    private fun estimateNativeConversationTokens(toolExecutionEnabled: Boolean): Int {
        return CustomProviderPromptContextSupport.estimateNativeConversationTokens(
            toolExecutionEnabled = toolExecutionEnabled,
            committedToolConversation = committedToolConversation,
            activeToolTurn = activeToolTurn,
            condensedNativeConversation = condensedNativeConversation,
            projectTreeResult = projectTreeResult
        )
    }

    private fun normalizeJsonObjectString(rawJson: String): String {
        return CustomProviderPromptContextSupport.normalizeJsonObjectString(rawJson)
    }

}
