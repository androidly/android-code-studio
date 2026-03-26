package com.tom.rv2ide.artificial.agents.custom

internal data class CustomProviderConversationCompactionConfig(
    val defaultContextWindowTokens: Int,
    val defaultInputWindowTokens: Int,
    val defaultReservedCompactionTokens: Int,
    val defaultMaxGenerationTokens: Int,
    val minCommittedNativeTurnsToKeep: Int,
    val nativeCompressionBatchSize: Int,
    val maxCondensedNativeEntries: Int
)

internal object CustomProviderConversationCompactionSupport {
    fun trimConversationHistory(conversationHistory: MutableList<CustomConversationMessage>) {
        if (conversationHistory.size > 20) {
            conversationHistory.removeAt(0)
            conversationHistory.removeAt(0)
        }
    }

    fun autoCompactIfNeeded(
        toolExecutionEnabled: Boolean,
        committedToolConversation: MutableList<CustomProviderTurn>,
        condensedNativeConversation: MutableList<String>,
        config: CustomProviderConversationCompactionConfig,
        estimateConversationTokens: (Boolean) -> Int,
        summarizeTurns: (List<CustomProviderTurn>) -> String,
        onCompacted: () -> Unit
    ) {
        var compacted = false
        val usableBudget = usableInputBudgetTokens(config)
        while (estimateConversationTokens(toolExecutionEnabled) > usableBudget &&
            committedToolConversation.size > config.minCommittedNativeTurnsToKeep
        ) {
            val removableTurnCount = committedToolConversation.size - config.minCommittedNativeTurnsToKeep
            val batchSize = minOf(config.nativeCompressionBatchSize, removableTurnCount)
            val summarized = summarizeTurns(committedToolConversation.take(batchSize))
            committedToolConversation.subList(0, batchSize).clear()
            compacted = true
            addCondensedSummary(condensedNativeConversation, summarized, config.maxCondensedNativeEntries)
        }
        while (estimateConversationTokens(toolExecutionEnabled) > usableBudget &&
            committedToolConversation.isNotEmpty()
        ) {
            val batchSize = minOf(config.nativeCompressionBatchSize, committedToolConversation.size)
            val summarized = summarizeTurns(committedToolConversation.take(batchSize))
            committedToolConversation.subList(0, batchSize).clear()
            compacted = true
            addCondensedSummary(condensedNativeConversation, summarized, config.maxCondensedNativeEntries)
        }
        if (compacted) {
            onCompacted()
        }
    }

    fun usableInputBudgetTokens(config: CustomProviderConversationCompactionConfig): Int {
        val hardInputBudget = maxOf(
            1,
            minOf(config.defaultInputWindowTokens, config.defaultContextWindowTokens - config.defaultMaxGenerationTokens)
        )
        return maxOf(1, hardInputBudget - minOf(config.defaultReservedCompactionTokens, config.defaultMaxGenerationTokens))
    }

    fun estimateConversationTokens(
        toolExecutionEnabled: Boolean,
        systemPrompt: String,
        condensedTurn: CustomProviderTurn?,
        committedTurns: List<CustomProviderTurn>,
        activeTurns: List<CustomProviderTurn>
    ): Int {
        var total = 64
        total += estimateTextTokens(systemPrompt)
        condensedTurn?.let { total += estimateTurnTokens(it) }
        total += committedTurns.sumOf(::estimateTurnTokens)
        total += activeTurns.sumOf(::estimateTurnTokens)
        return total
    }

    fun estimateTurnTokens(turn: CustomProviderTurn): Int {
        return when (turn) {
            is CustomProviderTurn.User -> estimateTextTokens(turn.text) + 12
            is CustomProviderTurn.Assistant -> {
                estimateTextTokens(turn.text) +
                    turn.toolCalls.sumOf { toolCall ->
                        estimateTextTokens(toolCall.name) +
                            estimateTextTokens(toolCall.argumentsJson) +
                            estimateTextTokens(toolCall.id) +
                            estimateTextTokens(toolCall.responseItemId.orEmpty()) +
                            18
                    } +
                    18
            }
            is CustomProviderTurn.ToolResult ->
                estimateTextTokens(turn.toolCallId) +
                    estimateTextTokens(turn.toolName) +
                    estimateTextTokens(turn.output) +
                    18
        }
    }

    fun estimateTextTokens(text: String): Int {
        if (text.isBlank()) {
            return 0
        }
        val asciiChars = text.count { it.code in 0..127 }
        val nonAsciiChars = text.length - asciiChars
        val newlineCount = text.count { it == '\n' }
        val structuralChars = text.count { char ->
            char == '{' || char == '}' || char == '[' || char == ']' ||
                char == ':' || char == ',' || char == '"' || char == '`'
        }
        val asciiTokens = (asciiChars + 3) / 4
        val nonAsciiTokens = nonAsciiChars
        return asciiTokens + nonAsciiTokens + newlineCount + (structuralChars / 8) + 6
    }

    fun summarizeTurns(turns: List<CustomProviderTurn>): String {
        if (turns.isEmpty()) {
            return ""
        }
        return turns.joinToString(" || ") { turn ->
            when (turn) {
                is CustomProviderTurn.User ->
                    "user requested: ${turn.text.replace(Regex("\\s+"), " ").take(160)}"
                is CustomProviderTurn.Assistant -> {
                    val textSummary = turn.text.replace(Regex("\\s+"), " ").take(160)
                    val toolSummary = turn.toolCalls.joinToString(", ") { it.name }.takeIf { it.isNotBlank() }
                    buildString {
                        if (textSummary.isNotBlank()) {
                            append("assistant responded: $textSummary")
                        }
                        if (toolSummary != null) {
                            if (isNotEmpty()) {
                                append(" | ")
                            }
                            append("assistant used tools: $toolSummary")
                        }
                    }.ifBlank { "assistant used tools without extra text" }
                }
                is CustomProviderTurn.ToolResult -> {
                    val summaryLine = Regex("(?m)^SUMMARY:\\s*(.+)$")
                        .find(turn.output)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        .orEmpty()
                    buildString {
                        append("tool ")
                        append(turn.toolName)
                        append(if (turn.isError) " returned error: " else " returned: ")
                        append(summaryLine.ifBlank { turn.output.replace(Regex("\\s+"), " ").take(140) })
                    }
                }
            }
        }.take(1000)
    }

    private fun addCondensedSummary(
        condensedNativeConversation: MutableList<String>,
        summarized: String,
        maxCondensedNativeEntries: Int
    ) {
        if (summarized.isBlank()) {
            return
        }
        condensedNativeConversation += summarized
        while (condensedNativeConversation.size > maxCondensedNativeEntries) {
            condensedNativeConversation.removeAt(0)
        }
    }
}
