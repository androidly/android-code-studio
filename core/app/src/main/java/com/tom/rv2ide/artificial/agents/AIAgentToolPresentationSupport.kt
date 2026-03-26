package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.file.FileWriteResult
import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
import java.io.File

internal object AIAgentToolPresentationSupport {

    internal enum class PreviewDecision {
        WAIT,
        SHOW,
        SUPPRESS
    }

    fun resolveAssistantPreviewDecision(
        content: String,
        isFinalResponse: Boolean = false
    ): PreviewDecision {
        val trimmedStart = content.trimStart()
        if (trimmedStart.startsWith("TOOL_CALL:") || trimmedStart.startsWith("FILE_TO_MODIFY:")) {
            return PreviewDecision.SUPPRESS
        }

        if (isFinalResponse) {
            return if (trimmedStart.isBlank()) {
                PreviewDecision.SUPPRESS
            } else {
                PreviewDecision.SHOW
            }
        }

        if (trimmedStart.isBlank()) {
            return PreviewDecision.WAIT
        }

        return if (trimmedStart.length >= 24 || trimmedStart.contains('\n')) {
            PreviewDecision.SHOW
        } else {
            PreviewDecision.WAIT
        }
    }

    fun resolveNativeAssistantPreviewDecision(
        content: String,
        isFinalResponse: Boolean = false
    ): PreviewDecision {
        val trimmedStart = content.trimStart()

        if (isFinalResponse) {
            return if (trimmedStart.isBlank()) {
                PreviewDecision.SUPPRESS
            } else {
                PreviewDecision.SHOW
            }
        }

        if (trimmedStart.startsWith("TOOL_CALL:")) {
            return PreviewDecision.SUPPRESS
        }

        if (trimmedStart.isBlank()) {
            return PreviewDecision.WAIT
        }

        return PreviewDecision.SHOW
    }

    fun buildToolContext(
        toolExecutionEnabled: Boolean,
        sessionContext: String,
        toolResults: List<AIToolExecutionResult>
    ): String {
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

    fun buildForcedFinalizationPrompt(
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

    fun buildForcedFinalizationContext(
        sessionContext: String,
        toolResults: List<AIToolExecutionResult>,
        toolModifications: List<BaseFileModification>,
        loopReason: String
    ): String {
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

    fun buildCompactToolResultSummary(toolResult: AIToolExecutionResult): String {
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

    fun buildForcedFinalFallback(
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

    fun containsProtocolBlocks(text: String): Boolean {
        val normalized = text.trimStart()
        return normalized.startsWith("TOOL_CALL:") ||
            normalized.startsWith("FILE_TO_MODIFY:") ||
            "\nTOOL_CALL:" in normalized ||
            "\nFILE_TO_MODIFY:" in normalized
    }

    fun formatToolStartMessage(toolCall: AIToolCall): String {
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

    fun formatToolEndMessage(toolResult: AIToolExecutionResult): String {
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
}
