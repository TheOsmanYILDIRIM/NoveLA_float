package my.noveldokusha.features.reader.novellex

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Original GLiNER2.5 Small runs in the owner's trusted companion, not inside the APK. */
internal object NovelLexClient {
    private const val MAX_PARAGRAPH = 1600
    private const val BATCH_SIZE = 32
    private const val MODEL = "fastino/gliner2.5-small-v1"
    data class Mention(val text: String, val label: String)

    fun validateEndpoint(raw: String): URL {
        val url = URL(raw.trim().trimEnd('/') + "/v1/extract")
        require(url.protocol == "http" || url.protocol == "https") { "Use HTTP(S)" }
        require(url.userInfo == null && url.query == null && url.ref == null) { "Invalid endpoint" }
        require(url.path == "/v1/extract") { "Invalid endpoint path" }
        val host = url.host.lowercase()
        val allowed = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" ||
            run {
                val p = host.split('.')
                val bytes = p.map { it.toIntOrNull() ?: -1 }
                if (bytes.size != 4 || bytes.any { it !in 0..255 }) false
                else bytes[0] == 10 || (bytes[0] == 192 && bytes[1] == 168) ||
                    (bytes[0] == 172 && bytes[1] in 16..31)
            }
        require(allowed) { "Only a local/private network companion is supported" }
        require(url.port == -1 || url.port in 1..65535)
        return url
    }

    private fun pieces(paragraphs: List<String>): List<String> = buildList {
        for (paragraph in paragraphs) {
            val text = paragraph.trim()
            var offset = 0
            while (offset < text.length) {
                var end = (offset + MAX_PARAGRAPH).coerceAtMost(text.length)
                if (end < text.length) {
                    val space = text.lastIndexOf(' ', end)
                    if (space > offset + MAX_PARAGRAPH / 2) end = space
                }
                val piece = text.substring(offset, end).trim()
                if (piece.isNotEmpty()) add(piece)
                offset = end
                while (offset < text.length && text[offset].isWhitespace()) offset++
            }
        }
    }

    suspend fun extract(endpoint: String, token: String, paragraphs: List<String>): List<Mention> =
        withContext(Dispatchers.IO) {
            require(token.length >= 24) { "Companion token is required (24+ characters)" }
            val url = validateEndpoint(endpoint)
            val all = pieces(paragraphs)
            require(all.isNotEmpty()) { "Chapter has no loaded paragraphs" }
            require(all.size <= 2500) { "Chapter too long for a single analysis" }
            val mentions = mutableListOf<Mention>()
            for (batch in all.chunked(BATCH_SIZE)) {
                val connection = (url.openConnection() as HttpURLConnection)
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 5000
                    connection.readTimeout = 120000
                    connection.doOutput = true
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Authorization", "Bearer $token")
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("Accept", "application/json")
                    val request = JSONObject().put("passages", JSONArray(batch)).toString().toByteArray(Charsets.UTF_8)
                    connection.outputStream.use { it.write(request) }
                    require(connection.responseCode == 200) { "NovelLex companion HTTP ${connection.responseCode}" }
                    val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    require(response.getString("model") == MODEL) { "Unexpected model ID" }
                    val results = response.getJSONArray("results")
                    require(results.length() == batch.size) { "Incomplete companion response" }
                    for (i in batch.indices) {
                        val entities = results.getJSONObject(i).getJSONArray("entities")
                        for (j in 0 until entities.length()) {
                            val item = entities.getJSONObject(j)
                            val start = item.getInt("start")
                            val end = item.getInt("end")
                            val text = item.getString("text")
                            val label = item.getString("label")
                            if (start in 0 until end && end <= batch[i].length && batch[i].substring(start, end) == text) {
                                mentions.add(Mention(text, label))
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
            mentions
        }
}

/** Local, per-book glossary. No raw book chapters or auth token are persisted. */
internal class NovelLexGlossary(context: Context, bookUrl: String) {
    private val prefs = context.getSharedPreferences("novellex_glossary", Context.MODE_PRIVATE)
    private val digest = MessageDigest.getInstance("SHA-256").digest(bookUrl.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val key = "book:$digest"

    private fun chapters(): JSONObject = JSONObject(prefs.getString(key, "{}") ?: "{}")

    fun read(): List<Triple<String, String, Int>> {
        val totals = linkedMapOf<Pair<String, String>, Int>()
        val chapters = chapters()
        for (chapterKey in chapters.keys()) {
            val rows = chapters.getJSONArray(chapterKey)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val itemKey = row.getString("text") to row.getString("label")
                totals[itemKey] = (totals[itemKey] ?: 0) + 1
            }
        }
        return totals.map { Triple(it.key.first, it.key.second, it.value) }
            .sortedWith(compareByDescending<Triple<String,String,Int>> { it.third }.thenBy { it.first })
    }

    /** Re-scanning an existing chapter replaces its term counts instead of double counting. */
    fun replaceChapter(chapterUrl: String, mentions: List<NovelLexClient.Mention>) {
        val chapterDigest = MessageDigest.getInstance("SHA-256").digest(chapterUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val chapters = chapters()
        val rows = JSONArray()
        for (m in mentions) {
            rows.put(JSONObject().put("text", m.text).put("label", m.label))
        }
        chapters.put(chapterDigest, rows)
        check(prefs.edit().putString(key, chapters.toString()).commit()) { "Failed to save glossary" }
    }

    fun clear() = prefs.edit().remove(key).apply()
}
