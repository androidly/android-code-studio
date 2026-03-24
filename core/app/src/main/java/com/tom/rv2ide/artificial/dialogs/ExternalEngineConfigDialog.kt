package com.tom.rv2ide.artificial.dialogs

import android.os.Bundle
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tom.rv2ide.artificial.agents.external.CodexTermuxBridge
import com.tom.rv2ide.artificial.agents.external.ExternalEngineSettings

class ExternalEngineConfigDialog(
    private val onSave: (ExternalEngineSettings) -> Unit
) : BottomSheetDialogFragment() {

    private var redirected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, theme)
    }

    override fun onStart() {
        super.onStart()
        if (redirected) {
            dismissAllowingStateLoss()
            return
        }
        redirected = true
        val hostContext = context ?: run {
            dismissAllowingStateLoss()
            return
        }
        val fragmentManager = parentFragmentManager
        if (fragmentManager.isStateSaved) {
            dismissAllowingStateLoss()
            return
        }
        val currentSettings = CodexTermuxBridge.ensureManagedPreset(context = hostContext)
        dismissAllowingStateLoss()
        showCodexDialog(
            fragmentManager = fragmentManager,
            hostContext = hostContext,
            currentSettings = currentSettings
        )
    }

    private fun showCodexDialog(
        fragmentManager: FragmentManager,
        hostContext: android.content.Context,
        currentSettings: ExternalEngineSettings
    ) {
        onSave(currentSettings)
        CodexCliConfigDialog {
            val savedSettings = CodexTermuxBridge.ensureManagedPreset(context = hostContext)
            onSave(savedSettings)
        }.show(fragmentManager, "CodexCliConfigDialog")
    }
}
