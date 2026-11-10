# NovelLex Mini v0.1 — public synthetic model comparison

This isolated experiment does not modify the NoveLA or MiruTime applications.

## Data and privacy

Only assistant-written, original synthetic examples are committed (134 sentences, 128 annotated entity spans). **No user EPUBs, real novel excerpts or private v0.2/v0.3 annotation files are published.**

## Previous training and this comparison

The [first CPU/LoRA run (#42707389516)](https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/42707389516) finished eight optimizer steps and saved a PEFT LoRA adapter for fastino/gliner2.5-small-v1.

The current GitHub workflow **does not retrain**: it downloads that exact first-run adapter with the GitHub CLI, checks SHA-256 of its weights and PEFT configuration, then evaluates the **unaltered base model** and **adapted model** with the same eight labels and threshold 0.5. Validation contains 26 synthetic sentences from the frozen original pair-based split, excluding all 108 training samples.

Output artifact: `comparison_report.json`. It contains micro exact-span/label precision, recall and F1, per-label metrics, exact sentence matches, negative-sentence false positives and error offsets. A numerical difference does not imply reliable performance on real fiction.

**Not a real novel benchmark.** AI-generated holdout prose is tiny and stylistically related to training examples. Real Mother of Learning holdout data remain private, require independent label review, and must never be added to a public workflow. Android/ONNX performance is not measured here.
