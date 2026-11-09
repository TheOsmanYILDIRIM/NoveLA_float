package my.noveldokusha.features.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.ColorMatrixColorFilter
import android.graphics.ComposeShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
import android.util.AttributeSet
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

    // A cached transparent engraved texture for each visible line strip.
    // Old persisted "native" values are mapped to the new baked engine.
    var engravingEngine: String = "baked"
        set(value) {
            val mode = if (value == "bitmap") "bitmap" else "baked"
            if (field != mode) {
                field = mode
                clearTiles()
                invalidate()
            }
        }

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
        set(value) {
            if (field !== value) {
                field = value
                cachedWallpaperShader = null
                tiles.values.forEach { it.compositeShader = null }
                invalidate()
            }
        }

    var wallpaperBoundsOnScreen: Rect? = null
        set(value) {
            if (field != value) { field = value?.let { Rect(it) }; invalidate() }
        }

    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val bakedPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskedInk = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Paint.color does NOT tint ARGB_8888 bitmap pixels; SRC_IN color filter does.
    private val tintedInk = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var lastInkColor = 0
    private var lastTintFilter: PorterDuffColorFilter? = null
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val clipPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
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
    private var lastContentRef: CharSequence? = null
    private var lastProtectedRanges: String? = null
    private var lastTextSize = -1f
    private var lastLetterSpacing = -1f
    private var lastTypeface: android.graphics.Typeface? = null
    // Only keep a handful of visible strips, never a full chapter-sized bitmap.
    private data class MaskTile(
        val top: Int, val mask: Bitmap, var factor: Bitmap? = null,
        var factorShader: BitmapShader? = null,
        var compositeShader: ComposeShader? = null,
        var bakedOverlay: Bitmap? = null,
    )
    private val tiles = LinkedHashMap<Int, MaskTile>(8, 0.75f, true)
    private val maxCachedTiles = 6
    private var cachedWallpaperShader: BitmapShader? = null
    private val shaderMatrix = Matrix()
    private val viewPosition = IntArray(2)

    private fun clearFactor() {
        tiles.values.forEach { tile ->
            tile.factor?.recycle()
            tile.factor = null
            tile.factorShader = null
            tile.compositeShader = null
            tile.bakedOverlay?.recycle()
            tile.bakedOverlay = null
        }
    }

    private fun clearTiles() {
        clearFactor()
        tiles.values.forEach { it.mask.recycle() }
        tiles.clear()
    }

    private fun protectedRanges(content: CharSequence): List<Pair<Int, Int>> {
        if (content !is Spanned) return emptyList()
        val spans = buildList<Any> {
            addAll(content.getSpans(0, content.length, ReplacementSpan::class.java))
            addAll(content.getSpans(0, content.length, BackgroundColorSpan::class.java))
            addAll(content.getSpans(0, content.length, ForegroundColorSpan::class.java))
        }
        return spans.mapNotNull { span ->
            val start = content.getSpanStart(span).coerceIn(0, content.length)
            val end = content.getSpanEnd(span).coerceIn(0, content.length)
            if (end > start) start to end else null
        }.distinct()
    }

    // Render a paragraph as independent groups of lines. Each bitmap remains
    // small even when a ListView row contains thousands of pixels of text.
    private fun maskFor(
        textLayout: Layout, content: CharSequence, excluded: List<Pair<Int, Int>>,
        startLine: Int, endLine: Int
    ): MaskTile? {
        val w = textLayout.width + 4
        val top = textLayout.getLineTop(startLine)
        val bottom = textLayout.getLineTop(endLine)
        val h = bottom - top + 4
        if (w <= 4 || h <= 4 || w > 2048 || h > 4096 ||
            w.toLong() * h > 1_600_000L) return null

        tiles[top]?.let { if (!it.mask.isRecycled) return it }
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val tile = MaskTile(top, bmp)
            val maskCanvas = Canvas(bmp)
            maskCanvas.translate(2f, 2f - top)
            // Tell Android Layout.draw which lines actually belong to this
            // tile; it can skip the rest of a long paragraph.
            maskCanvas.clipRect(-2f, top.toFloat() - 2f,
                textLayout.width.toFloat() + 2f, bottom.toFloat() + 2f)
            val savedColor = paint.color
            val savedAlpha = paint.alpha
            try {
                paint.color = Color.WHITE
                paint.alpha = 255
                textLayout.draw(maskCanvas)
                for ((from, to) in excluded) {
                    val first = maxOf(startLine, textLayout.getLineForOffset(from))
                    val last = minOf(endLine - 1, textLayout.getLineForOffset(to - 1))
                    for (line in first..last) {
                        val start = maxOf(from, textLayout.getLineStart(line))
                        val end = minOf(to, textLayout.getLineEnd(line))
                        if (end <= start) continue
                        val startX = textLayout.getPrimaryHorizontal(start)
                        val endX = textLayout.getPrimaryHorizontal(end)
                        maskCanvas.drawRect(
                            minOf(startX, endX) - 3f,
                            textLayout.getLineTop(line).toFloat() - 3f,
                            maxOf(startX, endX) + 3f,
                            textLayout.getLineBottom(line).toFloat() + 3f,
                            clearPaint
                        )
                    }
                }
            } finally {
                paint.color = savedColor
                paint.alpha = savedAlpha
            }
            // Keep every tile alive until the current onDraw finishes.
            tiles[top] = tile
            tile
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun factorFor(tile: MaskTile): Bitmap? {
        val mask = tile.mask
        val cached = tile.factor
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
                    if (alpha <= 0f) {
                        // Neutral multiplication outside the glyph edges,
                        // preventing bilinear samples from blending with black.
                        val neutral = (255f / 3.40f).roundToInt()
                        output[i] = Color.rgb(neutral, neutral, neutral)
                        continue
                    }
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
                    output[i] = Color.rgb(encoded, encoded, encoded)
                }
            }
            Bitmap.createBitmap(output, w, h, Bitmap.Config.ARGB_8888).also {
                tile.factor = it
            }
        } catch (_: OutOfMemoryError) {
            null
        }
    }


    /**
     * Bake a transparent, font-independent RGBA atlas strip only when the
     * paragraph, typeface, layout or engraving strength changes.
     *
     * Dark pixels reduce substrate brightness, white pixels add the opposing
     * bevel highlight. Unlike the precise Bitmap engine this is an
     * approximation of wallpaper multiplication, but it is independent of
     * scroll position and needs just one textured quad per strip onDraw.
     *
     * Keeping Android Layout.draw for the source mask preserves shaping,
     * ligatures, Unicode, custom fonts and measured text positions.
     */
    private fun bakedFor(tile: MaskTile): Bitmap? {
        tile.bakedOverlay?.let { if (!it.isRecycled) return it }
        val mask = tile.mask
        val w = mask.width
        val h = mask.height
        return try {
            val source = IntArray(w * h)
            mask.getPixels(source, 0, w, 0, 0, w, h)
            val pixels = IntArray(w * h)
            val t = (engravingIntensity / 100f).coerceIn(0f, 1f)
            fun lerp(soft: Float, balanced: Float, deep: Float): Float =
                if (t <= 0.5f) soft + (balanced - soft) * (t * 2f)
                else balanced + (deep - balanced) * ((t - 0.5f) * 2f)
            val depth = lerp(0.14f, 0.25f, 0.34f)
            val light = lerp(0.88f, 1.45f, 2.08f)
            val dark = lerp(0.42f, 0.62f, 0.78f)
            val shift = if (t >= 0.75f) 2 else 1

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val index = y * w + x
                    val alpha = (source[index] ushr 24) / 255f
                    if (alpha == 0f) continue // genuinely transparent outside glyphs
                    val rb = if (x + shift < w && y + shift < h)
                        (source[(y + shift) * w + x + shift] ushr 24) / 255f else 0f
                    val lt = if (x >= shift && y >= shift)
                        (source[(y - shift) * w + x - shift] ushr 24) / 255f else 0f
                    val factor = (1f - depth * alpha +
                        light * (alpha - rb).coerceAtLeast(0f) -
                        dark * (alpha - lt).coerceAtLeast(0f)).coerceIn(0.10f, 3.40f)
                    pixels[index] = if (factor < 1f) {
                        // Black SRC_OVER implements multiplication by factor exactly.
                        Color.argb(((1f - factor) * 255f).roundToInt(), 0, 0, 0)
                    } else {
                        // Brightening is an approximate SCREEN-like white overlay;
                        // the precise background sampling remains in Bitmap mode.
                        val opacity = ((factor - 1f) / factor).coerceIn(0f, 1f)
                        Color.argb((opacity * 255f).roundToInt(), 255, 255, 255)
                    }
                }
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).also {
                tile.bakedOverlay = it
            }
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val content = text
        val textLayout = layout
        if (!isEngraved || content.isEmpty() || textLayout == null ||
            (selectionStart >= 0 && selectionEnd >= 0 && selectionStart != selectionEnd)) {
            super.onDraw(canvas)
            return
        }

        val baked = engravingEngine != "bitmap"
        val x = compoundPaddingLeft.toFloat() - 2f
        val y = extendedPaddingTop.toFloat() - 2f
        val visible = canvas.clipBounds
        val visibleTop = (visible.top - y - 4f).coerceAtLeast(0f)
        val visibleBottom = (visible.bottom - y + 4).toInt().coerceAtMost(textLayout.height)
        val excluded = protectedRanges(content)
        // Revalidate once per drawing pass rather than once per line tile.
        // Long paragraphs previously generated the full content string for
        // every tile and repeated all span/range comparisons.
        val contentKey = if (lastContentRef === content) lastString ?: content.toString() else content.toString()
        val rangeKey = excluded.joinToString(";") { "${it.first}:${it.second}" }
        if (lastLayout !== textLayout || lastString != contentKey ||
            lastProtectedRanges != rangeKey || lastTextSize != textSize ||
            lastLetterSpacing != letterSpacing || lastTypeface !== typeface) {
            clearTiles()
            lastLayout = textLayout
            lastContentRef = content
            lastString = contentKey
            lastProtectedRanges = rangeKey
            lastTextSize = textSize
            lastLetterSpacing = letterSpacing
            lastTypeface = typeface
        }
        val visibleTiles = ArrayList<MaskTile>()
        val width = textLayout.width + 4
        if (width <= 4 || width > 2048) {
            super.onDraw(canvas)
            return
        }
        val maxHeight = minOf(512, 1_600_000 / width - 4)
        var line = 0
        while (line < textLayout.lineCount) {
            val start = line
            val top = textLayout.getLineTop(start)
            // Line-aligned strips avoid cutting a glyph at tile boundaries.
            while (line < textLayout.lineCount &&
                (line == start ||
                    textLayout.getLineTop(line + 1) - top <= maxHeight) &&
                line - start < 8) {
                line++
            }
            val bottom = textLayout.getLineTop(line)
            if (bottom < visibleTop || top > visibleBottom) continue
            val tile = maskFor(textLayout, content, excluded, start, line)
            if (tile != null) visibleTiles.add(tile)
        }
        if (visibleTiles.isEmpty()) {
            super.onDraw(canvas)
            return
        }

        // Unspanned text in baked mode is completely drawn from the atlas:
        // avoid super.onDraw + saveLayer + erasing every frame.
        // Protected spans/TTS and compound drawables retain TextView drawing.
        if (!baked || excluded.isNotEmpty() || compoundDrawables.any { it != null }) {
            val clip = canvas.clipBounds
            val save = canvas.saveLayer(
                clip.left.toFloat(), clip.top.toFloat(),
                clip.right.toFloat(), clip.bottom.toFloat(), null)
            super.onDraw(canvas)
            for (tile in visibleTiles) {
                canvas.drawBitmap(tile.mask, x, y + tile.top, eraser)
            }
            canvas.restoreToCount(save)
        }

        val backdrop = wallpaperBitmap
        val inkOpacity = (1f - engravingTransparency / 100f).coerceIn(0f, 1f)
        for (tile in visibleTiles) {
            val mask = tile.mask
            val tileY = y + tile.top
            rect.set(x, tileY, x + mask.width, tileY + mask.height)
            if (baked) {
                val overlay = bakedFor(tile)
                if (overlay != null) canvas.drawBitmap(overlay, x, tileY, bakedPaint)
                else drawGenericSurface(canvas, mask, x, tileY)
            } else {
                val factor = if (backdrop != null && !backdrop.isRecycled) factorFor(tile) else null
                val correct = backdrop != null && factor != null &&
                    drawWallpaperFactor(canvas, backdrop, tile, mask, x, tileY)
                if (!correct) drawGenericSurface(canvas, mask, x, tileY)
            }
            if (inkOpacity > 0f) {
                val ink = currentTextColor
                if (lastTintFilter == null || lastInkColor != ink) {
                    lastInkColor = ink
                    lastTintFilter = PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN)
                }
                tintedInk.colorFilter = lastTintFilter
                tintedInk.alpha = (255f * inkOpacity).roundToInt()
                canvas.drawBitmap(mask, x, tileY, tintedInk)
            }
        }
        // Evict only AFTER all visible bitmaps have been drawn. Otherwise
        // long visible paragraphs may reference recycled glyph masks.
        while (tiles.size > maxCachedTiles) {
            val iterator = tiles.entries.iterator()
            val entry = iterator.next()
            iterator.remove()
            entry.value.factor?.recycle()
            entry.value.bakedOverlay?.recycle()
            entry.value.mask.recycle()
        }
    }

    private fun drawWallpaperFactor(
        canvas: Canvas, wallpaper: Bitmap, tile: MaskTile, mask: Bitmap, x: Float, y: Float
    ): Boolean {
        // Match the Compose image's actual SCREEN rect, including insets.
        val bounds = wallpaperBoundsOnScreen ?: return false
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        getLocationOnScreen(viewPosition)
        val viewPos = viewPosition
        val scale = max(bounds.width().toFloat() / wallpaper.width,
                        bounds.height().toFloat() / wallpaper.height)
        val originX = bounds.left - viewPos[0] +
            (bounds.width() - wallpaper.width * scale) / 2f
        val originY = bounds.top - viewPos[1] +
            (bounds.height() - wallpaper.height * scale) / 2f

        val wallpaperShader = cachedWallpaperShader ?: BitmapShader(wallpaper,
            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { cachedWallpaperShader = it }
        shaderMatrix.setScale(scale, scale)
        shaderMatrix.postTranslate(originX, originY)
        wallpaperShader.setLocalMatrix(shaderMatrix)
        val factorShader = tile.factorShader ?: BitmapShader(tile.factor ?: return false,
            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).also { tile.factorShader = it }
        shaderMatrix.setTranslate(x, y)
        factorShader.setLocalMatrix(shaderMatrix)
        surfacePaint.shader = tile.compositeShader ?: ComposeShader(wallpaperShader,
            factorShader, PorterDuff.Mode.MULTIPLY).also { tile.compositeShader = it }
        // MULTIPLY alpha semantics differ by backend. Clip the shader result
        // explicitly to glyph coverage; never brighten a whole text rectangle.
        val layer = canvas.saveLayer(rect, null)
        try {
            canvas.drawRect(rect, surfacePaint)
            // factor ARGB has alpha 255 exactly where the original glyph has
            // nonzero coverage; its factor already includes antialias coverage.
            canvas.drawBitmap(mask, x, y, clipPaint)
        } finally {
            surfacePaint.shader = null
            canvas.restoreToCount(layer)
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
        clearTiles()
        lastLayout = null
        lastContentRef = null
        cachedWallpaperShader = null
        super.onDetachedFromWindow()
    }
}
