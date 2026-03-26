package com.tom.rv2ide.artificial.agents.custom

import com.tom.rv2ide.artificial.agents.ModificationAttempt
import com.tom.rv2ide.artificial.project.awareness.ProjectTreeResult
import java.io.File

internal object CustomProviderPromptContextSupport {
    fun buildPrompt(
        prompt: String,
        context: String?,
        projectTreeResult: ProjectTreeResult?,
        conversationHistory: List<CustomConversationMessage>,
        modificationHistory: List<ModificationAttempt>,
        currentAttemptCount: Int,
        maxRetryAttempts: Int
    ): String {
        val needsCorrection = isUserRequestingCorrection(prompt)

        return buildString {
            append("=== PROJECT STRUCTURE (THESE ARE THE EXACT PATHS YOU MUST USE) ===\n")
            if (projectTreeResult != null) {
                append(projectTreeResult.tree)
                append("\n\n")
                append("CRITICAL: Use ONLY the paths shown above. Do NOT make up fake paths like '/storage/emulated/0/project' or 'com.example.yourproject'.\n")
                append("CRITICAL: Look at the actual paths above and use those EXACT paths.\n\n")
            }

            append("IMPORTANT: File contents are not preloaded. Use project search and focused file-range reads to inspect only the relevant 50-200 lines before editing.\n\n")

            if (context != null) {
                append("=== ADDITIONAL CONTEXT ===\n")
                append(context)
                append("\n\n")
            }

            if (conversationHistory.isNotEmpty()) {
                append("=== CONVERSATION HISTORY ===\n")
                conversationHistory.forEach { message ->
                    append("${message.role.uppercase()}: ${message.content}\n\n")
                }
            }

            if (needsCorrection && modificationHistory.isNotEmpty()) {
                append("=== CORRECTION REQUIRED ===\n")
                append("The user indicated the previous modification was WRONG.\n")
                append("Previous failed attempts:\n")
                modificationHistory.takeLast(3).forEach { attempt ->
                    append("Attempt ${attempt.attemptNumber}: ${attempt.filePath}\n")
                    append("Result: ${if (attempt.success) "Applied but user rejected" else "Failed"}\n\n")
                }
                append("You MUST try a DIFFERENT approach. Do NOT repeat the same solution.\n")
                append("Analyze what went wrong and provide a better solution.\n\n")
            }

            if (currentAttemptCount > 0) {
                append("=== RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts ===\n")
                append("This is retry attempt number $currentAttemptCount.\n")
                append("Previous attempts did not satisfy the user.\n")
                append("Think carefully and provide a different solution.\n\n")
            }

            append("=== USER REQUEST ===\n")
            append(prompt)
        }
    }

    fun buildNativeUserTurn(
        prompt: String,
        modificationHistory: List<ModificationAttempt>,
        currentAttemptCount: Int,
        maxRetryAttempts: Int
    ): String {
        val needsCorrection = isUserRequestingCorrection(prompt)

        return buildString {
            if (needsCorrection && modificationHistory.isNotEmpty()) {
                appendLine("CORRECTION REQUIRED")
                appendLine("The previous implementation did not satisfy the user.")
                modificationHistory.takeLast(3).forEach { attempt ->
                    appendLine(
                        "- Attempt ${attempt.attemptNumber}: ${attempt.filePath} (${if (attempt.success) "applied but rejected" else "failed"})"
                    )
                }
                appendLine("Use a different approach.")
                appendLine()
            }

            if (currentAttemptCount > 0) {
                appendLine("RETRY ATTEMPT $currentAttemptCount/$maxRetryAttempts")
                appendLine("Do not repeat the same solution.")
                appendLine()
            }

            append(prompt.trim())
        }.trim()
    }

    fun buildNativeSystemPrompt(
        toolExecutionEnabled: Boolean,
        projectTreeResult: ProjectTreeResult?
    ): String {
        return buildString {
            appendLine("You are ACS AI Agent inside AndroidCodeStudio.")
            appendLine("Operate like a coding CLI agent with persistent multi-turn memory.")
            appendLine("All prior user requests, assistant outputs, tool calls, and tool results in this session remain relevant unless the user changes direction.")
            appendLine("Continue the existing task unless the user clearly changes direction.")
            appendLine("Runtime environment is Android with a Termux-style shell and common CLI tools such as rg, grep, sed, head, tail, cat, find, git, pkg, apt, curl, wget, stat, tree, du, df, ps, cp, mkdir, and touch.")
            appendLine("Do not assume the project tree is preloaded into the prompt. Use tools to discover paths and inspect focused ranges.")
            appendLine("Prefer search and focused reads before editing. Read only the relevant 50-200 lines when possible.")
            appendLine("Prefer precise range edits instead of rewriting whole files.")
            appendLine("Use exact project paths returned by the tools. Do not invent paths.")
            appendLine("When build validation is useful, run a build instead of guessing, but if the same build or command fails twice without any file edits, stop and explain the blocker instead of looping.")
            appendLine("Keep answers concise when no code or tool action is needed.")
            if (toolExecutionEnabled) {
                appendLine("Use native tool calls whenever tools are needed.")
                appendLine("Do not emit raw TOOL_CALL text blocks.")
                appendLine("Do not emit FILE_TO_MODIFY blocks while native tools are available.")
                appendLine("Use run_terminal_command only for a single safe command without chaining, pipes, or redirects.")
                appendLine("Never use 'cd ... && ...'. Put the directory in WORKDIR and keep COMMAND to a single command.")
                appendLine("Use non-interactive flags for package installation and other commands when available.")
                appendLine("If a command is blocked by safety policy or not in the allowlist, do not repeat it verbatim. Switch to another supported command or a different tool.")
                appendLine("Prefer build_project for gradle/gradlew builds. Direct gradlew terminal commands may be rerouted as builds.")
            } else {
                appendLine("Tool execution is disabled. Do not request tools or invent tool results.")
            }

            projectTreeResult?.tree
                ?.lineSequence()
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { rootPath ->
                    appendLine()
                    appendLine("Project root: $rootPath")
                }
        }.trim()
    }

    fun currentNativeConversation(
        committedToolConversation: List<CustomProviderTurn>,
        activeToolTurn: List<CustomProviderTurn>,
        condensedNativeConversation: List<String>
    ): List<CustomProviderTurn> {
        return buildList {
            buildCondensedConversationTurn(condensedNativeConversation)?.let(::add)
            addAll(committedToolConversation)
            addAll(activeToolTurn)
        }
    }

    fun buildCondensedConversationTurn(
        condensedNativeConversation: List<String>
    ): CustomProviderTurn? {
        if (condensedNativeConversation.isEmpty()) {
            return null
        }

        return CustomProviderTurn.User(
            buildString {
                appendLine("SESSION SUMMARY FROM EARLIER TURNS")
                appendLine("Treat this summary as authoritative context that replaces older compacted turns.")
                condensedNativeConversation.forEach { summaryEntry ->
                    append("- ")
                    appendLine(summaryEntry)
                }
            }.trim()
        )
    }

    fun autoCompactNativeConversationIfNeeded(
        toolExecutionEnabled: Boolean,
        committedToolConversation: MutableList<CustomProviderTurn>,
        activeToolTurn: List<CustomProviderTurn>,
        condensedNativeConversation: MutableList<String>,
        config: CustomProviderConversationCompactionConfig,
        projectTreeResult: ProjectTreeResult?,
        onCompacted: () -> Unit
    ) {
        CustomProviderConversationCompactionSupport.autoCompactIfNeeded(
            toolExecutionEnabled = toolExecutionEnabled,
            committedToolConversation = committedToolConversation,
            condensedNativeConversation = condensedNativeConversation,
            config = config,
            estimateConversationTokens = { executionEnabled ->
                estimateNativeConversationTokens(
                    toolExecutionEnabled = executionEnabled,
                    committedToolConversation = committedToolConversation,
                    activeToolTurn = activeToolTurn,
                    condensedNativeConversation = condensedNativeConversation,
                    projectTreeResult = projectTreeResult
                )
            },
            summarizeTurns = CustomProviderConversationCompactionSupport::summarizeTurns,
            onCompacted = onCompacted
        )
    }

    fun estimateNativeConversationTokens(
        toolExecutionEnabled: Boolean,
        committedToolConversation: List<CustomProviderTurn>,
        activeToolTurn: List<CustomProviderTurn>,
        condensedNativeConversation: List<String>,
        projectTreeResult: ProjectTreeResult?
    ): Int {
        return CustomProviderConversationCompactionSupport.estimateConversationTokens(
            toolExecutionEnabled = toolExecutionEnabled,
            systemPrompt = buildNativeSystemPrompt(toolExecutionEnabled, projectTreeResult),
            condensedTurn = buildCondensedConversationTurn(condensedNativeConversation),
            committedTurns = committedToolConversation,
            activeTurns = activeToolTurn
        )
    }

    fun normalizeJsonObjectString(rawJson: String): String {
        val trimmed = rawJson.trim()
        if (trimmed.isBlank()) {
            return "{}"
        }

        return trimmed.toJsonObjectOrNull()?.toString() ?: trimmed
    }

    fun readRelevantFiles(projectTreeResult: ProjectTreeResult?): Map<String, String> {
        val filesContent = mutableMapOf<String, String>()
        val tree = projectTreeResult?.tree ?: return filesContent

        tree.lines()
            .filter { it.isNotBlank() }
            .forEach { filePath ->
                val trimmedPath = filePath.trim()
                val file = File(trimmedPath)

                if (file.isFile &&
                    (trimmedPath.endsWith(".kt") ||
                        trimmedPath.endsWith(".java") ||
                        trimmedPath.endsWith(".xml") ||
                        trimmedPath.endsWith(".gradle") ||
                        trimmedPath.endsWith(".gradle.kts")) &&
                    !trimmedPath.contains("/build/") &&
                    !trimmedPath.contains("/.gradle/")
                ) {
                    try {
                        filesContent[trimmedPath] = file.readText()
                    } catch (_: Exception) {
                    }
                }
            }

        return filesContent
    }

    fun isUserRequestingCorrection(message: String): Boolean {
        val correctionKeywords = listOf(
            "wrong", "not what", "mistake", "error", "incorrect",
            "that's not", "not right", "fix", "undo", "revert",
            "different", "try again", "not working"
        )
        return correctionKeywords.any { message.lowercase().contains(it) }
    }
}
