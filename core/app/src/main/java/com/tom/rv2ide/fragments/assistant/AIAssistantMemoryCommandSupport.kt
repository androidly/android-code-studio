package com.tom.rv2ide.fragments.assistant

import java.io.File

internal sealed interface AIAssistantMemoryCommand {
    data object ShowProject : AIAssistantMemoryCommand
    data object ShowGlobal : AIAssistantMemoryCommand
    data object Help : AIAssistantMemoryCommand
    data class AddProject(val text: String) : AIAssistantMemoryCommand
    data class AddGlobal(val text: String) : AIAssistantMemoryCommand
    data object Invalid : AIAssistantMemoryCommand
}

internal object AIAssistantMemoryCommandSupport {
    private const val MAX_MEMORY_FILE_PREVIEW_CHARS = 12_000

    fun parse(rawArguments: String): AIAssistantMemoryCommand {
        val tokens = rawArguments.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        return when {
            tokens.isEmpty() || tokens.first().equals("show", ignoreCase = true) -> {
                AIAssistantMemoryCommand.ShowProject
            }
            tokens.first().equals("help", ignoreCase = true) -> {
                AIAssistantMemoryCommand.Help
            }
            tokens.first().equals("global", ignoreCase = true) && tokens.size == 1 -> {
                AIAssistantMemoryCommand.ShowGlobal
            }
            tokens.first().equals("global", ignoreCase = true) &&
                (tokens.getOrNull(1)?.equals("add", ignoreCase = true) == true) -> {
                AIAssistantMemoryCommand.AddGlobal(tokens.drop(2).joinToString(" "))
            }
            tokens.first().equals("add", ignoreCase = true) -> {
                AIAssistantMemoryCommand.AddProject(tokens.drop(1).joinToString(" "))
            }
            else -> AIAssistantMemoryCommand.Invalid
        }
    }

    fun buildHelpItem(): AIAssistantStatusItem {
        return AIAssistantStatusItem(
            title = "Memory",
            body = buildString {
                appendLine("/memory")
                appendLine("/memory add <text>")
                appendLine("/memory global")
                append("/memory global add <text>")
            },
            tone = AIAssistantTone.NEUTRAL
        )
    }

    fun buildShowFileItem(
        file: File,
        isGlobal: Boolean
    ): AIAssistantStatusItem {
        val label = if (isGlobal) "Global memory" else "Project memory"
        if (!file.exists()) {
            return AIAssistantStatusItem(
                title = "Memory",
                body = "$label file is empty.\nPath: ${file.absolutePath}",
                tone = AIAssistantTone.NEUTRAL
            )
        }

        val content = runCatching { file.readText() }.getOrNull().orEmpty().trim()
        return AIAssistantStatusItem(
            title = "Memory",
            body = if (content.isBlank()) {
                "$label file is empty.\nPath: ${file.absolutePath}"
            } else {
                buildString {
                    appendLine("$label · ${file.absolutePath}")
                    appendLine()
                    append(
                        if (content.length > MAX_MEMORY_FILE_PREVIEW_CHARS) {
                            content.take(MAX_MEMORY_FILE_PREVIEW_CHARS) + "\n\n... (truncated)"
                        } else {
                            content
                        }
                    )
                }.trim()
            },
            tone = AIAssistantTone.NEUTRAL
        )
    }

    fun buildAppendFileItem(
        file: File,
        text: String
    ): AIAssistantStatusItem {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return AIAssistantStatusItem(
                title = "Memory",
                body = "Usage: /memory add <text>",
                tone = AIAssistantTone.WARNING
            )
        }

        return runCatching {
            file.parentFile?.mkdirs()
            val existing = if (file.exists()) file.readText() else ""
            val separator = when {
                existing.isBlank() -> ""
                existing.endsWith('\n') -> ""
                else -> "\n"
            }
            file.writeText(existing + separator + "- $normalizedText\n")
            AIAssistantStatusItem(
                title = "Memory",
                body = "Saved to ${file.absolutePath}",
                tone = AIAssistantTone.SUCCESS
            )
        }.getOrElse { error ->
            AIAssistantStatusItem(
                title = "Memory",
                body = error.message ?: "Unable to update the memory file.",
                tone = AIAssistantTone.ERROR
            )
        }
    }

    fun buildUsageItem(): AIAssistantStatusItem {
        return AIAssistantStatusItem(
            title = "Memory",
            body = "Usage: /memory [add|global|global add|help]",
            tone = AIAssistantTone.WARNING
        )
    }
}
