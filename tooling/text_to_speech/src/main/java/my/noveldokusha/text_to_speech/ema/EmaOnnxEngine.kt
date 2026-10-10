package my.noveldokusha.text_to_speech.ema

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.util.Random
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline FP32 EMA Lightning inference. Weights are generated from the pinned
 * EMA source through Optimum -> ORT Extended -> OnnxSlim and bundled in APK assets.
 *
 * Note: numeric/date normalization is not equivalent to Python normalizer-tr yet.
 * The UI must not describe this as full normalizer-tr parity.
 */
internal class EmaOnnxEngine(context: Context) : AutoCloseable {
    companion object {
        private const val ROOT = "ema/"
        const val SAMPLE_RATE = 48_000
        private const val CONTEXT = 8
        private const val FIRST_WINDOW = 25
        private const val NEXT_WINDOW = 100
        private const val MAX_FRAMES = 3000

        fun available(context: Context): Boolean = runCatching {
            val files = context.assets.list("ema")?.toSet() ?: emptySet()
            files.containsAll(setOf("text_stage.onnx", "sound_stage.onnx", "decoder.onnx", "vocab.json"))
        }.getOrDefault(false)
    }

    private val env = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
    }
    private val textSession = env.createSession(context.assets.open(ROOT + "text_stage.onnx").use { it.readBytes() }, sessionOptions)
    private val soundSession = env.createSession(context.assets.open(ROOT + "sound_stage.onnx").use { it.readBytes() }, sessionOptions)
    private val decoderSession = env.createSession(context.assets.open(ROOT + "decoder.onnx").use { it.readBytes() }, sessionOptions)
    private val vocab = JSONObject(context.assets.open(ROOT + "vocab.json").bufferedReader().use { it.readText() })
    private val stoi = vocab.getJSONObject("stoi")
    private val times = vocab.getJSONArray("times").length()
    private val latentDim = vocab.getInt("latent_dim")

    private fun infer(session: OrtSession, inputs: Map<String, Any>): Any {
        val tensors = inputs.mapValues { (_, array) -> OnnxTensor.createTensor(env, array) }
        try {
            session.run(tensors).use { output ->
                return (output[0] as OnnxTensor).value
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    private fun ids(text: String) = LongArray(text.length) { index ->
        stoi.optLong(text[index].toString(), 1L)
    }

    /**
     * Each input has already been divided into a short phrase. The frame plan
     * mirrors Engine.plan: sum character durations per word, round, clamp and
     * expand word/frame position vectors.
     */
    fun render(phrase: String, speed: Float, seed: Long, cancelled: () -> Boolean): ShortArray {
        require(speed in 0.25f..4f)
        val text = normalizeAlphabet(phrase)
        if (text.isBlank()) return ShortArray(0)
        val n = text.length
        val ids = ids(text)
        val words = LongArray(n)
        val starts = LongArray(n)
        var word = 0L
        var start = 0L
        for (i in text.indices) {
            if (i > 0 && text[i] != ' ' && text[i-1] == ' ') {
                word++
                start = i.toLong()
            }
            words[i] = word
            starts[i] = start
        }
        val h: Array<Array<FloatArray>>
        val durations: FloatArray
        @Suppress("UNCHECKED_CAST")
        run {
            val inputs = mapOf("ids" to arrayOf(ids), "mask" to arrayOf(BooleanArray(n) { true }))
            val tensors = inputs.mapValues { (_, v) -> OnnxTensor.createTensor(env, v) }
            try {
                textSession.run(tensors).use { result ->
                    h = (result[0] as OnnxTensor).value as Array<Array<FloatArray>>
                    durations = ((result[1] as OnnxTensor).value as Array<FloatArray>)[0]
                }
            } finally { tensors.values.forEach { it.close() } }
        }
        val counts = FloatArray(word.toInt() + 1)
        for (i in 0 until n) counts[words[i].toInt()] += durations[i] / speed
        val wordFrames = IntArray(counts.size) { counts[it].roundToInt().coerceIn(1, 250) }
        val frameCount = min(wordFrames.sum(), MAX_FRAMES)
        val fw = LongArray(frameCount)
        val fp = FloatArray(frameCount)
        var f = 0
        for (w in wordFrames.indices) {
            val frames = wordFrames[w]
            for (j in 0 until frames) {
                if (f >= frameCount) break
                fw[f] = w.toLong()
                fp[f] = j.toFloat() / frames
                f++
            }
        }
        if (cancelled()) return ShortArray(0)
        val rng = Random(seed)
        val noise = Array(1) {
            Array(times) {
                Array(frameCount) {
                    FloatArray(latentDim) { rng.nextGaussian().toFloat() }
                }
            }
        }
        val latents = infer(soundSession, mapOf(
            "h" to h,
            "dur" to arrayOf(FloatArray(n) { durations[it] / speed }),
            "mask" to arrayOf(BooleanArray(n) { true }),
            "cw" to arrayOf(words),
            "wstart" to arrayOf(starts),
            "fw" to arrayOf(fw),
            "fp" to arrayOf(fp),
            "fmask" to arrayOf(BooleanArray(frameCount) { true }),
            "noise" to noise
        )) as Array<Array<FloatArray>>
        val samples = ArrayList<Short>(frameCount * 1920)
        var from = 0
        while (from < frameCount && !cancelled()) {
            val to = min(frameCount, from + if (from == 0) FIRST_WINDOW else NEXT_WINDOW)
            val contextStart = (from - CONTEXT).coerceAtLeast(0)
            val contextEnd = (to + CONTEXT).coerceAtMost(frameCount)
            val input = Array(1) {
                Array(latentDim) { channel ->
                    FloatArray(contextEnd - contextStart) { t ->
                        latents[0][contextStart + t][channel]
                    }
                }
            }
            val audio = (infer(decoderSession, mapOf("z" to input)) as Array<FloatArray>)[0]
            val low = (from - contextStart) * 1920
            val high = (to - contextStart) * 1920
            for (i in low until min(high, audio.size)) {
                samples.add((audio[i].coerceIn(-1f, 1f) * 32767).roundToInt().toShort())
            }
            from = to
        }
        return samples.toShortArray()
    }

    private fun normalizeAlphabet(text: String): String {
        // Maintain Turkish I/İ semantics and model's own vocabulary.
        val lowered = text.replace('İ', 'i').replace('I', 'ı')
            .lowercase(java.util.Locale.forLanguageTag("tr-TR"))
        return lowered.map { if (stoi.has(it.toString())) it else ' ' }
            .joinToString("").replace(Regex("\\s+"), " ").trim()
    }

    override fun close() {
        decoderSession.close()
        soundSession.close()
        textSession.close()
        sessionOptions.close()
    }
}
