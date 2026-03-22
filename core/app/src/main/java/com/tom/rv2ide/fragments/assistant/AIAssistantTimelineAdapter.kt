package com.tom.rv2ide.fragments.assistant

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tom.rv2ide.R

class AIAssistantTimelineAdapter(
    private val onDiffClicked: (AIAssistantDiffItem) -> Unit
) : ListAdapter<AIAssistantTimelineItem, RecyclerView.ViewHolder>(TimelineDiffCallback) {

    init {
        setHasStableIds(true)
    }

    fun getItemsSnapshot(): List<AIAssistantTimelineItem> = currentList.toList()

    fun getItemAt(position: Int): AIAssistantTimelineItem? = currentList.getOrNull(position)

    fun replaceAll(
        newItems: List<AIAssistantTimelineItem>,
        onCommitted: (() -> Unit)? = null
    ) {
        submitList(newItems.toList()) {
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
            is AIAssistantResponseItem -> (holder as MessageViewHolder).bind("Assistant", item.response)
            is AIAssistantWelcomeItem -> (holder as MessageViewHolder).bind(item.title, item.body)
            is AIAssistantDiffItem -> (holder as DiffViewHolder).bind(item)
            is AIAssistantStatusItem -> (holder as StatusViewHolder).bind(item)
        }
    }

    private class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val body = itemView.findViewById<TextView>(R.id.promptText)

        fun bind(item: AIAssistantUserItem) {
            AIAssistantRichTextRenderer.render(body, item.prompt)
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

        fun bind(header: String, content: String) {
            title.text = header
            AIAssistantRichTextRenderer.render(body, content)
        }
    }

    private class StreamingResponseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.streamCard)
        private val title = itemView.findViewById<TextView>(R.id.streamTitle)
        private val status = itemView.findViewById<TextView>(R.id.streamStatus)
        private val body = itemView.findViewById<TextView>(R.id.streamBody)
        private val attachmentLabel = itemView.findViewById<TextView>(R.id.streamAttachmentLabel)
        private val attachmentContainer = itemView.findViewById<LinearLayout>(R.id.streamAttachmentContainer)
        private val progress = itemView.findViewById<LinearProgressIndicator>(R.id.streamProgress)
        private var lastAttachmentSignature: String? = null

        fun bind(item: AIAssistantStreamingResponseItem) {
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
                isStreaming = hasResponse && item.isStreaming
            )
            bindAttachmentsIfNeeded(item.attachments)
            attachmentLabel.isVisible = item.attachments.isNotEmpty()
            attachmentContainer.isVisible = item.attachments.isNotEmpty()
            progress.isVisible = item.isStreaming
        }

        private fun bindAttachmentsIfNeeded(attachments: List<AIAssistantToolItem>) {
            val nextSignature = attachmentSignature(attachments)
            if (nextSignature == lastAttachmentSignature) {
                return
            }
            lastAttachmentSignature = nextSignature
            attachmentContainer.removeAllViews()
            if (attachments.isEmpty()) {
                return
            }

            val inflater = LayoutInflater.from(itemView.context)
            attachments.forEach { attachment ->
                val attachmentView = inflater.inflate(
                    R.layout.item_ai_assistant_tool_attachment,
                    attachmentContainer,
                    false
                )
                bindAttachmentView(attachmentView, attachment)
                attachmentContainer.addView(attachmentView)
            }
        }

        private fun bindAttachmentView(root: View, item: AIAssistantToolItem) {
            val card = root.findViewById<MaterialCardView>(R.id.attachmentCard)
            val title = root.findViewById<TextView>(R.id.attachmentTitle)
            val phase = root.findViewById<TextView>(R.id.attachmentPhase)
            val stageTrail = root.findViewById<TextView>(R.id.attachmentStageTrail)
            val summary = root.findViewById<TextView>(R.id.attachmentSummary)
            val progress = root.findViewById<LinearProgressIndicator>(R.id.attachmentProgress)
            val commandLabel = root.findViewById<TextView>(R.id.attachmentCommandLabel)
            val command = root.findViewById<TextView>(R.id.attachmentCommand)
            val workingDirectory = root.findViewById<TextView>(R.id.attachmentWorkdir)
            val outputLabel = root.findViewById<TextView>(R.id.attachmentOutputLabel)
            val output = root.findViewById<TextView>(R.id.attachmentOutput)

            command.typeface = Typeface.MONOSPACE
            workingDirectory.typeface = Typeface.MONOSPACE
            output.typeface = Typeface.MONOSPACE

            title.text = item.title
            phase.text = item.stage.label
            stageTrail.text = item.stageTrail
            summary.text = item.summary
            command.text = item.command.orEmpty()
            workingDirectory.text = item.workingDirectory?.let { "cwd  $it" }.orEmpty()
            output.text = if (item.stage == AIAssistantToolStage.STREAMING && !item.outputPreview.isNullOrBlank()) {
                item.outputPreview + "\n▍"
            } else {
                item.outputPreview.orEmpty()
            }
            stageTrail.isVisible = item.stageTrail.isNotBlank()
            commandLabel.isVisible = !item.command.isNullOrBlank()
            command.isVisible = !item.command.isNullOrBlank()
            workingDirectory.isVisible = !item.workingDirectory.isNullOrBlank()
            outputLabel.text = if (item.stage == AIAssistantToolStage.STREAMING) "Live output" else "Output"
            outputLabel.isVisible = !item.outputPreview.isNullOrBlank()
            output.isVisible = !item.outputPreview.isNullOrBlank()
            progress.isVisible = item.isLive
            progress.setIndicatorColor(MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary, 0))

            val palette = when (item.stage) {
                AIAssistantToolStage.PLANNED -> ToolPalette(
                    containerAttr = com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    textAttr = com.google.android.material.R.attr.colorOnSurface,
                    chipContainerAttr = com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    chipTextAttr = com.google.android.material.R.attr.colorOnSurface
                )
                AIAssistantToolStage.RUNNING -> ToolPalette(
                    containerAttr = com.google.android.material.R.attr.colorSecondaryContainer,
                    textAttr = com.google.android.material.R.attr.colorOnSecondaryContainer,
                    chipContainerAttr = com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    chipTextAttr = com.google.android.material.R.attr.colorOnSurface
                )
                AIAssistantToolStage.STREAMING -> ToolPalette(
                    containerAttr = com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    textAttr = com.google.android.material.R.attr.colorOnSurface,
                    chipContainerAttr = com.google.android.material.R.attr.colorSecondaryContainer,
                    chipTextAttr = com.google.android.material.R.attr.colorOnSecondaryContainer
                )
                AIAssistantToolStage.COMPLETED -> ToolPalette(
                    containerAttr = com.google.android.material.R.attr.colorPrimaryContainer,
                    textAttr = com.google.android.material.R.attr.colorOnPrimaryContainer,
                    chipContainerAttr = com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    chipTextAttr = com.google.android.material.R.attr.colorOnSurface
                )
                AIAssistantToolStage.FAILED -> ToolPalette(
                    containerAttr = com.google.android.material.R.attr.colorErrorContainer,
                    textAttr = com.google.android.material.R.attr.colorOnErrorContainer,
                    chipContainerAttr = com.google.android.material.R.attr.colorErrorContainer,
                    chipTextAttr = com.google.android.material.R.attr.colorOnErrorContainer
                )
                AIAssistantToolStage.CANCELLED -> ToolPalette(
                    containerAttr = com.google.android.material.R.attr.colorTertiaryContainer,
                    textAttr = com.google.android.material.R.attr.colorOnTertiaryContainer,
                    chipContainerAttr = com.google.android.material.R.attr.colorSurfaceContainerHighest,
                    chipTextAttr = com.google.android.material.R.attr.colorOnSurface
                )
            }
            applyCardTone(
                card,
                root,
                palette.containerAttr,
                palette.textAttr,
                title,
                stageTrail,
                summary,
                commandLabel,
                command,
                workingDirectory,
                outputLabel,
                output
            )
            phase.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(root, palette.chipContainerAttr, 0)
            )
            phase.setTextColor(MaterialColors.getColor(root, palette.chipTextAttr, 0))
        }

        private data class ToolPalette(
            @AttrRes val containerAttr: Int,
            @AttrRes val textAttr: Int,
            @AttrRes val chipContainerAttr: Int,
            @AttrRes val chipTextAttr: Int
        )
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
            AIAssistantRichTextRenderer.render(body, item.body.orEmpty())
            body.isVisible = !item.body.isNullOrBlank()
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
