package cn.rmshadows.textsend

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.Filter

/**
 * ExposedDropdownMenu 用：不按当前文字过滤，打开始终列出全部选项。
 * 默认 ArrayAdapter 会按「发给所有」之类过滤，结果只剩一项。
 */
class FixedListAdapter(
    context: Context,
    resource: Int,
    private val items: List<String>
) : ArrayAdapter<String>(context, resource, items) {

    private val all = ArrayList(items)

    override fun getCount(): Int = all.size

    override fun getItem(position: Int): String? = all.getOrNull(position)

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                return FilterResults().apply {
                    values = all
                    count = all.size
                }
            }

            @Suppress("UNCHECKED_CAST")
            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                notifyDataSetChanged()
            }
        }
    }
}
