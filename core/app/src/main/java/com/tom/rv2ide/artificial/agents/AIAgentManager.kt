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

class AIAgentManager(private val context: Context) {

    private val snippetParser = SnippetParser()
    private val permissionManager = AIPermissionManager(context)
    private val toolExecutor = AIToolExecutor(
        context = context,
        projectRootProvider = { currentProjectRoot },
        writeFile = { filePath, content ->
            currentAgent?.writeFile(filePath, content) ?: FileWriteResult.Error("No agent initialized")
        },
        recordModification = { filePath, oldContent, newContent, success ->
            currentAgent?.recordModification(filePath, oldContent, newContent, success)
        }
    )
    private var currentProjectRoot: File? = null
    private var currentProviderId: String = Agents(context).getProvider()
    private var currentAgent: AIAgent? = null
    private val providerSwitchDialog = ProviderSwitchDialog(context)
    private val maxToolRounds = 4

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
        currentAgent = factory.create(context)
        android.util.Log.d("AIAgentManager", "Agent created: ${currentAgent != null}")
        
        factory.getApiKey()?.let { apiKey ->
            android.util.Log.d("AIAgentManager", "Initializing agent with API key")
            currentAgent?.initialize(apiKey, context)
            currentAgent?.setContext(context)
            
            currentProjectRoot?.let { root ->
                val projectData = ProjectData(context)
                val projectTree = projectData.showProjectTree(root)
                currentAgent?.setProjectData(projectTree)
            }
            
            android.util.Log.d("AIAgentManager", "Agent initialized: ${currentAgent?.isInitialized()}")
        }
        
        return currentAgent?.isInitialized() ?: false
    }

    fun getCurrentProviderId(): String = currentProviderId
    
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
        val projectData = ProjectData(context)
        val projectTree = projectData.showProjectTree(projectRoot)

        currentAgent?.setProjectData(projectTree)
        permissionManager.addAllowedDirectory(projectRoot.absolutePath)

        return true
    }

    fun clearConversation() {
        currentAgent?.clearConversation()
    }

    suspend fun executeRequest(userRequest: String, callback: AIAgentCallback) {
        var success = false
        var providerSwitched = false

        currentAgent?.resetAttemptCount()
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
                      } else if ((currentAgent?.canRetry() == true) && !providerSwitched) {
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
                
                if (currentAgent?.canRetry() == true) {
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
        val toolResults = mutableListOf<AIToolExecutionResult>()
        val toolModifications = mutableListOf<BaseFileModification>()

        repeat(maxToolRounds + 1) { round ->
            val context = buildToolContext(toolResults)
            val result = currentAgent?.generateCode(
                prompt = userRequest,
                context = context,
                language = "kotlin",
                projectStructure = null
            ) ?: Result.failure(Exception("No agent initialized"))

            result.exceptionOrNull()?.let { return Result.failure(it) }
            val response = result.getOrNull() ?: return Result.failure(Exception("Empty AI response"))

            val toolCalls = AIToolCallParser.parseToolCalls(response)
            if (toolCalls.isEmpty()) {
                return Result.success(
                    ResolvedAgentResponse(
                        response = response,
                        toolModifications = toolModifications.toList()
                    )
                )
            }

            if (round >= maxToolRounds) {
                return Result.failure(
                    IllegalStateException("Tool call limit exceeded. The agent kept requesting tools without producing a final answer.")
                )
            }

            if (!permissionManager.isToolExecutionEnabled()) {
                toolResults += AIToolExecutionResult(
                    toolName = "tool_permission",
                    success = false,
                    summary = "Tool execution is disabled",
                    output = "Enable AI Tool Execution in AI Preferences to allow builds, Termux package installs, and safe terminal commands."
                )
                callback.onProcessing("AI requested a tool, but AI Tool Execution is disabled in settings.")
                return@repeat
            }

            if (response.contains("FILE_TO_MODIFY:")) {
                toolResults += AIToolExecutionResult(
                    toolName = "tool_protocol",
                    success = false,
                    summary = "The model mixed TOOL_CALL and FILE_TO_MODIFY in a single response.",
                    output = "Return only TOOL_CALL blocks when a tool is needed. After tool results are available, send FILE_TO_MODIFY blocks or plain text in a later response."
                )
                return@repeat
            }

            callback.onProcessing("Executing ${toolCalls.size} tool request(s)...")

            toolCalls.forEach { toolCall ->
                callback.onToolCallStarted(toolCall)
                callback.onProcessing(formatToolStartMessage(toolCall))
                val toolResult = toolExecutor.execute(toolCall)
                toolResults += toolResult
                toolResult.fileChange?.let { fileChange ->
                    val fileName = File(fileChange.filePath).name
                    callback.onFileModifying(fileChange.filePath, fileName)
                    val writeSuccessful = fileChange.writeResult is FileWriteResult.Success
                    callback.onFileModified(fileChange.filePath, fileName, writeSuccessful)
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

        return Result.failure(IllegalStateException("Tool call loop terminated unexpectedly"))
    }

    private fun buildToolContext(toolResults: List<AIToolExecutionResult>): String {
        val toolExecutionEnabled = permissionManager.isToolExecutionEnabled()

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
                appendLine("- Use run_terminal_command for safe non-build terminal commands such as pkg/apt/git/rg/ls/find/cat/head/tail/grep/python/cmake.")
                appendLine("- Use non-interactive flags when possible, for example pkg install -y.")
                appendLine("- Do not request dangerous commands, shell operators, pipes, redirects, or command chaining.")
                appendLine("- After tool results are returned, either request another tool or produce FILE_TO_MODIFY blocks / final text.")
            } else {
                appendLine("Tool execution is disabled in settings.")
                appendLine("Do NOT emit TOOL_CALL blocks while tool execution is disabled.")
                appendLine("If tools are needed, tell the user to enable AI Tool Execution from AI preferences first.")
            }

            if (toolResults.isNotEmpty()) {
                appendLine()
                appendLine("=== TOOL RESULTS ===")
                toolResults.takeLast(4).forEachIndexed { index, result ->
                    appendLine(result.toContextBlock(index + 1))
                    appendLine()
                }
            }
        }.trim()
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
        val currentProviderName = currentAgent?.providerName ?: "Unknown"
        val availableProviders = getAvailableProviders()
            .filter { it.id != currentProviderId && it.isAvailable }
            .map { Pair(it.id, it.name) }
        
        providerSwitchDialog.showProviderErrorDialog(
            currentProviderName,
            errorMessage,
            availableProviders,
            onProviderSelected = { providerId ->
                setProvider(providerId)
                val agents = Agents(context)
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
                    val agents = Agents(context)
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
            currentAgent?.reinitializeWithNewModel(apiKey, context)
        }
    }

    fun getCurrentModelName(): String {
        val agents = Agents(context)
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
        fun onToolCallStarted(toolCall: AIToolCall) {}
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
    val toolModifications: List<BaseFileModification>
)

data class UnifiedModificationAttempt(
    val timestamp: Long,
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val attemptNumber: Int = 0,
    val success: Boolean = false
)
