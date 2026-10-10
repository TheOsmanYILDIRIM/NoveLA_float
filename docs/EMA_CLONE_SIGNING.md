# NoveLA EMA Clone — independent Android install and signing

This branch includes `:app:assembleClone`, Android application ID
`my.novela.clone` and app label `NoveLA EMA Clone`.
It does **not** overwrite the regular `my.novela` or debug `my.novela.debug`.

## Permanent signing key

A dedicated RSA-3072 PKCS#12 signing keystore was generated once.
It is **never committed** to the public repo.

- Alias: `novela-clone`
- Certificate SHA-256: `C4:65:30:9B:E2:43:6C:22:75:4D:57:FD:B4:AF:9F:6D:C2:F5:24:8A:41:1E:5F:F9:1B:52:81:70:A1:9B:64:B2`
- The private key and passwords are supplied only as a secure, user-downloaded backup archive.

GitHub Actions **repository secrets** that must be created by the repo owner:
`NOVELA_CLONE_KEYSTORE_BASE64`, `NOVELA_CLONE_STORE_PASSWORD`,
`NOVELA_CLONE_KEY_PASSWORD`. Their values are in the owner-only archive
`novela_ema_clone_kalici_imza.zip`.

Open repository Settings → Secrets and variables → Actions → New repository
secret and add each of the three values. No secrets are visible in the repo.

### Update stability

`NOVELA_CLONE_VERSION_CODE` is calculated as `1000 + github.run_number`
when the clone workflow runs. For every future workflow run, that increases
monotonically. Retain the package ID and certificate fingerprint forever.

A different key or lower/equal Android versionCode will **not** update
an existing clone APK in place.

## Build and validation

Workflow: `.github/workflows/ema-clone-build.yml`.
Trigger: update `.github/ema-clone-trigger.txt`.

The workflow fails closed if the key secrets are absent; it **never** produces
an arbitrary fresh-key APK. Once secrets exist, it:

1. Rebuilds verified EMA FP32 ONNX from pinned Optimum source.
2. Copies complete model/vocab into Android assets.
3. Creates signed `:app:assembleClone` APK.
4. Confirms `my.novela.clone`, validates APK signature against pinned
   certificate digest, checks all four model assets, and uploads the APK.

The official app is unchanged. Full end-to-end Turkish device speech still
needs practical playback testing after installation.
