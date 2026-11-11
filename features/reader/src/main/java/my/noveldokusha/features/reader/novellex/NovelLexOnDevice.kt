package my.noveldokusha.features.reader.novellex

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.reflect.Array as ReflectArray
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.regex.Pattern
import kotlin.math.exp

/**
 * Entirely on-device inference for the ORIGINAL fastino/gliner2.5-small-v1.
 * Uses the bundled boundary ONNX export, tokenizer and schema protocol.
 * No sockets, HTTP endpoints, Termux, external inference or data transmission.
 */
internal object NovelLexOnDevice {
    const val MODEL_ID = "fastino/gliner2.5-small-v1"
    val LABELS = listOf(
        "fictional creature species", "fictional person", "named fantasy technique",
        "named fictional organization", "named fictional place", "named fictional world concept",
        "named magical artifact", "named rank", "personal title"
    )
    data class Mention(val text: String, val label: String, val score: Float)
    private data class Word(val text: String, val start: Int, val end: Int)
    private data class Candidate(val start: Int, val end: Int, val score: Float)
    private val wordPattern = Pattern.compile(
        "(?:https?://\\S+|www\\.\\S+)|[\\p{L}\\p{N}_]+(?:[-_][\\p{L}\\p{N}_]+)*|\\S",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CHARACTER_CLASS
    )
    private val lock = Any()
    private var session: OrtSession? = null
    private var tokenizer: NovelLexTokenizer? = null
    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    suspend fun extract(context: Context, paragraphs: List<String>): List<Mention> =
        withContext(Dispatchers.Default) {
            require(paragraphs.isNotEmpty()) { "No original chapter paragraphs loaded" }
            val chunks = paragraphs.flatMap(::splitPassage)
            require(chunks.isNotEmpty()) { "Nothing to analyze" }
            require(chunks.size <= 3000) { "Chapter exceeds the safe analysis limit" }
            synchronized(lock) {
                ensureLoaded(context.applicationContext)
                val all = ArrayList<Mention>()
                for (chunk in chunks) {
                    all.addAll(extractOne(chunk, tokenizer!!, session!!))
                }
                all
            }
        }

    private fun words(text: String): List<Word> {
        val matcher = wordPattern.matcher(text)
        val result = ArrayList<Word>()
        while (matcher.find()) {
            result.add(Word(matcher.group().lowercase(), matcher.start(), matcher.end()))
        }
        return result
    }

    // Overlapping word windows prevent hard truncation and preserve multiword names.
    private fun splitPassage(paragraph: String): List<String> {
        val source = paragraph.trim()
        if (source.isEmpty()) return emptyList()
        val ws = words(source)
        if (ws.isEmpty()) return emptyList()
        val chunks = ArrayList<String>()
        var from = 0
        while (from < ws.size) {
            val to = minOf(from + 176, ws.size)
            chunks.add(source.substring(ws[from].start, ws[to - 1].end))
            if (to == ws.size) break
            from += 144 // 32-word overlap
        }
        return chunks
    }

    private fun extractOne(text: String, tok: NovelLexTokenizer, model: OrtSession): List<Mention> {
        val normalized = if (text.endsWith('.') || text.endsWith('!') || text.endsWith('?')) text else text + "."
        val ws = words(normalized)
        if (ws.isEmpty()) return emptyList()
        val schema = arrayListOf("(", "[P]", "entities", "(")
        for (label in LABELS) {
            schema.add("[E]")
            schema.add(label)
        }
        schema.add(")")
        schema.add(")")
        val combined = schema + "[SEP_TEXT]" + ws.map { it.text }
        val ids = ArrayList<Long>()
        val wordPositions = ArrayList<Long>()
        val queryPositions = ArrayList<Long>()
        for ((index, item) in combined.withIndex()) {
            if (index < schema.size && item == "[E]") queryPositions.add(ids.size.toLong())
            if (index > schema.size) wordPositions.add(ids.size.toLong())
            ids.addAll(tok.encode(item).asList())
        }
        check(queryPositions.size == LABELS.size && wordPositions.size == ws.size)
        val inputs = HashMap<String, OnnxTensor>()
        fun addLong(name: String, values: LongArray) {
            inputs[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(values), longArrayOf(1L, values.size.toLong()))
        }
        fun addFloat(name: String, values: FloatArray) {
            inputs[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(values), longArrayOf(1L, values.size.toLong()))
        }
        addLong("input_ids", ids.toLongArray())
        addLong("attention_mask", LongArray(ids.size) { 1L })
        addLong("text_word_indices", wordPositions.toLongArray())
        addFloat("text_word_mask", FloatArray(ws.size) { 1f })
        addLong("query_marker_indices", queryPositions.toLongArray())
        addFloat("query_marker_mask", FloatArray(LABELS.size) { 1f })
        if ("cls_marker_indices" in model.inputNames) {
            addLong("cls_marker_indices", longArrayOf(0L))
            addFloat("cls_marker_mask", floatArrayOf(0f))
        }
        if ("rel_marker_indices" in model.inputNames) {
            addLong("rel_marker_indices", longArrayOf(0L))
            addFloat("rel_marker_mask", floatArrayOf(0f))
        }

        try {
            model.run(inputs).use { output ->
                fun required(name: String): OnnxTensor {
                    return output.get(name).orElseThrow {
                        IllegalStateException("GLiNER ONNX export missing " + name)
                    } as OnnxTensor
                }
                val indexTensor = required("pair_indices")
                val logitTensor = required("pair_logits")
                val validTensor = required("pair_valid")
                val shape = logitTensor.info.shape
                require(shape.size == 3 && shape[0] == 1L && shape[1] == LABELS.size.toLong()) {
                    "Unexpected GLiNER pair logits dimensions"
                }
                val candidateCount = shape[2].toInt()
                require(candidateCount in 1..2048) { "Invalid GLiNER candidate count" }
                val indices = flattened(indexTensor.value)
                val logits = flattened(logitTensor.value)
                val valid = flattened(validTensor.value)
                require(indices.size == LABELS.size * candidateCount * 2 &&
                    logits.size == LABELS.size * candidateCount &&
                    valid.size == LABELS.size * candidateCount) {
                    "Incompatible GLiNER ONNX output shapes"
                }
                val found = ArrayList<Mention>()
                for (q in LABELS.indices) {
                    val bySpan = LinkedHashMap<Pair<Int, Int>, Candidate>()
                    for (c in 0 until candidateCount) {
                        val at = q * candidateCount + c
                        val enabled = when (val v = valid[at]) {
                            is Boolean -> v
                            is Number -> v.toDouble() != 0.0
                            else -> false
                        }
                        if (!enabled) continue
                        val start = (indices[2 * at] as Number).toInt()
                        val end = (indices[2 * at + 1] as Number).toInt()
                        if (start < 0 || end <= start || end > ws.size) continue
                        val score = (1.0 / (1.0 + exp(-(logits[at] as Number).toDouble()))).toFloat()
                        if (score < 0.5f) continue
                        val key = start to end
                        if (bySpan[key] == null || bySpan[key]!!.score < score) {
                            bySpan[key] = Candidate(start, end, score)
                        }
                    }
                    // Flat NER within each label: highest probability span wins overlap.
                    val chosen = ArrayList<Candidate>()
                    for (item in bySpan.values.sortedByDescending { it.score }) {
                        if (chosen.none { it.start < item.end && item.start < it.end }) chosen.add(item)
                    }
                    for (item in chosen) {
                        val begin = ws[item.start].start
                        val end = ws[item.end - 1].end
                        val surface = normalized.substring(begin, end).trim()
                        if (surface.isNotEmpty()) found.add(Mention(surface, LABELS[q], item.score))
                    }
                }
                return found
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun flattened(value: Any?): List<Any> {
        val output = ArrayList<Any>()
        fun visit(node: Any?) {
            if (node == null) return
            if (node.javaClass.isArray) {
                for (i in 0 until ReflectArray.getLength(node)) visit(ReflectArray.get(node, i))
            } else output.add(node)
        }
        visit(value)
        return output
    }

    private fun ensureLoaded(context: Context) {
        if (session != null && tokenizer != null) return
        val files = NovelLexModelStore.validatedFiles(context)
        val tokenizerText = files.tokenizer.readText(Charsets.UTF_8)
        val t = NovelLexTokenizer.fromJson(tokenizerText)
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(2)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            val s = env.createSession(files.graph.absolutePath, options)
            check("pair_logits" in s.outputNames && "pair_indices" in s.outputNames &&
                "pair_valid" in s.outputNames) {
                s.close()
                "The model ZIP does not contain the GLiNER2.5 boundary reranker export"
            }
            tokenizer = t
            session = s
        } finally {
            options.close()
        }
    }
}
