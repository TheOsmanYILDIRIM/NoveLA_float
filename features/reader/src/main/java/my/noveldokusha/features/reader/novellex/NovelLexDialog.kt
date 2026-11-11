package my.noveldokusha.features.reader.novellex

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
    var message by remember { mutableStateOf("Original GLiNER2.5 Small runs privately on this device. No internet or companion.") }
    var entries by remember(bookUrl) { mutableStateOf(glossary.read()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("NovelLex · Offline GLiNER") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Scan the loaded chapter offline. Model predictions are suggestions; verify terminology before translating.")
                Text(message)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        val paragraphs = chapterParagraphs()
                        busy = true
                        scope.launch {
                            try {
                                val mentions = NovelLexOnDevice.extract(context, paragraphs)
                                glossary.replaceChapter(chapterUrl, mentions)
                                entries = glossary.read()
                                message = "Found " + mentions.size + " mentions; " + entries.size +
                                    " distinct local terms. Re-scanning replaces this chapter."
                            } catch (e: Exception) {
                                message = "Offline GLiNER error: " + (e.message ?: "model unavailable")
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Scan loaded chapter") }
                    TextButton(enabled = !busy, onClick = {
                        glossary.clear()
                        entries = emptyList()
                        message = "This book's glossary cleared."
                    }) { Text("Clear") }
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
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } },
    )
}
