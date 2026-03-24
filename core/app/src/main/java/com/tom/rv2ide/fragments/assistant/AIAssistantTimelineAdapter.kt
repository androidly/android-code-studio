package com.tom.rv2ide.fragments.assistant

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tom.rv2ide.R

class AIAssistantTimelineAdapter(
    private val onDiffClicked: (AIAssistantDiffItem) -> Unit
) : ListAdapter<AIAssistantTimelineItem, RecyclerView.ViewHolder>(TimelineDiffCallback) {
    private val streamAttachmentUiStates = mutableMapOf<Long, StreamAttachmentUiState>()

    init {
        setHasStableIds(true)
    }

    fun getItemsSnapshot(): List<AIAssistantTimelineItem> = currentList

    fun getItemAt(position: Int): AIAssistantTimelineItem? = currentList.getOrNull(position)

    fun replaceAll(
        newItems: List<AIAssistantTimelineItem>,
        onCommitted: (() -> Unit)? = null
    ) {
        val liveStreamItemIds = newItems
            .filterIsInstance<AIAssistantStreamingResponseItem>()
            .map(AIAssistantStreamingResponseItem::id)
            .toSet()
        streamAttachmentUiStates.keys.retainAll(liveStreamItemIds)
        submitList(newItems) {
            onCommitted?.invoke()
        }
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is AIAssistantHistoryDividerItem -> VIEW_TYPE_HISTORY
            is AIAssistantUserItem -> VIEW_TYPE_USER
            is AIAssistantStreamingResponseItem -> VIEW_TYPE_STREAMING_RESPONSE
            is AIAssistantResponseItem,
            is AIAssistantWelcomeItem -> VIEW_TYPE_MESSAGE
            is AIAssistantDiffItem -> VIEW_TYPE_DIFF
            is AIAssistantStatusItem -> VIEW_TYPE_STATUS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HISTORY -> HistoryDividerViewHolder(inflater.inflate(R.layout.item_ai_assistant_history, parent, false))
            VIEW_TYPE_STREAMING_RESPONSE -> StreamingResponseViewHolder(inflater.inflate(R.layout.item_ai_assistant_stream, parent, false))
            VIEW_TYPE_USER -> UserViewHolder(inflater.inflate(R.layout.item_ai_assistant_user, parent, false))
            VIEW_TYPE_DIFF -> DiffViewHolder(inflater.inflate(R.layout.item_ai_assistant_diff, parent, false), onDiffClicked)
            VIEW_TYPE_STATUS -> StatusViewHolder(inflater.inflate(R.layout.item_ai_assistant_status, parent, false))
            else -> MessageViewHolder(inflater.inflate(R.layout.item_ai_assistant_message, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is AIAssistantHistoryDividerItem -> (holder as HistoryDividerViewHolder).bind(item)
            is AIAssistantStreamingResponseItem -> (holder as StreamingResponseViewHolder).bind(item)
            is AIAssistantUserItem -> (holder as UserViewHolder).bind(item)
            is AIAssistantResponseItem -> (holder as MessageViewHolder).bind("Assistant", item.response, item.id)
            is AIAssistantWelcomeItem -> (holder as MessageViewHolder).bind(item.title, item.body, item.id)
            is AIAssistantDiffItem -> (holder as DiffViewHolder).bind(item)
            is AIAssistantStatusItem -> (holder as StatusViewHolder).bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is UserViewHolder -> holder.recycle()
            is MessageViewHolder -> holder.recycle()
            is StreamingResponseViewHolder -> holder.recycle()
            is StatusViewHolder -> holder.recycle()
            else -> Unit
        }
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is UserViewHolder -> holder.detach()
            is MessageViewHolder -> holder.detach()
            is StreamingResponseViewHolder -> holder.detach()
            is StatusViewHolder -> holder.detach()
            else -> Unit
        }
        super.onViewDetachedFromWindow(holder)
    }

    private class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val body = itemView.findViewById<TextView>(R.id.promptText)

        fun bind(item: AIAssistantUserItem) {
            AIAssistantRichTextRenderer.render(body, item.prompt, messageId = item.id)
        }

        fun recycle() {
            AIAssistantRichTextRenderer.cancel(body)
            body.text = ""
            body.tag = null
        }

        fun detach() {
            AIAssistantRichTextRenderer.cancel(body)
        }
    }

    private class HistoryDividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.historyTitle)
        private val subtitle = itemView.findViewById<TextView>(R.id.historySubtitle)

        fun bind(item: AIAssistantHistoryDividerItem) {
            title.text = if (item.hiddenCount == 1) {
                "1 earlier message"
            } else {
                "${item.hiddenCount} earlier messages"
            }
            subtitle.text = "Showing recent ${item.visibleCount} of ${item.totalCount}. Scroll up to load more."
        }
    }

    private class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.titleText)
        private val body = itemView.findViewById<TextView>(R.id.bodyText)

        fun bind(header: String, content: String, itemId: Long) {
            title.text = header
            AIAssistantRichTextRenderer.render(body, content, messageId = itemId)
        }

        fun recycle() {
            AIAssistantRichTextRenderer.cancel(body)
            body.text = ""
            body.tag = null
        }

        fun detach() {
            AIAssistantRichTextRenderer.cancel(body)
        }
    }

    private inner class StreamingResponseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.streamCard)
        private val title = itemView.findViewById<TextView>(R.id.streamTitle)
        private val status = itemView.findViewById<TextView>(R.id.streamStatus)
        private val body = itemView.findViewById<TextView>(R.id.streamBody)
        private val activityCard = itemView.findViewById<MaterialCardView>(R.id.streamActivityCard)
        private val attachmentHeader = itemView.findViewById<View>(R.id.streamAttachmentHeader)
        private val attachmentToggleText = itemView.findViewById<TextView>(R.id.streamAttachmentToggleText)
        private val attachmentChevron = itemView.findViewById<TextView>(R.id.streamAttachmentChevron)
        private val attachmentPreview = itemView.findViewById<TextView>(R.id.streamAttachmentPreview)
        private val attachmentRecyclerView = itemView.findViewById<RecyclerView>(R.id.streamAttachmentRecyclerView)
        private val progress = itemView.findViewById<LinearProgressIndicator>(R.id.streamProgress)
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
                val item = boundItem ?: return@setOnClickListener
                val state = resolveAttachmentUiState(item)
                streamAttachmentUiStates[item.id] = state.copy(expanded = !state.expanded, userToggled = true)
                applyAttachmentSection(item, !state.expanded)
            }
        }

        fun bind(item: AIAssistantStreamingResponseItem) {
            boundItem = item
            title.text = item.header
            status.text = item.status.orEmpty()
            status.isVisible = status.text.toString().isNotBlank()
            val hasResponse = item.response.isNotBlank()
            val bodyText = when {
                hasResponse -> item.response
                !item.placeholder.isNullOrBlank() -> item.placeholder
                item.isWorking -> "Working on it..."
                else -> ""
            }
            val (containerAttr, textAttr) = if (item.isStreaming && !hasResponse) {
                com.google.android.material.R.attr.colorSecondaryContainer to com.google.android.material.R.attr.colorOnSecondaryContainer
            } else if (item.isStreaming) {
                com.google.android.material.R.attr.colorSurfaceContainerHighest to com.google.android.material.R.attr.colorOnSurface
            } else {
                com.google.android.material.R.attr.colorSurfaceContainerHigh to com.google.android.material.R.attr.colorOnSurface
            }
            applyCardTone(card, itemView, containerAttr, textAttr, title, body)
            AIAssistantRichTextRenderer.render(
                body,
                bodyText,
                messageId = item.id,
                isStreaming = hasResponse && item.isStreaming
            )
            applyAttachmentSection(item)
            progress.isVisible = item.isStreaming
        }

        private fun applyAttachmentSection(item: AIAssistantStreamingResponseItem, expandedOverride: Boolean? = null) {
            val hasAttachments = item.attachments.isNotEmpty()
            activityCard.isVisible = hasAttachments
            if (!hasAttachments) {
                attachmentPreview.text = ""
                attachmentRecyclerView.isVisible = false
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
            attachmentPreview.text = buildAttachmentPreviewText(item.attachments, expanded)
            attachmentPreview.isVisible = attachmentPreview.text.isNotBlank()
            attachmentRecyclerView.isVisible = expanded
        }

        private fun bindAttachmentsIfNeeded(attachments: List<AIAssistantToolItem>) {
            val nextSignature = attachmentSignature(attachments)
            if (nextSignature == lastAttachmentSignature) {
                return
            }
            lastAttachmentSignature = nextSignature
            attachmentAdapter.replaceAll(attachments)
        }

        private fun resolveAttachmentUiState(item: AIAssistantStreamingResponseItem): StreamAttachmentUiState {
            val signature = attachmentSectionSignature(item)
            val existingState = streamAttachmentUiStates[item.id]
            val nextState = when {
                existingState == null -> StreamAttachmentUiState(
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
            streamAttachmentUiStates[item.id] = nextState
            return nextState
        }

        fun recycle() {
            AIAssistantRichTextRenderer.cancel(body)
            body.text = ""
            attachmentAdapter.replaceAll(emptyList())
            lastAttachmentSignature = null
            boundItem = null
            body.tag = null
        }

        fun detach() {
            AIAssistantRichTextRenderer.cancel(body)
        }
    }

    private class StatusViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.statusCard)
        private val title = itemView.findViewById<TextView>(R.id.statusTitle)
        private val body = itemView.findViewById<TextView>(R.id.statusBody)

        fun bind(item: AIAssistantStatusItem) {
            title.text = item.title
            val (containerAttr, textAttr) = when (item.tone) {
                AIAssistantTone.RUNNING -> android.R.attr.colorActivatedHighlight to com.google.android.material.R.attr.colorOnSecondaryContainer
                AIAssistantTone.SUCCESS -> com.google.android.material.R.attr.colorSecondaryContainer to com.google.android.material.R.attr.colorOnSecondaryContainer
                AIAssistantTone.WARNING -> com.google.android.material.R.attr.colorTertiaryContainer to com.google.android.material.R.attr.colorOnTertiaryContainer
                AIAssistantTone.ERROR -> com.google.android.material.R.attr.colorErrorContainer to com.google.android.material.R.attr.colorOnErrorContainer
                AIAssistantTone.NEUTRAL -> com.google.android.material.R.attr.colorSurfaceContainerHighest to com.google.android.material.R.attr.colorOnSurface
            }
            applyCardTone(card, itemView, containerAttr, textAttr, title, body)
            AIAssistantRichTextRenderer.render(body, item.body.orEmpty(), messageId = item.id)
            body.isVisible = !item.body.isNullOrBlank()
        }

        fun recycle() {
            AIAssistantRichTextRenderer.cancel(body)
            body.text = ""
            body.tag = null
        }

        fun detach() {
            AIAssistantRichTextRenderer.cancel(body)
        }
    }

    private class DiffViewHolder(
        itemView: View,
        private val onDiffClicked: (AIAssistantDiffItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.diffTitle)
        private val meta = itemView.findViewById<TextView>(R.id.diffMeta)
        private val added = itemView.findViewById<TextView>(R.id.addedText)
        private val removed = itemView.findViewById<TextView>(R.id.removedText)

        init {
            added.typeface = Typeface.MONOSPACE
            removed.typeface = Typeface.MONOSPACE
        }

        fun bind(item: AIAssistantDiffItem) {
            title.text = item.fileName
            meta.text = "${item.changeLabel}  +${item.preview.addedCount}  -${item.preview.removedCount}"
            added.text = item.preview.addedLines.joinToString("\n") { "+ $it" }
            removed.text = item.preview.removedLines.joinToString("\n") { "- $it" }
            added.isVisible = item.preview.addedLines.isNotEmpty()
            removed.isVisible = item.preview.removedLines.isNotEmpty()
            itemView.setOnClickListener { onDiffClicked(item) }
        }
    }

        companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_MESSAGE = 1
        private const val VIEW_TYPE_STATUS = 2
        private const val VIEW_TYPE_HISTORY = 3
        private const val VIEW_TYPE_DIFF = 4
        private const val VIEW_TYPE_STREAMING_RESPONSE = 5
        private val toolAttachmentViewPool = RecyclerView.RecycledViewPool()
        private val TimelineDiffCallback = object : DiffUtil.ItemCallback<AIAssistantTimelineItem>() {
            override fun areItemsTheSame(
                oldItem: AIAssistantTimelineItem,
                newItem: AIAssistantTimelineItem
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: AIAssistantTimelineItem,
                newItem: AIAssistantTimelineItem
            ): Boolean {
                return oldItem == newItem
            }
        }

        private data class StreamAttachmentUiState(
            val expanded: Boolean,
            val userToggled: Boolean,
            val signature: String
        )

        private fun attachmentSignature(attachments: List<AIAssistantToolItem>): String {
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

        private fun buildAttachmentPreviewText(
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

        private fun applyCardTone(
            card: MaterialCardView,
            root: View,
            @AttrRes containerAttr: Int,
            @AttrRes textAttr: Int,
            vararg textViews: TextView
        ) {
            val background = MaterialColors.getColor(root, containerAttr, 0)
            val textColor = MaterialColors.getColor(root, textAttr, 0)
            card.setCardBackgroundColor(background)
            textViews.forEach { textView ->
                textView.setTextColor(textColor)
            }
        }
    }
}
