package my.noveldokusha.text_to_speech.ema

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Opt-in exportable, app-private EMA playback trace.
 * Never record book text, URLs, voice snippets or user identifiers.
 * The rolling file survives reader restart and can be copied from TTS settings.
 */
object EmaDiagnostics {
    private const val NAME = "ema-diagnostics.txt"
    private const val MAX_BYTES = 96 * 1024
    private val lock = Any()

    fun record(context: Context, event: String) {
        val safe = event.replace(Regex("[\\r\\n]"), " ").take(240)
        val line = "${System.currentTimeMillis()} elapsed=${SystemClock.elapsedRealtime()} ${safe}\n"
        Log.i("EmaTTS", safe)
        synchronized(lock) {
            runCatching {
                val file = File(context.applicationContext.filesDir, NAME)
                if (file.length() > MAX_BYTES) {
                    val tail = file.readText().takeLast(MAX_BYTES / 2)
                    file.writeText("... older EMA events truncated ...\n" + tail)
                }
                file.appendText(line)
            }.onFailure { Log.w("EmaTTS", "Diagnostic file write failed", it) }
        }
    }

    fun report(context: Context): String = synchronized(lock) {
        val body = runCatching {
            File(context.applicationContext.filesDir, NAME)
                .takeIf { it.isFile }?.readText().orEmpty()
        }.getOrElse { "Cannot read EMA diagnostics: ${it.javaClass.simpleName}" }
        "NoveLA EMA diagnostics (no book text included)\n" + body
    }

    fun clear(context: Context) = synchronized(lock) {
        runCatching { File(context.applicationContext.filesDir, NAME).delete() }
        Unit
    }
}
