package eu.kanade.tachiyomi.ui.library.category

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import com.mikepenz.fastadapter.listeners.OnBindViewHolderListenerImpl
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.util.view.marginBottom
import eu.kanade.tachiyomi.util.view.marginTop

class CategoryRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    val manager = LinearLayoutManager(context)
    private val fastAdapter: FastAdapter<CategoryItem>
    var onCategoryClicked: (Int) -> Unit = { _ -> }
    private val itemAdapter = ItemAdapter<CategoryItem>()
    var selectedCategory: Int = 0

    init {
        fastAdapter = FastAdapter.with(itemAdapter)
        layoutManager = manager
        adapter = fastAdapter
    }

    fun setCategories(items: List<Category>) {
        itemAdapter.set(items.map(::CategoryItem))
        fastAdapter.onBindViewHolderListener = (object : OnBindViewHolderListenerImpl<CategoryItem>() {
            override fun onBindViewHolder(
                viewHolder: ViewHolder,
                position: Int,
                payloads: List<Any>
            ) {
                super.onBindViewHolder(viewHolder, position, payloads)
                (viewHolder as? CategoryItem.ViewHolder)?.categoryTitle?.isSelected =
                    selectedCategory == position
            }
        })
        fastAdapter.onClickListener = { _, _, item, _ ->
            onCategoryClicked(item.category.order)
            true
        }
    }

    fun setCategories(selected: Int) {
        selectedCategory = selected
        for (i in 0..manager.itemCount) {
            (findViewHolderForAdapterPosition(i) as? CategoryItem.ViewHolder)?.categoryTitle?.isSelected =
                selectedCategory == i
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val recyclerView = (parent.parent as ViewGroup).findViewById<RecyclerView>(R.id.recycler)
        val top = recyclerView.marginTop
        val bottom = recyclerView.marginBottom
        val parent = recyclerView.measuredHeight - top - bottom
        val heightS = if (parent > 0)
            MeasureSpec.makeMeasureSpec(parent, MeasureSpec.AT_MOST)
        else heightSpec
        super.onMeasure(widthSpec, heightS)
    }
}
