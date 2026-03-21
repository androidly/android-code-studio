package com.tom.rv2ide.artificial.tools

import com.tom.rv2ide.artificial.file.FileWriteResult

data class AIToolCall(
    val name: String,
    val arguments: Map<String, String>,
    val rawBlock: String
) {
    fun argument(key: String): String? = arguments[key.lowercase()]
}

data class AIToolFileChange(
    val filePath: String,
    val previousContent: String?,
    val newContent: String,
    val writeResult: FileWriteResult
)

data class AIToolExecutionResult(
    val toolName: String,
    val success: Boolean,
    val summary: String,
    val output: String,
    val executedCommand: String? = null,
    val workingDirectory: String? = null,
    val exitCode: Int? = null,
    val fileChange: AIToolFileChange? = null
) {
    fun toContextBlock(index: Int): String {
        return buildString {
            appendLine("TOOL_RESULT #$index")
            appendLine("NAME: $toolName")
            appendLine("SUCCESS: $success")
            executedCommand?.takeIf { it.isNotBlank() }?.let { appendLine("COMMAND: $it") }
            workingDirectory?.takeIf { it.isNotBlank() }?.let { appendLine("WORKDIR: $it") }
            exitCode?.let { appendLine("EXIT_CODE: $it") }
            fileChange?.let { appendLine("FILE_CHANGED: ${it.filePath}") }
            appendLine("SUMMARY: $summary")
            if (output.isNotBlank()) {
                appendLine("OUTPUT:")
                appendLine(output)
            }
        }.trim()
    }
}
