package com.mikasa.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.mikasa.R

/**
 * 功能列表适配器（支持搜索过滤 + 折叠组 + 按钮开关 + 纯文字 + 单选）
 *
 * 条目类型：
 * - TYPE_ITEM        普通功能行（名称 + 勾选框）
 * - TYPE_GROUP       折叠组标题（点击展开/折叠，展开后显示子项）
 * - TYPE_GROUP_CHILD 折叠组子项（缩进显示 + 勾选框）
 * - TYPE_SWITCH      按钮开关（名称 + 开关，点击切换开/关）
 * - TYPE_TEXT        纯文字行（公告/卡密/设备等信息，无勾选控件）
 * - TYPE_BUTTON      整行主按钮（注入等，点击触发 onButtonClick）
 * - TYPE_HEADER      分节标题（大号粗体文字）
 * - TYPE_RADIO       单选项（名称 + 副标题 + 圆点；同 group 内互斥，点击触发 onRadio）
 */
class FunctionAdapter(
    private val items: MutableList<FunctionItem> = mutableListOf()
) : RecyclerView.Adapter<FunctionAdapter.ViewHolder>() {

    companion object {
        const val TYPE_ITEM = 0
        const val TYPE_GROUP = 1
        const val TYPE_GROUP_CHILD = 2
        const val TYPE_SWITCH = 3
        const val TYPE_TEXT = 4
        const val TYPE_BUTTON = 5
        const val TYPE_HEADER = 6
        const val TYPE_RADIO = 7
        const val TYPE_SLIDER = 8
    }

    /** 开关切换回调（名字, 是否开启） */
    var onToggle: ((String, Boolean) -> Unit)? = null

    /** 勾选回调（名字, 是否勾选） */
    var onCheck: ((String, Boolean) -> Unit)? = null

    /** 主按钮点击回调（按钮名） */
    var onButtonClick: ((String) -> Unit)? = null

    /** 单选回调（分组, 项名）：同 group 内互斥，由调用方持有选中态并重建 */
    var onRadio: ((String, String) -> Unit)? = null

    /** 滑杆回调（名字, 当前值） */
    var onSlider: ((String, Int) -> Unit)? = null

    // 原始完整数据（用于过滤后恢复）
    private var allItems: List<FunctionItem> = emptyList()

    data class FunctionItem(
        val name: String,
        var isChecked: Boolean = false,
        val type: Int = TYPE_ITEM,
        var expanded: Boolean = false,
        val group: String? = null,
        val subtitle: String = "",
        val sliderValue: Int = 0,
        val sliderMax: Int = 100
    )

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView? = itemView.findViewById(R.id.tv_function_name)
        val checkBox: CheckBox? = itemView.findViewById(R.id.cb_function)
        val groupName: TextView? = itemView.findViewById(R.id.tv_group_name)
        val groupArrow: TextView? = itemView.findViewById(R.id.tv_group_arrow)
        val switchName: TextView? = itemView.findViewById(R.id.tv_switch_name)
        val switchButton: Switch? = itemView.findViewById(R.id.switch_button)
        val action: Button? = itemView.findViewById(R.id.btn_action)
        val radio: RadioButton? = itemView.findViewById(R.id.rb_select)
        val sub: TextView? = itemView.findViewById(R.id.tv_function_sub)
        val slider: SeekBar? = itemView.findViewById(R.id.sb_value)
    }

    override fun getItemViewType(position: Int): Int = items[position].type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = when (viewType) {
            TYPE_GROUP -> R.layout.item_function_group
            TYPE_SWITCH -> R.layout.item_function_switch
            TYPE_TEXT -> R.layout.item_function_text
            TYPE_BUTTON -> R.layout.item_function_button
            TYPE_HEADER -> R.layout.item_function_header
            TYPE_RADIO -> R.layout.item_function_radio
            TYPE_SLIDER -> R.layout.item_function_slider
            else -> R.layout.item_function
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        when (item.type) {
            TYPE_GROUP -> bindGroup(holder, item)
            TYPE_GROUP_CHILD -> bindChild(holder, item)
            TYPE_SWITCH -> bindSwitch(holder, item)
            TYPE_TEXT -> bindText(holder, item)
            TYPE_BUTTON -> bindButton(holder, item)
            TYPE_HEADER -> bindHeader(holder, item)
            TYPE_RADIO -> bindRadio(holder, item)
            TYPE_SLIDER -> bindSlider(holder, item)
            else -> bindItem(holder, item)
        }
    }

    /** 普通功能行 */
    private fun bindItem(holder: ViewHolder, item: FunctionItem) {
        holder.name?.text = item.name
        holder.checkBox?.isChecked = item.isChecked
        holder.checkBox?.setOnCheckedChangeListener { _, isChecked ->
            item.isChecked = isChecked
            onCheck?.invoke(item.name, isChecked)
            onToggle?.invoke(item.name, isChecked)
        }
        holder.itemView.setOnClickListener {
            holder.checkBox?.isChecked = !(holder.checkBox?.isChecked ?: false)
        }
    }

    /** 折叠组标题 */
    private fun bindGroup(holder: ViewHolder, item: FunctionItem) {
        holder.groupName?.text = item.name
        holder.groupArrow?.text = if (item.expanded) "▾" else "▸"
        holder.itemView.setOnClickListener {
            item.expanded = !item.expanded
            rebuildVisible()
            Toast.makeText(
                holder.itemView.context,
                if (item.expanded) "已展开：${item.name}" else "已折叠：${item.name}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 折叠组子项 */
    private fun bindChild(holder: ViewHolder, item: FunctionItem) {
        holder.name?.text = item.name
        holder.checkBox?.isChecked = item.isChecked
        holder.name?.setPadding(dp(holder, 20), 0, 0, 0)
        holder.checkBox?.setOnCheckedChangeListener { _, isChecked ->
            item.isChecked = isChecked
            onCheck?.invoke(item.name, isChecked)
            onToggle?.invoke(item.name, isChecked)
        }
        holder.itemView.setOnClickListener {
            holder.checkBox?.isChecked = !(holder.checkBox?.isChecked ?: false)
        }
    }

    /** 按钮开关 */
    private fun bindSwitch(holder: ViewHolder, item: FunctionItem) {
        holder.switchName?.text = item.name
        holder.switchButton?.isChecked = item.isChecked
        holder.switchButton?.setOnCheckedChangeListener { _, isChecked ->
            item.isChecked = isChecked
            onToggle?.invoke(item.name, isChecked)
        }
        holder.itemView.setOnClickListener {
            val new = !(holder.switchButton?.isChecked ?: false)
            holder.switchButton?.isChecked = new
        }
    }

    /** 纯文字行（无勾选控件，用于公告/卡密/设备信息展示） */
    private fun bindText(holder: ViewHolder, item: FunctionItem) {
        holder.name?.text = item.name
    }

    /** 整行主按钮（注入等） */
    private fun bindButton(holder: ViewHolder, item: FunctionItem) {
        holder.action?.text = item.name
        holder.itemView.setOnClickListener { onButtonClick?.invoke(item.name) }
    }

    /** 分节标题 */
    private fun bindHeader(holder: ViewHolder, item: FunctionItem) {
        holder.name?.text = item.name
    }

    /** 单选项（同 group 内互斥，选中态由调用方维护） */
    private fun bindRadio(holder: ViewHolder, item: FunctionItem) {
        holder.name?.text = item.name
        val sub = holder.sub
        if (sub != null) {
            if (item.subtitle.isBlank()) { sub.visibility = View.GONE }
            else { sub.visibility = View.VISIBLE; sub.text = item.subtitle }
        }
        holder.radio?.isChecked = item.isChecked
        holder.itemView.setOnClickListener {
            onRadio?.invoke(item.group ?: "", item.name)
        }
    }

    /** 滑杆行：可调数值（大小等），实时回调 */
    private fun bindSlider(holder: ViewHolder, item: FunctionItem) {
        holder.name?.text = "${item.name}: ${item.sliderValue}/${item.sliderMax}"
        val sb = holder.slider ?: return
        sb.max = item.sliderMax
        sb.progress = item.sliderValue.coerceIn(0, item.sliderMax)
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    item.sliderValue = progress
                    holder.name?.text = "${item.name}: $progress/${item.sliderMax}"
                    onSlider?.invoke(item.name, progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun dp(holder: ViewHolder, value: Int): Int {
        return (value * holder.itemView.resources.displayMetrics.density).toInt()
    }

    override fun getItemCount(): Int = items.size

    fun setItems(newItems: List<FunctionItem>) {
        allItems = newItems
        rebuildVisible()
    }

    /** 根据折叠状态重建显示列表：折叠组的子项在折叠时不显示，展开时才显示 */
    private fun rebuildVisible() {
        val visible = mutableListOf<FunctionItem>()
        var groupExpanded = false
        for (item in allItems) {
            when (item.type) {
                TYPE_GROUP -> {
                    groupExpanded = item.expanded
                    visible.add(item)
                }
                TYPE_GROUP_CHILD -> if (groupExpanded) visible.add(item)
                else -> visible.add(item)
            }
        }
        items.clear()
        items.addAll(visible)
        notifyDataSetChanged()
    }

    /** 按关键字过滤（空串恢复全部；折叠子项跟随所属组显示） */
    fun filter(query: String) {
        val q = query.trim()
        items.clear()
        if (q.isEmpty()) {
            items.addAll(allItems)
        } else {
            var inGroup = false
            var groupExpanded = false
            for (item in allItems) {
                when (item.type) {
                    TYPE_GROUP -> {
                        inGroup = true
                        groupExpanded = item.name.contains(q, ignoreCase = true)
                        if (groupExpanded) items.add(item)
                    }
                    TYPE_GROUP_CHILD -> {
                        if (inGroup) {
                            if (groupExpanded || item.name.contains(q, ignoreCase = true)) {
                                items.add(item)
                            }
                        } else if (item.name.contains(q, ignoreCase = true)) {
                            items.add(item)
                        }
                    }
                    else -> {
                        if (item.name.contains(q, ignoreCase = true)) items.add(item)
                    }
                }
            }
        }
        notifyDataSetChanged()
    }

    /** 当前显示的数据 */
    fun getItems(): List<FunctionItem> = items
}
