/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 */
package com.khateev.medvoice

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.khateev.medvoice.domain.VitalsExtractor
import com.khateev.medvoice.ml.MedIntentClassifier
import com.khateev.medvoice.speech.SileroVad
import com.khateev.medvoice.speech.WhisperEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File

/**
 * On-device latency / memory benchmark for the MedVoice pipeline.
 *
 * Runs the exact production chain used by [VoicePipelineTest] — Silero VAD ->
 * whisper.cpp -> number normalization -> BERT intent classification — over the
 * bundled TTS fixtures, and reports per-stage percentiles rather than a mean.
 *
 * Both whisper models are measured (base.en-q5_1 and small.en-q5_1) so the
 * size / latency trade-off is visible instead of assumed.
 *
 * Run:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.injected.build.abi=arm64-v8a \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.khateev.medvoice.LatencyBenchmarkTest
 *
 * Report: /sdcard/Android/data/com.khateev.medvoice/files/bench/latency.json
 */
@RunWith(AndroidJUnit4::class)
class LatencyBenchmarkTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private fun arg(name: String, default: Int): Int =
        InstrumentationRegistry.getArguments().getString(name)?.toIntOrNull() ?: default

    /** Fixtures are short clinical commands; note/ood clips are included on purpose. */
    private val wavs = listOf(
        "bp_samantha.wav", "hr_samantha.wav", "temp_samantha.wav", "spo2_samantha.wav",
        "pain_samantha.wav", "weight_samantha.wav", "rr_samantha.wav",
        "discharge_daniel.wav", "codeblue_daniel.wav", "switchpatient_daniel.wav",
        "addpatient_samantha.wav", "undolast_daniel.wav", "openchart_samantha.wav",
        "ood_daniel.wav"
    )

    @Test
    fun benchmarkPipeline() {
        val warmup = arg("warmup", 1)          // passes over the whole fixture set
        val repeats = arg("repeats", 8)        // timed passes  -> repeats * wavs samples
        // Internal storage: always writable, and readable over adb via `run-as` on a debug build.
        val outDir = File(targetContext.filesDir, "bench").apply { mkdirs() }

        val pcm = wavs.associateWith { loadWavPcm("tts/$it") }
        val totalAudioSec = pcm.values.sumOf { it.size } / WhisperEngine.SAMPLE_RATE.toDouble()

        val report = JSONObject().apply {
            put("device", JSONObject().apply {
                put("model", android.os.Build.MODEL)
                put("manufacturer", android.os.Build.MANUFACTURER)
                put("soc", if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "unknown")
                put("android", android.os.Build.VERSION.RELEASE)
                put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull())
                put("cores", Runtime.getRuntime().availableProcessors())
            })
            put("config", JSONObject().apply {
                put("warmup_passes", warmup)
                put("timed_passes", repeats)
                put("fixtures", wavs.size)
                put("samples_per_stage", repeats * wavs.size)
                put("fixture_audio_seconds", round3(totalAudioSec))
            })
            put("model_sizes_bytes", modelSizes())
        }

        val variants = JSONArray()
        for (useSmall in listOf(false, true)) {
            variants.put(measureVariant(if (useSmall) "small.en-q5_1" else "base.en-q5_1", useSmall, pcm, warmup, repeats))
        }
        report.put("variants", variants)

        val out = File(outDir, "latency.json")
        val json = report.toString(2)
        out.writeText(json)
        // Logcat copy: survives even if the file cannot be pulled.
        val compact = report.toString()
        compact.chunked(3000).forEachIndexed { i, c -> android.util.Log.i("MedVoiceBench", "PART$i $c") }
        android.util.Log.i("MedVoiceBench", "DONE parts=${(compact.length + 2999) / 3000} file=${out.absolutePath}")
        println("MEDVOICE_BENCH_FILE ${out.absolutePath}")
    }

    private fun measureVariant(
        name: String,
        useSmall: Boolean,
        pcm: Map<String, FloatArray>,
        warmup: Int,
        repeats: Int
    ): JSONObject {
        val whisper = WhisperEngine(targetContext)
        val classifier = MedIntentClassifier(targetContext)
        val vad = SileroVad(targetContext)

        val memBaseline = pss()

        val asrInitMs = timeMs { check(runBlocking { whisper.initialize(useSmall) }.isSuccess) { "whisper init failed" } }
        val clsInitMs = timeMs { check(runBlocking { classifier.initialize() }.isSuccess) { "classifier init failed" } }
        val memAfterLoad = pss()

        val vadT = ArrayList<Double>()
        val asrT = ArrayList<Double>()
        val normT = ArrayList<Double>()
        val clsT = ArrayList<Double>()
        val e2eT = ArrayList<Double>()
        var peakPss = memAfterLoad.getInt("total_pss_kb")

        repeat(warmup) { for (w in wavs) runOnce(whisper, classifier, vad, pcm.getValue(w), null) }

        repeat(repeats) {
            for (w in wavs) {
                val s = DoubleArray(4)
                runOnce(whisper, classifier, vad, pcm.getValue(w), s)
                vadT += s[0]; asrT += s[1]; normT += s[2]; clsT += s[3]
                e2eT += s.sum()
            }
            val p = pss().getInt("total_pss_kb")
            if (p > peakPss) peakPss = p
        }

        val half = e2eT.size / 2
        val result = JSONObject().apply {
            put("whisper_model", name)
            put("init_ms", JSONObject().apply {
                put("whisper_load", round3(asrInitMs))
                put("classifier_load", round3(clsInitMs))
            })
            put("stages_ms", JSONObject().apply {
                put("vad_full_utterance", stats(vadT))
                put("asr_whisper", stats(asrT))
                put("normalize_sanitize", stats(normT))
                put("intent_classifier", stats(clsT))
                put("end_to_end", stats(e2eT))
            })
            // Cheap thermal/drift slice: first half vs second half of the same run.
            put("drift_end_to_end_ms", JSONObject().apply {
                put("first_half", stats(e2eT.subList(0, half)))
                put("second_half", stats(e2eT.subList(half, e2eT.size)))
            })
            put("memory_kb", JSONObject().apply {
                put("baseline", memBaseline)
                put("after_model_load", memAfterLoad)
                put("peak_total_pss_kb", peakPss)
                put("models_resident_pss_kb", memAfterLoad.getInt("total_pss_kb") - memBaseline.getInt("total_pss_kb"))
                put("native_heap_allocated_kb", (Debug.getNativeHeapAllocatedSize() / 1024).toInt())
            })
        }

        whisper.close(); classifier.close(); vad.close()
        return result
    }

    /** One full pipeline pass. If [out] is non-null, fills [vad, asr, norm, cls] ms. */
    private fun runOnce(
        whisper: WhisperEngine,
        classifier: MedIntentClassifier,
        vad: SileroVad,
        pcm: FloatArray,
        out: DoubleArray?
    ) {
        val tVad = timeMs {
            vad.reset()
            var i = 0
            val chunk = FloatArray(SileroVad.CHUNK)
            while (i + SileroVad.CHUNK <= pcm.size) {
                System.arraycopy(pcm, i, chunk, 0, SileroVad.CHUNK)
                vad.speechProbability(chunk)
                i += SileroVad.CHUNK
            }
        }
        var text = ""
        val tAsr = timeMs { text = runBlocking { whisper.transcribe(pcm) } }
        var forClassify = ""
        val tNorm = timeMs {
            forClassify = VitalsExtractor.sanitizeForClassification(VitalsExtractor.normalizeNumbers(text))
        }
        val tCls = timeMs { runBlocking { classifier.classify(forClassify) } }
        out?.let { it[0] = tVad; it[1] = tAsr; it[2] = tNorm; it[3] = tCls }
    }

    private fun modelSizes(): JSONObject {
        val o = JSONObject()
        for (a in listOf("medical_intent.onnx", "silero_vad.onnx", "ggml-base.en-q5_1.bin", "ggml-small.en-q5_1.bin")) {
            o.put(a, runCatching { targetContext.assets.openFd(a).use { it.length } }.getOrDefault(-1L))
        }
        return o
    }

    private fun pss(): JSONObject {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return JSONObject().apply {
            put("total_pss_kb", mi.totalPss)
            put("native_pss_kb", mi.nativePss)
            put("dalvik_pss_kb", mi.dalvikPss)
        }
    }

    private inline fun timeMs(block: () -> Unit): Double {
        val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1_000_000.0
    }

    private fun stats(v: List<Double>): JSONObject {
        val s = v.sorted()
        fun p(q: Double): Double = if (s.isEmpty()) 0.0 else
            s[minOf(s.size - 1, Math.ceil(q * s.size).toInt().coerceAtLeast(1) - 1)]
        return JSONObject().apply {
            put("n", s.size)
            put("min", round3(s.firstOrNull() ?: 0.0))
            put("p50", round3(p(0.50)))
            put("p90", round3(p(0.90)))
            put("p95", round3(p(0.95)))
            put("p99", round3(p(0.99)))
            put("max", round3(s.lastOrNull() ?: 0.0))
            put("mean", round3(if (s.isEmpty()) 0.0 else s.average()))
        }
    }

    private fun round3(d: Double) = Math.round(d * 1000.0) / 1000.0

    private fun loadWavPcm(assetPath: String): FloatArray {
        val bytes = testContext.assets.open(assetPath).readBytes()
        val dataOffset = findDataChunk(bytes)
        val sampleCount = (bytes.size - dataOffset) / 2
        val pcm = FloatArray(sampleCount)
        var i = dataOffset
        for (s in 0 until sampleCount) {
            val lo = bytes[i].toInt() and 0xFF
            val hi = bytes[i + 1].toInt()
            pcm[s] = ((hi shl 8) or lo) / 32768f
            i += 2
        }
        return pcm
    }

    private fun findDataChunk(bytes: ByteArray): Int {
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = DataInputStream(ByteArrayInputStream(bytes, pos + 4, 4)).let {
                val b = ByteArray(4); it.readFully(b)
                ((b[3].toInt() and 0xFF) shl 24) or ((b[2].toInt() and 0xFF) shl 16) or
                        ((b[1].toInt() and 0xFF) shl 8) or (b[0].toInt() and 0xFF)
            }
            if (id == "data") return pos + 8
            pos += 8 + size + (size and 1)
        }
        error("no data chunk in WAV")
    }
}
