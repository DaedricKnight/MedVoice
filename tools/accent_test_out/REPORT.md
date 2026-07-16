# Accent & Intonation Stress Test — Report (2026-07-16)

Harness: `tools/accent_stress_test.py` — 10 clinical phrases × 7 macOS TTS
accents (US/GB/AU/IE/IN/ZA + aged-GB) × 4 conditions (140/180/220 wpm, plus
180 wpm with white noise at SNR 10 dB) = 280 clips through the exact production
pipeline: whisper.cpp → transcript sanitization + name stripping → INT8 BERT
intent classifier → 0.85 confidence gate.

Verdicts: **PASS** (correct intent above gate), **REJECTED** (below gate or
`none` — safe, app re-prompts), **WRONG_INTENT** (confident wrong — dangerous).

## Headline results

| Configuration | Pass | Rejected (safe) | Wrong (dangerous) |
|---|---|---|---|
| intent v2 + base.en (baseline) | 256/280 (91.4%) | 13 | **11** |
| intent v2.1 + base.en (charge/blue augmentation) | 260/280 (92.9%) | 14 | 6 |
| **intent v2.2 + base.en (shipped)** | **269/280 (96.1%)** | 7 | **4** |
| intent v2.1 + small.en (accuracy mode) | 269/280 (96.1%) | 9 | **2** |

## Per-accent pass rate

| Accent | v2 + base | v2.2 + base | v2.1 + small |
|---|---|---|---|
| US | 100% | 100% | 98% |
| GB | 95% | **100%** | 100% |
| AU | 92% | 95% | **100%** |
| IE | 98% | **100%** | 98% |
| IN | 92% | **100%** | 95% |
| ZA | 92% | 95% | **100%** |
| GB-aged | 70% | **82%** | 82% |

## What was fixed and how

1. **"chart" → "charge"** (7/11 dangerous failures in baseline; non-US accents,
   classified `gen_locate_colleague` at 99%). Fixed by ASR-confusion
   augmentation: "open patient charge/chard for…" trained as
   `emr_open_patient_chart`.
2. **"pain" → "Jane"/"15" in noise**: name-stripping then deleted the keyword,
   leaving "level 7 out of 10" → misrouted. Fixed by training the bare
   pain-scale fragment "level N out of 10" as `symptom_log_pain_level`.
3. **"code blue" → "cold/cole blue"**: augmented as `emrgy_call_code_blue`
   (the intent stays confirmation-protected in the app).

## Command-phrase A/B (candidates.txt)

One-word commands are phonetically fragile; two-word phrases survive accents:

| Action | Weak phrase | Robust phrase |
|---|---|---|
| Undo | "undo" — **7%** | "scratch that" — **96%** |
| Confirm | "confirm" — 64% | "yes confirm" — **96%** |
| Cancel | "cancel" — 64% ("Pencil", "Cancer") | "negative" — **96%** |
| Open chart | "…chart for…" — 68% | "…file for…" — **89%** |

The app now teaches the robust phrases via TTS prompts and accepts all synonyms.

## Residual known failures (v2.2 + base, 4 cases)

All on the hardest conditions (aged voice and/or noise): heavily garbled
transcripts ("Cole Cau-Doo", "Add a mutation", "Do the lost entry") land on
benign intents. Mitigation for accent-heavy deployments: the in-app
**accuracy mode (🎯, whisper small.en)** — GB/AU/ZA at 100%, aged voice +10 pp.

## Reproduce

```
python3 tools/accent_stress_test.py                       # base.en
MV_WHISPER_MODEL=app/src/main/assets/ggml-small.en-q5_1.bin \
MV_OUT_DIR=tools/accent_test_out/small python3 tools/accent_stress_test.py
python3 tools/candidate_phrases_test.py                   # phrase A/B
```

Raw data: `results.csv` (baseline), `v21_base/`, `v21_small/`, `v22_base/`,
`candidates.txt`.
