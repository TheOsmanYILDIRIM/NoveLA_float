package com.osman.opusdemo

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** Separate installable EN -> TR translation experiment. */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: ModelStore
    private lateinit var source: EditText
    private lateinit var result: TextView
    private lateinit var status: TextView
    private lateinit var downloadButton: Button
    private lateinit var translateButton: Button
    private var translator: TeradataOnnxTranslator? = null
    private var lastDiagnostic = ""

    private fun label(text: String, size: Float = 16f): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.rgb(30, 35, 42))
            setPadding(0, 12, 0, 12)
        }
    }

    private fun button(label: String, callback: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setOnClickListener { callback() }
        }
    }

    private fun report(message: String) {
        lastDiagnostic += System.currentTimeMillis().toString() + " " + message + "\n"
        runOnUiThread { status.text = message + "\n\n" + store.report() }
    }

    private fun busy(value: Boolean) {
        runOnUiThread {
            downloadButton.isEnabled = !value
            translateButton.isEnabled = !value
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ModelStore(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(Color.rgb(249, 250, 253))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        root.addView(label("OPUS Tiny · English → Türkçe", 25f))
        root.addView(label(
            "Offline Android ONNX experiment\n" +
            "Model stored outside APK and preserved through updates.", 14f
        ))
        source = EditText(this).apply {
            hint = "Enter an English sentence"
            setText("The old house stood at the edge of the forest.")
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP
            setBackgroundColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
        }
        root.addView(source, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        downloadButton = button("Download model once (~97 MB)") {
            busy(true)
            worker.execute {
                try {
                    store.download(::report)
                    report("Ready to translate offline. Model will remain after APK updates.")
                } catch (failure: Exception) {
                    report("DOWNLOAD FAILED: " + failure.javaClass.simpleName +
                        ": " + failure.message)
                } finally {
                    busy(false)
                }
            }
        }
        root.addView(downloadButton)
        translateButton = button("Translate offline") {
            val english = source.text.toString()
            busy(true)
            worker.execute {
                try {
                    check(store.ready()) { "Download model and tokenizer first" }
                    if (translator == null) {
                        val loadStart = SystemClock.elapsedRealtime()
                        translator = TeradataOnnxTranslator(store.model, store.tokenizer)
                        report("Model loaded in " +
                            (SystemClock.elapsedRealtime() - loadStart) + " ms\n" +
                            translator!!.describe())
                    }
                    val (turkish, diagnostics) = translator!!.translate(english)
                    val ram = Debug.getNativeHeapAllocatedSize() / 1_000_000
                    runOnUiThread { result.text = turkish }
                    report("TRANSLATION OK: " + diagnostics + ", nativeHeapMB=" + ram)
                } catch (failure: Exception) {
                    runOnUiThread { result.text = "Translation unavailable. See diagnostics." }
                    report("TRANSLATION FAILED: " + failure.javaClass.simpleName +
                        ": " + failure.message)
                } finally {
                    busy(false)
                }
            }
        }
        root.addView(translateButton)
        root.addView(label("Türkçe sonuç", 18f))
        result = label("No translation yet", 18f)
        root.addView(result)
        root.addView(label("Model & runtime diagnostics", 18f))
        status = label(store.report(), 13f)
        root.addView(status)
        root.addView(button("Copy diagnostics") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("OPUS Tiny Android diagnostics",
                    "OPUS Tiny EN-TR ONNX experiment\n" + store.report() + "\n" +
                        lastDiagnostic)
            )
            report("Diagnostic text copied; paste in chat.")
        })
        report(if (store.ready()) "Saved model found — no download needed"
               else "Model is not downloaded on this device")
    }

    override fun onDestroy() {
        // This instance never deletes the model directory.
        worker.execute { translator?.close(); translator = null }
        worker.shutdown()
        super.onDestroy()
    }
}
