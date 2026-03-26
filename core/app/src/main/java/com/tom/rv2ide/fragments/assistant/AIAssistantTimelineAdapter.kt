package com.tom.rv2ide.fragments.assistant

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.tom.rv2ide.R

class AIAssistantTimelineAdapter(
    private val onDiffClicked: (AIAssistantDiffItem) -> Unit,
    private val onSessionSwitchRequested: ((String) -> Unit)? = null,
    private val onSessionDeleteRequested: ((String) -> Unit)? = null
) : ListAdapter<AIAssistantTimelineItem, RecyclerView.ViewHolder>(TimelineDiffCallback) {
    private val streamAttachmentUiStates = mutableMapOf<Long, AIAssistantStreamAttachmentUiState>()

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
        return when (val item = getItem(position)) {
            is AIAssistantHistoryDividerItem -> VIEW_TYPE_HISTORY
            is AIAssistantUserItem -> VIEW_TYPE_USER
            is AIAssistantStreamingResponseItem -> VIEW_TYPE_STREAMING_RESPONSE
            is AIAssistantResponseItem,
            is AIAssistantWelcomeItem -> VIEW_TYPE_MESSAGE
            is AIAssistantDiffItem -> VIEW_TYPE_DIFF
            is AIAssistantStatusItem -> VIEW_TYPE_STATUS
            is AIAssistantSessionBrowserItem -> VIEW_TYPE_SESSION_BROWSER
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HISTORY -> HistoryDividerViewHolder(inflater.inflate(R.layout.item_ai_assistant_history, parent, false))
            VIEW_TYPE_SESSION_BROWSER -> SessionBrowserViewHolder(inflater.inflate(R.layout.item_ai_assistant_session_browser, parent, false))
            VIEW_TYPE_STREAMING_RESPONSE -> AIAssistantStreamingResponseViewHolder(
                inflater.inflate(R.layout.item_ai_assistant_stream, parent, false),
                streamAttachmentUiStates
            )
            VIEW_TYPE_USER -> UserViewHolder(inflater.inflate(R.layout.item_ai_assistant_user, parent, false))
            VIEW_TYPE_DIFF -> DiffViewHolder(inflater.inflate(R.layout.item_ai_assistant_diff, parent, false), onDiffClicked)
            VIEW_TYPE_STATUS -> StatusViewHolder(inflater.inflate(R.layout.item_ai_assistant_status, parent, false))
            else -> MessageViewHolder(inflater.inflate(R.layout.item_ai_assistant_message, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is AIAssistantHistoryDividerItem -> (holder as HistoryDividerViewHolder).bind(item)
            is AIAssistantStreamingResponseItem -> (holder as AIAssistantStreamingResponseViewHolder).bind(item)
            is AIAssistantUserItem -> (holder as UserViewHolder).bind(item)
            is AIAssistantResponseItem -> (holder as MessageViewHolder).bind("Assistant", item.response, item.id)
            is AIAssistantWelcomeItem -> (holder as MessageViewHolder).bind(item.title, item.body, item.id)
            is AIAssistantDiffItem -> (holder as DiffViewHolder).bind(item)
            is AIAssistantStatusItem -> (holder as StatusViewHolder).bind(item)
            is AIAssistantSessionBrowserItem -> (holder as SessionBrowserViewHolder).bind(item)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is UserViewHolder -> holder.recycle()
            is MessageViewHolder -> holder.recycle()
            is AIAssistantStreamingResponseViewHolder -> holder.recycle()
            is StatusViewHolder -> holder.recycle()
            else -> Unit
        }
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        when (holder) {
            is UserViewHolder -> holder.detach()
            is MessageViewHolder -> holder.detach()
            is AIAssistantStreamingResponseViewHolder -> holder.detach()
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

    private inner class SessionBrowserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.sessionBrowserTitle)
        private val subtitle = itemView.findViewById<TextView>(R.id.sessionBrowserSubtitle)
        private val empty = itemView.findViewById<TextView>(R.id.sessionBrowserEmpty)
        private val rowsContainer = itemView.findViewById<LinearLayout>(R.id.sessionBrowserRowsContainer)
        private val rowInflater = LayoutInflater.from(itemView.context)

        fun bind(item: AIAssistantSessionBrowserItem) {
            title.text = item.title
            subtitle.text = item.subtitle.orEmpty()
            subtitle.isVisible = !item.subtitle.isNullOrBlank()
            rowsContainer.removeAllViews()
            val hasRows = item.sessions.isNotEmpty()
            rowsContainer.isVisible = hasRows
            empty.text = "No saved sessions yet."
            empty.isVisible = !hasRows
            if (!hasRows) {
                return
            }
            item.sessions.forEach { session ->
                val rowView = rowInflater.inflate(
                    R.layout.item_ai_assistant_session_browser_row,
                    rowsContainer,
                    false
                )
                bindRow(rowView, session)
                rowsContainer.addView(rowView)
            }
        }

        private fun bindRow(
            rowView: View,
            row: AIAssistantSessionBrowserEntry
        ) {
            val rowCard = rowView.findViewById<MaterialCardView>(R.id.sessionRowCard)
            val rowTitle = rowView.findViewById<TextView>(R.id.sessionRowTitle)
            val rowState = rowView.findViewById<TextView>(R.id.sessionRowState)
            val rowSummary = rowView.findViewById<TextView>(R.id.sessionRowSummary)
            val rowMeta = rowView.findViewById<TextView>(R.id.sessionRowMeta)
            val switchButton = rowView.findViewById<MaterialButton>(R.id.sessionRowSwitchButton)
            val deleteButton = rowView.findViewById<MaterialButton>(R.id.sessionRowDeleteButton)
            rowTitle.text = buildString {
                append(row.order)
                append(". ")
                append(row.title)
            }
            rowSummary.text = row.summary.orEmpty()
            rowSummary.isVisible = !row.summary.isNullOrBlank()
            rowMeta.text = row.meta.orEmpty()
            rowMeta.isVisible = !row.meta.isNullOrBlank()
            rowState.text = if (row.isActive) {
                "Current"
            } else {
                ""
            }
            rowState.isVisible = row.isActive
            switchButton.text = if (row.isActive) "Current" else "Switch"
            deleteButton.text = "Delete"
            switchButton.isEnabled = !row.isActive && onSessionSwitchRequested != null && row.sessionId.isNotBlank()
            deleteButton.isEnabled = row.canDelete && onSessionDeleteRequested != null && row.sessionId.isNotBlank()
            switchButton.alpha = if (switchButton.isEnabled) 1f else 0.72f
            deleteButton.alpha = if (deleteButton.isEnabled) 1f else 0.72f
            switchButton.setOnClickListener { onSessionSwitchRequested?.invoke(row.sessionId) }
            deleteButton.setOnClickListener { onSessionDeleteRequested?.invoke(row.sessionId) }

            val containerAttr: Int
            val textAttr: Int
            val strokeAttr: Int
            if (row.isActive) {
                containerAttr = com.google.android.material.R.attr.colorSecondaryContainer
                textAttr = com.google.android.material.R.attr.colorOnSecondaryContainer
                strokeAttr = com.google.android.material.R.attr.colorSecondary
            } else {
                containerAttr = com.google.android.material.R.attr.colorSurfaceContainerHighest
                textAttr = com.google.android.material.R.attr.colorOnSurface
                strokeAttr = com.google.android.material.R.attr.colorOutlineVariant
            }
            AIAssistantTimelineCardTone.apply(
                rowCard,
                itemView,
                containerAttr,
                textAttr,
                rowTitle,
                rowSummary,
                rowMeta
            )
            rowCard.strokeColor = MaterialColors.getColor(itemView, strokeAttr, 0)
            if (row.isActive) {
                rowState.setBackgroundColor(
                    MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorSecondary, 0)
                )
                rowState.setTextColor(
                    MaterialColors.getColor(itemView, com.google.android.material.R.attr.colorOnSecondary, 0)
                )
            }
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
            AIAssistantTimelineCardTone.apply(card, itemView, containerAttr, textAttr, title, body)
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
        private const val VIEW_TYPE_SESSION_BROWSER = 6
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
    }
}
