package my.noveldokusha.features.reader.novellex

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Existing per-book glossary storage is kept for backwards compatibility. */
internal class NovelLexGlossary(context: Context, bookUrl: String) {
    private val prefs = context.getSharedPreferences("novellex_glossary", Context.MODE_PRIVATE)
    private fun digest(input: String): String = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private val key = "book:" + digest(bookUrl)

    private fun chapters(): JSONObject = JSONObject(prefs.getString(key, "{}") ?: "{}")

    fun read(): List<Triple<String, String, Int>> {
        val totals = linkedMapOf<Pair<String, String>, Int>()
        val chapters = try {
            chapters()
        } catch (e: Exception) {
            // Old/corrupt SharedPreferences must never crash the reader UI.
            // Leave the raw preference untouched for manual recovery.
            Log.e("NovelLex", "Glossary JSON unreadable; original data preserved", e)
            return emptyList()
        }
        for (chapterKey in chapters.keys()) {
            val rows = chapters.optJSONArray(chapterKey) ?: continue
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val text = row.optString("text", "").trim()
                val label = row.optString("label", "").trim()
                if (text.isEmpty() || label.isEmpty()) continue
                val itemKey = text to label
                totals[itemKey] = (totals[itemKey] ?: 0) + 1
            }
        }
        return totals.map { Triple(it.key.first, it.key.second, it.value) }
            .sortedWith(compareByDescending<Triple<String, String, Int>> { it.third }.thenBy { it.first })
    }

    /** Re-scanning a chapter replaces its terms rather than double-counting them. */
    fun replaceChapter(chapterUrl: String, mentions: List<NovelLexOnDevice.Mention>) {
        require(chapterUrl.isNotBlank()) { "Chapter ID is missing" }
        val chapters = chapters()
        val rows = JSONArray()
        for (mention in mentions) {
            rows.put(JSONObject().put("text", mention.text).put("label", mention.label))
        }
        chapters.put(digest(chapterUrl), rows)
        check(prefs.edit().putString(key, chapters.toString()).commit()) { "Failed to save local glossary" }
    }

    fun clear() = prefs.edit().remove(key).apply()
}
