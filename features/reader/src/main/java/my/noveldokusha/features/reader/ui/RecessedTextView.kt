package my.noveldokusha.features.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.roundToInt

/**
 * Surface-based engraving: alter the brightness of the *existing background*
 * only underneath the real glyphs. No solid lettering at 100% ink transparency.
 * Shadows remain on the upper/left INNER walls; light catches lower/right walls.
 *
 * The original TextView provides hit testing, font layout, accessibility and
 * selection. During selection/TTS highlighting it uses its normal rendering.
 */
class RecessedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var isEngraved: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var engravingIntensity: Float = 50f
        set(value) { val v = value.coerceIn(0f, 100f); if (field != v) { field = v; invalidate() } }
    // 100% transparent = pure texture; 0% = original, fully opaque font color.
    var engravingTransparency: Float = 100f
        set(value) { val v = value.coerceIn(0f, 100f); if (field != v) { field = v; invalidate() } }

    private val normalMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        color = Color.WHITE
    }
    private val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val innerColor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val bounds = RectF()
    private var cachedLayout: Layout? = null
    private var cachedText: String? = null
    private var cachedSize: Float = -1f
    private var cachedLetterSpacing: Float = -1f
    private var cachedTypeface: android.graphics.Typeface? = null
    private var glyphMask: Bitmap? = null

    private fun maskFor(textLayout: Layout, content: String): Bitmap? {
        val w = textLayout.width + 4
        val h = textLayout.height + 4
        if (w <= 4 || h <= 4 || w > 2048 || h > 4096) return null
        val old = glyphMask
        if (old != null && cachedLayout === textLayout && cachedText == content &&
            cachedSize == textSize && cachedLetterSpacing == letterSpacing &&
            cachedTypeface === typeface && old.width == w && old.height == h) return old
        old?.recycle()
        glyphMask = null
        return try {
            val image = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val maskCanvas = Canvas(image)
            maskCanvas.translate(2f, 2f)
            val oldInk = paint.color
            val oldAlpha = paint.alpha
            try {
                paint.color = Color.WHITE
                paint.alpha = 255
                textLayout.draw(maskCanvas)
            } finally {
                paint.color = oldInk
                paint.alpha = oldAlpha
            }
            cachedLayout = textLayout
            cachedText = content
            cachedSize = textSize
            cachedLetterSpacing = letterSpacing
            cachedTypeface = typeface
            glyphMask = image
            image
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val content = text
        val textLayout = layout
        // Selection handles, special spans and TTS/word highlights take priority.
        val specialSpans = content is Spanned && (
            content.getSpans(0, content.length, ReplacementSpan::class.java).isNotEmpty() ||
            content.getSpans(0, content.length, BackgroundColorSpan::class.java).isNotEmpty() ||
            content.getSpans(0, content.length, ForegroundColorSpan::class.java).isNotEmpty()
        )
        if (!isEngraved || content.isEmpty() || textLayout == null || specialSpans ||
            (selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd)
        ) {
            super.onDraw(canvas)
            return
        }
        val mask = maskFor(textLayout, content.toString())
        if (mask == null) {
            super.onDraw(canvas)
            return
        }

        val x = compoundPaddingLeft.toFloat() - 2f
        val y = extendedPaddingTop.toFloat() - 2f
        bounds.set(x, y, x + mask.width, y + mask.height)
        // super.onDraw still handles TextView styling/semantics. Remove only its
        // glyph pixels inside a layer, without clearing the backdrop underneath.
        val original = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        canvas.drawBitmap(mask, x, y, eraser)
        canvas.restoreToCount(original)

        val scale = engravingIntensity / 100f
        val depth = 0.14f + 0.24f * scale
        val wallShadow = 0.16f + 0.35f * scale
        val wallLight = 0.10f + 0.36f * scale
        // Black alpha multiplies the original background brightness, leaving its
        // surface grain visible rather than replacing it with an opaque font fill.
        normalMaskPaint.color = Color.BLACK
        normalMaskPaint.alpha = (255f * depth).roundToInt()
        canvas.drawBitmap(mask, x, y, normalMaskPaint)

        val edge = if (engravingIntensity >= 75f) 2f else 1f
        // Equivalent mask polarity to the approved Python B/reference test.
        // upper-left wall is dark, lower-right wall catches light.
        drawInnerRim(canvas, mask, x, y, edge, edge, Color.BLACK,
            (255f * wallShadow).roundToInt())
        drawInnerRim(canvas, mask, x, y, -edge, -edge, Color.WHITE,
            (255f * wallLight).roundToInt())

        val inkOpacity = (1f - engravingTransparency / 100f).coerceIn(0f, 1f)
        if (inkOpacity > 0f) {
            val ink = currentTextColor
            normalMaskPaint.color = ink
            normalMaskPaint.alpha = (Color.alpha(ink) * inkOpacity).roundToInt()
            canvas.drawBitmap(mask, x, y, normalMaskPaint)
        }
    }

    private fun drawInnerRim(canvas: Canvas, mask: Bitmap, x: Float, y: Float,
                             dx: Float, dy: Float, color: Int, strength: Int) {
        val saved = canvas.saveLayer(bounds, null)
        normalMaskPaint.color = Color.WHITE
        normalMaskPaint.alpha = 255
        canvas.drawBitmap(mask, x, y, normalMaskPaint)
        canvas.drawBitmap(mask, x + dx, y + dy, eraser)
        innerColor.color = color
        innerColor.alpha = strength.coerceIn(0, 255)
        canvas.drawRect(bounds, innerColor)
        canvas.restoreToCount(saved)
    }

    override fun onDetachedFromWindow() {
        glyphMask?.recycle()
        glyphMask = null
        cachedLayout = null
        super.onDetachedFromWindow()
    }
}
