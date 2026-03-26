package com.tom.rv2ide.artificial.agents.custom

import org.json.JSONArray
import org.json.JSONObject

internal data class CustomProviderPersistentState(
    val conversationHistory: List<CustomConversationMessage>,
    val committedToolConversation: List<CustomProviderTurn>,
    val condensedNativeConversation: List<String>,
    val toolCallSequence: Int,
    val lastNativeResponsesRequestSignature: String?,
    val lastNativeResponsesResponseId: String?
)

internal object CustomProviderPersistentStateSupport {
    fun exportState(
        conversationHistory: List<CustomConversationMessage>,
        committedToolConversation: List<CustomProviderTurn>,
        condensedNativeConversation: List<String>,
        toolCallSequence: Int,
        lastNativeResponsesRequestSignature: String?,
        lastNativeResponsesResponseId: String?
    ): String {
        return JSONObject().apply {
            put(
                "conversationHistory",
                JSONArray().apply {
                    conversationHistory.forEach { message ->
                        put(
                            JSONObject().apply {
                                put("role", message.role)
                                put("content", message.content)
                            }
                        )
                    }
                }
            )
            put(
                "committedToolConversation",
                JSONArray().apply {
                    committedToolConversation.forEach { turn ->
                        put(turn.toJson())
                    }
                }
            )
            put(
                "condensedNativeConversation",
                JSONArray().apply {
                    condensedNativeConversation.forEach { summaryEntry ->
                        put(summaryEntry)
                    }
                }
            )
            put("toolCallSequence", toolCallSequence)
            put("lastNativeResponsesRequestSignature", lastNativeResponsesRequestSignature.orEmpty())
            put("lastNativeResponsesResponseId", lastNativeResponsesResponseId.orEmpty())
        }.toString()
    }

    fun importState(serializedState: String): CustomProviderPersistentState {
        val state = JSONObject(serializedState)

        val conversationHistory = buildList {
            state.optJSONArray("conversationHistory")?.let { items ->
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val role = item.optString("role").trim()
                    val content = item.optString("content").trim()
                    if (role.isBlank() || content.isBlank()) {
                        continue
                    }
                    add(CustomConversationMessage(role = role, content = content))
                }
            }
        }

        val committedToolConversation = buildList {
            state.optJSONArray("committedToolConversation")?.let { items ->
                for (index in 0 until items.length()) {
                    items.optJSONObject(index)
                        ?.toCustomProviderTurnOrNull()
                        ?.let(::add)
                }
            }
        }

        val condensedNativeConversation = buildList {
            state.optJSONArray("condensedNativeConversation")?.let { items ->
                for (index in 0 until items.length()) {
                    items.optString(index)
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }
        }

        return CustomProviderPersistentState(
            conversationHistory = conversationHistory,
            committedToolConversation = committedToolConversation,
            condensedNativeConversation = condensedNativeConversation,
            toolCallSequence = state.optInt("toolCallSequence", 0),
            lastNativeResponsesRequestSignature = state.optString("lastNativeResponsesRequestSignature")
                .takeIf { it.isNotBlank() },
            lastNativeResponsesResponseId = state.optString("lastNativeResponsesResponseId")
                .takeIf { it.isNotBlank() }
        )
    }
}
