package my.noveldokusha.text_to_speech.ema

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Direct reader PCM path; independent of Android's TTS engine discovery. */
class EmaLocalPlayer(private val context: Context) {
    companion object {
        const val VOICE_ID = EmaTextToSpeechService.VOICE_ID
        const val ENGINE_ID = "novela:ema-local"
        fun available(context: Context) = EmaOnnxEngine.available(context)
    }

    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null
    private var engine: EmaOnnxEngine? = null

    fun close() {
        stop()
        synchronized(this) { engine?.close(); engine = null }
    }

    fun stop() {
        generation++
        val old = track
        track = null
        runCatching { old?.pause(); old?.flush(); old?.release() }
    }

    suspend fun play(text: String, speed: Float, onStart: () -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val token = generation
            var audio: AudioTrack? = null
            try {
                val activeEngine = engine ?: EmaOnnxEngine(context).also { engine = it }
                val minBytes = AudioTrack.getMinBufferSize(
                    EmaOnnxEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(8192)
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
                if (token != generation) return@withContext false
                track = audio
                onStart()
                audio.play()
                val phrases = text.split(Regex("(?<=[.!?])\\s+|(?<=.{160})\\s+"))
                var totalFramesWritten = 0L
                for ((index, phrase) in phrases.withIndex()) {
                    if (token != generation) return@withContext false
                    if (phrase.isBlank()) continue
                    val pcm = activeEngine.render(phrase, speed.coerceIn(0.25f, 4f),
                        phrase.hashCode().toLong() + index) { token != generation }
                    var offset = 0
                    while (offset < pcm.size && token == generation) {
                        val written = audio.write(pcm, offset, pcm.size - offset, AudioTrack.WRITE_BLOCKING)
                        if (written <= 0) error("AudioTrack write failed: $written")
                        offset += written
                        totalFramesWritten += written
                    }
                }
                if (token != generation) return@withContext false
                // Wait for the final queued PCM frames to finish before advancing the reader.
                val endFrames = totalFramesWritten
                while (token == generation && audio.playState == AudioTrack.PLAYSTATE_PLAYING &&
                    audio.playbackHeadPosition.toLong() < endFrames) {
                    kotlinx.coroutines.delay(20)
                }
                token == generation
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
