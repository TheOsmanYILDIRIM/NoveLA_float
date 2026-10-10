# NoveLA EMA Clone — install separately, update without re-entering secrets

Clone application ID: `my.novela.clone` (ordinary NoveLA remains `my.novela`).
Clone label: NoveLA EMA Clone.

## No new GitHub secrets

The clone build now uses the **existing NoveLA release signing credentials**
from the current repository, already referenced by the standard
`.github/workflows/buildRelease.yml`:

- `SIGNING_KEY`
- `KEY_STORE_PASSWORD`
- `KEY_PASSWORD`
- `ALIAS`

The same signing certificate may sign both official and clone variants,
but **Android does not confuse them because the application IDs are distinct**.
Future clone builds reuse that same certificate and increment clone
`versionCode = 1000 + github.run_number`. This lets an updated clone APK
replace an older *clone* without deleting its app data.

No credential is committed or printed, and no random per-build signing
identity is permitted. If the pre-existing credentials have not actually been
configured, the workflow fails with an explicit message.

The previously generated **separate clone key** is not used by this path.
Do not install a clone signed with that distinct certificate if expecting it to
update a clone signed by the standard NoveLA release certificate. Changing
signing certificates later requires an Android-compatible key rotation process.

## Build

Workflow: `.github/workflows/ema-clone-build.yml`.
To run: change `.github/ema-clone-trigger.txt` on the integration branch.
Result artifact: `NoveLA-EMA-Clone-existing-key`.

Verification checks:
- The new `my.novela.clone` application ID
- APK signed with the pre-existing release key
- APK contains FP32 `text_stage.onnx`, `sound_stage.onnx`,
  `decoder.onnx`, `vocab.json`
- SHA256 published in artifact

## Multiple repositories

GitHub **organization** Actions secrets can be shared to selected/all
repositories from the organization's Secrets and variables → Actions page.
Personal-account repositories do not gain that cross-repository scope.
For separate personal repos without an organization, a dedicated private
**central build/signing repository** can be configured once and used to
build source checked out from the other repositories by workflow dispatch.
That requires an explicit, carefully scoped access design and is not set up here.

Full on-device Turkish speech and compatibility testing remain pending.
