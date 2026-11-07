package my.noveldokusha.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import my.noveldokusha.feature.local_database.AppDatabase
import my.noveldokusha.feature.local_database.tables.NovelLexTerm
import my.noveldokusha.text_translator.domain.NovelLexMarkers
import my.noveldokusha.text_translator.domain.NovelLexTermRef
import timber.log.Timber
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Book URL (not title) is the identity; all glossary changes are persisted in Room. */
@Singleton
class NovelLexService @Inject constructor(private val database: AppDatabase) {
    private val updateMutex = Mutex()
    private data class CachedIndex(
        val book: String, val source: String, val target: String,
        val size: Int, val matcher: NovelLexMarkers
    )
    @Volatile private var cachedIndex: CachedIndex? = null
    private val englishPhrase = Regex("(?<![\\p{L}])[A-Z][a-z]+(?:[ \\t]+[A-Z][a-z]+){1,3}(?![\\p{L}])")

    /**
     * Re-evaluate ALL cached original chapters on every new chapter. This is deliberately
     * independent of old translations, which are not rewritten or invalidated.
     */
    suspend fun refresh(bookUrl: String, sourceLang: String, targetLang: String) {
        if (sourceLang.substringBefore('-').lowercase() != "en") return
        updateMutex.withLock {
            val bodies = withContext(Dispatchers.IO) {
                database.chapterBodyDao().getBodiesForBook(bookUrl)
            }
            if (bodies.isEmpty()) return@withLock
            val counts = withContext(Dispatchers.Default) {
                val count = HashMap<String, Int>()
                for (body in bodies) for (found in englishPhrase.findAll(body.body)) {
                    val candidate = found.value.replace(Regex("[ \\t]+"), " ")
                    count[candidate] = (count[candidate] ?: 0) + 1
                }
                count
            }
            val dao = database.novelLexTermDao()
            val existing = withContext(Dispatchers.IO) { dao.forBook(bookUrl, sourceLang, targetLang) }
                .associateBy { it.original }
            val updates = counts.asSequence().filter { it.value >= 3 }.mapNotNull { (term, count) ->
                val current = existing[term]
                if (current != null) {
                    if (current.occurrenceCount == count) null else current.copy(occurrenceCount = count)
                } else {
                    NovelLexTerm(
                        id = stableId(bookUrl, sourceLang, targetLang, term),
                        bookUrl = bookUrl, sourceLang = sourceLang, targetLang = targetLang,
                        original = term, translated = "", occurrenceCount = count
                    )
                }
            }.toList()
            if (updates.isNotEmpty()) {
                withContext(Dispatchers.IO) { dao.upsert(updates) }
                cachedIndex = null
            }
        }
    }

    /**
     * Shared reader/download translation gate. Each paragraph must pass the same marker
     * validation regardless of provider. If an LLM drops a marker, retry ONLY that
     * paragraph untagged instead of leaking protocol text into the book.
     */
    suspend fun translateBatch(
        bookUrl: String, sourceLang: String, targetLang: String,
        texts: List<String>, request: suspend (List<String>) -> Map<String, String>,
    ): Map<String, String> {
        if (texts.isEmpty()) return emptyMap()
        val snapshot = cachedIndex?.takeIf {
            it.book == bookUrl && it.source == sourceLang && it.target == targetLang
        } ?: updateMutex.withLock {
            cachedIndex?.takeIf {
                it.book == bookUrl && it.source == sourceLang && it.target == targetLang
            } ?: withContext(Dispatchers.IO) {
                database.novelLexTermDao().forBook(bookUrl, sourceLang, targetLang)
            }.let { entries ->
                CachedIndex(
                    bookUrl, sourceLang, targetLang, entries.size,
                    NovelLexMarkers(entries.map {
                        NovelLexTermRef(it.id, it.original, it.translated, it.locked)
                    })
                ).also { cachedIndex = it }
            }
        }
        if (snapshot.size == 0) return request(texts)
        val index = snapshot.matcher
        val prepared = texts.map(index::prepare)
        if (prepared.none { it.occurrences.isNotEmpty() }) return request(texts)
        val tagged = request(prepared.map { it.markedText })
        val result = LinkedHashMap<String, String>(texts.size)
        val toRetry = ArrayList<String>()
        val learned = LinkedHashMap<String, String>()
        for (p in prepared) {
            val answer = tagged[p.markedText]
            if (answer == null || (p.occurrences.isNotEmpty() && answer == p.markedText)) {
                toRetry.add(p.originalText)
                continue
            }
            val restored = index.restore(p, answer, targetLang)
            if (!restored.valid || restored.text.contains("⟦NLX")) {
                Timber.w("NovelLex: translation provider altered a marker; retry untagged")
                toRetry.add(p.originalText)
            } else {
                result[p.originalText] = restored.text
                learned.putAll(restored.learned)
            }
        }
        if (toRetry.isNotEmpty()) {
            val fallback = request(toRetry.distinct())
            for (original in toRetry) {
                val value = fallback[original] ?: original
                result[original] = if (value.contains("⟦NLX")) original else value
            }
        }
        if (learned.isNotEmpty()) updateMutex.withLock {
            val dao = database.novelLexTermDao()
            val latest = withContext(Dispatchers.IO) { dao.forBook(bookUrl, sourceLang, targetLang) }
            val changes = latest.mapNotNull { term ->
                val suggestion = learned[term.id]?.trim()?.takeIf { it.length in 1..100 }
                if (suggestion != null && term.translated.isBlank() && !term.locked)
                    term.copy(translated = suggestion)
                else null
            }
            if (changes.isNotEmpty()) {
                withContext(Dispatchers.IO) { dao.upsert(changes) }
                cachedIndex = null
            }
        }
        return result
    }

    private fun stableId(book: String, src: String, dst: String, term: String): String {
        val key = "$book\u0000$src\u0000$dst\u0000$term"
        return MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }
}
