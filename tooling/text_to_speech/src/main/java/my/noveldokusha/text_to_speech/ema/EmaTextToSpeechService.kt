package my.noveldokusha.text_to_speech.ema

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native, in-app Android TTS engine.
 *
 * It integrates with NoveLA's existing TextToSpeechManager without replacing
 * its chapter queue, MediaSession, playback speed, or fallback Android TTS.
 * Available only when the validated model assets are actually bundled.
 */
class EmaTextToSpeechService : TextToSpeechService() {
    companion object {
        const val VOICE_ID = "ema-lightning-tr-fp32"
    }

    private val stopRequested = AtomicBoolean(false)
    private var engine: EmaOnnxEngine? = null
    private val turkish = Locale.forLanguageTag("tr-TR")

    override fun onCreate() {
        super.onCreate()
        // Avoid blocking the main thread with ONNX model initialization.
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return if (lang.equals("tur", true) || lang.equals("tr", true)) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        if (EmaOnnxEngine.available(this)) onIsLanguageAvailable(lang, country, variant)
        else TextToSpeech.LANG_MISSING_DATA

    override fun onGetLanguage(): Array<String> = arrayOf("tur", "TUR", "")

    override fun onGetVoices(): List<Voice> {
        if (!EmaOnnxEngine.available(this)) return emptyList()
        return listOf(Voice(
            VOICE_ID, turkish, Voice.QUALITY_HIGH, Voice.LATENCY_NORMAL,
            false, emptySet()
        ))
    }

    override fun onLoadVoice(voiceName: String?): Int =
        if (voiceName == VOICE_ID && EmaOnnxEngine.available(this))
            TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onIsValidVoiceName(voiceName: String?): Int =
        if (voiceName == VOICE_ID && EmaOnnxEngine.available(this))
            TextToSpeech.SUCCESS else TextToSpeech.ERROR

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopRequested.set(false)
        if (!EmaOnnxEngine.available(this)) {
            callback.error()
            return
        }
        try {
            val active = engine ?: EmaOnnxEngine(this).also { engine = it }
            val rate = (request.speechRate / 100f).coerceIn(0.25f, 4f)
            // Restrict length to keep worst-case allocations reasonable.
            val phrase = request.charSequenceText.toString().take(700)
            val samples = active.render(phrase, rate, phrase.hashCode().toLong(), stopRequested::get)
            if (stopRequested.get()) return
            if (callback.start(EmaOnnxEngine.SAMPLE_RATE, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
                callback.error()
                return
            }
            // audioAvailable must be <= callback.maxBufferSize.
            val chunkSamples = (callback.maxBufferSize / 2).coerceAtLeast(1)
            var position = 0
            while (position < samples.size && !stopRequested.get()) {
                val end = minOf(samples.size, position + chunkSamples)
                val bytes = ByteArray((end - position) * 2)
                var j = 0
                for (i in position until end) {
                    val v = samples[i].toInt()
                    bytes[j++] = (v and 255).toByte()
                    bytes[j++] = ((v ushr 8) and 255).toByte()
                }
                if (callback.audioAvailable(bytes, 0, bytes.size) != TextToSpeech.SUCCESS) {
                    callback.error()
                    return
                }
                position = end
            }
            if (!stopRequested.get()) callback.done()
        } catch (failure: Exception) {
            android.util.Log.e("EmaTTS", "Local synthesis failed; system TTS remains available", failure)
            callback.error()
        }
    }

    override fun onStop() {
        stopRequested.set(true)
    }

    override fun onDestroy() {
        stopRequested.set(true)
        engine?.close()
        engine = null
        super.onDestroy()
    }
}
