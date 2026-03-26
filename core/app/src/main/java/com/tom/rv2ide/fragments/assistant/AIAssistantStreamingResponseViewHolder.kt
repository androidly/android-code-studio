package com.tom.rv2ide.fragments.assistant

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.tom.rv2ide.R

internal data class AIAssistantStreamAttachmentUiState(
    val expanded: Boolean,
    val userToggled: Boolean,
    val signature: String
)

internal class AIAssistantStreamingResponseViewHolder(
    itemView: View,
    private val streamAttachmentUiStates: MutableMap<Long, AIAssistantStreamAttachmentUiState>
) : RecyclerView.ViewHolder(itemView) {
    private val card = itemView.findViewById<MaterialCardView>(R.id.streamCard)
    private val metaRow = itemView.findViewById<View>(R.id.streamMetaRow)
    private val title = itemView.findViewById<TextView>(R.id.streamTitle)
    private val status = itemView.findViewById<TextView>(R.id.streamStatus)
    private val body = itemView.findViewById<TextView>(R.id.streamBody)
    private val topWorkingRow = itemView.findViewById<View>(R.id.streamTopWorkingRow)
    private val topWorkingText = itemView.findViewById<AIAssistantWorkingTextView>(R.id.streamTopWorkingText)
    private val bottomWorkingRow = itemView.findViewById<View>(R.id.streamBottomWorkingRow)
    private val bottomWorkingText = itemView.findViewById<AIAssistantWorkingTextView>(R.id.streamBottomWorkingText)
    private val activityCard = itemView.findViewById<MaterialCardView>(R.id.streamActivityCard)
    private val attachmentHeader = itemView.findViewById<View>(R.id.streamAttachmentHeader)
    private val attachmentToggleText = itemView.findViewById<TextView>(R.id.streamAttachmentToggleText)
    private val attachmentChevron = itemView.findViewById<TextView>(R.id.streamAttachmentChevron)
    private val attachmentPreview = itemView.findViewById<TextView>(R.id.streamAttachmentPreview)
    private val attachmentRecyclerView = itemView.findViewById<RecyclerView>(R.id.streamAttachmentRecyclerView)
    private val attachmentFooter = itemView.findViewById<View>(R.id.streamAttachmentFooter)
    private val attachmentFooterText = itemView.findViewById<TextView>(R.id.streamAttachmentFooterText)
    private val attachmentFooterChevron = itemView.findViewById<TextView>(R.id.streamAttachmentFooterChevron)
    private val attachmentAdapter = AIAssistantAttachmentAdapter()
    private var lastAttachmentSignature: String? = null
    private var boundItem: AIAssistantStreamingResponseItem? = null

    init {
        attachmentRecyclerView.apply {
            layoutManager = LinearLayoutManager(itemView.context)
            adapter = attachmentAdapter
            itemAnimator = null
            isNestedScrollingEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setRecycledViewPool(toolAttachmentViewPool)
        }
        attachmentHeader.setOnClickListener {
            toggleAttachmentSection()
        }
        attachmentFooter.setOnClickListener {
            toggleAttachmentSection()
        }
    }

    fun bind(item: AIAssistantStreamingResponseItem) {
        boundItem = item
        val hideMetaRow = item.isWorking ||
            item.isStreaming ||
            (item.header.equals("Assistant", ignoreCase = true) && item.status.isNullOrBlank())
        title.text = item.header
        status.text = item.status.orEmpty()
        metaRow.isVisible = !hideMetaRow
        title.isVisible = !hideMetaRow
        status.isVisible = !hideMetaRow && status.text.toString().isNotBlank()
        val hasResponse = item.response.isNotBlank()
        val bodyText = when {
            hasResponse -> item.response
            !item.placeholder.isNullOrBlank() -> item.placeholder
            else -> ""
        }
        val (containerAttr, textAttr) = if (item.isStreaming && !hasResponse) {
            com.google.android.material.R.attr.colorSecondaryContainer to com.google.android.material.R.attr.colorOnSecondaryContainer
        } else if (item.isStreaming) {
            com.google.android.material.R.attr.colorSurfaceContainerHighest to com.google.android.material.R.attr.colorOnSurface
        } else {
            com.google.android.material.R.attr.colorSurfaceContainerHigh to com.google.android.material.R.attr.colorOnSurface
        }
        AIAssistantTimelineCardTone.apply(card, itemView, containerAttr, textAttr, title, body)
        if (bodyText.isNotBlank()) {
            body.isVisible = true
            AIAssistantRichTextRenderer.render(
                body,
                bodyText,
                messageId = item.id,
                isStreaming = hasResponse && item.isStreaming
            )
        } else {
            AIAssistantRichTextRenderer.cancel(body)
            body.text = ""
            body.tag = null
            body.isVisible = false
        }
        applyWorkingRows(item)
        applyAttachmentSection(item)
    }

    fun recycle() {
        AIAssistantRichTextRenderer.cancel(body)
        body.text = ""
        topWorkingText.setShimmerEnabled(false)
        bottomWorkingText.setShimmerEnabled(false)
        attachmentAdapter.replaceAll(emptyList())
        lastAttachmentSignature = null
        boundItem = null
        body.tag = null
    }

    fun detach() {
        AIAssistantRichTextRenderer.cancel(body)
        topWorkingText.setShimmerEnabled(false)
        bottomWorkingText.setShimmerEnabled(false)
    }

    private fun applyAttachmentSection(
        item: AIAssistantStreamingResponseItem,
        expandedOverride: Boolean? = null
    ) {
        val hasAttachments = item.attachments.isNotEmpty()
        activityCard.isVisible = hasAttachments
        if (!hasAttachments) {
            attachmentPreview.text = ""
            attachmentRecyclerView.isVisible = false
            attachmentFooter.isVisible = false
            return
        }

        bindAttachmentsIfNeeded(item.attachments)
        val attachmentCount = item.attachments.size
        val expanded = expandedOverride ?: resolveAttachmentUiState(item).expanded
        attachmentToggleText.text = if (expanded) {
            "Activity · $attachmentCount " + if (attachmentCount == 1) "step" else "steps"
        } else {
            "Show $attachmentCount " + if (attachmentCount == 1) "step" else "steps"
        }
        attachmentChevron.text = if (expanded) "v" else ">"
        attachmentPreview.text = AIAssistantStreamingAttachmentSupport.buildAttachmentPreviewText(
            attachments = item.attachments,
            expanded = expanded
        )
        attachmentPreview.isVisible = attachmentPreview.text.isNotBlank()
        attachmentRecyclerView.isVisible = expanded
        attachmentFooterText.text = "Hide activity"
        attachmentFooterChevron.text = "^"
        attachmentFooter.isVisible = expanded
    }

    private fun bindAttachmentsIfNeeded(attachments: List<AIAssistantToolItem>) {
        val nextSignature = AIAssistantStreamingAttachmentSupport.attachmentSignature(attachments)
        if (nextSignature == lastAttachmentSignature) {
            return
        }
        lastAttachmentSignature = nextSignature
        attachmentAdapter.replaceAll(attachments)
    }

    private fun resolveAttachmentUiState(item: AIAssistantStreamingResponseItem): AIAssistantStreamAttachmentUiState {
        val nextState = AIAssistantStreamingAttachmentSupport.resolveUiState(
            item = item,
            existingState = streamAttachmentUiStates[item.id]
        )
        streamAttachmentUiStates[item.id] = nextState
        return nextState
    }

    private fun toggleAttachmentSection() {
        val item = boundItem ?: return
        val state = resolveAttachmentUiState(item)
        val expanded = !state.expanded
        streamAttachmentUiStates[item.id] = state.copy(expanded = expanded, userToggled = true)
        applyAttachmentSection(item, expanded)
    }

    private fun applyWorkingRows(item: AIAssistantStreamingResponseItem) {
        val showBottomWorking = item.isStreaming
        topWorkingRow.isVisible = false
        bottomWorkingRow.isVisible = showBottomWorking
        val workingLabel = itemView.context.getString(R.string.ai_assistant_working)
        val workingText = item.status
            ?.takeIf { it.isNotBlank() }
            ?.let { "$workingLabel · $it" }
            ?: workingLabel
        topWorkingText.text = ""
        bottomWorkingText.text = workingText
        topWorkingText.setShimmerEnabled(false)
        bottomWorkingText.setShimmerEnabled(showBottomWorking)
    }

    private companion object {
        val toolAttachmentViewPool = RecyclerView.RecycledViewPool()
    }
}

internal object AIAssistantStreamingAttachmentSupport {
    fun resolveUiState(
        item: AIAssistantStreamingResponseItem,
        existingState: AIAssistantStreamAttachmentUiState?
    ): AIAssistantStreamAttachmentUiState {
        val signature = attachmentSectionSignature(item)
        return when {
            existingState == null -> AIAssistantStreamAttachmentUiState(
                expanded = defaultAttachmentSectionExpanded(item),
                userToggled = false,
                signature = signature
            )
            existingState.userToggled -> existingState.copy(signature = signature)
            existingState.signature != signature -> existingState.copy(
                expanded = defaultAttachmentSectionExpanded(item),
                signature = signature
            )
            else -> existingState
        }
    }

    fun attachmentSignature(attachments: List<AIAssistantToolItem>): String {
        if (attachments.isEmpty()) {
            return ""
        }
        return buildString {
            attachments.forEach { attachment ->
                append(attachment.id)
                append(':')
                append(attachment.stage.name)
                append(':')
                append(attachment.summary.hashCode())
                append(':')
                append(attachment.outputPreview.orEmpty().hashCode())
                append('|')
            }
        }
    }

    fun buildAttachmentPreviewText(
        attachments: List<AIAssistantToolItem>,
        expanded: Boolean
    ): String {
        val latest = attachments.lastOrNull() ?: return ""
        val latestSummary = listOfNotNull(
            latest.stage.label,
            latest.title.takeIf { it.isNotBlank() },
            latest.summary.takeIf { it.isNotBlank() }
        ).joinToString(" · ").trim()
        return if (expanded) {
            "Latest: $latestSummary"
        } else {
            latestSummary
        }
    }

    private fun attachmentSectionSignature(item: AIAssistantStreamingResponseItem): String {
        return buildString {
            append(attachmentSignature(item.attachments))
            append('#')
            append(item.response.isNotBlank())
            append('#')
            append(item.isStreaming)
        }
    }

    private fun defaultAttachmentSectionExpanded(item: AIAssistantStreamingResponseItem): Boolean {
        if (item.attachments.isEmpty()) {
            return false
        }
        if (item.response.isNotBlank()) {
            return false
        }
        return item.attachments.size == 1 && item.attachments.lastOrNull()?.isLive == true
    }
}
