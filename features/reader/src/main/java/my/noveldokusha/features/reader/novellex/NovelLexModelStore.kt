package my.noveldokusha.features.reader.novellex

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Separately delivered original GLiNER2.5 Small INT8 model.
 *
 * Nothing over HTTP; the user selects a local ZIP via Android's system picker.
 * Streaming extraction is bound by size, rejects unexpected entries, verifies
 * the trusted manifest bundled with the APK, and commits atomically.
 *
 * Installed files stay in filesDir across APK updates.
 */
internal object NovelLexModelStore {
    private const val MODEL_ID = "fastino/gliner2.5-small-v1"
    private const val MODEL_ZIP_LIMIT = 220_000_000L
    private const val TOKENIZER_LIMIT = 16_000_000L
    private const val MANIFEST_LIMIT = 32_768L
    private val lock = Any()

    internal data class Installed(val graph: File, val tokenizer: File)

    private fun trusted(context: Context): JSONObject =
        context.assets.open("novellex/manifest.json").bufferedReader(Charsets.UTF_8).use {
            JSONObject(it.readText())
        }.also { manifest ->
            require(manifest.getString("model_id") == MODEL_ID)
            require(manifest.getString("precision") == "int8-embeddings")
            require(manifest.getLong("model_bytes") in 25_000_000L..MODEL_ZIP_LIMIT)
            require(manifest.getString("model_sha256").matches(Regex("[a-f0-9]{64}")))
            require(manifest.getString("tokenizer_sha256").matches(Regex("[a-f0-9]{64}")))
        }

    private fun modelDir(context: Context, manifest: JSONObject): File =
        File(context.filesDir, "novellex/" + manifest.getString("model_sha256").take(16))

    /**
     * Cheap file existence/length check for Compose main thread.
     * Cryptographic checks happen during import and again before loading.
     */
    fun isInstalled(context: Context): Boolean = runCatching {
        val manifest = trusted(context)
        val dir = modelDir(context, manifest)
        File(dir, "model.onnx").length() == manifest.getLong("model_bytes") &&
            File(dir, "tokenizer.json").isFile &&
            File(dir, "manifest.json").isFile &&
            File(dir, "tokenizer.json").length() in 1..TOKENIZER_LIMIT
    }.getOrDefault(false)

    /** Called from a background thread before an ONNX session is created. */
    fun validatedFiles(context: Context): Installed {
        val manifest = trusted(context)
        val dir = modelDir(context, manifest)
        val graph = File(dir, "model.onnx")
        val tokenizer = File(dir, "tokenizer.json")
        require(graph.isFile && graph.length() == manifest.getLong("model_bytes") &&
            tokenizer.isFile && tokenizer.length() in 1..TOKENIZER_LIMIT) {
            "Önce Glossary ekranından NovelLex model ZIP dosyasını içe aktar."
        }
        require(sha256(graph) == manifest.getString("model_sha256") &&
            sha256(tokenizer) == manifest.getString("tokenizer_sha256")) {
            "Model doğrulaması başarısız. ZIP dosyasını yeniden içe aktar."
        }
        return Installed(graph, tokenizer)
    }

    suspend fun importZip(context: Context, uri: Uri): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val app = context.applicationContext
            val expected = trusted(app)
            val root = File(app.filesDir, "novellex")
            require(root.isDirectory || root.mkdirs()) { "Model klasörü oluşturulamadı." }
            val target = modelDir(app, expected)
            // Do not erase a valid previously installed model.
            if (isInstalled(app)) {
                val current = runCatching { validatedFiles(app) }.getOrNull()
                if (current != null) return@synchronized
            }
            val staging = File(root, ".import-" + System.nanoTime())
            require(staging.mkdir()) { "Geçici model klasörü oluşturulamadı." }
            try {
                val seen = mutableSetOf<String>()
                var importedManifest: JSONObject? = null
                val input = app.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("ZIP dosyası açılamadı.")
                input.use { stream ->
                    ZipInputStream(stream.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val name = entry.name
                            require(!entry.isDirectory && name in setOf(
                                "model.onnx", "tokenizer.json", "manifest.json"
                            ) && seen.add(name)) {
                                "ZIP içinde beklenmeyen veya tekrar eden dosya: $name"
                            }
                            if (name == "manifest.json") {
                                val bytes = readBounded(zip, MANIFEST_LIMIT)
                                importedManifest = JSONObject(String(bytes, Charsets.UTF_8))
                            } else {
                                val limit = if (name == "model.onnx") {
                                    expected.getLong("model_bytes")
                                } else TOKENIZER_LIMIT
                                val destination = File(staging, name)
                                val digest = MessageDigest.getInstance("SHA-256")
                                var copied = 0L
                                FileOutputStream(destination).use { out ->
                                    val buffer = ByteArray(256 * 1024)
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        copied += count
                                        require(copied <= limit) { "ZIP içindeki dosya izin verilen boyutu aşıyor." }
                                        digest.update(buffer, 0, count)
                                        out.write(buffer, 0, count)
                                    }
                                    out.fd.sync()
                                }
                                val expectedHash = expected.getString(
                                    if (name == "model.onnx") "model_sha256" else "tokenizer_sha256"
                                )
                                require(hashHex(digest.digest()) == expectedHash) {
                                    "$name SHA-256 uyuşmuyor."
                                }
                                if (name == "model.onnx") require(copied == expected.getLong("model_bytes")) {
                                    "Model dosyasının boyutu uyuşmuyor."
                                }
                            }
                            zip.closeEntry()
                        }
                    }
                }
                require(seen == setOf("model.onnx", "tokenizer.json", "manifest.json")) {
                    "ZIP içinde model.onnx, tokenizer.json ve manifest.json bulunmalı."
                }
                val zipped = requireNotNull(importedManifest)
                for (field in listOf("model_id", "model_sha256", "tokenizer_sha256", "precision")) {
                    require(zipped.getString(field) == expected.getString(field)) {
                        "Model manifest uyumsuz: $field"
                    }
                }
                require(zipped.getLong("model_bytes") == expected.getLong("model_bytes"))
                File(staging, "manifest.json").writeText(expected.toString(), Charsets.UTF_8)
                // Final directory rename is atomic within filesDir; keep older
                // installed copies in other checksum directories for rollback.
                if (target.exists()) {
                    val backup = File(root, ".previous-" + System.nanoTime())
                    require(target.renameTo(backup)) { "Eski model taşınamadı." }
                    if (!staging.renameTo(target)) {
                        backup.renameTo(target)
                        error("Model kurulum işlemi tamamlanamadı.")
                    }
                    backup.deleteRecursively()
                } else {
                    check(staging.renameTo(target)) { "Model kurulum işlemi tamamlanamadı." }
                }
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private fun readBounded(stream: ZipInputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(out.size().toLong() + count <= limit) { "ZIP manifest çok büyük." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return hashHex(digest.digest())
    }

    private fun hashHex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
