package com.tom.rv2ide.fragments.assistant

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.tom.rv2ide.R

class AIAssistantTimelineAdapter(
    private val onDiffClicked: (AIAssistantDiffItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<AIAssistantTimelineItem>()

    init {
        setHasStableIds(true)
    }

    fun append(item: AIAssistantTimelineItem) {
        items += item
        notifyItemInserted(items.lastIndex)
    }

    fun appendAll(newItems: List<AIAssistantTimelineItem>) {
        if (newItems.isEmpty()) {
            return
        }
        val start = items.size
        items += newItems
        notifyItemRangeInserted(start, newItems.size)
    }

    fun clearAll() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun getItemId(position: Int): Long = items[position].id

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is AIAssistantUserItem -> VIEW_TYPE_USER
            is AIAssistantResponseItem,
            is AIAssistantWelcomeItem -> VIEW_TYPE_MESSAGE
            is AIAssistantToolItem -> VIEW_TYPE_TOOL
            is AIAssistantDiffItem -> VIEW_TYPE_DIFF
            is AIAssistantStatusItem -> VIEW_TYPE_STATUS
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_USER -> UserViewHolder(inflater.inflate(R.layout.item_ai_assistant_user, parent, false))
            VIEW_TYPE_TOOL -> ToolViewHolder(inflater.inflate(R.layout.item_ai_assistant_tool, parent, false))
            VIEW_TYPE_DIFF -> DiffViewHolder(inflater.inflate(R.layout.item_ai_assistant_diff, parent, false), onDiffClicked)
            VIEW_TYPE_STATUS -> StatusViewHolder(inflater.inflate(R.layout.item_ai_assistant_status, parent, false))
            else -> MessageViewHolder(inflater.inflate(R.layout.item_ai_assistant_message, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is AIAssistantUserItem -> (holder as UserViewHolder).bind(item)
            is AIAssistantResponseItem -> (holder as MessageViewHolder).bind("Assistant", item.response)
            is AIAssistantWelcomeItem -> (holder as MessageViewHolder).bind(item.title, item.body)
            is AIAssistantToolItem -> (holder as ToolViewHolder).bind(item)
            is AIAssistantDiffItem -> (holder as DiffViewHolder).bind(item)
            is AIAssistantStatusItem -> (holder as StatusViewHolder).bind(item)
        }
    }

    private class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val body = itemView.findViewById<TextView>(R.id.promptText)

        fun bind(item: AIAssistantUserItem) {
            body.text = item.prompt
        }
    }

    private class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title = itemView.findViewById<TextView>(R.id.titleText)
        private val body = itemView.findViewById<TextView>(R.id.bodyText)

        fun bind(header: String, content: String) {
            title.text = header
            body.text = content
        }
    }

    private class StatusViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.statusCard)
        private val title = itemView.findViewById<TextView>(R.id.statusTitle)
        private val body = itemView.findViewById<TextView>(R.id.statusBody)

        fun bind(item: AIAssistantStatusItem) {
            title.text = item.title
            body.text = item.body.orEmpty()
            body.isVisible = !item.body.isNullOrBlank()

            val (containerAttr, textAttr) = when (item.tone) {
                AIAssistantTone.RUNNING -> android.R.attr.colorActivatedHighlight to com.google.android.material.R.attr.colorOnSecondaryContainer
                AIAssistantTone.SUCCESS -> com.google.android.material.R.attr.colorSecondaryContainer to com.google.android.material.R.attr.colorOnSecondaryContainer
                AIAssistantTone.WARNING -> com.google.android.material.R.attr.colorTertiaryContainer to com.google.android.material.R.attr.colorOnTertiaryContainer
                AIAssistantTone.ERROR -> com.google.android.material.R.attr.colorErrorContainer to com.google.android.material.R.attr.colorOnErrorContainer
                AIAssistantTone.NEUTRAL -> com.google.android.material.R.attr.colorSurfaceContainerHighest to com.google.android.material.R.attr.colorOnSurface
            }
            applyCardTone(card, itemView, containerAttr, textAttr, title, body)
        }
    }

    private class ToolViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.toolCard)
        private val title = itemView.findViewById<TextView>(R.id.toolTitle)
        private val summary = itemView.findViewById<TextView>(R.id.toolSummary)
        private val command = itemView.findViewById<TextView>(R.id.toolCommand)
        private val workingDirectory = itemView.findViewById<TextView>(R.id.toolWorkdir)
        private val output = itemView.findViewById<TextView>(R.id.toolOutput)

        init {
            command.typeface = Typeface.MONOSPACE
            workingDirectory.typeface = Typeface.MONOSPACE
            output.typeface = Typeface.MONOSPACE
        }

        fun bind(item: AIAssistantToolItem) {
            title.text = item.title
            summary.text = item.summary
            command.text = item.command.orEmpty()
            workingDirectory.text = item.workingDirectory.orEmpty()
            output.text = item.outputPreview.orEmpty()
            command.isVisible = !item.command.isNullOrBlank()
            workingDirectory.isVisible = !item.workingDirectory.isNullOrBlank()
            output.isVisible = !item.outputPreview.isNullOrBlank()

            val (containerAttr, textAttr) = when (item.tone) {
                AIAssistantTone.RUNNING -> com.google.android.material.R.attr.colorSecondaryContainer to com.google.android.material.R.attr.colorOnSecondaryContainer
                AIAssistantTone.SUCCESS -> com.google.android.material.R.attr.colorPrimaryContainer to com.google.android.material.R.attr.colorOnPrimaryContainer
                AIAssistantTone.ERROR -> com.google.android.material.R.attr.colorErrorContainer to com.google.android.material.R.attr.colorOnErrorContainer
                AIAssistantTone.WARNING -> com.google.android.material.R.attr.colorTertiaryContainer to com.google.android.material.R.attr.colorOnTertiaryContainer
                AIAssistantTone.NEUTRAL -> com.google.android.material.R.attr.colorSurfaceContainerHigh to com.google.android.material.R.attr.colorOnSurface
            }
            applyCardTone(card, itemView, containerAttr, textAttr, title, summary, command, workingDirectory, output)
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
        private const val VIEW_TYPE_TOOL = 3
        private const val VIEW_TYPE_DIFF = 4

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
