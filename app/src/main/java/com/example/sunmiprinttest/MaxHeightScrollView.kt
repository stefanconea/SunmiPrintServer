package com.example.sunmiprinttest

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * A ScrollView that wraps to its content's natural height up to [maxHeightDp],
 * then caps there and lets the user scroll the rest -- used for the live
 * preview box so it grows with short content instead of always reserving a
 * fixed, mostly-empty area.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    var maxHeightDp: Int = 250

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeightPx = (maxHeightDp * resources.displayMetrics.density).toInt()
        val cappedSpec = MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, cappedSpec)
    }
}
