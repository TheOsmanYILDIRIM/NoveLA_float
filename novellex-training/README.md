# NovelLex Mini v0.1 — public CPU training experiment

Independent research experiment for a GLiNER2.5 Small **LoRA adapter**. Does NOT alter NoveLA or MiruTime app code.

## Data safety

The versioned seed is \`synthetic_seed_v01.jsonl.gz\` (gzip of 134 original, assistant-authored synthetic fantasy sentences). It has 128 annotated spans and includes hard negatives. No user-provided EPUB, copyrighted novel excerpt, or private v0.2/v0.3 annotation is included.

\`pilot.py validate\` checks the dataset SHA-256, counts and offsets, then creates 108 training and 26 provisional validation examples. Each positive/negative pair stays in one split. This **is not a real-novel benchmark**, nor a claim of an independently human-verified corpus.

## First training

Pushes on branch \`experiment/novellex-mini-public-training-20261110\` trigger this workflow. GitHub's standard CPU runner installs GLiNER2 2.1.0 and downloads \`fastino/gliner2.5-small-v1\` (Apache-2.0). It performs only **8 CPU LoRA optimization steps**, then requires a real adapter checkpoint. Artifacts are attached to the Actions run; no release, app build, secrets, or application changes.

The adapter requires a compatible original GLiNER2.5 base model. Public sample accuracy is not sufficient to judge any real novel. Future evaluation against the untouched *private* novel holdout must be done away from public GitHub and requires more independently adjudicated labels.
