package my.noveldokusha.text_to_speech.ema

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** In-reader PCM player; does not register a system-wide Android TTS engine. */
class EmaLocalPlayer(private val context: Context) {
    companion object {
        const val VOICE_ID = "ema-lightning-tr-fp32"
        const val ENGINE_ID = "novela:ema-local"
        fun available(context: Context) = EmaOnnxEngine.available(context)
    }

    private val generation = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var track: AudioTrack? = null
    private val engineLock = Any()
    private var engine: EmaOnnxEngine? = null

    fun close() {
        stop()
        synchronized(engineLock) { engine?.close(); engine = null }
    }

    fun stop() {
        generation.incrementAndGet()
        val old = track
        track = null
        runCatching { old?.pause(); old?.flush(); old?.release() }
    }

    /**
     * The original 160-character regex could split an utterance at arbitrary
     * spaces, including in the middle of a clause. Keep bounded, word-safe
     * chunks and prefer a natural pause near the target.
     */
    private fun splitForSpeech(text: String, targetChars: Int = 110): List<String> {
        val clean = text.replace("🔻", " ").replace("🔺", " ")
            .replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var rest = clean
        while (rest.length > targetChars) {
            val limit = targetChars.coerceAtMost(rest.lastIndex)
            val window = rest.substring(0, limit + 1)
            val natural = listOf(". ", "! ", "? ", "; ", ": ", ", ")
                .map { window.lastIndexOf(it) + it.length }
                .filter { it >= targetChars / 2 }
                .maxOrNull()
            val splitAt = natural ?: run {
                val whitespace = window.lastIndexOf(' ')
                if (whitespace > 0) whitespace + 1 else rest.indexOf(' ', startIndex = limit)
                    .takeIf { it > 0 }?.plus(1) ?: rest.length
            }
            out.add(rest.substring(0, splitAt).trim())
            rest = rest.substring(splitAt).trimStart()
        }
        if (rest.isNotBlank()) out.add(rest)
        return out.filter { it.isNotBlank() }
    }

    suspend fun play(text: String, speed: Float, onStart: () -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val token = generation.get()
            var audio: AudioTrack? = null
            try {
                coroutineScope {
                    // Generate on a producer coroutine while AudioTrack consumes buffered PCM.
                    // Previously play() started before even the first synthesis completed.
                    val pcmQueue = Channel<ShortArray>(capacity = 4)
                    val producer = launch(Dispatchers.IO) {
                        try {
                            val phrases = splitForSpeech(text)
                            for ((index, phrase) in phrases.withIndex()) {
                                if (token != generation.get()) break
                                val started = SystemClock.elapsedRealtime()
                                var totalSamples = 0L
                                var firstWindowMs = -1L
                                // ONNX sessions remain serialized, but decoded windows
                                // are sent to the consumer immediately with bounded backpressure.
                                synchronized(engineLock) {
                                    val active = engine ?: EmaOnnxEngine(context).also { engine = it }
                                    active.renderChunks(phrase, speed.coerceIn(0.25f, 4f),
                                        phrase.hashCode().toLong() + index,
                                        { token != generation.get() }) { pcm ->
                                        if (token != generation.get()) return@renderChunks
                                        if (firstWindowMs < 0L) {
                                            firstWindowMs = SystemClock.elapsedRealtime() - started
                                        }
                                        totalSamples += pcm.size
                                        // Bounded channel: never build an unbounded multi-minute PCM queue.
                                        runBlocking { pcmQueue.send(pcm) }
                                    }
                                }
                                Log.i("EmaTTS", "PCM phrase=$index chars=${phrase.length} firstMs=$firstWindowMs renderMs=${SystemClock.elapsedRealtime() - started} audioMs=${totalSamples * 1000L / EmaOnnxEngine.SAMPLE_RATE}")
                            }
                            pcmQueue.close()
                        } catch (failure: Throwable) {
                            pcmQueue.close(failure)
                            if (failure is CancellationException) throw failure
                        }
                    }
                    try {
                        // Do not begin playback or issue onStart until audible PCM is ready.
                        val firstResult = pcmQueue.receiveCatching()
                        firstResult.exceptionOrNull()?.let { throw it }
                        val first = firstResult.getOrNull() ?: return@coroutineScope false
                        if (token != generation.get()) return@coroutineScope false
                        val minBytes = AudioTrack.getMinBufferSize(EmaOnnxEngine.SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
                        audio = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder()
                                .setSampleRate(EmaOnnxEngine.SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(minBytes)
                            .setTransferMode(AudioTrack.MODE_STREAM).build()
                        if (token != generation.get() || audio?.state != AudioTrack.STATE_INITIALIZED) return@coroutineScope false
                        track = audio
                        onStart()
                        audio?.play()
                        var totalFramesWritten = 0L
                        var pcm: ShortArray? = first
                        while (pcm != null && token == generation.get()) {
                            var offset = 0
                            while (offset < pcm.size && token == generation.get()) {
                                val written = audio!!.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                                if (written <= 0) error("AudioTrack write failed: $written")
                                offset += written
                                totalFramesWritten += written
                            }
                            val nextResult = pcmQueue.receiveCatching()
                            nextResult.exceptionOrNull()?.let { throw it }
                            pcm = nextResult.getOrNull()
                        }
                        if (token != generation.get()) return@coroutineScope false
                        // Let the last queued samples reach the output before advancing.
                        while (token == generation.get() && audio?.playState == AudioTrack.PLAYSTATE_PLAYING &&
                            audio!!.playbackHeadPosition.toLong() < totalFramesWritten) {
                            kotlinx.coroutines.delay(20)
                        }
                        if (token == generation.get()) {
                            Log.i("EmaTTS", "Playback finished frames=$totalFramesWritten underruns=${audio?.underrunCount}")
                        }
                        token == generation.get()
                    } finally {
                        producer.cancel()
                        pcmQueue.cancel()
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                Log.e("EmaTTS", "Direct PCM playback failed", failure)
                false
            } finally {
                if (track === audio) track = null
                runCatching { audio?.pause(); audio?.flush(); audio?.release() }
            }
        }
}
