# MedVoice on-device latency, memory and quantization cost

Four SoCs, one harness: the standalone [`:bench`](bench/README.md) APK, `fat`
flavor, whisper `base.en-q5_1`, running the production chain out of
`:voice-core` — Silero VAD → whisper.cpp → number normalization → INT8 BERT
intent classification — over the 14 bundled TTS command fixtures (24.6 s of
audio, mean 1.76 s per command).

Samsung devices measured on Samsung Remote Test Lab; the Pixel locally over adb.
Every sample was taken with the app foreground and the screen on — see
[Why the harness matters](#why-the-harness-matters).

## End to end, milliseconds

| | Galaxy Z Fold8 Ultra<br>Snapdragon 8 Elite | Galaxy S24 FE<br>Exynos 2400e | Galaxy M53 5G<br>Dimensity 900 | Pixel 8 Pro<br>Tensor G3 |
|---|--:|--:|--:|--:|
| p50 | **379.9** | 564.6 | 937.4 | 1285.2 |
| p90 | 444.1 | 663.7 | 1107.1 | 2185.2 |
| p95 | 475.9 | 735.7 | 1132.8 | 2441.8 |
| p99 | 508.7 | 759.7 | 1211.6 | **3545.2** |
| **p99 / p50** | 1.34 | 1.35 | 1.29 | **2.76** |
| samples | 1512 | 770 | 210 | >100 |

## Per stage, p50 / p99 milliseconds

| | Fold8 Ultra | S24 FE | M53 | Pixel 8 Pro |
|---|--:|--:|--:|--:|
| Silero VAD | 8.2 / 17.1 | 11.6 / 31.5 | 67.0 / 142.0 | 33.1 / 72.9 |
| whisper.cpp ASR | 370.6 / 494.9 | 553.2 / 737.2 | 862.7 / 1095.0 | 1243.7 / 3504.8 |
| normalize + sanitize | 0.1 / 0.1 | 0.1 / 0.2 | 0.5 / 1.3 | 0.2 / 0.5 |
| INT8 intent classifier | 0.9 / 1.0 | 1.2 / 1.7 | 2.5 / 14.6 | 3.6 / 5.2 |
| ASR share of end to end | 97.6% | 98.0% | 92.0% | 96.8% |
| real-time factor | 0.21× | 0.31× | 0.49× | 0.71× |

## Memory and load, megabytes / milliseconds

| | Fold8 Ultra | S24 FE | M53 | Pixel 8 Pro |
|---|--:|--:|--:|--:|
| Models resident (PSS delta) | 180 | 198 | 164 | 241 |
| Peak process PSS | 359 | 342 | 243 | 334 |
| Model load | 286 | 331 | 591 | 711 |

## What the numbers say

**ASR is the entire budget.** whisper.cpp is 92–98% of end to end on every
device. The intent classifier — the part that was trained, quantized and tuned —
costs 0.9–3.6 ms. Optimizing it buys nothing; every millisecond worth chasing is
in the decoder.

**Medians spread 3.4×, tails spread 7.0×.** From 380 ms to 1285 ms at p50, but
509 ms to 3545 ms at p99. The tail is where device choice actually shows up.

**One chip owns that difference.** Three of the four SoCs sit at p99/p50 ≈ 1.3.
The Tensor G3 is at **2.76**: a tolerable 1285 ms median hiding a 3.5 s
hundredth command. It is not slow so much as inconsistent, and for a voice
interface the tail is what a user feels.

That is the same chip that lost 91% of its throughput over 30 minutes of
continuous inference in
[megadetector-int8-ptq](https://github.com/DaedricKnight/megadetector-int8-ptq).
Two unrelated workloads, one culprit — under sustained load it shows up as decay
over time, under bursty load as a fat tail.

**Device ranking is a property of the workload, not the chip.** In the
MegaDetector detector benchmark the Tensor G3 (399 ms steady) beat the Dimensity
900 (598 ms) by 1.5×. Here the Dimensity beats the Tensor by 1.37×. The same two
pieces of silicon swap order when the workload changes from a convolutional
detector under ONNX Runtime to a transformer decoder under GGML.

**Everything is faster than real time.** RTF 0.21–0.71× against a mean command
of 1.76 s, so even the slowest device transcribes faster than the speech arrives.

## Why the harness matters

Two measurements of the same code on the same Pixel 8 Pro, minutes apart:

| harness | end to end p50 | models resident |
|---|--:|--:|
| `:bench` APK, foreground | **1285 ms** | 241 MB |
| `LatencyBenchmarkTest` over adb, USB-attached | 2771 ms | 109 MB |

**2.16× apart.** The instrumentation figure was the one first published here and
it was wrong — it is what a continuously-running, USB-attached, charging device
reports, not what the app does in a user's hand. The four-device table above is
the bench APK throughout.

A second, larger trap the harness has to close: a backgrounded Android process is
CPU-throttled hard. Measured on the Pixel, the same run reported **e2e p50
9919 ms behind a lock screen against 1285 ms in the foreground — 7.7×** — with
no error and no warning. `:bench` gates every sample on foreground state and
discards anything that overlapped a trip to the background.

## Model sizes

| Asset | Size |
|---|--:|
| `medical_intent.onnx` (BERT-Tiny, INT8) | 4.46 MB |
| `silero_vad.onnx` | 2.22 MB |
| `ggml-base.en-q5_1.bin` | 56.95 MB |
| `ggml-small.en-q5_1.bin` | 181.29 MB |

## Quantization cost: FP32 → INT8, intent classifier

The FP32 baseline had to be reconstructed by retraining, because
`quantize_medical.py` deleted the FP32 weights in place. Both models evaluated on
the same 618-sample validation set, same tokenizer, same label order — only
weight precision differs.

| | FP32 | INT8 | Δ |
|---|--:|--:|--:|
| Size | 17.1 MB | 4.46 MB | **3.8× smaller** |
| Accuracy | 99.676% | 99.676% | 0.000 pp |
| Pass rate at the 0.85 confidence gate | 99.191% | 99.191% | 0.000 pp |
| Mean confidence | 0.9980 | 0.9974 | −0.065 pp |
| Prediction disagreements | — | — | **0 / 618** |

Dynamic INT8 quantization is free on this task: not one prediction changed, and
not one example crossed the confidence gate in either direction. Retraining from
scratch also reproduced the shipped model's gate pass rate (99.191% against
99.18% documented previously).

## What this does and does not settle

Settled: the per-stage latency distribution across four SoCs from one harness,
that ASR dominates it everywhere, that tail behaviour separates devices twice as
much as median behaviour does, the resident memory cost, and that INT8
quantization of the intent model is lossless at the resolution this validation
set supports.

Not settled:

- **small.en.** The 2.55× latency and 2.5× memory penalty against base.en was
  measured only on the Pixel and only under the instrumentation harness now known
  to inflate absolute numbers by 2.16×. The ratio is probably still about right;
  the absolute figures are not, and it has not been re-run on the farm because
  the 181 MB model does not fit a farm-sized APK.
- **Accuracy resolution.** With 618 samples and 2 errors, a degradation smaller
  than ~0.16 pp cannot be detected. "Below the measurable threshold" is the
  honest claim, not "zero".
- **In-domain data only.** The FP32/INT8 comparison uses clean validation text and
  says nothing about quantization under accents or noise.
- **Per-command breakdown.** Latency is aggregated over a fixture mix that
  includes long utterances (`openchart`, `switchpatient`, `addpatient`) alongside
  short vitals commands; short commands alone are faster.
- **Thermal behaviour over time.** Every run here is bursty by construction. The
  Tensor G3 tail is consistent with early throttling but this harness does not
  prove it; a sustained variant would.
