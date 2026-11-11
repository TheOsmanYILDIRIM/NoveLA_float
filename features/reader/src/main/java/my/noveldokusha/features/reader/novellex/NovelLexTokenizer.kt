package my.noveldokusha.features.reader.novellex

import org.json.JSONObject
import java.text.Normalizer

/**
 * Local GLiNER2.5 DeBERTa-v3 SentencePiece/Unigram tokenizer.
 * Reads the bundled Hugging Face tokenizer.json; never contacts the Hub.
 *
 * The original processor tokenizes each schema marker/word independently.
 * A non-Unigram tokenizer is deliberately rejected instead of returning false terms.
 */
internal class NovelLexTokenizer private constructor(
    private val vocab: Map<String, Piece>,
    private val specials: Map<String, Int>,
    private val unknownId: Int,
    private val maxPieceLength: Int
) {
    private data class Piece(val id: Int, val score: Double)

    fun encode(token: String): LongArray {
        specials[token]?.let { return longArrayOf(it.toLong()) }
        // HF's Metaspace pre-tokenizer inserts a word-boundary symbol for each independent token.
        val input = "\u2581" + Normalizer.normalize(token, Normalizer.Form.NFC)
            .replace(" ", "\u2581")
        val costs = DoubleArray(input.length + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(input.length + 1) { -1 }
        val ids = IntArray(input.length + 1) { unknownId }
        costs[0] = 0.0
        for (start in input.indices) {
            if (!costs[start].isFinite()) continue
            val endLimit = minOf(input.length, start + maxPieceLength)
            var pos = start
            while (pos < endLimit) {
                pos += Character.charCount(Character.codePointAt(input, pos))
                if (pos > endLimit) break
                val piece = vocab[input.substring(start, pos)] ?: continue
                val score = costs[start] + piece.score
                if (score > costs[pos]) {
                    costs[pos] = score
                    previous[pos] = start
                    ids[pos] = piece.id
                }
            }
            if (previous[start] != -1 || start == 0) {
                val end = start + Character.charCount(Character.codePointAt(input, start))
                if (costs[end] == Double.NEGATIVE_INFINITY) {
                    costs[end] = costs[start] - 100.0
                    previous[end] = start
                    ids[end] = unknownId
                }
            }
        }
        check(previous[input.length] >= 0) { "Tokenizer cannot encode token" }
        val result = ArrayList<Long>()
        var cursor = input.length
        while (cursor > 0) {
            result.add(ids[cursor].toLong())
            cursor = previous[cursor]
            check(cursor >= 0) { "Invalid tokenizer backtrack" }
        }
        result.reverse()
        return result.toLongArray()
    }

    companion object {
        fun fromJson(json: String): NovelLexTokenizer {
            val root = JSONObject(json)
            val model = root.getJSONObject("model")
            require(model.getString("type") == "Unigram") {
                "Unsupported model tokenizer: expected SentencePiece Unigram"
            }
            val rows = model.getJSONArray("vocab")
            val vocab = HashMap<String, Piece>(rows.length())
            var longest = 1
            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                val piece = row.getString(0)
                vocab[piece] = Piece(i, row.getDouble(1))
                longest = maxOf(longest, piece.length)
            }
            val specials = HashMap<String, Int>()
            val added = root.optJSONArray("added_tokens")
            if (added != null) {
                for (i in 0 until added.length()) {
                    val row = added.getJSONObject(i)
                    specials[row.getString("content")] = row.getInt("id")
                }
            }
            for (marker in listOf("[P]", "[E]", "[SEP_TEXT]")) {
                require(specials.containsKey(marker)) { "Required GLiNER marker missing: " + marker }
            }
            return NovelLexTokenizer(vocab, specials, model.getInt("unk_id"), longest)
        }
    }
}
