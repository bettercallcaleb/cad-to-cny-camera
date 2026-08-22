package org.ramgpt.cad2cnycam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.math.BigDecimal

enum class PriceDisplayMode { APPEND, REPLACE }
enum class OverlayStabilizationState { TRACKING, STABLE }

enum class DebugOverlayStatus { RAW, ACCEPTED, PENDING, REJECTED }

data class DebugOverlayItem(
    val mappedBoundingBox: RectF,
    val label: String,
    val status: DebugOverlayStatus
)

data class PriceOverlayItem(
    val trackId: Long,
    val cadPrice: BigDecimal,
    val cnyPrice: BigDecimal,
    val sourceBoundingBox: Rect,
    val previewBoundingBox: RectF,
    val confidenceScore: Float,
    val stabilizationState: OverlayStabilizationState,
    val isLightBackground: Boolean,
    val missedFrames: Int
)

class PriceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sourceBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val candidateBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val debugTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private var items: List<PriceOverlayItem> = emptyList()
    private var debugCandidates: List<DebugOverlayItem> = emptyList()
    var displayMode: PriceDisplayMode = PriceDisplayMode.REPLACE
        set(value) { field = value; invalidate() }
    var showDebugBounds: Boolean = false
        set(value) { field = BuildConfig.DEBUG && value; invalidate() }

    fun showItems(newItems: List<PriceOverlayItem>) {
        items = newItems.toList()
        invalidate()
    }

    fun clearPrice() = showItems(emptyList())

    fun showDebugCandidateBoxes(boxes: List<DebugOverlayItem>) {
        debugCandidates = boxes.toList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showDebugBounds) debugCandidates.forEach { debug ->
            val color = when (debug.status) {
                DebugOverlayStatus.RAW -> 0xFF2979FF.toInt()
                DebugOverlayStatus.ACCEPTED -> 0xFF00C853.toInt()
                DebugOverlayStatus.PENDING -> 0xFFFFB300.toInt()
                DebugOverlayStatus.REJECTED -> 0xFF9E9E9E.toInt()
            }
            candidateBoxPaint.color = color
            debugTextPaint.color = color
            canvas.drawRect(debug.mappedBoundingBox, candidateBoxPaint)
            canvas.drawText(debug.label, debug.mappedBoundingBox.left,
                (debug.mappedBoundingBox.top - 3f * density).coerceAtLeast(debugTextPaint.textSize),
                debugTextPaint)
        }
        val occupied = mutableListOf<RectF>()
        items.sortedByDescending { it.confidenceScore }.forEach { item ->
            drawItem(canvas, item, occupied)?.let(occupied::add)
        }
    }

    private fun drawItem(canvas: Canvas, item: PriceOverlayItem, occupied: List<RectF>): RectF? {
        val label = "¥" + item.cnyPrice.toPlainString()
        val source = item.previewBoundingBox
        textPaint.color = if (item.isLightBackground) 0xFF181818.toInt() else Color.WHITE
        if (showDebugBounds) canvas.drawRect(source, sourceBoxPaint)

        // Match the physical glyph height. The clamp is only a safety bound; unlike
        // the old 24dp minimum, it cannot inflate ordinary shelf-label text.
        textPaint.textSize = (source.height() * 0.88f).coerceIn(12f * density, 40f * density)
        val paddingX = 2.5f * density
        val paddingY = 1.5f * density

        val panel = if (displayMode == PriceDisplayMode.REPLACE) {
            val inflated = RectF(source).apply { inset(-2f * density, -1.5f * density) }
            val requiredWidth = textPaint.measureText(label) + paddingX * 2
            if (requiredWidth > inflated.width()) {
                val horizontalGrowth = (requiredWidth - inflated.width()) / 2f
                inflated.inset(-horizontalGrowth, 0f)
            }
            keepInside(inflated)
        } else {
            val font = textPaint.fontMetrics
            val textWidth = textPaint.measureText(label)
            val panelWidth = textWidth + paddingX * 2
            val panelHeight = font.descent - font.ascent + paddingY * 2
            val gap = 4f * density
            val right = RectF(source.right + gap, source.centerY() - panelHeight / 2f,
                source.right + gap + panelWidth, source.centerY() + panelHeight / 2f)
            if (right.right <= width - gap) keepInside(right)
            else keepInside(RectF(source.left, source.bottom + gap,
                source.left + panelWidth, source.bottom + gap + panelHeight))
        }

        if (occupied.any { RectF.intersects(it, panel) }) return null

        backgroundPaint.color = if (item.isLightBackground) 0xECFAFAF7.toInt()
            else 0xEC181818.toInt()
        val visibility = (1f - item.missedFrames / 6f).coerceIn(0.2f, 1f)
        backgroundPaint.alpha = (236 * visibility).toInt()
        textPaint.alpha = (255 * visibility).toInt()
        canvas.drawRoundRect(panel, 2f * density, 2f * density, backgroundPaint)
        val textWidth = textPaint.measureText(label)
        val font = textPaint.fontMetrics
        val baseline = panel.centerY() - (font.ascent + font.descent) / 2f
        canvas.drawText(label, panel.centerX() - textWidth / 2f, baseline, textPaint)
        return RectF(panel)
    }

    private fun keepInside(rect: RectF): RectF {
        if (rect.left < 0f) rect.offset(-rect.left, 0f)
        if (rect.right > width) rect.offset(width - rect.right, 0f)
        if (rect.top < 0f) rect.offset(0f, -rect.top)
        if (rect.bottom > height) rect.offset(0f, height - rect.bottom)
        return rect
    }
}
