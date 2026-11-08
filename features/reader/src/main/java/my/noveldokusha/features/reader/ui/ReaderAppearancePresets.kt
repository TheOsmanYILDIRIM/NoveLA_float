package my.noveldokusha.features.reader.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.preference.PreferenceManager
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.features.reader.tools.BackgroundImageLoader
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named, local reader appearance snapshots. Never touches translation, TTS, books or progress.
 * Wallpaper and imported font references remain in the app's existing private storage.
 */
internal class ReaderAppearancePresets(private val appPreferences: AppPreferences) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(appPreferences.context)

    internal data class Applied(val success: Boolean, val recreate: Boolean = false, val reload: Boolean = false)

    private fun snapshot(): Map<String, Any> = with(appPreferences) {
        mapOf(
            "APP_THEME" to APP_THEME.value,
            "THEME_DARK_MODE" to THEME_DARK_MODE.value,
            "READER_FONT_SIZE" to READER_FONT_SIZE.value,
            "READER_FONT_FAMILY" to READER_FONT_FAMILY.value,
            "READER_LINE_HEIGHT" to READER_LINE_HEIGHT.value,
            "READER_PARAGRAPH_SPACING" to READER_PARAGRAPH_SPACING.value,
            "READER_LETTER_SPACING" to READER_LETTER_SPACING.value,
            "READER_TEXT_COLOR" to READER_TEXT_COLOR.value,
            "READER_TEXT_COLOR_MODE" to READER_TEXT_COLOR_MODE.value,
            "READER_TEXT_CONTRAST_OFFSET" to READER_TEXT_CONTRAST_OFFSET.value,
            "READER_BACKGROUND_IMAGE" to READER_BACKGROUND_IMAGE.value,
            "READER_TEXT_JUSTIFY" to READER_TEXT_JUSTIFY.value,
            "READER_TEXT_HYPHENATION" to READER_TEXT_HYPHENATION.value,
            "READER_PARAGRAPH_INDENT" to READER_PARAGRAPH_INDENT.value,
            "READER_PARAGRAPH_FIRST_LETTER_BOLD" to READER_PARAGRAPH_FIRST_LETTER_BOLD.value,
            "READER_SENTENCE_SPLITTING" to READER_SENTENCE_SPLITTING.value,
            "READER_TEXT_BOLD" to READER_TEXT_BOLD.value,
            "READER_TEXT_ITALIC" to READER_TEXT_ITALIC.value,
            "READER_TEXT_UNDERLINE" to READER_TEXT_UNDERLINE.value,
            "READER_TEXT_SHADOW" to READER_TEXT_SHADOW.value,
            "READER_TEXT_ENGRAVED" to READER_TEXT_ENGRAVED.value,
            "READER_ENGRAVING_ENGINE" to READER_ENGRAVING_ENGINE.value,
            "READER_ENGRAVING_INTENSITY" to READER_ENGRAVING_INTENSITY.value,
            "READER_ENGRAVING_TRANSPARENCY" to READER_ENGRAVING_TRANSPARENCY.value,
            "READER_TEXT_SMOOTH" to READER_TEXT_SMOOTH.value,
            "READER_MARGIN_LEFT" to READER_MARGIN_LEFT.value,
            "READER_MARGIN_RIGHT" to READER_MARGIN_RIGHT.value,
            "READER_MARGIN_TOP" to READER_MARGIN_TOP.value,
            "READER_MARGIN_BOTTOM" to READER_MARGIN_BOTTOM.value,
        )
    }

    private fun read(): JSONArray = runCatching {
        JSONArray(prefs.getString(STORAGE_KEY, "[]") ?: "[]")
    }.getOrElse { JSONArray() }

    fun names(): List<String> {
        val array = read()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name") }
            .filter { !it.isNullOrBlank() }
    }

    fun save(rawName: String): Boolean {
        val name = rawName.trim()
        if (name.isEmpty() || name.length > 48) return false
        val all = read()
        if (all.length() >= 32 && name !in names()) return false
        val values = JSONObject()
        for ((key, value) in snapshot()) values.put(key, value)
        val newEntry = JSONObject().put("name", name).put("values", values)
        val updated = JSONArray()
        var replaced = false
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optString("name") == name) {
                if (!replaced) updated.put(newEntry)
                replaced = true
            } else updated.put(item)
        }
        if (!replaced) updated.put(newEntry)
        return prefs.edit().putString(STORAGE_KEY, updated.toString()).commit()
    }

    fun apply(name: String): Applied {
        val entry = (0 until read().length()).mapNotNull { read().optJSONObject(it) }
            .firstOrNull { it.optString("name") == name } ?: return Applied(false)
        val values = entry.optJSONObject("values") ?: return Applied(false)
        val known = snapshot()
        // Do not silently select a deleted wallpaper from an older theme.
        val wallpaper = values.optString("READER_BACKGROUND_IMAGE", "")
        if (BackgroundImageLoader.isImported(wallpaper) &&
            BackgroundImageLoader.resolveFile(wallpaper) == null) return Applied(false)
        val recreate = values.optString("APP_THEME") != appPreferences.APP_THEME.value ||
            values.optString("THEME_DARK_MODE") != appPreferences.THEME_DARK_MODE.value
        val reload = values.optBoolean("READER_SENTENCE_SPLITTING") !=
            appPreferences.READER_SENTENCE_SPLITTING.value
        val editor = prefs.edit()
        for ((key, template) in known) {
            if (!values.has(key)) continue
            when (template) {
                is Boolean -> editor.putBoolean(key, values.optBoolean(key, template))
                is Float -> editor.putFloat(key, values.optDouble(key, template.toDouble()).toFloat())
                is String -> editor.putString(key, values.optString(key, template))
            }
        }
        return if (editor.commit()) Applied(true, recreate, reload) else Applied(false)
    }

    fun remove(name: String): Boolean {
        val updated = JSONArray()
        val all = read()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optString("name") != name) updated.put(item)
        }
        return prefs.edit().putString(STORAGE_KEY, updated.toString()).commit()
    }

    fun usesBackground(value: String): Boolean {
        if (!BackgroundImageLoader.isImported(value)) return false
        val all = read()
        return (0 until all.length()).any { i ->
            all.optJSONObject(i)?.optJSONObject("values")
                ?.optString("READER_BACKGROUND_IMAGE") == value
        }
    }

    private companion object {
        const val STORAGE_KEY = "READER_SAVED_APPEARANCES"
    }
}

/** Resolve once on the reused downsampled wallpaper; never decode pixels per text row. */
internal object ReaderTextContrast {
    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    fun color(background: String, offset: Float, wallpaper: Bitmap?, darkFallback: Boolean): String {
        val base = backgroundColor(background, wallpaper, darkFallback)
        val r = Color.red(base)
        val g = Color.green(base)
        val b = Color.blue(base)
        val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        // Positive values increase contrast on both light and dark wallpapers.
        // Negative values deliberately move toward the background's luminance.
        val direction = if (luminance >= .5f) -1f else 1f
        val delta = direction * offset.coerceIn(-100f, 100f) * 2.55f
        val result = Color.rgb(
            (r + delta).toInt().coerceIn(0, 255),
            (g + delta).toInt().coerceIn(0, 255),
            (b + delta).toInt().coerceIn(0, 255),
        )
        return "%08X".format(result)
    }

    private fun backgroundColor(background: String, wallpaper: Bitmap?, darkFallback: Boolean): Int {
        val preset = ReaderBackgroundPresets.firstOrNull { it.id == background }
        if (preset != null) return preset.colors[preset.colors.size / 2].toArgb()
        if (BackgroundImageLoader.isImported(background) && wallpaper != null &&
            !wallpaper.isRecycled && wallpaper.width > 0 && wallpaper.height > 0) {
            // Central 50% x 40%, 20x20 samples: text occupies the middle of the page.
            var red = 0L; var green = 0L; var blue = 0L
            for (y in 0 until 20) for (x in 0 until 20) {
                val px = ((x + .5f) / 20f * .5f + .25f) * (wallpaper.width - 1)
                val py = ((y + .5f) / 20f * .4f + .3f) * (wallpaper.height - 1)
                val pixel = wallpaper.getPixel(px.toInt(), py.toInt())
                val alpha = Color.alpha(pixel) / 255f
                red += (Color.red(pixel) * alpha + 255f * (1f - alpha)).toLong()
                green += (Color.green(pixel) * alpha + 255f * (1f - alpha)).toLong()
                blue += (Color.blue(pixel) * alpha + 255f * (1f - alpha)).toLong()
            }
            return Color.rgb((red / 400).toInt(), (green / 400).toInt(), (blue / 400).toInt())
        }
        return if (darkFallback) Color.rgb(22, 22, 25) else Color.rgb(248, 248, 248)
    }
}
