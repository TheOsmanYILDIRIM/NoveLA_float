package my.noveldokusha.features.reader.novellex

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * The model archive is imported from local storage only once.
 * Subsequent signed APK updates retain filesDir/novellex and do not redownload
 * the model or require any external app, PC, server or internet connection.
 */
@Composable
internal fun NovelLexDialog(
    bookUrl: String,
    chapterUrl: String,
    chapterParagraphs: () -> List<String>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val glossary = remember(bookUrl) { NovelLexGlossary(context, bookUrl) }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(NovelLexModelStore.isInstalled(context)) }
    var message by remember {
        mutableStateOf(
            if (modelReady) "Yerel GLiNER modeli kurulu; internet gerekmez."
            else "Model henüz kurulmadı. NovelLex model ZIP dosyasını bir kez içe aktar."
        )
    }
    var entries by remember(bookUrl) { mutableStateOf(glossary.read()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            message = "Model doğrulanıyor ve telefonun uygulama klasörüne kuruluyor…"
            scope.launch {
                try {
                    NovelLexModelStore.importZip(context, uri)
                    modelReady = NovelLexModelStore.isInstalled(context)
                    message = if (modelReady) "Model kuruldu. Sonraki APK güncellemelerinde korunur."
                    else "Model kurulamadı; ZIP'i yeniden seç."
                } catch (e: Exception) {
                    Log.e("NovelLex", "Model ZIP import failure", e)
                    modelReady = NovelLexModelStore.isInstalled(context)
                    message = "ZIP içe aktarma hatası: " + (e.message ?: "Bilinmeyen hata")
                } finally {
                    busy = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("NovelLex · Çevrimdışı sözlük") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("GLiNER modeli APK'dan ayrıdır. ZIP'i yalnız ilk kurulumda seçmen yeterli.")
                Text(message)
                Button(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
                    Text(if (modelReady) "Model ZIP'i yeniden içe aktar" else "Model ZIP içe aktar")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && modelReady, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val paragraphs = chapterParagraphs()
                                require(paragraphs.isNotEmpty()) { "Bu bölümde taranacak metin yok." }
                                val mentions = NovelLexOnDevice.extract(context, paragraphs)
                                glossary.replaceChapter(chapterUrl, mentions)
                                entries = glossary.read()
                                message = mentions.size.toString() + " terim bulundu; " +
                                    entries.size.toString() + " farklı terim kaydedildi."
                            } catch (e: Exception) {
                                Log.e("NovelLex", "Local chapter scan failure", e)
                                message = "Tarama hatası: " + (e.message ?: e.javaClass.simpleName)
                            } catch (e: LinkageError) {
                                Log.e("NovelLex", "ONNX Runtime native library unavailable", e)
                                message = "ONNX/JNI yükleme hatası: " + e.javaClass.simpleName + ": " + (e.message ?: e.toString())
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Bölümü tara") }
                    TextButton(enabled = !busy, onClick = {
                        try {
                            glossary.clear()
                            entries = emptyList()
                            message = "Bu kitabın sözlüğü temizlendi. Model korunuyor."
                        } catch (e: Exception) {
                            Log.e("NovelLex", "Glossary clear failure", e)
                            message = "Sözlük temizlenemedi: " + (e.message ?: "bilinmeyen hata")
                        }
                    }) { Text("Sözlüğü temizle") }
                }
                if (busy) CircularProgressIndicator()
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp).fillMaxWidth()) {
                    items(entries, key = { it.first + ":" + it.second }) { row ->
                        Text(row.first + " · " + row.second + " · " + row.third,
                            modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Kapat") } },
    )
}
