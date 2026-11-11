# NovelLex — Offline original GLiNER2.5 Small for NoveLA

## Deployment contract

Everything runs on the Android phone. No Python companion, Termux, local PC,
API keys, remote HTTP endpoint or network access at reading/inference time.

The original model is fastino/gliner2.5-small-v1 (Apache-2.0), not the separate
NovelLex-trained LoRA. The Android runtime consumes the third-party boundary
ONNX export nicolasembleton/gliner2.5-small-v1-onnx (288.5 MB model) using
onnxruntime-android. Conversion is not additional fine-tuning.
- https://huggingface.co/fastino/gliner2.5-small-v1
- https://huggingface.co/nicolasembleton/gliner2.5-small-v1-onnx
- https://github.com/Pastel-Org/gliner2.5-onnx-webgpu

## Implementation

- Model and tokenizer.json are downloaded at APK BUILD time ONLY by
  novellex/prepare_assets.py, checked by SHA-256 and packaged under
  assets/novellex. They are not downloaded by Android.
- The first local scan verifies the assets and atomically copies the graph into
  application-private storage for ONNX Runtime. This needs about 300 MB of
  additional disk and sufficient RAM for a 74M-parameter model.
- Kotlin runtime builds the [P]/[E]/[SEP_TEXT] GLiNER schema, runs a Unigram
  tokenizer, constructs ONNX tensors, performs CPU inference, and decodes
  reranked pair_indices, pair_logits and pair_valid span candidates.
- Reader More > NovelLex analyzes only the loaded chapter when tapped and
  replaces that chapter's rows in the existing local per-book glossary. Raw
  chapter text is not persisted or transmitted. Previous glossary rows remain.

## Build

CI and Gradle both invoke: python3 novellex/prepare_assets.py

The build fails if the original offline assets cannot be downloaded or checked.
An APK with missing weights must not pretend to offer offline inference.
A full APK may exceed 300 MB.

For audited production builds pin a model export commit with environment variable
NOVELLEX_MODEL_REVISION instead of the current main branch. The generated
manifest records its revision, model SHA256 and tokenizer SHA256.
Production packaging still needs a fixed revision and verified hash.

Verify existing assets without network:
python3 novellex/prepare_assets.py --verify

## Verification still required

This code has not yet been proven on real Android ARM64 hardware.
The published export targets ONNX Runtime Web. Confirm Android ONNX CPU
compatibility, HF tokenizer normalization parity against Python, output-score
parity, memory/latency and end-to-end glossary accuracy on a real device.
Until then it is EXPERIMENTAL; compilation alone is not inference proof.

This model is English-only; quality for Turkish source text is unverified.
Predicted fantasy terms are suggestions, not translations.
