# MedVoice — Edge AI Medical Tablet Prototype

Android tablet prototype for hands-free clinical data entry. A clinician dictates
commands ("record blood pressure 120 over 80"); on-device models transcribe the
speech and classify the intent, and the app writes the value into the patient
chart. **Fully offline** — the APK bundles its own ASR (whisper.cpp), so neither
audio nor text ever leaves the device (no Google speech services involved).

## Architecture

```
Microphone (AudioRecord, 16 kHz PCM)
  └► SileroVad (ONNX, ~2 MB)          speech probability per 32 ms chunk
       └► UtteranceSegmenter          pre-roll + end-of-speech detection
            └► WhisperEngine          whisper.cpp base.en-q5_1 (57 MB, JNI, offline)
                 └► normalizeNumbers()  "one twenty over eighty" -> "120 over 80"
                      └► stripNumbers() "record blood pressure"  (numbers/punct are OOD)
                           └► MedIntentClassifier (ONNX, BERT-Tiny INT8, 106 intents)
                                ├► confidence < 0.85  -> re-prompt ("please rephrase")
                                ├► critical intent    -> on-screen confirmation dialog
                                └► VitalsExtractor    -> regex + range validation -> chart
```

Key components:

- `speech/WhisperEngine.kt` + `cpp/whisper_jni.cpp` — whisper.cpp (vendored in
  `third_party/whisper.cpp`) with the base.en-q5_1 model; medical
  `initial_prompt`; `audio_ctx` scaled to utterance length for a large speedup
  (with a +128-frame margin — too tight a context makes the decoder loop).
- `speech/SileroVad.kt` — Silero VAD v5 via ONNX Runtime (stateful: recurrent
  state + 64-sample context between chunks).
- `speech/UtteranceSegmenter.kt` — testable state machine: pre-roll ~320 ms,
  end-of-utterance after ~800 ms silence, 12 s hard cap.
- `speech/TranscriptCleaner.kt` — collapses whisper's occasional exact-repeat
  decoding loops.
- `ml/MedIntentClassifier.kt` — ONNX Runtime session over `medical_intent.onnx`
  (quantized BERT-Tiny, ~4.5 MB, trained in the sibling `LocalLLM` project;
  v2 model: 108 commands + a `none` out-of-domain class, trained WITH numbers,
  decimals and whisper-style punctuation — 99.18% of the validation set passes
  the 0.85 threshold, INT8. Training/augmentation: `LocalLLM/augment_medical_v2.py`
  + `train_medical_v2.py`; calibration needs the extended 200-epoch schedule,
  otherwise confidences are too soft for the threshold).
- `ml/WordPieceTokenizer.kt` — BERT WordPiece tokenizer (same scheme as LocalLLM).
- `ml/VitalsExtractor.kt` — spoken-number normalization, number/punctuation
  stripping for classification, and per-vital regex extraction with
  physiological range checks (BP 60-260/30-160, SpO₂ 50-100, temp 30-45 °C /
  90-110 °F, ...).
- `ChartViewModel.kt` — confidence threshold, critical-intent confirmation
  (prescriptions, discharge, transfers, door unlock, code blue), chart state.
- `data/ChartDatabase.kt` + `data/ChartRepository.kt` — Room persistence:
  patients, per-patient vitals history (chart shows the latest per type), and
  the clinical note. Data survives process death; the last opened patient is
  restored on launch (SharedPreferences). Patient switching: tap the header, or
  say "open patient chart" (`emr_open_patient_chart` opens the picker). Three
  demo patients are seeded on first run; new patients are added via
  "+ Add patient" in the picker.
- `ml/PatientMatcher.kt` — voice patient selection: "open patient chart for
  Jane Roe" switches directly to that patient. Known patient names are stripped
  before intent classification (names are OOD like numbers: "open chart for
  robert miles" scores 55% raw, 100% stripped), matched against the patient
  list with Levenshtein tolerance 1 for ASR slips ("row" -> "roe"), and the
  current patient names are appended to whisper's initial_prompt. Ambiguous
  names fall back to the picker.
- `ml/VoiceShortcuts.kt` — exact-match spoken shortcuts handled before the
  intent model: "confirm"/"cancel" resolve the critical-action dialog by voice,
  "undo"/"scratch that" delete the last recorded vital.
- `VitalAlerts.kt` — clinical range validation, distinct from the confidence
  threshold: SpO₂ 82 parses fine and the model is sure, but the value is
  alarming. It is still recorded (data must not be lost), the tile turns red
  with ⚠, the audit log gets a warning, and TTS prompts "say undo if this is a
  mistake".
- Audit trail (`audit` table) — every command, transcript, model confidence,
  rejection, confirmation and chart change is persisted; the on-screen log is a
  Flow over it and survives process death.
- Offline TTS feedback (system TextToSpeech): results, warnings and
  confirmation prompts are spoken; the mic is paused while the app speaks so
  whisper never transcribes its own voice. Whisper meta-annotations like
  "(speaking in foreign language)" are stripped before classification —
  left in, one classified as gen_call_interpreter at 100%.

All 106 recognizable commands are documented in [INTENTS_EN.md](INTENTS_EN.md)
(English) and [INTENTS_RU.md](INTENTS_RU.md) (Russian).

## Why regex for values instead of a second model

The intent model classifies *which* command was spoken; numeric payloads are a
closed, structured domain with known physiological ranges. Regex + range checks
fail predictably (parse or re-prompt), which is the safer failure mode for
clinical data. Spoken-word numbers are handled by a ~50-line normalizer.

History: the v1 model was trained on phrases *without* numbers, and decimals
like "38.5" pushed it far out of distribution — the pipeline stripped numbers
before classification as a workaround. The v2 model is trained with numeric
augmentation (digits, decimals, "over"/"/", trailing punctuation, plus numbers
in non-vitals contexts so digits alone don't signal "vitals"), so numbers now
flow to the classifier directly; only dynamic patient names are still stripped.

## Testing without a human speaker

Test phrases are synthesized with macOS TTS (`say -v Samantha/Daniel
--file-format=WAVE --data-format=LEI16@16000`) and bundled as androidTest
assets (`app/src/androidTest/assets/tts/`). Two levels:

1. **Host smoke test** — the same WAVs through `whisper-cli` (built from
   `third_party/whisper.cpp`) to validate model + prompt before touching
   Android.
2. **Instrumented end-to-end** (`VoicePipelineTest`) — on device, each WAV runs
   the exact production chain (VAD segmentation → whisper JNI → normalization →
   intent classification), asserting the final intent, the extracted value
   (120/80, 38.5 °C, ...), confidence above threshold, and that the
   out-of-domain phrase stays below it. A separate test feeds silence-padded
   audio through `UtteranceSegmenter` to verify end-of-speech detection.

```
./gradlew :app:testDebugUnitTest          # extraction/normalization, JVM
./gradlew :app:connectedDebugAndroidTest  # full voice pipeline on device
```

## Accuracy mode & accent robustness

The 🎯 toggle switches ASR from base.en to **small.en**, measured at roughly
**2.55x the latency** and 2.5x the resident memory in exchange for much better
handling of hard accents. That ratio comes from the instrumentation harness on a
single device, which is known to inflate absolute numbers by 2.16x — the ratio is
about right, the absolute figures behind it are not, and it has not been re-run on
the farm because a 181 MB model does not fit a farm-sized APK. The small model
also exceeds GitHub's file size limit — fetch it once after cloning:
`./tools/fetch_models.sh`.

Accent/intonation robustness is measured by an automated stand
(`tools/accent_stress_test.py`): synthesized speech in 7 accents × speed ×
noise through the full production pipeline. Current numbers and methodology:
[tools/accent_test_out/REPORT.md](tools/accent_test_out/REPORT.md) —
96.1% pass on base.en, GB/AU/ZA at 100% on small.en.

## Running

Open in Android Studio, run on a tablet (landscape) or any emulator. First
launch copies the whisper model out of assets (~1 s). Note: whisper on an
emulator is much slower than on real arm64 hardware.

## Performance

Measured on four SoCs with the same standalone harness ([`:bench`](bench/README.md)),
base.en, end to end per command:

| | Snapdragon 8 Elite<br>Z Fold8 Ultra | Exynos 2400e<br>S24 FE | Dimensity 900<br>M53 | Tensor G3<br>Pixel 8 Pro |
|---|--:|--:|--:|--:|
| p50 | **380 ms** | 565 ms | 937 ms | 1285 ms |
| p99 | 509 ms | 760 ms | 1212 ms | **3545 ms** |
| p99/p50 | 1.34 | 1.35 | 1.29 | **2.76** |

whisper.cpp is 92-98% of that budget on every device; the INT8 intent classifier
costs 0.9-3.6 ms and Silero VAD 8-67 ms. Resident memory 164-241 MB.

Medians spread 3.4x across devices, tails spread 7.0x — and one chip owns the
difference. Three of the four sit at p99/p50 around 1.3; the Tensor G3 is at 2.76,
a tolerable median hiding a 3.5 s hundredth command. It is the same chip that lost
91% of its throughput under sustained load in
[megadetector-int8-ptq](https://github.com/DaedricKnight/megadetector-int8-ptq).

Full per-stage percentiles, memory, and the FP32 -> INT8 accuracy cost:
**[BENCHMARK.md](BENCHMARK.md)**. Reproduce with the `:bench` APK, or locally with
`LatencyBenchmarkTest` — but note the two harnesses disagree by 2.16x on the same
device, and the APK is the representative one.

## Demo phrases

- `record blood pressure 120 over 80` / `blood pressure one twenty over eighty`
- `log heart rate seventy two`, `temperature thirty eight point five`
- `oxygen saturation 97`, `log respiratory rate 18`, `record weight 82 kilograms`
- `pain level 7 out of 10`
- `please discharge the patient` → confirmation dialog
- `make me a sandwich` → rejected below confidence threshold (68%)
