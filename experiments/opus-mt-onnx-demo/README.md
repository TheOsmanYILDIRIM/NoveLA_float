# OPUS-MT Tiny EN→TR ONNX — standalone Android experiment

**Independent demo app**, package `com.osman.opusdemo`, under `experiments/opus-mt-onnx-demo`. It does not alter the NoveLA app, its text translator, or EMA audio.

Model: [Teradata/opus-mt_tiny_eng-tur](https://huggingface.co/Teradata/opus-mt_tiny_eng-tur) (Apache-2.0). **INT8 ONNX ≈95 MB**, FP32 ≈177 MB; NOT the upstream ≈20 MB TFLite. It is one Marian encoder+decoder graph with `com.microsoft::BeamSearch` inserted for Teradata BYOM. **No assurance it works in ONNX Runtime Android**. The experiment intentionally surfaces unsupported operator/runtime errors instead of falling back to fabricated translations.

## Run
1. GitHub Actions → `OPUS Tiny Android demo` → **Run workflow** on `experiment/opus-mt-onnx-android-demo`, download `OPUS-ONNX-Demo-Debug-APK` if workflow succeeds. Or with Android SDK/JDK 17: from repository root run `./gradlew -p experiments/opus-mt-onnx-demo :app:assembleDebug`. The repo's Gradle wrapper is reused; no nested wrapper.
2. Install the **separate** APK. Tap **Download INT8 model + tokenizer** (first use downloads about 97 MB from Hugging Face; Wi-Fi recommended).
3. Type a short English sentence (up to 256 input tokens), tap **Translate offline**. If the graph and tokenizer are supported, Turkish output, inference milliseconds, token counts and memory usage appear; otherwise the exact stage and failure are displayed.
4. To share failure text, tap **Copy diagnostics**. Downloaded files are app-private, not committed or shipped in APK. Translation after the first download uses no network.

## Model/runtime risks
- This Teradata artifact is specifically exported for SQL `mldb.ONNXSeq2Seq`, not documented as Android-compatible. Graph creation may fail due to `BeamSearch` or unsupported operators; that is a valid compatibility result, **not a successful translator**.
- `tokenizer.json` is a Teradata-repacked Marian tokenizer. This prototype supports standard HuggingFace `model`/vocab Unigram or BPE JSON forms, and checks for unsupported shapes. If Teradata's structure differs, it fails explicitly until a matching tokenizer implementation is added. Approximate tokenization is not evidence of semantic parity.
- The demo uses greedy/token beam defaults provided by the ONNX graph and caps output to 96 tokens; it intentionally does not attempt to recreate Teradata SQL BYOM features. First device run must verify both tokenization and output against upstream official Transformers examples.
- No NoveLA integration, no production quality claim, no prebundled weights, no API keys.
