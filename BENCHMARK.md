# MedVoice on-device latency, memory and quantization cost

Hardware: **Pixel 8 Pro (Tensor G3), Android 17, arm64-v8a, 9 cores.**
Build: `debug` — the native library is compiled `-O3 -DNDEBUG` (`voice-core/src/main/cpp/CMakeLists.txt`
overrides the debug flags), so whisper.cpp runs fully optimized.

Harness: `app/src/androidTest/.../LatencyBenchmarkTest.kt`, running the exact production
chain used by `VoicePipelineTest` — Silero VAD → whisper.cpp → number normalization /
stripping → INT8 BERT intent classification. Fixtures are the 14 bundled TTS command
clips (24.6 s of audio, mean 1.76 s per command). 1 warmup pass + 8 timed passes,
**n = 112 per stage per model**.

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.class=com.khateev.medvoice.LatencyBenchmarkTest \
  -Pandroid.testInstrumentationRunnerArguments.warmup=1 \
  -Pandroid.testInstrumentationRunnerArguments.repeats=8
```

## Latency, milliseconds

### base.en-q5_1 (57 MB)

| stage | p50 | p90 | p95 | p99 | max |
|---|--:|--:|--:|--:|--:|
| Silero VAD (whole utterance) | 113.9 | 141.9 | 153.2 | 165.8 | 377.6 |
| whisper.cpp ASR | 2650.2 | 3329.6 | 3539.8 | 3831.5 | 5064.4 |
| normalize + sanitize | 2.0 | 2.5 | 2.8 | 3.5 | 4.0 |
| BERT intent classifier | 10.5 | 17.8 | 20.1 | 22.9 | 30.0 |
| **end to end** | **2770.6** | **3476.3** | **3678.6** | **3972.1** | **5209.7** |

Model load: whisper 601 ms, classifier 373 ms.

### small.en-q5_1 (181 MB)

| stage | p50 | p90 | p95 | p99 | max |
|---|--:|--:|--:|--:|--:|
| Silero VAD (whole utterance) | 112.4 | 143.2 | 158.6 | 183.6 | 203.5 |
| whisper.cpp ASR | 6760.9 | 8983.1 | 9404.2 | 10409.3 | 10933.6 |
| normalize + sanitize | 1.5 | 2.1 | 2.4 | 3.4 | 3.5 |
| BERT intent classifier | 9.0 | 17.3 | 19.7 | 21.8 | 26.2 |
| **end to end** | **6899.2** | **9079.0** | **9540.2** | **10504.0** | **11035.5** |

Model load: whisper 1165 ms, classifier 389 ms.

## What the numbers say

**ASR is the entire budget.** whisper.cpp is 95.7% of end-to-end on base.en and 98.0%
on small.en. The intent classifier — the part that was quantized and tuned — costs
10.5 ms at p50. Optimizing it further buys nothing; every millisecond worth chasing is
in the decoder.

**Real-time factor is above 1 on this SoC.** Mean command audio is 1.76 s; base.en
transcribes it in 2.65 s (RTF 1.51×) and small.en in 6.76 s (RTF 3.85×). On a Tensor G3
this pipeline is slower than the speech it processes.

**small.en costs 2.55× the latency and 3.2× the model size** for its accuracy advantage
on hard accents. That is the trade-off to state explicitly rather than assume.

**The tail is tight: p99/p50 = 1.43** on base.en. Worst observed case 5.2 s.

**No thermal drift.** End-to-end p50 moved from 2749 ms in the first half of the run to
2782 ms in the second (+1.2%); small.en actually got 2.4% faster. This is the opposite of
the sustained-inference behaviour measured on the same class of hardware in
[megadetector-int8-ptq](https://github.com/DaedricKnight/megadetector-int8-ptq), where a
Tensor G3 lost 91% of throughput over 30 minutes — and the reason is workload shape:
voice commands are bursty (≈3 s of compute, then idle), so the SoC never reaches the
thermal envelope that continuous detection does.

## Memory

| | base.en | small.en |
|---|--:|--:|
| Models resident (PSS delta after load) | 109 MB | 274 MB |
| Peak process PSS | 239 MB | 421 MB |

## Model sizes

| Asset | Size |
|---|--:|
| `medical_intent.onnx` (BERT-Tiny, INT8) | 4.46 MB |
| `silero_vad.onnx` | 2.22 MB |
| `ggml-base.en-q5_1.bin` | 56.95 MB |
| `ggml-small.en-q5_1.bin` | 181.29 MB |

## Quantization cost: FP32 → INT8, intent classifier

The FP32 baseline had to be reconstructed by retraining, because `quantize_medical.py`
deleted the FP32 weights in place. Both models evaluated on the same 618-sample
validation set, same tokenizer, same label order — only weight precision differs.

| | FP32 | INT8 | Δ |
|---|--:|--:|--:|
| Size | 17.1 MB | 4.46 MB | **3.8× smaller** |
| Accuracy | 99.676% | 99.676% | 0.000 pp |
| Pass rate at the 0.85 confidence gate | 99.191% | 99.191% | 0.000 pp |
| Mean confidence | 0.9980 | 0.9974 | −0.065 pp |
| Prediction disagreements | — | — | **0 / 618** |

Dynamic INT8 quantization is free on this task: not one prediction changed, and not one
example crossed the confidence gate in either direction.

Independent confirmation of reproducibility: retraining from scratch reproduced the
shipped model's gate pass rate (99.191% against 99.18% documented previously).

## What this does and does not settle

Settled: the per-stage latency distribution on a Tensor G3, that ASR dominates it
completely, that the pipeline does not thermally degrade under bursty command load, the
resident memory cost of each whisper model, and that INT8 quantization of the intent
model is lossless at the resolution this validation set supports.

Not settled:

- **Other silicon.** One SoC, and by the measurements in `megadetector-int8-ptq` the
  weakest of the four tested — a Snapdragon 8 Elite ran the same class of workload about
  3× faster there. These numbers are a floor, not a typical figure.
- **Accuracy resolution.** With 618 samples and 2 errors, a degradation smaller than
  ~0.16 pp cannot be detected. "Below the measurable threshold" is the honest claim, not
  "zero".
- **In-domain data only.** The FP32/INT8 comparison uses clean validation text. It says
  nothing about quantization behaviour under accents or noise, which would need the full
  audio pipeline rather than the classifier alone.
- **Per-command breakdown.** Latency is aggregated over a mixed fixture set that includes
  long utterances (`openchart`, `switchpatient`, `addpatient`) alongside short vitals
  commands; short commands alone would be faster.
