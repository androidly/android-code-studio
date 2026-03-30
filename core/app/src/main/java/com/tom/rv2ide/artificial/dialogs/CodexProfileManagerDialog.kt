package com.tom.rv2ide.artificial.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agents.external.CodexCliAuthMode
import com.tom.rv2ide.artificial.agents.external.CodexCliConfig
import com.tom.rv2ide.artificial.agents.external.CodexCliProfile
import com.tom.rv2ide.artificial.agents.external.CodexCliReasoningEffort
import com.tom.rv2ide.artificial.agents.external.CodexCliSettings

internal sealed class CodexProfileManagerEvent {
    data class Activated(val profileName: String) : CodexProfileManagerEvent()
    data class Saved(
        val profileName: String,
        val profileBecameActive: Boolean
    ) : CodexProfileManagerEvent()

    data class Deleted(
        val profileName: String,
        val affectedActiveProfile: Boolean
    ) : CodexProfileManagerEvent()
}

internal class CodexProfileManagerDialog(
    private val onEvent: (CodexProfileManagerEvent) -> Unit
) : BottomSheetDialogFragment() {

    private var expandedProfileId: String? = null
    private var recyclerView: RecyclerView? = null
    private val adapter by lazy {
        CodexProfileManagerAdapter(
            onSwitchClicked = ::activateProfile,
            onViewClicked = ::toggleProfileDetails,
            onEditClicked = ::editProfile,
            onDeleteClicked = ::confirmDeleteProfile,
            onAddClicked = ::createProfile
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_codex_profile_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById<RecyclerView>(R.id.codexProfileManagerRecyclerView).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@CodexProfileManagerDialog.adapter
        }
        refreshProfiles()
    }

    override fun onStart() {
        super.onStart()
        val bottomSheetDialog = dialog as? BottomSheetDialog ?: return
        bottomSheetDialog.behavior.apply {
            isDraggable = true
            skipCollapsed = false
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onDestroyView() {
        recyclerView?.adapter = null
        recyclerView = null
        super.onDestroyView()
    }

    private fun refreshProfiles() {
        val profiles = CodexCliConfig.getProfiles()
        if (profiles.none { it.id == expandedProfileId }) {
            expandedProfileId = null
        }
        adapter.submitProfiles(
            profiles = profiles,
            activeProfileId = CodexCliConfig.getActiveProfileId(),
            expandedProfileId = expandedProfileId
        )
    }

    private fun toggleProfileDetails(profile: CodexCliProfile) {
        expandedProfileId = if (expandedProfileId == profile.id) null else profile.id
        refreshProfiles()
    }

    private fun activateProfile(profile: CodexCliProfile) {
        CodexCliConfig.setActiveProfile(profile.id)
        expandedProfileId = profile.id
        refreshProfiles()
        onEvent(CodexProfileManagerEvent.Activated(profile.name))
    }

    private fun editProfile(profile: CodexCliProfile) {
        openEditor(profileId = profile.id, createNew = false)
    }

    private fun createProfile() {
        openEditor(profileId = null, createNew = true)
    }

    private fun openEditor(
        profileId: String?,
        createNew: Boolean
    ) {
        CodexCliConfigDialog(
            profileId = profileId,
            createNew = createNew,
            makeActiveOnSave = false
        ) { settings ->
            expandedProfileId = settings.profileId.takeIf { it.isNotBlank() }
            refreshProfiles()
            onEvent(
                CodexProfileManagerEvent.Saved(
                    profileName = settings.resolvedProfileName,
                    profileBecameActive = settings.profileId.isNotBlank() &&
                        settings.profileId == CodexCliConfig.getActiveProfileId()
                )
            )
        }.show(parentFragmentManager, "CodexCliConfigDialog")
    }

    private fun confirmDeleteProfile(profile: CodexCliProfile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ai_assistant_delete_codex_profile_dialog_title)
            .setMessage(
                getString(
                    R.string.ai_assistant_delete_codex_profile_dialog_message,
                    profile.name
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val affectedActiveProfile = profile.id == CodexCliConfig.getActiveProfileId()
                val deleted = CodexCliConfig.deleteProfile(profile.id)
                if (!deleted) {
                    return@setPositiveButton
                }
                if (expandedProfileId == profile.id) {
                    expandedProfileId = null
                }
                refreshProfiles()
                onEvent(
                    CodexProfileManagerEvent.Deleted(
                        profileName = profile.name,
                        affectedActiveProfile = affectedActiveProfile
                    )
                )
            }
            .show()
    }
}

private sealed class CodexProfileManagerListItem {
    data class ProfileCard(
        val profile: CodexCliProfile,
        val isActive: Boolean,
        val isExpanded: Boolean
    ) : CodexProfileManagerListItem()

    object AddCard : CodexProfileManagerListItem()
}

private class CodexProfileManagerAdapter(
    private val onSwitchClicked: (CodexCliProfile) -> Unit,
    private val onViewClicked: (CodexCliProfile) -> Unit,
    private val onEditClicked: (CodexCliProfile) -> Unit,
    private val onDeleteClicked: (CodexCliProfile) -> Unit,
    private val onAddClicked: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var items: List<CodexProfileManagerListItem> = listOf(CodexProfileManagerListItem.AddCard)

    fun submitProfiles(
        profiles: List<CodexCliProfile>,
        activeProfileId: String,
        expandedProfileId: String?
    ) {
        items = profiles.map { profile ->
            CodexProfileManagerListItem.ProfileCard(
                profile = profile,
                isActive = profile.id == activeProfileId,
                isExpanded = profile.id == expandedProfileId
            )
        } + CodexProfileManagerListItem.AddCard
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is CodexProfileManagerListItem.ProfileCard -> VIEW_TYPE_PROFILE
            CodexProfileManagerListItem.AddCard -> VIEW_TYPE_ADD
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_PROFILE -> {
                val view = inflater.inflate(R.layout.item_codex_profile_card, parent, false)
                ProfileViewHolder(
                    view = view,
                    onSwitchClicked = onSwitchClicked,
                    onViewClicked = onViewClicked,
                    onEditClicked = onEditClicked,
                    onDeleteClicked = onDeleteClicked
                )
            }

            else -> {
                val view = inflater.inflate(R.layout.item_codex_profile_add_card, parent, false)
                AddViewHolder(view, onAddClicked)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is CodexProfileManagerListItem.ProfileCard -> (holder as ProfileViewHolder).bind(item)
            CodexProfileManagerListItem.AddCard -> (holder as AddViewHolder).bind()
        }
    }

    override fun getItemCount(): Int = items.size

    private class ProfileViewHolder(
        view: View,
        private val onSwitchClicked: (CodexCliProfile) -> Unit,
        private val onViewClicked: (CodexCliProfile) -> Unit,
        private val onEditClicked: (CodexCliProfile) -> Unit,
        private val onDeleteClicked: (CodexCliProfile) -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val cardView = view.findViewById<MaterialCardView>(R.id.codexProfileCard)
        private val titleText = view.findViewById<TextView>(R.id.codexProfileCardTitle)
        private val stateText = view.findViewById<TextView>(R.id.codexProfileCardState)
        private val summaryText = view.findViewById<TextView>(R.id.codexProfileCardSummary)
        private val detailText = view.findViewById<TextView>(R.id.codexProfileCardDetails)
        private val switchButton = view.findViewById<MaterialButton>(R.id.codexProfileCardSwitchButton)
        private val viewButton = view.findViewById<MaterialButton>(R.id.codexProfileCardViewButton)
        private val editButton = view.findViewById<MaterialButton>(R.id.codexProfileCardEditButton)
        private val deleteButton = view.findViewById<MaterialButton>(R.id.codexProfileCardDeleteButton)

        fun bind(item: CodexProfileManagerListItem.ProfileCard) {
            val profile = item.profile
            val settings = profile.toSettings()
            val context = itemView.context

            titleText.text = profile.name
            stateText.isVisible = item.isActive
            stateText.text = context.getString(R.string.ai_assistant_session_active_word)
            summaryText.text = buildSummaryText(context, settings)
            detailText.text = buildDetailsText(context, settings)
            detailText.isVisible = item.isExpanded

            val activeStrokeColor = MaterialColors.getColor(
                itemView,
                android.R.attr.colorPrimary
            )
            val defaultStrokeColor = MaterialColors.getColor(
                itemView,
                com.google.android.material.R.attr.colorOutlineVariant
            )
            cardView.strokeColor = if (item.isActive) activeStrokeColor else defaultStrokeColor
            cardView.strokeWidth = if (item.isActive) dpToPx(2) else dpToPx(1)

            switchButton.text = context.getString(R.string.ai_assistant_session_switch)
            switchButton.isEnabled = !item.isActive
            viewButton.text = context.getString(
                if (item.isExpanded) {
                    R.string.ai_assistant_codex_profile_hide
                } else {
                    R.string.ai_assistant_codex_profile_view
                }
            )

            itemView.setOnClickListener { onViewClicked(profile) }
            switchButton.setOnClickListener { onSwitchClicked(profile) }
            viewButton.setOnClickListener { onViewClicked(profile) }
            editButton.setOnClickListener { onEditClicked(profile) }
            deleteButton.setOnClickListener { onDeleteClicked(profile) }
        }

        private fun buildSummaryText(
            context: android.content.Context,
            settings: CodexCliSettings
        ): String {
            val summary = settings.summaryText()
            return if (settings.isValid) {
                summary
            } else {
                listOf(summary, context.getString(R.string.ai_assistant_codex_profile_needs_setup))
                    .filter(String::isNotBlank)
                    .joinToString(" • ")
            }
        }

        private fun buildDetailsText(
            context: android.content.Context,
            settings: CodexCliSettings
        ): String {
            return buildString {
                appendLine("${context.getString(R.string.ai_assistant_codex_provider_id_hint)}: ${settings.normalizedProviderId}")
                appendLine("${context.getString(R.string.ai_assistant_codex_provider_name_hint)}: ${settings.resolvedProviderName}")
                appendLine(
                    "${context.getString(R.string.ai_assistant_base_url_hint)}: " +
                        settings.normalizedBaseUrl.ifBlank { "-" }
                )
                appendLine(
                    "${context.getString(R.string.ai_assistant_api_key_hint)}: " +
                        if (settings.apiKey.isBlank()) {
                            context.getString(R.string.ai_assistant_codex_profile_api_key_missing)
                        } else {
                            context.getString(R.string.ai_assistant_codex_profile_api_key_configured)
                        }
                )
                appendLine("${context.getString(R.string.ai_assistant_codex_model_hint)}: ${settings.model.ifBlank { "-" }}")
                appendLine(
                    "${context.getString(R.string.ai_assistant_codex_review_model_hint)}: " +
                        settings.resolvedReviewModel.ifBlank { "-" }
                )
                appendLine(
                    "${context.getString(R.string.ai_assistant_codex_reasoning_hint)}: " +
                        reasoningLabel(settings.reasoningEffort)
                )
                appendLine(
                    "${context.getString(R.string.ai_assistant_codex_auth_mode_hint)}: " +
                        authModeLabel(context, settings.authMode)
                )
                appendLine(
                    "${context.getString(R.string.ai_assistant_codex_context_window_hint)}: " +
                        settings.contextWindow
                )
                append(
                    "${context.getString(R.string.ai_assistant_codex_auto_compact_hint)}: " +
                        settings.autoCompactTokenLimit
                )
            }
        }

        private fun authModeLabel(
            context: android.content.Context,
            authMode: CodexCliAuthMode
        ): String {
            return when (authMode) {
                CodexCliAuthMode.ENV_KEY -> context.getString(R.string.ai_assistant_codex_auth_mode_env_key)
                CodexCliAuthMode.OPENAI_AUTH -> context.getString(R.string.ai_assistant_codex_auth_mode_openai_auth)
            }
        }

        private fun reasoningLabel(reasoningEffort: CodexCliReasoningEffort): String {
            return when (reasoningEffort) {
                CodexCliReasoningEffort.NONE -> "none"
                CodexCliReasoningEffort.MINIMAL -> "minimal"
                CodexCliReasoningEffort.LOW -> "low"
                CodexCliReasoningEffort.MEDIUM -> "medium"
                CodexCliReasoningEffort.HIGH -> "high"
                CodexCliReasoningEffort.XHIGH -> "xhigh"
            }
        }

        private fun dpToPx(value: Int): Int {
            return (value * itemView.resources.displayMetrics.density).toInt()
        }
    }

    private class AddViewHolder(
        view: View,
        private val onAddClicked: () -> Unit
    ) : RecyclerView.ViewHolder(view) {

        private val addTitle = view.findViewById<TextView>(R.id.codexProfileAddTitle)
        private val addSummary = view.findViewById<TextView>(R.id.codexProfileAddSummary)

        fun bind() {
            addTitle.text = itemView.context.getString(R.string.ai_assistant_add_codex_profile_title)
            addSummary.text = itemView.context.getString(R.string.ai_assistant_codex_profile_add_card_summary)
            itemView.setOnClickListener { onAddClicked() }
        }
    }

    companion object {
        private const val VIEW_TYPE_PROFILE = 1
        private const val VIEW_TYPE_ADD = 2
    }
}
