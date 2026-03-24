package com.tom.rv2ide.fragments.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tom.rv2ide.R

internal enum class AIAssistantSlashCommandBehavior {
    EXECUTE,
    INSERT
}

internal data class AIAssistantSlashCommandOption(
    val commandText: String,
    val title: String,
    val description: String,
    val behavior: AIAssistantSlashCommandBehavior
)

internal class AIAssistantCommandSheetDialog(
    private val onCommandSelected: (AIAssistantSlashCommandOption) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_ai_assistant_command_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recyclerView = view.findViewById<RecyclerView>(R.id.commandSheetRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = AIAssistantCommandSheetAdapter(defaultCommands()) { command ->
            onCommandSelected(command)
            dismissAllowingStateLoss()
        }
    }

    override fun onStart() {
        super.onStart()
        val bottomSheetDialog = dialog as? BottomSheetDialog ?: return
        bottomSheetDialog.behavior.apply {
            isDraggable = true
            skipCollapsed = false
            isFitToContents = false
            halfExpandedRatio = 0.5f
            state = BottomSheetBehavior.STATE_HALF_EXPANDED
        }
    }

    private fun defaultCommands(): List<AIAssistantSlashCommandOption> = listOf(
        executeCommand(
            commandText = "/new",
            title = "New Session",
            description = "Start a fresh assistant chat without deleting earlier sessions."
        ),
        executeCommand(
            commandText = "/list",
            title = "List Sessions",
            description = "Show saved sessions for this project and their switch numbers."
        ),
        insertCommand(
            commandText = "/switch ",
            title = "Switch Session",
            description = "Fill /switch so you can jump to a saved session by number."
        ),
        executeCommand(
            commandText = "/history",
            title = "Session History",
            description = "Show the recent turns from the active saved session."
        ),
        insertCommand(
            commandText = "/search ",
            title = "Search Sessions",
            description = "Fill /search to look up sessions, prompts, or answers by keyword."
        ),
        insertCommand(
            commandText = "/delete ",
            title = "Delete Session",
            description = "Fill /delete so you can remove an old session by number."
        ),
        executeCommand(
            commandText = "/status",
            title = "Session Status",
            description = "Show the active session, queue, provider, and runtime state."
        ),
        executeCommand(
            commandText = "/compress",
            title = "Compress Context",
            description = "Check the current engine's context compaction support."
        ),
        executeCommand(
            commandText = "/memory",
            title = "Project Memory",
            description = "Show the current workspace memory file."
        ),
        executeCommand(
            commandText = "/review",
            title = "Review Changes",
            description = "Open the current diff review if the last run edited files."
        ),
        executeCommand(
            commandText = "/stop",
            title = "Stop Run",
            description = "Stop the active assistant run immediately."
        ),
        executeCommand(
            commandText = "/clear",
            title = "Clear Session",
            description = "Clear only the current session timeline and conversation state."
        ),
        executeCommand(
            commandText = "/help",
            title = "Command Help",
            description = "Show the built-in slash-command help inside the assistant timeline."
        )
    )

    private fun executeCommand(
        commandText: String,
        title: String,
        description: String
    ): AIAssistantSlashCommandOption = AIAssistantSlashCommandOption(
        commandText = commandText,
        title = title,
        description = description,
        behavior = AIAssistantSlashCommandBehavior.EXECUTE
    )

    private fun insertCommand(
        commandText: String,
        title: String,
        description: String
    ): AIAssistantSlashCommandOption = AIAssistantSlashCommandOption(
        commandText = commandText,
        title = title,
        description = description,
        behavior = AIAssistantSlashCommandBehavior.INSERT
    )
}

private class AIAssistantCommandSheetAdapter(
    private val commands: List<AIAssistantSlashCommandOption>,
    private val onCommandSelected: (AIAssistantSlashCommandOption) -> Unit
) : RecyclerView.Adapter<AIAssistantCommandSheetAdapter.CommandViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommandViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ai_assistant_command, parent, false)
        return CommandViewHolder(view, onCommandSelected)
    }

    override fun onBindViewHolder(holder: CommandViewHolder, position: Int) {
        holder.bind(commands[position])
    }

    override fun getItemCount(): Int = commands.size

    class CommandViewHolder(
        itemView: View,
        private val onCommandSelected: (AIAssistantSlashCommandOption) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val commandText = itemView.findViewById<TextView>(R.id.commandItemCommand)
        private val titleText = itemView.findViewById<TextView>(R.id.commandItemTitle)
        private val descriptionText = itemView.findViewById<TextView>(R.id.commandItemDescription)
        private val actionText = itemView.findViewById<TextView>(R.id.commandItemAction)

        fun bind(command: AIAssistantSlashCommandOption) {
            commandText.text = command.commandText.trimEnd()
            titleText.text = command.title
            descriptionText.text = command.description
            actionText.text = when (command.behavior) {
                AIAssistantSlashCommandBehavior.EXECUTE -> "Run"
                AIAssistantSlashCommandBehavior.INSERT -> "Insert"
            }
            itemView.setOnClickListener { onCommandSelected(command) }
        }
    }
}
