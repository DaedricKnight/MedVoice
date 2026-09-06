# MedVoice bench — standalone latency APK for remote device farms

Samsung Remote Test Lab gives no adb: you upload an APK, launch it, and read a
laggy remote screen. An instrumentation test cannot run there, so this is an
app — it **auto-starts**, holds the screen awake, and rewrites the table after
every pass. **Any screenshot is a valid datapoint** for the pass count it shows,
so a session cut short still yields numbers.

The measured chain is the production one straight out of `:voice-core` —
Silero VAD → whisper.cpp → number normalization → INT8 BERT classification —
the same code path `LatencyBenchmarkTest` and the app itself use.

## Two flavors

| Flavor | APK | Model | Use when |
|---|--:|---|---|
| `fat` | **100 MB** | `ggml-base.en-q5_1` bundled | the farm accepts the upload; no network needed |
| `slim` | **43 MB** | downloaded on first launch (~57 MB from HuggingFace) | the farm caps upload size; device has network |

```bash
./gradlew :bench:assembleFatDebug     # bench/build/outputs/apk/fat/debug/
./gradlew :bench:assembleSlimDebug    # bench/build/outputs/apk/slim/debug/
```

`arm64-v8a` only — one ABI is enough for any modern phone and keeps the upload
small. `noCompress += listOf("bin", "onnx")` is required: whisper.cpp reaches the
model through `openFd`, which only works on assets stored uncompressed.

## Reading the result

The screen shows device, SoC, elapsed time, pass count, sample count, and the
per-stage table (p50/p90/p95/p99) plus model load time, resident memory and peak
PSS. **p99 needs ~100 samples** — the status line says when it becomes
meaningful (8 passes over the 14 fixtures).

Where logs or files are available:

```bash
adb logcat -s MedVoiceBench          # one line per pass
adb pull /sdcard/Android/data/com.khateev.medvoice.bench/files/medvoice_latency.csv
```

## The trap this harness avoids

A backgrounded Android process is CPU-throttled hard. Measured on a Pixel 8 Pro,
the same run reported **e2e p50 9919 ms behind a lock screen against 2771 ms in
the foreground — 3.6x** — with no error and no warning. A benchmark that keeps
collecting while the screen sleeps silently reports garbage.

So samples are gated: the loop waits for the foreground, and any sample that
overlapped a trip to the background is discarded and counted on screen. If the
screen sleeps mid-session the status reads `paused — bring the app to the
foreground` and the numbers stay clean.

Keep the device awake for the whole run. On a farm the session does this; locally
the phone must be unlocked, or `adb shell svc power stayon usb`.
