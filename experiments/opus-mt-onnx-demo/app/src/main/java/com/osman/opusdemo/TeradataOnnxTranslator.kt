package com.osman.opusdemo

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.LongBuffer
import java.nio.IntBuffer
import java.nio.FloatBuffer

/**
 * Teradata ONNX BeamSearch Android compatibility probe.
 * Errors are displayed verbatim, never replaced with fake translations.
 */
class TeradataOnnxTranslator(weights: File, tokenizerFile: File) : AutoCloseable {
    private val tokenizer = MarianJsonTokenizer(tokenizerFile)
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
    }
    private val session = try {
        env.createSession(weights.absolutePath, options)
    } catch (e: Exception) {
        options.close()
        throw IllegalStateException(
            "ONNX Runtime Android cannot load this Teradata BeamSearch model: " + e.message, e
        )
    }

    fun describe(): String = tokenizer.metadata() +
        "\nInputs: " + session.inputInfo.entries.joinToString {
            it.key + ":" + ((it.value.info as? TensorInfo)?.type ?: "?")
        } + "\nOutputs: " + session.outputInfo.keys.joinToString()

    private fun valueFor(name: String, ids: LongArray): Pair<LongArray, LongArray> {
        return when (name.lowercase()) {
            "input_ids", "encoder_input_ids", "source_ids" ->
                ids to longArrayOf(1, ids.size.toLong())
            "attention_mask", "encoder_attention_mask" ->
                LongArray(ids.size) { 1 } to longArrayOf(1, ids.size.toLong())
            "max_length" -> longArrayOf(96) to longArrayOf(1)
            "min_length" -> longArrayOf(1) to longArrayOf(1)
            "num_beams" -> longArrayOf(2) to longArrayOf(1)
            "num_return_sequences" -> longArrayOf(1) to longArrayOf(1)
            "length_penalty" -> longArrayOf(1) to longArrayOf(1)
            "repetition_penalty" -> longArrayOf(1) to longArrayOf(1)
            else -> error("Unknown input " + name + " (Teradata BYOM adapter needed)")
        }
    }

    private fun flattenFirst(value: Any?): LongArray = when (value) {
        is LongArray -> value
        is IntArray -> value.map(Int::toLong).toLongArray()
        is Array<*> -> flattenFirst(value.firstOrNull())
        else -> error("Unexpected BeamSearch output type: " + (value?.javaClass?.name ?: "null"))
    }

    fun translate(text: String): Pair<String, String> {
        val ids = tokenizer.encode(text)
        val feeds = LinkedHashMap<String, OnnxTensor>()
        try {
            for ((name, info) in session.inputInfo) {
                val spec = info.info as? TensorInfo ?: error("Non-tensor input: " + name)
                val (values, shape) = valueFor(name, ids)
                val actualShape = when {
                    spec.shape.size == shape.size -> shape
                    spec.shape.isEmpty() && values.size == 1 -> longArrayOf()
                    else -> error("Wrong rank for " + name)
                }
                feeds[name] = when (spec.type) {
                    OnnxJavaType.INT64 -> OnnxTensor.createTensor(
                        env, LongBuffer.wrap(values), actualShape
                    )
                    OnnxJavaType.INT32 -> OnnxTensor.createTensor(
                        env, IntBuffer.wrap(values.map(Long::toInt).toIntArray()), actualShape
                    )
                    OnnxJavaType.FLOAT -> OnnxTensor.createTensor(
                        env, FloatBuffer.wrap(values.map(Long::toFloat).toFloatArray()), actualShape
                    )
                    else -> error("Unsupported input type for " + name + ": " + spec.type)
                }
            }
            val start = android.os.SystemClock.elapsedRealtime()
            val output = session.run(feeds).use { result ->
                flattenFirst((result[0] as OnnxTensor).value)
            }
            val ms = android.os.SystemClock.elapsedRealtime() - start
            return tokenizer.decode(output) to
                ("inputTokens=" + ids.size + " outputTokens=" + output.size + " ms=" + ms)
        } finally {
            feeds.values.forEach { it.close() }
        }
    }

    override fun close() {
        session.close()
        options.close()
    }
}
