package com.tom.rv2ide.artificial.agents

import com.tom.rv2ide.artificial.tools.AIToolCall
import com.tom.rv2ide.artificial.tools.AIToolExecutionResult
import java.io.File
import kotlin.math.max
import kotlin.math.min

internal object AIToolLoopGuard {

    private val loopSensitiveToolNames = setOf(
        "build_project",
        "run_terminal_command",
        "replace_file_range"
    )

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

        if (!current.success && current.toolName in loopSensitiveToolNames) {
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

    private fun normalizeToolArgument(value: String): String {
        return value.trim().replace(Regex("\\s+"), " ")
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
    internal class Tracker {

        private val toolSignatureHistory = mutableListOf<String>()
        private val toolOutcomeHistory = mutableListOf<ToolExecutionFingerprint>()

        fun registerToolCalls(toolCalls: List<AIToolCall>): String? {
            val toolSignature = buildToolCallSignature(toolCalls)
            val loopMessage = detectToolLoop(toolSignatureHistory, toolSignature)
            if (loopMessage == null) {
                toolSignatureHistory += toolSignature
            }
            return loopMessage
        }

        fun registerToolResult(
            toolResult: AIToolExecutionResult,
            successfulFileChangeCount: Int
        ): String? {
            val loopMessage = detectToolResultLoop(
                toolOutcomeHistory = toolOutcomeHistory,
                toolResult = toolResult,
                successfulFileChangeCount = successfulFileChangeCount
            )
            if (loopMessage == null) {
                toolOutcomeHistory += buildToolExecutionFingerprint(
                    toolResult = toolResult,
                    successfulFileChangeCount = successfulFileChangeCount
                )
            }
            return loopMessage
        }
    }

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
}
