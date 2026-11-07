# NoveLA EMA Clone — test-build signing policy

The clone installs separately as `my.novela.clone` and is labelled
`NoveLA EMA Clone`. The official app uses `my.novela` and is untouched.

## Current status: fixed PUBLIC QA/test certificate, no secrets

GitHub Actions currently has **no configured signing secrets**:
`SIGNING_KEY`, `KEY_STORE_PASSWORD`, `KEY_PASSWORD`, `ALIAS` were all
empty in run #38040567713. Thus the prior plan to reuse official release
signing credentials could not work.

To fulfill the no-secrets requirement **for testing only**, the workflow
uses a fixed, publicly stored PKCS#12 test keystore under
`test-signing/novela-ema-clone-TEST-ONLY.p12.base64`.

- Alias: `novela-ema-clone-demo`
- Certificate SHA-256: `C0:23:8E:B2:7C:05:BA:1F:80:A4:6C:77:6B:10:B6:D5:A3:42:D6:54:F4:BE:F9:49:15:C3:02:EE:4D:4E:C6:6E`
- Package ID: `my.novela.clone`
- Version code: `1000 + github.run_number`, increasing for subsequent builds

**Security warning:** The testing key is PUBLIC. Anybody can sign APKs
with it. It is not suitable for Play Store production distribution,
sensitive user data, trusted update channels, or cross-app signature
permissions. Install only artifacts from verified project runs; check
the artifact origin before installation.

If the project later needs a private/release signing identity, configure
a private signing key once with GitHub secrets or an organization-central
signing workflow. Changing keys for an already-installed clone cannot
be treated as a normal update; a migration/reinstall may be needed.

The earlier privately backed dedicated key archive is NOT used by the
current test workflow. Do not mix that key with this public test key.

The workflow also checks the APK package, signing fingerprint, SHA256
and four FP32 model assets. An Android device speech smoke test is
still required.
