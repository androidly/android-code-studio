package com.tom.rv2ide.artificial.agents

internal class AIAgentAssistantPreviewController(
    private val callback: AIAgentManager.AIAgentCallback,
    private val decisionResolver: (String, Boolean) -> AIAgentToolPresentationSupport.PreviewDecision
) {
    private val previewBuffer = StringBuilder()
    private var previewStarted = false
    private var previewSuppressed = false

    fun onTextDelta(delta: String) {
        if (delta.isEmpty()) {
            return
        }

        previewBuffer.append(delta)
        if (previewSuppressed) {
            return
        }

        if (!previewStarted) {
            when (decisionResolver(previewBuffer.toString(), false)) {
                AIAgentToolPresentationSupport.PreviewDecision.WAIT -> return
                AIAgentToolPresentationSupport.PreviewDecision.SUPPRESS -> {
                    previewSuppressed = true
                    return
                }
                AIAgentToolPresentationSupport.PreviewDecision.SHOW -> {
                    previewStarted = true
                    callback.onAssistantTextStarted()
                    callback.onAssistantTextDelta(previewBuffer.toString())
                    previewBuffer.clear()
                    return
                }
            }
        }

        callback.onAssistantTextDelta(delta)
    }

    fun finish(fullResponse: String) {
        if (!previewStarted && !previewSuppressed) {
            when (decisionResolver(fullResponse, true)) {
                AIAgentToolPresentationSupport.PreviewDecision.SHOW -> {
                    previewStarted = true
                    callback.onAssistantTextStarted()
                    callback.onAssistantTextDelta(fullResponse)
                }
                AIAgentToolPresentationSupport.PreviewDecision.SUPPRESS,
                AIAgentToolPresentationSupport.PreviewDecision.WAIT -> Unit
            }
        }

        if (previewStarted) {
            callback.onAssistantTextFinished(fullResponse)
        }
    }
}

internal fun createAssistantPreviewController(
    callback: AIAgentManager.AIAgentCallback
): AIAgentAssistantPreviewController {
    return AIAgentAssistantPreviewController(callback) { content, isFinal ->
        AIAgentToolPresentationSupport.resolveAssistantPreviewDecision(content, isFinal)
    }
}

internal fun createNativeAssistantPreviewController(
    callback: AIAgentManager.AIAgentCallback
): AIAgentAssistantPreviewController {
    return AIAgentAssistantPreviewController(callback) { content, isFinal ->
        AIAgentToolPresentationSupport.resolveNativeAssistantPreviewDecision(content, isFinal)
    }
}
