package com.xiaoran.nb.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xiaoran.nb.R

/**
 * 左侧导航适配器
 */
class NavAdapter(
    private val items: List<NavItem>,
    private var selectedPosition: Int = 0,
    private val onItemClick: (Int, NavItem) -> Unit
) : RecyclerView.Adapter<NavAdapter.ViewHolder>() {

    data class NavItem(
        val iconRes: Int,
        val title: String
    )

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val container: LinearLayout = itemView.findViewById(R.id.nav_item_container)
        val icon: ImageView = itemView.findViewById(R.id.iv_nav_icon)
        val text: TextView = itemView.findViewById(R.id.tv_nav_text)
        val indicator: View = itemView.findViewById(R.id.v_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nav, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.icon.setImageResource(item.iconRes)
        holder.text.text = item.title

        val isSelected = position == selectedPosition
        if (isSelected) {
            holder.container.setBackgroundResource(R.drawable.bg_nav_item_selected)
            holder.text.setTextColor(holder.itemView.context.getColor(R.color.white))
            holder.icon.setColorFilter(holder.itemView.context.getColor(R.color.white))
            holder.indicator.visibility = View.VISIBLE
        } else {
            holder.container.setBackgroundColor(holder.itemView.context.getColor(android.R.color.transparent))
            holder.text.setTextColor(holder.itemView.context.getColor(R.color.text_secondary))
            holder.icon.setColorFilter(holder.itemView.context.getColor(R.color.text_secondary))
            holder.indicator.visibility = View.INVISIBLE
        }

        holder.itemView.setOnClickListener {
            val oldPosition = selectedPosition
            selectedPosition = position
            notifyItemChanged(oldPosition)
            notifyItemChanged(position)
            onItemClick(position, item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun getSelectedPosition(): Int = selectedPosition
}