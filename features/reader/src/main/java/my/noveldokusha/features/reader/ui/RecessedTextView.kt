package my.noveldokusha.features.reader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Optional inward-facing glyph bevel. Unlike a regular text shadow, it never
 * paints outside the glyph alpha mask and therefore looks engraved, not raised.
 * All original TextView selection, layouts, spans and accessibility are retained.
 */
class RecessedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var isEngraved: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val subtractPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val textBounds = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isEngraved) return
        val textLayout = layout ?: return
        if (textLayout.width <= 0 || textLayout.height <= 0) return
        // Fallback for exceptionally long paragraphs to avoid oversized layers.
        if (textLayout.width > 2048 || textLayout.height > 4096) return
        val source = text
        if (source.isEmpty()) return
        // Word-highlights (notably TTS/manual) must stay intact.
        if (source is Spanned &&
            (source.getSpans(0, source.length, ReplacementSpan::class.java).isNotEmpty() ||
             source.getSpans(0, source.length, BackgroundColorSpan::class.java).isNotEmpty())
        ) return

        val checkpoint = canvas.save()
        canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())
        textBounds.set(-2f, -2f, textLayout.width + 2f, textLayout.height + 2f)
        val pixel = resources.displayMetrics.density.coerceAtMost(2f)
        // Light from upper-left: dark upper/left INNER walls, lit lower/right walls.
        drawInnerRim(canvas, textLayout, pixel, pixel, Color.argb(165, 0, 4, 10))
        drawInnerRim(canvas, textLayout, -pixel, -pixel, Color.argb(120, 194, 208, 218))
        canvas.restoreToCount(checkpoint)
    }

    private fun drawInnerRim(
        canvas: Canvas,
        textLayout: Layout,
        dx: Float,
        dy: Float,
        rimColor: Int
    ) {
        // The first mask is the glyph itself. Removing a shifted mask leaves
        // only the narrow INTERIOR edge. SRC_IN tints that rim, not its exterior.
        val result = canvas.saveLayer(textBounds, null)
        textLayout.draw(canvas)
        val subtract = canvas.saveLayer(textBounds, subtractPaint)
        canvas.translate(dx, dy)
        textLayout.draw(canvas)
        canvas.translate(-dx, -dy)
        canvas.restoreToCount(subtract)
        tintPaint.color = rimColor
        canvas.drawRect(textBounds, tintPaint)
        canvas.restoreToCount(result)
    }
}
