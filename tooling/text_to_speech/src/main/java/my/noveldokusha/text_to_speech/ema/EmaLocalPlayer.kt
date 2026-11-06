package my.noveldokusha.text_to_speech.ema

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val lookaheadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var lookaheadJob: Job? = null
    private var engine: EmaOnnxEngine? = null

    private data class Lookahead(val id: String, val text: String, val speed: Float,
                                 val token: Int, var chunks: List<ShortArray>? = null)
    private val lookaheadLock = Any()
    private val lookaheadQueue = ArrayDeque<Lookahead>()

    /** Reserve only a small number of future paragraphs; render one phrase ahead. */
    fun queueLookahead(id: String, text: String, speed: Float) {
        synchronized(lookaheadLock) {
            if (lookaheadQueue.size >= 8 || lookaheadQueue.any { it.id == id }) return
            lookaheadQueue.addLast(Lookahead(id, text, speed, generation.get()))
        }
    }

    private fun takeLookahead(id: String, text: String, speed: Float, token: Int): List<ShortArray>? =
        synchronized(lookaheadLock) {
            val first = lookaheadQueue.firstOrNull() ?: return@synchronized null
            if (first.id != id) return@synchronized null
            lookaheadQueue.removeFirst()
            if (first.text == text && first.speed == speed && first.token == token)
                first.chunks?.takeIf { it.isNotEmpty() } else null
        }

    private fun prepareLookahead(token: Int) {
        val next = synchronized(lookaheadLock) {
            lookaheadQueue.firstOrNull()?.takeIf { it.token == token && it.chunks == null }
        } ?: return
        EmaDiagnostics.record(context, "PREFETCH_BEGIN token=$token chars=${next.text.length}")
        val phrase = splitForSpeech(next.text).firstOrNull() ?: return
        val samples = mutableListOf<ShortArray>()
        try {
            synchronized(engineLock) {
                val active = engine ?: EmaOnnxEngine(context).also { engine = it }
                active.renderChunks(phrase, next.speed.coerceIn(0.25f, 4f),
                    phrase.hashCode().toLong(), { token != generation.get() }) { pcm ->
                    if (token == generation.get()) samples.add(pcm)
                }
            }
            synchronized(lookaheadLock) {
                if (token == generation.get() && lookaheadQueue.firstOrNull() === next) {
                    if (samples.isNotEmpty()) next.chunks = samples
                    EmaDiagnostics.record(context, "PREFETCH_DONE token=$token windows=${samples.size}")
                }
            }
        } catch (failure: Exception) {
            EmaDiagnostics.record(context, "PREFETCH_ERROR kind=${failure.javaClass.simpleName}")
        }
    }


    fun close() {
        stop()
        lookaheadScope.coroutineContext[Job]?.cancel()
        synchronized(engineLock) { engine?.close(); engine = null }
    }

    fun stop() {
        generation.incrementAndGet()
        lookaheadJob?.cancel()
        EmaDiagnostics.record(context, "STOP generation=${generation.get()}")
        synchronized(lookaheadLock) { lookaheadQueue.clear() }
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
        // Speech-only cleanup: retain the visible text and spoken letters.
        val clean = text.replace(Regex("[\\p{S}\\p{C}]"), " ")
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

    suspend fun play(text: String, speed: Float, onStart: () -> Unit, prefetchId: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val token = generation.get()
            var audio: AudioTrack? = null
            try {
                coroutineScope {
                    // Generate on a producer coroutine while AudioTrack consumes buffered PCM.
                    // Previously play() started before even the first synthesis completed.
                    val pcmQueue = Channel<ShortArray>(capacity = 8)
                    EmaDiagnostics.record(context, "PLAY_REQUEST token=$token chars=${text.length} speed=$speed")
                    // Detached producer: a native ONNX stall cannot hold the reader job forever.
                    val producer = lookaheadScope.launch {
                        try {
                            val phrases = splitForSpeech(text)
                            val cachedFirst = prefetchId?.let { takeLookahead(it, text, speed, token) }
                            for ((index, phrase) in phrases.withIndex()) {
                                if (token != generation.get()) break
                                if (index == 0 && cachedFirst != null) {
                                    for (pcm in cachedFirst) {
                                        if (token != generation.get()) break
                                        pcmQueue.send(pcm)
                                    }
                                    Log.i("EmaTTS", "Using prefetched paragraph id=" + prefetchId)
                                    continue
                                }
                                val started = SystemClock.elapsedRealtime()
                                EmaDiagnostics.record(context, "ONNX_BEGIN token=$token phrase=$index chars=${phrase.length}")
                                var totalSamples = 0L
                                var firstWindowMs = -1L
                                // Recover from a single failed piece without aborting the reader.
                                try {
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
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (failure: Exception) {
                                    EmaDiagnostics.record(context, "ONNX_ERROR phrase=$index kind=${failure.javaClass.simpleName}")
                                    synchronized(engineLock) {
                                        runCatching { engine?.close() }
                                        engine = null
                                    }
                                }
                                EmaDiagnostics.record(context, "ONNX_END token=$token phrase=$index firstMs=$firstWindowMs renderMs=${SystemClock.elapsedRealtime() - started} audioMs=${totalSamples * 1000L / EmaOnnxEngine.SAMPLE_RATE}")
                            }
                            pcmQueue.close()
                            if (token == generation.get()) {
                                lookaheadJob?.cancel()
                                lookaheadJob = lookaheadScope.launch { prepareLookahead(token) }
                            }
                        } catch (failure: Throwable) {
                            pcmQueue.close(failure)
                            if (failure is CancellationException) throw failure
                        }
                    }
                    try {
                        // Do not begin playback or issue onStart until audible PCM is ready.
                        val firstResult = withTimeoutOrNull(25_000L) { pcmQueue.receiveCatching() }
                        if (firstResult == null) {
                            EmaDiagnostics.record(context, "PCM_FIRST_TIMEOUT token=$token afterMs=25000")
                            return@coroutineScope false
                        }
                        firstResult.exceptionOrNull()?.let { throw it }
                        val first = firstResult.getOrNull() ?: return@coroutineScope false
                        if (token != generation.get()) return@coroutineScope false
                        val minBytes = AudioTrack.getMinBufferSize(EmaOnnxEngine.SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(8192)
                        val bufferBytes = maxOf(minBytes, EmaOnnxEngine.SAMPLE_RATE * 2 * 2)
                        audio = AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(AudioFormat.Builder()
                                .setSampleRate(EmaOnnxEngine.SAMPLE_RATE)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(bufferBytes)
                            .setTransferMode(AudioTrack.MODE_STREAM).build()
                        if (token != generation.get() || audio?.state != AudioTrack.STATE_INITIALIZED) return@coroutineScope false
                        track = audio
                        onStart()
                        audio?.play()
                        EmaDiagnostics.record(context, "AUDIO_START token=$token")
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
                            val nextResult = withTimeoutOrNull(15_000L) { pcmQueue.receiveCatching() }
                            if (nextResult == null) {
                                EmaDiagnostics.record(context, "PCM_NEXT_TIMEOUT token=$token afterMs=15000")
                                return@coroutineScope false
                            }
                            nextResult.exceptionOrNull()?.let { throw it }
                            pcm = nextResult.getOrNull()
                        }
                        if (token != generation.get()) return@coroutineScope false
                        // Let the last queued samples reach the output before advancing.
                        val drainDeadline = SystemClock.elapsedRealtime() + 15_000L
                        while (token == generation.get() && audio?.playState == AudioTrack.PLAYSTATE_PLAYING &&
                            audio!!.playbackHeadPosition.toLong() < totalFramesWritten &&
                            SystemClock.elapsedRealtime() < drainDeadline) {
                            kotlinx.coroutines.delay(20)
                        }
                        if (token == generation.get()) {
                            EmaDiagnostics.record(context, "AUDIO_DONE token=$token frames=$totalFramesWritten underruns=${audio?.underrunCount}")
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
                EmaDiagnostics.record(context, "PLAY_ERROR kind=${failure.javaClass.simpleName}")
                false
            } finally {
                if (track === audio) track = null
                runCatching { audio?.pause(); audio?.flush(); audio?.release() }
            }
        }
}
