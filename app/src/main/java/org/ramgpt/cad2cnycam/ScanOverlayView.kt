package org.ramgpt.cad2cnycam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    var showGuide: Boolean = false
        set(value) { field = value; invalidate() }
    private val shade = Paint().apply { color = 0x66000000 }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(53, 208, 127)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showGuide) return
        val boxWidth = width * 0.82f
        val boxHeight = height * 0.25f
        val box = RectF((width - boxWidth) / 2, (height - boxHeight) / 2,
            (width + boxWidth) / 2, (height + boxHeight) / 2)
        canvas.drawRect(0f, 0f, width.toFloat(), box.top, shade)
        canvas.drawRect(0f, box.bottom, width.toFloat(), height.toFloat(), shade)
        canvas.drawRect(0f, box.top, box.left, box.bottom, shade)
        canvas.drawRect(box.right, box.top, width.toFloat(), box.bottom, shade)
        canvas.drawRoundRect(box, 18f, 18f, border)
    }
}
