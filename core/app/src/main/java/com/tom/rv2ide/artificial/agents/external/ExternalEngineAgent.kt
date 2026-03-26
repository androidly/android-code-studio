package com.tom.rv2ide.artificial.agents.external

import android.content.Context
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.artificial.agents.AIAgent
import com.tom.rv2ide.artificial.agents.AIAgentRegistry
import com.tom.rv2ide.artificial.agents.AIAgentStreamListener
import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.agents.PersistentConversationAgent
import com.tom.rv2ide.artificial.agents.addBoundedModificationAttempt
import com.tom.rv2ide.artificial.file.AIFileWriter
import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

open class ExternalEngineException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ExternalEngineConfigurationException(message: String) : ExternalEngineException(message)

class ExternalEngineExecutionException(message: String, cause: Throwable? = null) :
    ExternalEngineException(message, cause)

class ExternalEngineAgent : AIAgent, PersistentConversationAgent {

    override val providerId: String = "external"
    override val providerName: String = "Codex CLI"

    private var appContext: Context? = null
    private var fileWriter: AIFileWriter? = null
    private var settings: ExternalEngineSettings? = null
    private var projectRootPath: String? = null
    private var conversationSessionId: String = DEFAULT_CONVERSATION_SESSION_ID
    private val modificationHistory = mutableListOf<ModificationAttempt>()
    private var currentAttemptCount: Int = 0

    companion object {
        private const val DEFAULT_CONVERSATION_SESSION_ID = "default"
        private const val MAX_CAPTURED_OUTPUT_CHARS = 180_000
        private const val SESSION_STATE_THREAD_ID = "thread_id"
        private const val SESSION_STATE_UPDATED_AT_MS = "updated_at_ms"

        fun registerAgent() {
            AIAgentRegistry.register("external", object : AIAgentRegistry.AgentFactory {
                override fun create(context: Context): AIAgent {
                    return ExternalEngineAgent()
                }

                override fun hasValidApiKey(): Boolean {
                    return ExternalEngineConfig.hasValidConfig()
                }

                override fun getApiKey(): String? {
                    return ExternalEngineConfig.getCommandTemplate().takeIf { it.isNotBlank() }
                }
            })
        }
    }

    override fun initialize(apiKey: String, context: Context) {
        val resolvedSettings = CodexTermuxBridge.ensureManagedPreset(context = context)
        if (!resolvedSettings.isValid) {
            throw ExternalEngineConfigurationException("Codex CLI is not configured")
        }

        appContext = context.applicationContext
        fileWriter = AIFileWriter(context.applicationContext)
        settings = resolvedSettings
    }

    override fun reinitializeWithNewModel(apiKey: String, context: Context) {
        initialize(apiKey, context)
    }

    override fun setContext(context: Context) {
        appContext = context.applicationContext
        fileWriter = AIFileWriter(context.applicationContext)
    }

    override fun setProjectData(projectTreeResult: ProjectTreeResult) {
        projectRootPath = projectTreeResult.tree
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
    }

    override fun setConversationSessionId(sessionId: String) {
        conversationSessionId = sessionId.trim().ifBlank { DEFAULT_CONVERSATION_SESSION_ID }
    }

    override fun clearConversation() {
        modificationHistory.clear()
        currentAttemptCount = 0
        currentSessionFile()?.let { sessionFile ->
            runCatching {
                if (sessionFile.exists()) {
                    sessionFile.delete()
                }
            }
        }
    }

    override suspend fun generateCode(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?
    ): Result<String> {
        return generateCodeStreaming(
            prompt = prompt,
            context = context,
            language = language,
            projectStructure = projectStructure,
            listener = object : AIAgentStreamListener {
                override fun onTextDelta(delta: String) = Unit
            }
        )
    }

    override suspend fun generateCodeStreaming(
        prompt: String,
        context: String?,
        language: String,
        projectStructure: String?,
        listener: AIAgentStreamListener
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            CodexTermuxBridge.status().runtimeIssue()?.let { issue ->
                return@withContext Result.failure(ExternalEngineConfigurationException(issue))
            }
            val resolvedSettings = settings ?: return@withContext Result.failure(
                ExternalEngineConfigurationException("Codex CLI is not configured")
            )
            val contextRef = appContext ?: return@withContext Result.failure(
                ExternalEngineConfigurationException("Codex CLI context is unavailable")
            )

            val promptPayload = buildPromptPayload(prompt, context)
            val workingDirectory = resolveWorkingDirectory(resolvedSettings, contextRef)
            val projectRoot = resolveProjectRoot(workingDirectory)
            val useManagedCodexRuntime =
                CodexTermuxBridge.isManagedCodexTemplate(resolvedSettings.commandTemplate)
            val sessionFile = currentSessionFile(workingDirectory)
                ?: return@withContext Result.failure(
                    ExternalEngineExecutionException("Unable to create session file")
                )
            val promptFile = if (useManagedCodexRuntime) {
                null
            } else {
                preparePromptFile(contextRef, promptPayload)
            }
            val launchConfiguration = when {
                useManagedCodexRuntime ||
                    resolvedSettings.commandTemplate.contains("{codex_shell_setup}") ||
                    resolvedSettings.commandTemplate.contains("{codex_config_flags}") ->
                    CodexTermuxBridge.prepareLaunchConfiguration(contextRef)
                else -> null
            }
            val command = if (useManagedCodexRuntime) {
                null
            } else {
                renderCommandTemplate(
                    commandTemplate = resolvedSettings.commandTemplate,
                    promptPayload = promptPayload,
                    projectRoot = projectRoot,
                    workingDirectory = workingDirectory,
                    sessionFile = sessionFile,
                    promptFile = promptFile
                        ?: return@withContext Result.failure(
                            ExternalEngineExecutionException("Unable to create prompt file")
                        ),
                    launchConfiguration = launchConfiguration,
                    codexResumeArgs = resolveCodexResumeArgs(
                        sessionFile = sessionFile,
                        launchConfiguration = launchConfiguration
                    )
                )
            }
            val managedCodexArgs = if (useManagedCodexRuntime) {
                buildManagedCodexArgs(
                    promptPayload = promptPayload,
                    workingDirectory = workingDirectory,
                    sessionFile = sessionFile,
                    launchConfiguration = launchConfiguration
                        ?: return@withContext Result.failure(
                            ExternalEngineExecutionException("Codex launch configuration is unavailable")
                        )
                )
            } else {
                null
            }

            val response = executeExternalCommand(
                context = contextRef,
                command = command,
                argv = managedCodexArgs,
                workingDirectory = workingDirectory,
                sessionFile = sessionFile,
                promptPayload = promptPayload,
                passPromptViaStdin = if (useManagedCodexRuntime) {
                    false
                } else {
                    resolvedSettings.passPromptViaStdin
                },
                listener = listener,
                launchConfiguration = launchConfiguration,
                useCodexJsonStream = useManagedCodexRuntime ||
                    (
                        launchConfiguration != null &&
                            command != null &&
                            command.contains("codex exec") &&
                            command.contains("--json")
                        )
            )

            promptFile?.let { runCatching { it.delete() } }
            Result.success(response)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ExternalEngineException) {
            Result.failure(error)
        } catch (error: Exception) {
            Result.failure(
                ExternalEngineExecutionException(
                    message = error.message ?: "External Engine execution failed",
                    cause = error
                )
            )
        }
    }

    override fun recordModification(
        filePath: String,
        oldContent: String?,
        newContent: String,
        success: Boolean
    ) {
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
        val lastSuccessfulModification = modificationHistory.lastOrNull { it.success } ?: return false
        return if (lastSuccessfulModification.previousContent != null) {
            val writeResult = writeFile(
                lastSuccessfulModification.filePath,
                lastSuccessfulModification.previousContent
            )
            if (writeResult is FileWriteResult.Success) {
                modificationHistory.remove(lastSuccessfulModification)
                true
            } else {
                false
            }
        } else {
            runCatching {
                File(lastSuccessfulModification.filePath).delete()
                modificationHistory.remove(lastSuccessfulModification)
                true
            }.getOrDefault(false)
        }
    }

    override fun getModificationHistory(): List<ModificationAttempt> {
        return modificationHistory.toList()
    }

    override fun resetAttemptCount() {
        currentAttemptCount = 0
    }

    override fun incrementAttemptCount() {
        currentAttemptCount += 1
    }

    override fun getCurrentAttemptCount(): Int {
        return currentAttemptCount
    }

    override fun canRetry(): Boolean {
        return currentAttemptCount < 1
    }

    override fun writeFile(filePath: String, content: String): FileWriteResult {
        return fileWriter?.writeFile(filePath, content)
            ?: FileWriteResult.Error("Codex CLI file writer is not initialized")
    }

    override fun isInitialized(): Boolean {
        return settings?.isValid == true && appContext != null
    }

    override fun persistentConversationFingerprint(): String {
        return settings?.fingerprint() ?: ExternalEngineConfig.fingerprint()
    }

    override fun exportPersistentConversationState(): String {
        val sessionFile = currentSessionFile()
        return JSONObject()
            .put("provider", providerId)
            .put("fingerprint", persistentConversationFingerprint())
            .put("conversationSessionId", conversationSessionId)
            .put("sessionFile", sessionFile?.absolutePath.orEmpty())
            .put("threadId", sessionFile?.let(::readStoredThreadId))
            .toString()
    }

    override fun importPersistentConversationState(serializedState: String) {
        if (serializedState.isBlank()) {
            return
        }

        val state = JSONObject(serializedState)
        val restoredFingerprint = state.optString("fingerprint").trim()
        if (restoredFingerprint.isNotBlank() && restoredFingerprint != persistentConversationFingerprint()) {
            throw ExternalEngineConfigurationException("Codex CLI configuration changed")
        }
        val restoredThreadId = state.optString("threadId").trim()
        if (restoredThreadId.isNotBlank()) {
            currentSessionFile()?.let { sessionFile ->
                if (!sessionFile.exists() || readStoredThreadId(sessionFile).isNullOrBlank()) {
                    writeStoredThreadId(sessionFile, restoredThreadId)
                }
            }
        }
    }

    private fun ensureInitialized() {
        if (!isInitialized()) {
            throw ExternalEngineConfigurationException("Codex CLI is not configured")
        }
    }

    private fun buildPromptPayload(prompt: String, context: String?): String {
        val normalizedPrompt = prompt.trim()
        val normalizedContext = context?.trim().orEmpty()
        if (normalizedContext.isBlank()) {
            return normalizedPrompt
        }

        return buildString {
            appendLine(normalizedPrompt)
            appendLine()
            appendLine("[AndroidCodeStudio session context]")
            append(normalizedContext)
        }.trim()
    }

    private fun resolveWorkingDirectory(
        settings: ExternalEngineSettings,
        context: Context
    ): File {
        return when (settings.workingDirectoryMode) {
            ExternalWorkingDirectoryMode.PROJECT_ROOT -> resolveProjectRoot(null) ?: Environment.HOME
            ExternalWorkingDirectoryMode.HOME -> Environment.HOME
            ExternalWorkingDirectoryMode.PREFIX -> Environment.PREFIX
        }.takeIf { it.exists() } ?: context.filesDir
    }

    private fun resolveProjectRoot(fallback: File?): File? {
        val parsedRoot = projectRootPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.exists() }
        return parsedRoot ?: fallback
    }

    private fun currentSessionFile(defaultWorkingDirectory: File? = null): File? {
        val context = appContext ?: return null
        val rootIdentity = projectRootPath
            ?.takeIf { it.isNotBlank() }
            ?: defaultWorkingDirectory?.absolutePath
            ?: "__global__"
        val rootDigest = digest(rootIdentity)
        val sessionDigest = digest(conversationSessionId)
        val configDigest = settings?.fingerprint() ?: ExternalEngineConfig.fingerprint()
        val sessionDir = File(context.filesDir, "ai_external_engine/sessions").apply { mkdirs() }
        return File(sessionDir, "${configDigest}_${rootDigest}_${sessionDigest}.session")
    }

    private fun preparePromptFile(context: Context, promptPayload: String): File {
        val promptDir = File(context.cacheDir, "ai_external_engine/prompts").apply { mkdirs() }
        return File.createTempFile("prompt_", ".txt", promptDir).apply {
            writeText(promptPayload)
        }
    }

    private fun renderCommandTemplate(
        commandTemplate: String,
        promptPayload: String,
        projectRoot: File?,
        workingDirectory: File,
        sessionFile: File,
        promptFile: File,
        launchConfiguration: CodexTermuxBridge.CodexLaunchConfiguration?,
        codexResumeArgs: String
    ): String {
        return commandTemplate
            .replace("{codex_shell_setup}", launchConfiguration?.shellSetup.orEmpty())
            .replace("{codex_config_flags}", launchConfiguration?.cliConfigFlags.orEmpty())
            .replace("{codex_resume_args}", codexResumeArgs)
            .replace("{project_root}", shellQuote((projectRoot ?: workingDirectory).absolutePath))
            .replace("{working_directory}", shellQuote(workingDirectory.absolutePath))
            .replace("{session_file}", shellQuote(sessionFile.absolutePath))
            .replace("{prompt_file}", shellQuote(promptFile.absolutePath))
            .replace("{prompt}", shellQuote(promptPayload))
    }

    private suspend fun executeExternalCommand(
        context: Context,
        command: String?,
        argv: List<String>?,
        workingDirectory: File,
        sessionFile: File,
        promptPayload: String,
        passPromptViaStdin: Boolean,
        listener: AIAgentStreamListener,
        launchConfiguration: CodexTermuxBridge.CodexLaunchConfiguration?,
        useCodexJsonStream: Boolean
    ): String {
        val processBuilder = if (!argv.isNullOrEmpty()) {
            ProcessBuilder(argv)
        } else {
            val resolvedCommand = command
                ?: throw ExternalEngineExecutionException("External engine command is empty")
            val shell = when {
                Environment.BASH_SHELL.exists() -> Environment.BASH_SHELL.absolutePath
                else -> "/system/bin/sh"
            }
            val shellArgs = if (shell.endsWith("bash")) {
                listOf(shell, "-lc", resolvedCommand)
            } else {
                listOf(shell, "-c", resolvedCommand)
            }
            ProcessBuilder(shellArgs)
        }
        processBuilder.directory(workingDirectory)
        processBuilder.redirectErrorStream(true)
        val environment = processBuilder.environment()
        environment.putAll(TermuxShellEnvironment().getEnvironment(IDEApplication.instance, false))

        val customEnvironment = HashMap<String, String>()
        Environment.putEnvironment(customEnvironment, false)
        environment.putAll(customEnvironment)
        sanitizeExternalCliEnvironment(environment)
        ensurePathEnvironment(environment)
        environment.putAll(launchConfiguration?.environmentVariables.orEmpty())

        val process = try {
            processBuilder.start()
        } catch (error: IOException) {
            throw ExternalEngineExecutionException("Failed to start external engine command", error)
        }

        val activeProcess = AtomicReference(process)
        val cancellationHandle = kotlinx.coroutines.currentCoroutineContext()[Job]?.invokeOnCompletion {
            activeProcess.getAndSet(null)?.let { runningProcess ->
                runningProcess.destroy()
                runCatching {
                    if (runningProcess.isAlive) {
                        runningProcess.destroyForcibly()
                    }
                }
            }
        }

        return try {
            if (passPromptViaStdin) {
                process.outputStream.bufferedWriter().use { writer ->
                    writer.write(promptPayload)
                    writer.flush()
                }
            } else {
                runCatching { process.outputStream.close() }
            }

            val codexJsonCollector = if (useCodexJsonStream) {
                ExternalEngineCodexJsonCollector(
                    sessionFile = sessionFile,
                    workingDirectory = workingDirectory,
                    listener = listener,
                    readStoredThreadId = ::readStoredThreadId,
                    writeStoredThreadId = ::writeStoredThreadId
                )
            } else {
                null
            }

            val output = StringBuilder()
            val readerThread = if (useCodexJsonStream) {
                Thread {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) {
                                output.appendLine(line)
                                trimCapturedOutput(output)
                            }
                            codexJsonCollector?.consume(line)
                        }
                    }
                }.apply { start() }
            } else {
                Thread {
                    process.inputStream.bufferedReader().use { reader ->
                        val buffer = CharArray(2048)
                        while (true) {
                            val read = reader.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            val chunk = String(buffer, 0, read)
                            synchronized(output) {
                                output.append(chunk)
                                trimCapturedOutput(output)
                            }
                            listener.onTextDelta(chunk)
                        }
                    }
                }.apply { start() }
            }

            val exitCode = process.waitFor()
            readerThread.join(1_500L)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()

            if (useCodexJsonStream) {
                codexJsonCollector?.finish(
                    capturedOutput = synchronized(output) { output.toString() },
                    exitCode = exitCode
                ) ?: "[external-engine] Process exited with code $exitCode."
            } else {
                val captured = synchronized(output) { output.toString().trim() }
                when {
                    captured.isNotBlank() && exitCode == 0 -> captured
                    captured.isNotBlank() -> "$captured\n\n[external-engine] Process exited with code $exitCode."
                    else -> "[external-engine] Process exited with code $exitCode."
                }
            }
        } catch (cancelled: CancellationException) {
            process.destroy()
            throw cancelled
        } finally {
            activeProcess.set(null)
            cancellationHandle?.dispose()
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    private fun resolveCodexResumeArgs(
        sessionFile: File,
        launchConfiguration: CodexTermuxBridge.CodexLaunchConfiguration?
    ): String {
        if (launchConfiguration == null) {
            return ""
        }
        val storedThreadId = readStoredThreadId(sessionFile)
        return if (storedThreadId.isNullOrBlank()) {
            "-"
        } else {
            "resume ${shellQuote(storedThreadId)} -"
        }
    }

    private fun buildManagedCodexArgs(
        promptPayload: String,
        workingDirectory: File,
        sessionFile: File,
        launchConfiguration: CodexTermuxBridge.CodexLaunchConfiguration
    ): List<String> {
        val executablePath = CodexTermuxBridge.status().executablePath?.trim().takeIf { !it.isNullOrBlank() }
            ?: "codex"
        val storedThreadId = readStoredThreadId(sessionFile)
        return buildList {
            add(executablePath)
            add("exec")
            if (storedThreadId.isNullOrBlank()) {
                add("--skip-git-repo-check")
                addAll(launchConfiguration.cliConfigArgs)
                add("--dangerously-bypass-approvals-and-sandbox")
                add("--json")
                add("--cd")
                add(workingDirectory.absolutePath)
                add(promptPayload)
            } else {
                add("resume")
                add("--skip-git-repo-check")
                addAll(launchConfiguration.cliConfigArgs)
                add("--dangerously-bypass-approvals-and-sandbox")
                add(storedThreadId)
                add("--json")
                add(promptPayload)
            }
        }
    }

    private fun readStoredThreadId(sessionFile: File): String? {
        if (!sessionFile.exists()) {
            return null
        }
        val rawState = runCatching { sessionFile.readText() }.getOrNull()?.trim().orEmpty()
        if (rawState.isBlank()) {
            return null
        }
        val jsonThreadId = runCatching {
            JSONObject(rawState).optString(SESSION_STATE_THREAD_ID).trim()
        }.getOrNull()
        return when {
            !jsonThreadId.isNullOrBlank() -> jsonThreadId
            rawState == "codex-exec" -> null
            else -> rawState
        }
    }

    private fun writeStoredThreadId(sessionFile: File, threadId: String) {
        if (threadId.isBlank()) {
            return
        }
        sessionFile.parentFile?.mkdirs()
        sessionFile.writeText(
            JSONObject()
                .put(SESSION_STATE_THREAD_ID, threadId)
                .put(SESSION_STATE_UPDATED_AT_MS, System.currentTimeMillis())
                .toString()
        )
    }

    private fun ensurePathEnvironment(environment: MutableMap<String, String>) {
        val mergedEntries = linkedSetOf(
            Environment.BIN_DIR.absolutePath,
            File(Environment.PREFIX, "bin").absolutePath
        )
        environment["PATH"]
            .orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach(mergedEntries::add)
        environment["PATH"] = mergedEntries.joinToString(":")
        environment["TMPDIR"] = Environment.TMP_DIR.absolutePath
    }

    private fun sanitizeExternalCliEnvironment(environment: MutableMap<String, String>) {
        // Termux already strips LD_LIBRARY_PATH for normal shells. Reintroducing the app's
        // private library path breaks native launchers such as codex.bin.
        environment.remove("LD_LIBRARY_PATH")
    }

    private fun trimCapturedOutput(buffer: StringBuilder) {
        if (buffer.length <= MAX_CAPTURED_OUTPUT_CHARS) {
            return
        }
        buffer.delete(0, buffer.length - MAX_CAPTURED_OUTPUT_CHARS)
    }

    private fun shellQuote(value: String): String {
        return buildString {
            append('\'')
            value.forEach { character ->
                if (character == '\'') {
                    append("'\\''")
                } else {
                    append(character)
                }
            }
            append('\'')
        }
    }

    private fun digest(value: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(16)
    }
}
