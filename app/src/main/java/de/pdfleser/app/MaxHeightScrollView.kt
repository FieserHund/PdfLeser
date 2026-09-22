package de.pdfleser.app

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/** ScrollView, die höchstens [maxHeight] Pixel hoch wird (für die Übersetzungskarte). */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : NestedScrollView(context, attrs) {

    var maxHeight = 0
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hs = if (maxHeight > 0) MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST) else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, hs)
    }
}
