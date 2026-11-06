package com.osman.opusdemo

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Files are saved in app filesDir, not cacheDir, and survive same-signer APK updates. */
class ModelStore(context: Context) {
    private val folder = File(context.applicationContext.filesDir, "opus-en-tr-model-v1")
    val model = File(folder, "model-int8.onnx")
    val tokenizer = File(folder, "tokenizer.json")

    private fun valid(file: File, min: Long) = file.isFile && file.length() >= min
    fun ready() = valid(model, 50_000_000L) && valid(tokenizer, 400_000L)
    fun report(): String =
        "Model file: " + (if (valid(model, 50_000_000L)) "READY" else "NOT DOWNLOADED") +
        " (" + model.length() / 1_000_000L + " MB)\n" +
        "Tokenizer: " + (if (valid(tokenizer, 400_000L)) "READY" else "NOT DOWNLOADED") +
        " (" + tokenizer.length() / 1000L + " KB)"

    private fun fetch(path: String, target: File, minBytes: Long, report: (String) -> Unit) {
        if (valid(target, minBytes)) {
            report("Reusing downloaded " + path)
            return
        }
        folder.mkdirs()
        val partial = File(folder, target.name + ".partial")
        partial.delete()
        val url = URL("https://huggingface.co/Teradata/opus-mt_tiny_eng-tur/resolve/main/" +
            path + "?download=true")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = true
        try {
            check(connection.responseCode == 200) {
                "HTTP " + connection.responseCode + " while downloading " + path
            }
            val expected = connection.contentLengthLong
            var bytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val chunk = ByteArray(65536)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        output.write(chunk, 0, count)
                        bytes += count
                        if (bytes % 5_242_880L < chunk.size) {
                            report(path + ": " + bytes / 1_000_000L + " MB")
                        }
                    }
                    output.fd.sync()
                }
            }
            check(bytes >= minBytes) { "Incomplete " + path + ": " + bytes }
            if (expected > 0L) check(bytes == expected) { "Size mismatch for " + path }
            check(partial.renameTo(target)) { "Could not finalize download" }
            report("Saved " + path + ": " + bytes / 1_000_000L + " MB")
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    fun download(report: (String) -> Unit) {
        fetch("onnx/model-int8.onnx", model, 50_000_000L, report)
        fetch("tokenizer.json", tokenizer, 400_000L, report)
        check(ready()) { "Required model files are missing" }
    }
}
