package my.noveldokusha.features.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pixel-accurate variant of NoveLA_Dokusuna_Oyma_Renksiz_Python.py.
 *
 * On imported wallpaper the glyph changes local substrate brightness:
 *    factor = 1 - depth * alpha + light * innerRightBottom - dark * innerLeftTop
 *    result = wallpaperPixel * clamp(factor, 0.10, 3.40).
 *
 * The mask/factor bitmap is cached per paragraph. GPU shaders crop the SAME
 * wallpaper with ContentScale.Crop at the current scrolling screen coordinates.
 * This avoids white/black "sticker" overlays and per-frame wallpaper readback.
 * On gradients/solid backgrounds we retain a conservative fallback until they
 * can be sampled identically. Text remains selectable and accessible.
 */
class RecessedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    var isEngraved: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    var engravingIntensity: Float = 50f
        set(value) {
            val v = value.coerceIn(0f, 100f)
            if (field != v) { field = v; clearFactor(); invalidate() }
        }

    // 100 = no colored lettering layer; 0 = original selected ink color.
    var engravingTransparency: Float = 100f
        set(value) {
            val v = value.coerceIn(0f, 100f)
            if (field != v) { field = v; invalidate() }
        }

    // Shared, asynchronously decoded copy of the image displayed by Compose.
    var wallpaperBitmap: Bitmap? = null
        set(value) { if (field !== value) { field = value; invalidate() } }

    private val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val maskedInk = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // Factors are encoded in 8 bits as (factor/3.4). Undo the encoding.
        colorFilter = ColorMatrixColorFilter(floatArrayOf(
            3.4f, 0f, 0f, 0f, 0f,
            0f, 3.4f, 0f, 0f, 0f,
            0f, 0f, 3.4f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
    }
    private val rect = RectF()
    private var lastLayout: Layout? = null
    private var lastString: String? = null
    private var lastTextSize = -1f
    private var lastLetterSpacing = -1f
    private var lastTypeface: android.graphics.Typeface? = null
    private var glyphMask: Bitmap? = null
    private var factorMask: Bitmap? = null

    private fun clearFactor() {
        factorMask?.recycle()
        factorMask = null
    }

    private fun maskFor(textLayout: Layout, content: String): Bitmap? {
        val w = textLayout.width + 4
        val h = textLayout.height + 4
        if (w <= 4 || h <= 4 || w > 2048 || h > 4096) return null
        val cached = glyphMask
        if (cached != null && !cached.isRecycled && lastLayout === textLayout &&
            lastString == content && lastTextSize == textSize &&
            lastLetterSpacing == letterSpacing && lastTypeface === typeface &&
            cached.width == w && cached.height == h) return cached

        clearFactor()
        cached?.recycle()
        glyphMask = null
        return try {
            // ARGB_8888 makes getPixels() alpha deterministic for the factor map.
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val maskCanvas = Canvas(bmp)
            maskCanvas.translate(2f, 2f)
            val savedColor = paint.color
            val savedAlpha = paint.alpha
            try {
                paint.color = Color.WHITE
                paint.alpha = 255
                textLayout.draw(maskCanvas)
            } finally {
                paint.color = savedColor
                paint.alpha = savedAlpha
            }
            lastLayout = textLayout
            lastString = content
            lastTextSize = textSize
            lastLetterSpacing = letterSpacing
            lastTypeface = typeface
            glyphMask = bmp
            bmp
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun factorFor(mask: Bitmap): Bitmap? {
        val cached = factorMask
        if (cached != null && !cached.isRecycled &&
            cached.width == mask.width && cached.height == mask.height) return cached

        val w = mask.width
        val h = mask.height
        return try {
            val pixels = IntArray(w * h)
            mask.getPixels(pixels, 0, w, 0, 0, w, h)
            val output = IntArray(w * h)
            val t = engravingIntensity / 100f
            // Exactly the Python soft / balanced / deep calibration at 0/50/100.
            fun lerp(soft: Float, balanced: Float, deep: Float): Float =
                if (t <= 0.5f) soft + (balanced - soft) * (t * 2f)
                else balanced + (deep - balanced) * ((t - 0.5f) * 2f)
            val depth = lerp(0.14f, 0.25f, 0.34f)
            val light = lerp(0.88f, 1.45f, 2.08f)
            val dark = lerp(0.42f, 0.62f, 0.78f)
            val shift = if (t >= 0.75f) 2 else 1

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    val alpha = (pixels[i] ushr 24) / 255f
                    if (alpha <= 0f) continue
                    // roll_zero(alpha, -shift, -shift) = source at x+shift,y+shift
                    val rightBottom = if (x + shift < w && y + shift < h)
                        (pixels[(y + shift) * w + x + shift] ushr 24) / 255f else 0f
                    val leftTop = if (x >= shift && y >= shift)
                        (pixels[(y - shift) * w + x - shift] ushr 24) / 255f else 0f
                    val rimRB = (alpha - rightBottom).coerceAtLeast(0f)
                    val rimLT = (alpha - leftTop).coerceAtLeast(0f)
                    val factor = (1f - depth * alpha + light * rimRB - dark * rimLT)
                        .coerceIn(0.10f, 3.40f)
                    val encoded = ((factor / 3.40f) * 255f).roundToInt().coerceIn(0, 255)
                    output[i] = Color.argb(255, encoded, encoded, encoded)
                }
            }
            Bitmap.createBitmap(output, w, h, Bitmap.Config.ARGB_8888).also {
                factorMask = it
            }
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val content = text
        val textLayout = layout
        val specialSpans = content is Spanned && (
            content.getSpans(0, content.length, ReplacementSpan::class.java).isNotEmpty() ||
            content.getSpans(0, content.length, BackgroundColorSpan::class.java).isNotEmpty() ||
            content.getSpans(0, content.length, ForegroundColorSpan::class.java).isNotEmpty()
        )
        if (!isEngraved || content.isEmpty() || textLayout == null || specialSpans ||
            (selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd)) {
            super.onDraw(canvas)
            return
        }
        val mask = maskFor(textLayout, content.toString())
        if (mask == null) { super.onDraw(canvas); return }

        val x = compoundPaddingLeft.toFloat() - 2f
        val y = extendedPaddingTop.toFloat() - 2f
        rect.set(x, y, x + mask.width, y + mask.height)

        // Remove the default flat text; leave the parent wallpaper untouched.
        val cut = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        canvas.drawBitmap(mask, x, y, eraser)
        canvas.restoreToCount(cut)

        val backdrop = wallpaperBitmap
        val factor = if (backdrop != null && !backdrop.isRecycled) factorFor(mask) else null
        val correct = backdrop != null && factor != null &&
            drawWallpaperFactor(canvas, backdrop, factor, x, y)
        if (!correct) drawGenericSurface(canvas, mask, x, y)

        val inkOpacity = (1f - engravingTransparency / 100f).coerceIn(0f, 1f)
        if (inkOpacity > 0f) {
            val ink = currentTextColor
            maskedInk.shader = null
            maskedInk.color = ink
            maskedInk.alpha = (Color.alpha(ink) * inkOpacity).roundToInt()
            canvas.drawBitmap(mask, x, y, maskedInk)
        }
    }

    private fun drawWallpaperFactor(
        canvas: Canvas, wallpaper: Bitmap, factor: Bitmap, x: Float, y: Float
    ): Boolean {
        val contentView = rootView.findViewById<View>(android.R.id.content) ?: return false
        if (contentView.width <= 0 || contentView.height <= 0) return false
        val viewPos = IntArray(2)
        val contentPos = IntArray(2)
        getLocationOnScreen(viewPos)
        contentView.getLocationOnScreen(contentPos)
        val scale = max(contentView.width.toFloat() / wallpaper.width,
                        contentView.height.toFloat() / wallpaper.height)
        val originX = (contentView.width - wallpaper.width * scale) / 2f -
            (viewPos[0] - contentPos[0])
        val originY = (contentView.height - wallpaper.height * scale) / 2f -
            (viewPos[1] - contentPos[1])

        val wallpaperShader = BitmapShader(wallpaper, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        wallpaperShader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate(originX, originY)
        })
        val factorShader = BitmapShader(factor, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        factorShader.setLocalMatrix(Matrix().apply { setTranslate(x, y) })
        surfacePaint.shader = ComposeShader(wallpaperShader, factorShader, PorterDuff.Mode.MULTIPLY)
        try {
            canvas.drawRect(rect, surfacePaint)
        } finally {
            surfacePaint.shader = null
        }
        return true
    }

    private fun drawGenericSurface(canvas: Canvas, mask: Bitmap, x: Float, y: Float) {
        // Solid/gradient themes cannot be pixel-sampled as a bitmap; conservative
        // background multiplication until those themes receive a shared shader.
        maskedInk.color = Color.BLACK
        maskedInk.alpha = (255f * (0.14f + 0.20f * engravingIntensity / 100f))
            .roundToInt()
        canvas.drawBitmap(mask, x, y, maskedInk)
        val offset = if (engravingIntensity >= 75f) 2f else 1f
        drawInnerRim(canvas, mask, x, y, offset, offset, Color.BLACK, 50)
        drawInnerRim(canvas, mask, x, y, -offset, -offset, Color.WHITE, 30)
    }

    private fun drawInnerRim(canvas: Canvas, mask: Bitmap, x: Float, y: Float,
                             dx: Float, dy: Float, color: Int, strength: Int) {
        val save = canvas.saveLayer(rect, null)
        maskedInk.color = Color.WHITE
        maskedInk.alpha = 255
        canvas.drawBitmap(mask, x, y, maskedInk)
        canvas.drawBitmap(mask, x + dx, y + dy, eraser)
        rimPaint.color = color
        rimPaint.alpha = strength.coerceIn(0, 255)
        canvas.drawRect(rect, rimPaint)
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        clearFactor()
        glyphMask?.recycle()
        glyphMask = null
        lastLayout = null
        super.onDetachedFromWindow()
    }
}
