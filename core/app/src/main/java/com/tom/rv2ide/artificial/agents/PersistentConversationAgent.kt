package com.tom.rv2ide.artificial.agents

interface PersistentConversationAgent {
    fun persistentConversationFingerprint(): String

    fun exportPersistentConversationState(): String

    fun importPersistentConversationState(serializedState: String)

    fun clearConversation()
}
