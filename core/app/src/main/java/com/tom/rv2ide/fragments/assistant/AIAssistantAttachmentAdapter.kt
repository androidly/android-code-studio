package com.tom.rv2ide.fragments.assistant

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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

class AIAssistantAttachmentAdapter :
    ListAdapter<AIAssistantToolItem, AIAssistantAttachmentAdapter.AttachmentViewHolder>(
        AttachmentDiffCallback
    ) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    fun replaceAll(
        newItems: List<AIAssistantToolItem>,
        onCommitted: (() -> Unit)? = null
    ) {
        submitList(newItems) {
            onCommitted?.invoke()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttachmentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_assistant_tool_attachment, parent, false)
        return AttachmentViewHolder(view)
    }

    override fun onBindViewHolder(holder: AttachmentViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: AttachmentViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: AttachmentViewHolder) {
        super.onViewDetachedFromWindow(holder)
    }

    class AttachmentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card = itemView.findViewById<MaterialCardView>(R.id.attachmentCard)
        private val title = itemView.findViewById<TextView>(R.id.attachmentTitle)
        private val phase = itemView.findViewById<TextView>(R.id.attachmentPhase)
        private val stageTrail = itemView.findViewById<TextView>(R.id.attachmentStageTrail)
        private val summary = itemView.findViewById<TextView>(R.id.attachmentSummary)
        private val progress = itemView.findViewById<LinearProgressIndicator>(R.id.attachmentProgress)
        private val commandLabel = itemView.findViewById<TextView>(R.id.attachmentCommandLabel)
        private val command = itemView.findViewById<TextView>(R.id.attachmentCommand)
        private val workingDirectory = itemView.findViewById<TextView>(R.id.attachmentWorkdir)
        private val outputLabel = itemView.findViewById<TextView>(R.id.attachmentOutputLabel)
        private val output = itemView.findViewById<TextView>(R.id.attachmentOutput)

        init {
            command.typeface = Typeface.MONOSPACE
            workingDirectory.typeface = Typeface.MONOSPACE
            output.typeface = Typeface.MONOSPACE
        }

        fun bind(item: AIAssistantToolItem) {
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
            stageTrail.isVisible = false
            commandLabel.isVisible = !item.command.isNullOrBlank()
            command.isVisible = !item.command.isNullOrBlank()
            workingDirectory.isVisible = !item.workingDirectory.isNullOrBlank()
            outputLabel.text = if (item.stage == AIAssistantToolStage.STREAMING) "Live output" else "Output"
            outputLabel.isVisible = !item.outputPreview.isNullOrBlank()
            output.isVisible = !item.outputPreview.isNullOrBlank()
            progress.isVisible = item.isLive
            progress.setIndicatorColor(MaterialColors.getColor(itemView, androidx.appcompat.R.attr.colorPrimary, 0))

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
                itemView,
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
                MaterialColors.getColor(itemView, palette.chipContainerAttr, 0)
            )
            phase.setTextColor(MaterialColors.getColor(itemView, palette.chipTextAttr, 0))
        }

        fun recycle() {
            title.text = ""
            phase.text = ""
            stageTrail.text = ""
            summary.text = ""
            command.text = ""
            workingDirectory.text = ""
            output.text = ""
            progress.isVisible = false
        }
    }

    private data class ToolPalette(
        @AttrRes val containerAttr: Int,
        @AttrRes val textAttr: Int,
        @AttrRes val chipContainerAttr: Int,
        @AttrRes val chipTextAttr: Int
    )

    companion object {
        private val AttachmentDiffCallback = object : DiffUtil.ItemCallback<AIAssistantToolItem>() {
            override fun areItemsTheSame(
                oldItem: AIAssistantToolItem,
                newItem: AIAssistantToolItem
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: AIAssistantToolItem,
                newItem: AIAssistantToolItem
            ): Boolean {
                return oldItem == newItem
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
