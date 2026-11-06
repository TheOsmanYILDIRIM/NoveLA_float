# SESSION_HANDOFF.md — NoveLA EMA Clone

Updated: 2026-11-06 (Europe/Istanbul). Read `AGENTS.md` first.

## Canonical current state
- Repo: `TheOsmanYILDIRIM/NoveLA_float`; mainline: `default` (untouched); active branch: `feature/ema-native-tts-integration`. PR: [#2](https://github.com/TheOsmanYILDIRIM/NoveLA_float/pull/2) OPEN, **DRAFT**, unmerged.
- GitHub Actions: [run #38042003127](https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38042003127) **SUCCESS**. Verified APK compiled and uploaded; `my.novela.clone`, `versionName=1.6.1-clone`, `versionCode=1005`; APK Signature Scheme **v2 valid**, one RSA-3072 test signer; the 4 EMA assets present.
- APK signer cert SHA256: `c0238eb27c05ba1f80a46c776b10b6d5a342d654f4bef94915c302ee4d4ec66e`. APK SHA256: `03f9d2d6a93c9ff612ed8b05326766a065e78bcd1cee60915baa52b0ebea3d3a`.
- Artifact: [NoveLA-EMA-Clone-PUBLIC-TEST-KEY #11665893872](https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38042003127/artifacts/11665893872); ZIP 72,312,119 bytes; expires **2026-10-24 09:48 UTC**. Artifact is a signed Android QA Clone + package/signature/hash evidence, not a production release.
- Successful run source commit: `3fd5e970c39319442c9ba7f724bb26af44125348`; following this, handoff/docs-only commits may advance HEAD. Check actual HEAD before starting work.

## Completed
- Selected verified **FP32** EMA Lightning (34.767 MB total, original pinned weights; Optimum exporter → ORT Extended → Slim). Abandoned INT8/Q4/NF4 after unsuccessful CPU/voice quality trials; do not repeat.
- Added Kotlin ONNX inference adapter and optional direct EMA PCM voice. The former Android `TextToSpeechService` registration was removed on 2026-11-06; normal system TTS engines are retained.
- Added independent Clone build type/package `my.novela.clone` (standard `my.novela` remains installable) and fixed public QA certificate; stable fingerprint/versioning permits updating later **test-signed clones**, not differently signed APKs.
- Fixed Kotlin compile errors (unavailable `RuleBasedNumberFormat`; literal `\\n` characters), then Actions #38042003127 passed; package, signing fingerprint, all 4 embedded model assets checked by actual APK tools.
- Fixed previous clone workflow SDK setup failure and documented no-secrets test-key fallback. `AGENTS.md` and this file added for canonical continuity.

## Decisions and caveats
- **Do not merge PR #2 yet**: no real Android playback test, so in-app synthesis quality and runtime behavior are not proven despite successful APK build.
- Python `normalizer-tr` has NOT been ported; integer spellout experiment was removed because `RuleBasedNumberFormat` was unavailable on the Android SDK. Numbers, punctuation and Turkish case normalization may be wrong; `java.util.Random` does not replicate PyTorch random noise. ONNX input shapes and synthesis duration planning still require on-device testing.
- Public TEST signer under `test-signing/` can be copied by anyone. Only for experimental builds; not Play Store or trusted production rollout. Existing `SIGNING_KEY`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`, `ALIAS` GitHub secrets were all absent at run #38040567713; previous private key archive is NOT used. Do not falsely claim private release signing.
- Older Actions failures (#38040567713 missing secrets, #38041164462 Kotlin compilation, others) are resolved for the *build*; avoid treating them as current blockers.
- No deploy, store release, merge or real-device smoke test occurred in this session.

## Single next action
- Install the verified Clone artifact on a physical Android phone alongside standard NoveLA. Check that `ema-lightning-tr-fp32` appears, speaks a simple Turkish sentence audibly, correctly stops/resumes/advances, and does not crash. Inspect `adb logcat` (tag `EmaTTS`) if voice fails, then make narrowly scoped fixes on PR #2 and rebuild/reverify package and signing. **No extra optimization and no merge before a successful device smoke test.**

## 2026-10-10 direct PCM integration follow-up (unverified)
- Commits: 20bf4bc2 (EmaLocalPlayer AudioTrack), 1d6c058d (reader voice routing), 51bcd2f5 (PCM drain), be403026 (serialize queued paragraphs and stop pending jobs), c52454fd (workflow_dispatch and signer assertion), 269eaadf (build trigger).
- Historical note: originally retained Android TextToSpeechService; superseded by the 2026-11-06 direct-only decision. Direct local voice uses novela:ema-local and the same FP32 ONNX assets. Source changes committed, but **no successful new APK run/artifact or on-device playback is confirmed**. fetch_commit_workflow_runs returned [] for push-trigger commit; that endpoint only reports PR-triggered runs, so [] does not prove no push run exists.
- Open risks: Kotlin compilation, local voice restoration via ReaderTextToSpeech, actual PCM output and cancellation, AudioTrack audio focus/MediaSession integration, word range callbacks (not yet implemented for direct PCM), Android TTS discovery on Android 15. Do not claim complete feature parity.
- Next: inspect Actions workflow page for the latest push run, obtain full failing job logs if failed; fix only confirmed failures, then verify my.novela.clone, QA signer and 4 assets. Test the direct EMA playback route and normal Android system voices on a physical phone. PR #2 remains draft/unmerged.

## 2026-11-06 direct-only TTS correction (source committed; build/device unverified)
- User explicitly removed the separate Android EMA TTS service route; manifest entry, Kotlin service and service engine XML removed. The experimental clone remains on `feature/ema-native-tts-integration`, not `default`.
- PCM player now waits for first synthesized audio, uses bounded producer/consumer buffering for subsequent phrases and serializes ONNX inference. Source-level improvement only; synthesis may still be slower than playback on real ARM devices.
- System TTS readiness is awaited, each multi-engine voice scan publishes atomically, and voice picker observes list mutations. Audio focus now requests MAY_DUCK rather than exclusive GAIN.
- Basic Turkish integer spelling added before vocabulary filtering. This does not implement full Python `normalizer-tr`. EMA still lacks actual word-range callbacks; speed, acoustic quality, numbers and continuity need device tests.
- The last historically successful QA APK is from 2026-10-10, BEFORE this correction, and is expired. No current corrected APK or real-device verification can be claimed until new CI evidence exists.
- Next action: build the clone from this branch, review exact Gradle failure or APK signature/model artifacts, then listen on a physical Android device and inspect `EmaTTS` logs. Keep PR #2 draft and unmerged.

## 2026-11-06 EMA visibility and music coexistence follow-up
- User observed normal Android TTS engines are visible and working but EMA is missing from the list. The EMA entry is deliberately withheld when model assets are absent in the installed APK: check `EmaOnnxEngine.available()` and `EmaTTS`/Timber logs before attributing it to UI only. Production builds without CI-installed assets cannot expose a functioning EMA engine.
- Voice search now matches language, voice ID and engine package. A missing-model diagnostic is emitted to the Android log; this is not an audio-quality validation.
- Narrator no longer requests Android audio focus, and the old synthetic silent AudioTrack/media-session claim in `ReaderTextToSpeech` is removed. Direct EMA playback can therefore coexist with external music without an app-level focus request. External system TTS engines may separately take focus; physical-device test remains necessary.
- Confirm only the `clone` build from the EMA workflow bundles the four ONNX/vocab assets. The normal `default` app and older APKs do not automatically receive new source changes.
- Next: run `ema-clone-build.yml` from current HEAD and verify APK build, actual asset contents and signature, then check EMA selection plus Spotify/YouTube Music coexistence and speech on a phone. No successful post-change CI or device result recorded at authoring time.

## 2026-11-06 model cache reuse correction
- Confirmed the selected FP32 ONNX binaries are NOT committed under `tooling/text_to_speech/src/main/assets/ema/`; pinned converter code is stored in the repo, and the old QA Actions artifact has expired. `feature/ema-selected-quantization` does not contain `dist/ema-selected/manifest.json` as a tracked file.
- Both `.github/workflows/ema-clone-build.yml` and `.github/workflows/ema-android-build.yml` now use the same revision-pinned GitHub Actions cache key for `ema-tools/dist/ema-selected/`.
- Cache hit skips PyTorch installation and ONNX export; both paths verify manifest source revision, recipe, precision, model sizes/digests and vocabulary before packaging. Cache miss regenerates once using the original pinned pipeline, then saves the verified bundle.
- GitHub Actions caches can expire or be evicted: this change improves repeated-build speed but is NOT permanent model storage. A durable release asset or Git LFS transfer requires an explicit new storage decision and a verified original binary source.
- No post-change successful workflow run, model download from cache, signed APK or on-device playback has been confirmed at this writing. Do not merge PR #2 without those validations.
