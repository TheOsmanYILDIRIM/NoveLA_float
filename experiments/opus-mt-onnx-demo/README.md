# OPUS-MT Tiny English → Turkish — Android ONNX demo

Standalone Android experiment. **This is a separate application from NoveLA**.

- Application ID: `com.osman.opusdemo` — never change it on later test builds.
- **Stable signing:** GitHub Actions creates a *release APK* using the repository's existing, publicly documented TEST-ONLY signing certificate. The APK signature remains identical between workflow runs.
- **Versioning:** GitHub Actions run number sets `versionCode`. A newer successful run can install over an older one.
- **Persistent model:** Teradata's ~95 MB INT8 ONNX graph and 2.12 MB tokenizer are downloaded to `Context.filesDir/opus-en-tr-model-v1`, **not `cacheDir`**. A later signed APK update reuses them automatically.
- **Do not uninstall:** Uninstalling or clearing app data can remove the downloaded model. Installing the new signed APK *over* the old one preserves the files.
- The model is not bundled into the APK, which keeps updates much smaller.

## Build / install

1. Open **GitHub → Actions → OPUS Tiny Android demo (stable updateable APK)** on branch `experiment/opus-mt-onnx-updatable-demo`.
2. Run the workflow or use the latest successful push run.
3. Download artifact `OPUS-Tiny-EN-TR-Android-APK`, extract `OPUS-Tiny-EN-TR-updateable.apk` and install.
4. First launch: select **Download model once (~97 MB)**. Future version upgrades will show that saved model is ready, without another download.
5. Type short English text and tap **Translate offline**. The runtime reports inference time or explicit incompatibility errors; **no fake translation fallback**.
6. For a later build, update by installing its **new release APK without uninstalling the old one**. Do not mix with a locally assembled debug APK, as debug signatures differ.

## Limitations

The supplied [Teradata/opus-mt_tiny_eng-tur](https://huggingface.co/Teradata/opus-mt_tiny_eng-tur) uses a graph with custom `BeamSearch` intended for Teradata BYOM. Android ONNX Runtime **may reject this operator**. The experiment uses ONNX Runtime Android 1.23.2, performs actual inference only if supported, and displays errors otherwise. The standard tokenizer JSON reader in the demo is an initial compatibility adapter, not verified equivalent to Teradata's BYOM tokenizer. This experiment is not ready for integration into NoveLA unless Android performance, decoding and quality pass real-device checks.

**Security:** The signer is a PUBLIC test-only key already used for NoveLA EMA Clone test APKs, not a private production signing key. The demo requests internet access only to download model files from Hugging Face. No novel content or app user data is sent to the model provider.

## Update acceptance criteria

- Install run N's signed release, download and verify model files.
- Install run N+1's signed release **over it**, same application ID and signer.
- The version code is higher; the app retains files in `filesDir`.
- Opening the new version shows **Saved model found — no download needed**; translate without internet.
- Clear data/uninstall should be documented as causing data loss, not a normal update.
