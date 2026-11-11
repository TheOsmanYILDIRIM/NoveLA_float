# NovelLex — external model ZIP, small updateable ARM64 APK

* GLiNER2.5 Small original model: fastino/gliner2.5-small-v1.
* The Android app uses ONNX Runtime locally and **never downloads the model**.
* The APK contains only a tiny trusted model manifest + ONNX Runtime native
  libraries, **not** model.onnx or tokenizer.json.
* The user downloads NovelLex_GLiner25_INT8_Model_Offline.zip **once** and
  opens Reader > More > NovelLex > Model ZIP içe aktar, selecting the ZIP
  using Android's system document picker.
* ZIP must contain exactly three files at root:
  model.onnx, tokenizer.json and manifest.json.
* 141055262-byte int8-embeddings model has SHA256
  de4e847915505b8c991926ff6511b2085d36f88fa9b18d5f085828e1d2080282.
  Tokenizer SHA256:
  cbc8ae6037812709c9c26f2a160f8dc48b0440bcb79c8141804259ae2d6adac3.
* Archive contents are checksum-verified against the **APK-bundled trusted
  manifest**, extracted with size limits and no unsafe ZIP paths, and moved
  into the application's private filesDir/novellex directory.
* Updating the **same application ID with the same signature** keeps the
  model, book data and glossary; uninstalling or clearing application data
  deletes the installed model.
* Previous malformed glossary JSON does not cause an uncaught exception in
  the Compose screen; the original corrupt raw value is preserved.
* Chapter scanning is manual. Model status and errors are surfaced in UI.

## Builds and acceptance

Use the isolated workflow .github/workflows/novellex-split-release.yml.
Builds a signed, R8-minified, ARM64-only release with
-PnovellexSplitModel -PnovellexArm64Only; versionCode 40 is greater than
previous integrated versionCode 39. Existing release signing secrets stay in
GitHub, never in ZIP or APK beyond the signature.

Build checks: no model.onnx or tokenizer.json inside APK, only one ARM64 ABI,
apksigner verify, embedded trusted manifest, and exactly two separately
downloadable release assets (APK + model ZIP).

Real phone reproduction and any logcat stacktrace are still **unverified**.
A crash caused by a native ONNX Runtime / memory pressure is NOT guaranteed
resolved by guarding JSON reads. On-phone inference, memory, first-run ZIP
import and update-preserved model need physical Android acceptance tests.
