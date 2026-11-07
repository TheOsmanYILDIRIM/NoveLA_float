package my.noveldokusha.text_translator.domain

import java.util.Locale

/** A deterministic, book-scoped glossary entry. IDs must be unique within a translation batch. */
data class NovelLexTermRef(
    val id: String,
    val original: String,
    val translated: String = "",
    val locked: Boolean = false,
)

data class NovelLexPrepared(
    val originalText: String,
    val markedText: String,
    val occurrences: List<NovelLexTermRef>,
)

data class NovelLexRestored(val text: String, val valid: Boolean, val learned: Map<String, String>)

/**
 * Literal multi-pattern matcher, not one regex per term. Longest non-overlapping match wins.
 * The same marker protocol works for any Unicode script; uppercase is only for Latin terms.
 */
class NovelLexMarkers(entries: Collection<NovelLexTermRef>) {
    private class Node {
        val edges = HashMap<Char, Node>()
        var term: NovelLexTermRef? = null
    }
    private val root = Node()
    private val marker = Regex("⟦NLX([0-9A-F]{16}):([^⟦⟧\\r\\n]*)⟧")

    init {
        entries.asSequence()
            .filter { it.original.isNotBlank() && it.id.matches(Regex("[0-9A-F]{16}")) }
            .sortedWith(compareByDescending<NovelLexTermRef> { it.original.length }.thenBy { it.id })
            .forEach { entry ->
                var node = root
                for (c in entry.original) node = node.edges.getOrPut(c.lowercaseChar()) { Node() }
                if (node.term == null) node.term = entry
            }
    }

    fun prepare(text: String): NovelLexPrepared {
        if (text.isEmpty() || text.contains("⟦NLX")) return NovelLexPrepared(text, text, emptyList())
        val out = StringBuilder(text.length + 32)
        val found = ArrayList<NovelLexTermRef>()
        var i = 0
        while (i < text.length) {
            var node = root
            var j = i
            var best: NovelLexTermRef? = null
            var bestEnd = i
            while (j < text.length) {
                node = node.edges[text[j].lowercaseChar()] ?: break
                j++
                if (node.term != null && boundary(text, i, j)) {
                    best = node.term
                    bestEnd = j
                }
            }
            if (best != null) {
                val source = text.substring(i, bestEnd)
                val visible = if (source.any { it in 'a'..'z' || it in 'A'..'Z' }) source.uppercase(Locale.ROOT) else source
                out.append("⟦NLX").append(best.id).append(':').append(visible).append('⟧')
                found.add(best)
                i = bestEnd
            } else {
                out.append(text[i]); i++
            }
        }
        return NovelLexPrepared(text, out.toString(), found)
    }

    private fun boundary(text: String, start: Int, end: Int): Boolean =
        (start == 0 || !word(text[start - 1])) && (end == text.length || !word(text[end]))

    private fun word(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * Strict validation: when a provider removes, invents or changes an ID the caller must
     * retry the ORIGINAL paragraph untagged. A malformed marker is never shown to readers.
     */
    fun restore(prepared: NovelLexPrepared, response: String, targetLanguage: String): NovelLexRestored {
        if (prepared.occurrences.isEmpty()) return NovelLexRestored(response, true, emptyMap())
        val expected = prepared.occurrences.groupingBy { it.id }.eachCount()
        val actualMatches = marker.findAll(response).toList()
        val actual = actualMatches.map { it.groupValues[1] }.groupingBy { it }.eachCount()
        if (actual != expected || response.contains("⟦NLX") && actualMatches.size != response.split("⟦NLX").size - 1) {
            return NovelLexRestored(response, false, emptyMap())
        }
        val byId = prepared.occurrences.associateBy { it.id }
        val learned = LinkedHashMap<String, String>()
        val out = StringBuilder(response.length)
        var cursor = 0
        for (match in actualMatches) {
            val entry = byId[match.groupValues[1]] ?: return NovelLexRestored(response, false, emptyMap())
            out.append(response, cursor, match.range.first)
            val providerSurface = match.groupValues[2].trim()
            if (providerSurface.isBlank()) return NovelLexRestored(response, false, emptyMap())
            val normalizedSurface = normalizeSuggestion(providerSurface, targetLanguage)
            if (entry.translated.isEmpty()) learned.putIfAbsent(entry.id, normalizedSurface)
            val after = match.range.last + 1
            val apostrophe = response.getOrNull(after)
            // When grammar is attached to a DIFFERENT translated lemma, don't guess morphology.
            // Provider wording stays intact until a language-specific inflector is available.
            val inflected = (apostrophe == '\'' || apostrophe == '’') && response.getOrNull(after + 1)?.isLetter() == true
            val useSaved = entry.translated.isNotBlank() &&
                (!inflected || entry.translated.equals(providerSurface, ignoreCase = true))
            val chosen = if (useSaved) entry.translated else normalizedSurface
            // Turkish: providers commonly put inflection outside a protected, uppercased term.
            // Do not insert an apostrophe for common nouns; retain it for proper names elsewhere.
            val dropApostrophe = targetLanguage.substringBefore('-').equals("tr", ignoreCase = true) &&
                (apostrophe == '\'' || apostrophe == '’') && response.getOrNull(after + 1)?.isLetter() == true
            out.append(chosen)
            cursor = after + if (dropApostrophe) 1 else 0
        }
        out.append(response, cursor, response.length)
        return NovelLexRestored(out.toString(), true, learned)
    }

    private fun normalizeSuggestion(surface: String, language: String): String {
        val words = surface.split(' ')
        if (words.size < 2 || surface.any { it.isLetter() && !it.isUpperCase() }) return surface
        val locale = Locale.forLanguageTag(language)
        return words.joinToString(" ") { word ->
            if (word.isEmpty()) word else word.take(1).uppercase(locale) + word.drop(1).lowercase(locale)
        }
    }
}
