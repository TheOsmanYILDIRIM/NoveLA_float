package my.novela.ematest

import android.app.Activity
import android.os.Bundle
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.widget.*
import my.noveldokusha.text_to_speech.ema.EmaOnnxEngine
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var samples: ShortArray? = null
    private var player: AudioTrack? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        val input = EditText(this).apply { setText("Merhaba! Bugün hava çok güzel. NoveLA ile kitap okumaya hazır mısın?"); minLines = 3 }
        val status = TextView(this).apply { text = "Ready. No synthesis yet." }
        val generate = Button(this).apply { text = "1. Generate WAV" }
        val play = Button(this).apply { text = "2. Play generated PCM"; isEnabled = false }
        val stop = Button(this).apply { text = "Stop" }
        root.addView(input); root.addView(generate); root.addView(play); root.addView(stop); root.addView(status)
        setContentView(root)
        generate.setOnClickListener {
            generate.isEnabled = false
            status.text = "Generating..."
            val text = input.text.toString()
            thread {
                try {
                    val started = android.os.SystemClock.elapsedRealtime()
                    val pcm = EmaOnnxEngine(this).use { it.render(text, 1f, 42L) { false } }
                    require(pcm.isNotEmpty()) { "No audio generated" }
                    val file = File(getExternalFilesDir(null), "ema-test.wav")
                    FileOutputStream(file).use { stream ->
                        val dataSize = pcm.size * 2
                        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                        header.put("RIFF".toByteArray()); header.putInt(36 + dataSize)
                        header.put("WAVEfmt ".toByteArray()); header.putInt(16)
                        header.putShort(1); header.putShort(1); header.putInt(48000)
                        header.putInt(96000); header.putShort(2); header.putShort(16)
                        header.put("data".toByteArray()); header.putInt(dataSize)
                        stream.write(header.array())
                        val bytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
                        pcm.forEach { bytes.putShort(it) }
                        stream.write(bytes.array())
                    }
                    samples = pcm
                    val ms = android.os.SystemClock.elapsedRealtime() - started
                    runOnUiThread { status.text = "Saved: ${file.absolutePath}\nRender: ${ms}ms, audio: ${pcm.size / 48}ms"; play.isEnabled = true }
                } catch (e: Exception) {
                    runOnUiThread { status.text = e.stackTraceToString() }
                } finally { runOnUiThread { generate.isEnabled = true } }
            }
        }
        play.setOnClickListener {
            val pcm = samples ?: return@setOnClickListener
            thread {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(maxOf(16384, AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                    .setTransferMode(AudioTrack.MODE_STREAM).build()
                player = track
                try {
                    track.play()
                    var pos = 0
                    while (pos < pcm.size && player === track) {
                        val n = track.write(pcm, pos, pcm.size - pos, AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        pos += n
                    }
                    while (player === track && track.playbackHeadPosition.toLong() < pos) Thread.sleep(20)
                } finally {
                    if (player === track) player = null
                    runCatching { track.stop(); track.release() }
                }
            }
        }
        stop.setOnClickListener { val old = player; player = null; runCatching { old?.pause(); old?.flush(); old?.release() } }
    }
}
