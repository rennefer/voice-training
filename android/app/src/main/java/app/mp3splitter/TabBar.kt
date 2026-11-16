package app.mp3splitter

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** One frosted bar of choices; the chosen one is polished brass (like FineVolume's step picker). */
class TabBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {

    private val dp = resources.displayMetrics.density
    private val tabs = mutableListOf<TextView>()
    var selected = 0
        private set
    var onSelect: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        background = Brass.panel(14 * dp)
        val p = (4 * dp).toInt()
        setPadding(p, p, p, p)
    }

    fun setOptions(labels: List<String>, weights: List<Float> = labels.map { 1f }) {
        removeAllViews(); tabs.clear()
        labels.forEachIndexed { i, label ->
            val t = TextView(context).apply {
                text = label
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                isSingleLine = true
                minHeight = (40 * dp).toInt()
                isClickable = true
                isFocusable = true
                setOnClickListener { if (isEnabled) select(i, notify = true) }
            }
            tabs += t
            addView(t, LayoutParams(0, LayoutParams.WRAP_CONTENT, weights[i]))
        }
        select(selected.coerceIn(0, labels.size - 1), notify = false)
    }

    fun setLabel(index: Int, label: String) { tabs.getOrNull(index)?.text = label }

    fun select(index: Int, notify: Boolean = false) {
        selected = index
        tabs.forEachIndexed { i, t ->
            val on = i == index
            t.setTextColor(if (on) Brass.ON_BRASS else Brass.TEXT_SOFT)
            t.background = if (on) Brass.metal(10 * dp) else null
        }
        if (notify) onSelect?.invoke(index)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        tabs.forEach { it.isEnabled = enabled }
        alpha = if (enabled) 1f else 0.5f
    }
}
