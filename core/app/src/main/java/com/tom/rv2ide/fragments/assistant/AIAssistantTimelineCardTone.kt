package com.tom.rv2ide.fragments.assistant

import android.view.View
import android.widget.TextView
import androidx.annotation.AttrRes
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors

internal object AIAssistantTimelineCardTone {
    fun apply(
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
