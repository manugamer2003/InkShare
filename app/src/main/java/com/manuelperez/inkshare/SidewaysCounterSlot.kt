package com.manuelperez.inkshare

import android.content.Context
import android.widget.FrameLayout

/** Measures the player panel sideways so its rotated bounds fit inside its seat. */
class SidewaysCounterSlot(context: Context, private val angle: Float) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (childCount == 0) return
        getChildAt(0).measure(
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount == 0) return
        val panel = getChildAt(0)
        val x = (width - panel.measuredWidth) / 2
        val y = (height - panel.measuredHeight) / 2
        panel.layout(x, y, x + panel.measuredWidth, y + panel.measuredHeight)
        panel.pivotX = panel.measuredWidth / 2f
        panel.pivotY = panel.measuredHeight / 2f
        panel.rotation = angle
    }
}
