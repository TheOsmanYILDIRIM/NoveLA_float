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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
    val prefs = remember(context) { context.getSharedPreferences("novellex_settings", 0) }
    val glossary = remember(bookUrl) { NovelLexGlossary(context, bookUrl) }
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "http://127.0.0.1:8765") ?: "") }
    var token by remember { mutableStateOf("") } // Never persist credentials.
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Runs original GLiNER2.5 Small on your trusted companion, not on this phone.") }
    var entries by remember(bookUrl) { mutableStateOf(glossary.read()) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("NovelLex · Original GLiNER") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    endpoint, { endpoint = it }, label = { Text("Local companion URL") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    token, { token = it }, label = { Text("Companion access token") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Opt-in: chapter text is transmitted to your own configured companion. Use only a trusted LAN.")
                Text(message)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy, onClick = {
                        val paragraphs = chapterParagraphs()
                        busy = true
                        scope.launch {
                            try {
                                // Store only the server address; raw chapter content is sent only on request.
                                NovelLexClient.validateEndpoint(endpoint)
                                prefs.edit().putString("endpoint", endpoint.trim()).apply()
                                val result = NovelLexClient.extract(endpoint, token, paragraphs)
                                glossary.replaceChapter(chapterUrl, result)
                                entries = glossary.read()
                                message = "Found ${result.size} mentions. Stored ${entries.size} distinct glossary terms. Re-scanning replaces this chapter."
                            } catch (e: Exception) {
                                message = "NovelLex error: ${e.message ?: "companion unavailable"}"
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Scan loaded chapter") }
                    TextButton(enabled = !busy, onClick = {
                        glossary.clear(); entries = emptyList(); message = "This book's glossary cleared."
                    }) { Text("Clear") }
                }
                if (busy) CircularProgressIndicator()
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(entries, key = { it.first + ":" + it.second }) { row ->
                        Text("${row.first} · ${row.second} · ${row.third}",
                            modifier = Modifier.padding(vertical = 3.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") } },
    )
}
