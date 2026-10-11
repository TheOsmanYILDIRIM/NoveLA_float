# SESSION_HANDOFF.md — NoveLA EMA Clone

Updated: 2026-10-10 (Europe/Istanbul). Read `AGENTS.md` first.

## Canonical current state
- Repo: `TheOsmanYILDIRIM/NoveLA_float`; mainline: `default` (untouched); active branch: `feature/ema-native-tts-integration`. PR: [#2](https://github.com/TheOsmanYILDIRIM/NoveLA_float/pull/2) OPEN, **DRAFT**, unmerged.
- GitHub Actions: [run #38042003127](https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38042003127) **SUCCESS**. Verified APK compiled and uploaded; `my.novela.clone`, `versionName=1.6.1-clone`, `versionCode=1005`; APK Signature Scheme **v2 valid**, one RSA-3072 test signer; the 4 EMA assets present.
- APK signer cert SHA256: `c0238eb27c05ba1f80a46c776b10b6d5a342d654f4bef94915c302ee4d4ec66e`. APK SHA256: `03f9d2d6a93c9ff612ed8b05326766a065e78bcd1cee60915baa52b0ebea3d3a`.
- Artifact: [NoveLA-EMA-Clone-PUBLIC-TEST-KEY #11665893872](https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38042003127/artifacts/11665893872); ZIP 72,312,119 bytes; expires **2026-10-24 09:48 UTC**. Artifact is a signed Android QA Clone + package/signature/hash evidence, not a production release.
- Successful run source commit: `3fd5e970c39319442c9ba7f724bb26af44125348`; following this, handoff/docs-only commits may advance HEAD. Check actual HEAD before starting work.

## Completed
- Selected verified **FP32** EMA Lightning (34.767 MB total, original pinned weights; Optimum exporter → ORT Extended → Slim). Abandoned INT8/Q4/NF4 after unsuccessful CPU/voice quality trials; do not repeat.
- Added Kotlin ONNX inference adapter and Android `TextToSpeechService` for optional offline EMA voice using existing NoveLA reading infrastructure. Bundles three graphs + vocab in the CI-built APK; regular Android TTS unaffected.
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
- Retained Android TextToSpeechService; direct local voice uses novela:ema-local and same FP32 ONNX assets. Source changes committed, but **no successful new APK run/artifact or on-device playback is confirmed**. fetch_commit_workflow_runs returned [] for push-trigger commit; that endpoint only reports PR-triggered runs, so [] does not prove no push run exists.
- Open risks: Kotlin compilation, local voice restoration via ReaderTextToSpeech, actual PCM output and cancellation, AudioTrack audio focus/MediaSession integration, word range callbacks (not yet implemented for direct PCM), Android TTS discovery on Android 15. Do not claim complete feature parity.
- Next: inspect Actions workflow page for the latest push run, obtain full failing job logs if failed; fix only confirmed failures, then verify my.novela.clone, QA signer and 4 assets. Test both EMA routes on physical phone. PR #2 remains draft/unmerged.

## 2026-10-11 — EMA PCM diagnostics test branch session closure
- Test branch: `test/ema-pcm-diagnostics`; last verified code change `24fa756526ed68698e12d4310b5c4b7346a562b4` (replace failing Android SDK setup action). This handoff commit advances the branch HEAD.
- Standalone `ema-test-app` with package `my.novela.ematest` exists, with WAV generation and direct PCM replay. No confirmed successful build or device result.
- Actions run #38055654511 failed because the pinned model Release was absent; run #38055770854 passed FP32 asset restore/hash checks but failed Android SDK license setup before Kotlin compilation. SDK setup correction committed; its subsequent run status was not established.
- Pinned model Release publication remains **unverified**. The APK workflow can fall back to the old verified Clone artifact #11665893872, which expires 2026-10-24. No new artifact URL may be claimed without confirmation.
- Open next steps: find the post-24fa7565 Actions run, inspect actual job logs, fix build failures, verify APK package/assets/signature, then compare raw WAV and AudioTrack playback on a physical phone.
- Keep mainline `default`, EMA feature branch and draft PR #2 untouched; do not merge without user approval and real-device validation. Work remains OPEN, not completed.
