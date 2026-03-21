/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.adapters.onboarding

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.SizeUtils
import com.google.android.material.button.MaterialButton
import com.tom.rv2ide.R
import com.tom.rv2ide.models.OnboardingPermissionItem

/** @author Akash Yadav */
class OnboardingPermissionsAdapter(
    private val permissions: List<OnboardingPermissionItem>,
    private val requestPermission: (String) -> Unit,
) : RecyclerView.Adapter<OnboardingPermissionsAdapter.ViewHolder>() {

  class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val title: TextView = itemView.findViewById(R.id.title)
    val description: TextView = itemView.findViewById(R.id.description)
    val grantButton: MaterialButton = itemView.findViewById(R.id.grant_button)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val itemView = LayoutInflater.from(parent.context)
        .inflate(R.layout.layout_onboarding_permission_item, parent, false)
    return ViewHolder(itemView)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val permission = permissions[position]

    holder.title.setText(permission.title)
    holder.description.setText(permission.description)
    holder.grantButton.setOnClickListener(null)
    holder.grantButton.isEnabled = true
    holder.grantButton.text = holder.itemView.context.getString(R.string.title_grant)
    holder.grantButton.icon = null
    holder.grantButton.iconTint = null
    holder.grantButton.iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
    holder.grantButton.iconPadding = SizeUtils.dp2px(8f)
    holder.grantButton.iconSize = 0

    if (!permission.isGranted) {
      holder.grantButton.setOnClickListener { requestPermission(permission.permission) }
      return
    }

    holder.grantButton.apply {
      isEnabled = false
      text = ""
      icon = ContextCompat.getDrawable(context, R.drawable.ic_ok)
      iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.green_500))
      iconGravity = MaterialButton.ICON_GRAVITY_TEXT_TOP
      iconPadding = 0
      iconSize = SizeUtils.dp2px(28f)
    }
  }

  override fun getItemCount(): Int {
    return permissions.size
  }
}
