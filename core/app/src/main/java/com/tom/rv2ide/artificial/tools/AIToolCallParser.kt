package com.tom.rv2ide.artificial.tools

object AIToolCallParser {

    private val keyLinePattern = Regex("^([A-Z][A-Z0-9_]*):(.*)$")

    fun parseToolCalls(response: String): List<AIToolCall> {
        if (!response.contains("TOOL_CALL:")) {
            return emptyList()
        }

        val toolCalls = mutableListOf<AIToolCall>()
        val lines = response.lines()
        var currentName: String? = null
        var currentArgs = linkedMapOf<String, String>()
        var currentRaw = mutableListOf<String>()
        var currentMultilineKey: String? = null
        var currentMultilineValue = mutableListOf<String>()

        fun flushMultilineArgument() {
            val key = currentMultilineKey ?: return
            currentArgs[key] = currentMultilineValue.joinToString("\n").trimEnd()
            currentMultilineKey = null
            currentMultilineValue = mutableListOf()
        }

        fun flushCurrent() {
            flushMultilineArgument()
            val toolName = currentName?.trim().orEmpty()
            if (toolName.isBlank()) {
                currentName = null
                currentArgs = linkedMapOf()
                currentRaw = mutableListOf()
                currentMultilineKey = null
                currentMultilineValue = mutableListOf()
                return
            }

            toolCalls.add(
                AIToolCall(
                    name = toolName,
                    arguments = currentArgs.toMap(),
                    rawBlock = currentRaw.joinToString("\n").trim()
                )
            )

            currentName = null
            currentArgs = linkedMapOf()
            currentRaw = mutableListOf()
        }

        for (line in lines) {
            when {
                line.startsWith("FILE_TO_MODIFY:") -> {
                    flushCurrent()
                    break
                }

                line.startsWith("TOOL_CALL:") -> {
                    flushCurrent()
                    currentName = line.substringAfter("TOOL_CALL:").trim()
                    currentRaw.add(line)
                }

                currentName != null -> {
                    val trimmed = line.trim()
                    val keyMatch = keyLinePattern.matchEntire(trimmed)

                    if (keyMatch != null) {
                        flushMultilineArgument()
                        currentRaw.add(line)

                        val key = keyMatch.groupValues[1].trim().lowercase()
                        val value = keyMatch.groupValues[2].trimStart()
                        if (key != "tool_call") {
                            if (value.isEmpty()) {
                                currentMultilineKey = key
                            } else {
                                currentArgs[key] = value.trim()
                            }
                        }
                    } else {
                        currentRaw.add(line)
                        if (currentMultilineKey != null) {
                            currentMultilineValue.add(line)
                        }
                    }
                }
            }
        }

        flushCurrent()
        return toolCalls
    }
}
