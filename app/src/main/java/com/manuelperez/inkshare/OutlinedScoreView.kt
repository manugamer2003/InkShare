package com.manuelperez.inkshare

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.appcompat.widget.AppCompatTextView

/** Centered, single-line score with a dark outline on any player background. */
class OutlinedScoreView(context: Context) : AppCompatTextView(context) {
    private val outline = Paint()

    override fun onDraw(canvas: Canvas) {
        outline.set(paint)
        outline.color = Color.BLACK
        outline.style = Paint.Style.STROKE
        outline.strokeWidth = 3f * resources.displayMetrics.density
        outline.strokeJoin = Paint.Join.ROUND
        outline.textAlign = Paint.Align.CENTER
        outline.clearShadowLayer()
        canvas.drawText(text.toString(), width / 2f, baseline.toFloat(), outline)
        super.onDraw(canvas)
    }
}
