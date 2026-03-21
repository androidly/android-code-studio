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
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.R
import com.tom.rv2ide.models.OnboardingItem

/**
 * Default implmentation of [RecyclerView.Adapter] for showing [OnboardingItem]s.
 *
 * @author Akash Yadav
 */
open class DefaultOnboardingItemAdapter<T : OnboardingItem>(
    protected val items: List<T>,
    protected val onItemClickListener: OnItemClickListener<T>? = null,
    protected val onItemLongClickListener: OnItemLongClickListener<T>? = null,
) : RecyclerView.Adapter<DefaultOnboardingItemAdapter.ViewHolder>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val itemView = LayoutInflater.from(parent.context).inflate(R.layout.layout_onboarding_item, parent, false)
    return ViewHolder(itemView)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    doBindViewHolder(holder, position, getItem(position))
  }

  protected open fun doBindViewHolder(
      holder: ViewHolder,
      position: Int,
      item: T,
  ) {
    holder.title.text = item.title

    if (item.description.isNotBlank()) {
      holder.description.text = item.description
      holder.description.visibility = View.VISIBLE
    } else {
      holder.description.visibility = View.INVISIBLE
    }

    if (item.icon != 0) {
      holder.icon.setImageResource(item.icon)
      holder.icon.visibility = View.VISIBLE
      if (item.iconTint != 0) {
        holder.icon.imageTintList = ColorStateList.valueOf(item.iconTint)
      } else {
        holder.icon.imageTintList = null
      }
    } else {
      holder.icon.visibility = View.INVISIBLE
      holder.icon.imageTintList = null
    }

    holder.itemView.isClickable = item.isClickable
    holder.itemView.isFocusable = item.isClickable

    if (item.isClickable && onItemClickListener != null) {
      holder.itemView.setOnClickListener { onItemClickListener.onClick(item, position, holder) }
    } else {
      holder.itemView.setOnClickListener(null)
    }

    if (item.isLongClickable && onItemLongClickListener != null) {
      holder.itemView.setOnLongClickListener {
        onItemLongClickListener.onLongClick(item, position, holder)
      }
    } else {
      holder.itemView.setOnLongClickListener(null)
    }
  }

  override fun getItemCount(): Int {
    return items.size
  }

  fun getItem(index: Int): T {
    return items[index]
  }

  class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val title: TextView = itemView.findViewById(R.id.title)
    val description: TextView = itemView.findViewById(R.id.description)
    val icon: ImageView = itemView.findViewById(R.id.icon)
  }

  fun interface OnItemClickListener<T : OnboardingItem> {

    fun onClick(item: T, position: Int, holder: ViewHolder)
  }

  fun interface OnItemLongClickListener<T : OnboardingItem> {

    fun onLongClick(item: T, position: Int, holder: ViewHolder): Boolean
  }
}
