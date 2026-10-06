package com.mikasa.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mikasa.R

/**
 * 顶部Tab适配器
 */
class TabAdapter(
    private val tabs: MutableList<String> = mutableListOf(),
    private var selectedPosition: Int = 0,
    private val onTabClick: (Int, String) -> Unit
) : RecyclerView.Adapter<TabAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val textView: TextView = itemView.findViewById(R.id.tv_tab)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tab, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tab = tabs[position]
        holder.textView.text = tab

        val isSelected = position == selectedPosition
        if (isSelected) {
            holder.textView.setBackgroundResource(R.drawable.bg_tab_selected)
            holder.textView.setTextColor(holder.itemView.context.getColor(R.color.white))
        } else {
            holder.textView.setBackgroundResource(R.drawable.bg_tab_normal)
            holder.textView.setTextColor(holder.itemView.context.getColor(R.color.text_primary))
        }

        holder.itemView.setOnClickListener {
            val oldPosition = selectedPosition
            selectedPosition = position
            notifyItemChanged(oldPosition)
            notifyItemChanged(position)
            onTabClick(position, tab)
        }
    }

    override fun getItemCount(): Int = tabs.size

    fun setTabs(newTabs: List<String>) {
        tabs.clear()
        tabs.addAll(newTabs)
        selectedPosition = 0
        notifyDataSetChanged()
    }

    fun getSelectedPosition(): Int = selectedPosition
}