package com.osman.opusdemo

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Experimental standard HuggingFace Unigram/BPE reader.
 * Unsupported Teradata-specific layouts fail explicitly.
 */
class MarianJsonTokenizer(file: File) {
    private val root = JSONObject(file.readText())
    private val model = root.optJSONObject("model")
        ?: error("Tokenizer JSON has no standard model object")
    private val type = model.optString("type")
    private val vocabulary = LinkedHashMap<String, Int>()
    private val inverse = HashMap<Int, String>()
    private val scores = HashMap<String, Double>()
    private val merges = HashMap<String, Int>()
    private val maxPiece: Int
    private val eos: Int
    private val unknown: Int

    init {
        when (type) {
            "Unigram" -> {
                val items = model.getJSONArray("vocab")
                for (i in 0 until items.length()) {
                    val pair = items.getJSONArray(i)
                    vocabulary[pair.getString(0)] = i
                    scores[pair.getString(0)] = pair.getDouble(1)
                }
            }
            "BPE" -> {
                val items = model.getJSONObject("vocab")
                for (key in items.keys()) vocabulary[key] = items.getInt(key)
                val data = model.getJSONArray("merges")
                for (i in 0 until data.length()) {
                    val item = data.get(i)
                    val key = if (item is JSONArray) {
                        item.getString(0) + "\u0000" + item.getString(1)
                    } else {
                        item.toString().trim().split(" ").joinToString("\u0000")
                    }
                    merges[key] = i
                }
            }
            else -> error("Unsupported tokenizer model.type=" + type)
        }
        vocabulary.forEach { (piece, id) -> inverse[id] = piece }
        maxPiece = vocabulary.keys.maxOfOrNull { it.length } ?: 1
        eos = vocabulary["</s>"] ?: error("Missing Marian EOS token")
        unknown = vocabulary["<unk>"] ?: error("Missing Marian UNK token")
    }

    fun metadata(): String = "Tokenizer " + type + " vocab=" + vocabulary.size

    private fun unigram(text: String): List<Int> {
        val best = DoubleArray(text.length + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(text.length + 1) { -1 }
        val chosen = IntArray(text.length + 1) { unknown }
        best[0] = 0.0
        for (i in text.indices) {
            if (!best[i].isFinite()) continue
            for (j in i + 1..minOf(i + maxPiece, text.length)) {
                val part = text.substring(i, j)
                val id = vocabulary[part] ?: continue
                val candidate = best[i] + (scores[part] ?: -100.0)
                if (candidate > best[j]) {
                    best[j] = candidate
                    previous[j] = i
                    chosen[j] = id
                }
            }
            if (best[i] - 100.0 > best[i + 1]) {
                best[i + 1] = best[i] - 100.0
                previous[i + 1] = i
                chosen[i + 1] = unknown
            }
        }
        val result = ArrayList<Int>()
        var pos = text.length
        while (pos > 0) {
            val prev = previous[pos]
            check(prev >= 0 && prev < pos) { "Tokenizer path failed" }
            result.add(chosen[pos])
            pos = prev
        }
        result.reverse()
        return result
    }

    private fun bpe(text: String): List<Int> {
        val parts = text.codePoints().toArray().map {
            String(Character.toChars(it))
        }.toMutableList()
        while (parts.size > 1) {
            var at = -1
            var rank = Int.MAX_VALUE
            for (i in 0 until parts.lastIndex) {
                val current = merges[parts[i] + "\u0000" + parts[i + 1]]
                if (current != null && current < rank) {
                    rank = current
                    at = i
                }
            }
            if (at < 0) break
            parts[at] = parts[at] + parts[at + 1]
            parts.removeAt(at + 1)
        }
        return parts.map { vocabulary[it] ?: unknown }
    }

    fun encode(input: String): LongArray {
        require(input.isNotBlank()) { "Enter an English sentence" }
        val text = "\u2581" + input.trim().replace(Regex("""\s+"""), " ")
            .replace(" ", "\u2581")
        val pieces = when (type) {
            "Unigram" -> unigram(text)
            "BPE" -> bpe(text)
            else -> error("Unsupported tokenizer")
        }
        require(pieces.size + 1 <= 256) { "Model accepts at most 256 input tokens" }
        return (pieces + eos).map(Int::toLong).toLongArray()
    }

    fun decode(ids: LongArray): String {
        return ids.asIterable().mapNotNull { id ->
            val piece = inverse[id.toInt()]
            if (piece == null || piece == "</s>" || piece == "<pad>" || piece == "<s>") null
            else piece
        }.joinToString("").replace("\u2581", " ").trim()
    }
}
