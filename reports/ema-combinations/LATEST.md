# EMA Lightning FP32 — chained optimization benchmark

- Result: **success**
- Run: https://github.com/TheOsmanYILDIRIM/NoveLA_float/actions/runs/38034789614
- Log: [latest.log](latest.log)
- Platform: Linux x86-64 Actions CPU, not Android ARM64
- No INT8/FP16 or model pruning; numerical waveform parity required

## Graph combinations

| Candidate | Result | Size (MB) | Minimum SNR (dB) | Sound 1×32×64 (ms) |
|---|---|---:|---:|---:|
| baseline | passed | 34.981 | 81.126 | 17.989 |
| slim_basic | passed | 78.557 | 81.496 | 15.811 |
| slim_extended | passed | 78.512 | 80.871 | 15.468 |
| slim_all | passed | 78.512 | 80.871 | 15.409 |
| basic_slim | passed | 34.871 | 81.496 | 15.816 |
| extended_slim | passed | 34.767 | 83.731 | 15.48 |
| all_slim | passed | 34.767 | 83.731 | 15.487 |
| slim_extended_ort | passed | 79.134 | 80.871 | 15.125 |
| extended_slim_ort | passed | 35.450 | 80.871 | 15.402 |
| slim_all_ort | passed | 79.134 | 80.871 | 15.388 |

## Per-chain CPU threads

| Candidate | 1 thread | 2 threads | 4 threads | 8 threads |
|---|---:|---:|---:|---:|
| baseline | — | — | — | — |
| slim_basic | 18.031 | 14.37 | 15.996 | 48.066 |
| slim_extended | 17.635 | 14.007 | 15.373 | 53.157 |
| slim_all | 17.762 | 13.89 | 15.568 | 33.916 |
| basic_slim | 17.998 | 14.291 | 15.949 | 54.237 |
| extended_slim | 18.136 | 14.077 | 15.926 | 48.176 |
| all_slim | 17.853 | 14.155 | 15.676 | 54.876 |
| slim_extended_ort | 17.528 | 13.735 | 15.374 | 50.825 |
| extended_slim_ort | 17.801 | 13.989 | 15.457 | 54.152 |
| slim_all_ort | 17.896 | 13.792 | 15.22 | 63.577 |

## Rejected or failed combinations


Detailed results and successful model graphs are available in the Actions artifact.
SNR compares the same output against original PyTorch, not MOS/WER.

