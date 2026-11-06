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

    suspend fun play(text: String, speed: Float, onStart: () -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val token = generation.get()
            var audio: AudioTrack? = null
            try {
                coroutineScope {
                    // Generate on a producer coroutine while AudioTrack consumes buffered PCM.
                    // Previously play() started before even the first synthesis completed.
                    val pcmQueue = Channel<ShortArray>(capacity = 2)
                    val producer = launch(Dispatchers.IO) {
                        try {
                            val phrases = text.trim().split(Regex("(?<=[.!?])\\s+|(?<=.{160})\\s+"))
                            for ((index, phrase) in phrases.withIndex()) {
                                if (token != generation.get()) break
                                if (phrase.isBlank()) continue
                                val started = SystemClock.elapsedRealtime()
                                // Never run two ONNX sessions against a shared engine simultaneously.
                                val pcm = synchronized(engineLock) {
                                    val active = engine ?: EmaOnnxEngine(context).also { engine = it }
                                    active.render(phrase, speed.coerceIn(0.25f, 4f),
                                        phrase.hashCode().toLong() + index) { token != generation.get() }
                                }
                                Log.i("EmaTTS", "PCM phrase index=$index renderMs=${SystemClock.elapsedRealtime() - started} audioMs=${pcm.size * 1000L / EmaOnnxEngine.SAMPLE_RATE}")
                                if (token != generation.get()) break
                                if (pcm.isNotEmpty()) pcmQueue.send(pcm)
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
