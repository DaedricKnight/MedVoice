/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 */
package com.khateev.medvoice.bench

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import com.khateev.medvoice.domain.VitalsExtractor
import com.khateev.medvoice.ml.MedIntentClassifier
import com.khateev.medvoice.speech.SileroVad
import com.khateev.medvoice.speech.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.URL

/**
 * Auto-running latency benchmark for remote device farms.
 *
 * Samsung Remote Test Lab exposes no adb — an APK is uploaded, launched, and read
 * off a laggy remote screen. So there is no start button: the run begins in
 * [onCreate], the screen is held awake, and the table is rewritten after every
 * pass. A session cut short still yields usable numbers, because any screenshot
 * is a valid datapoint for the pass count it shows.
 *
 * The measured chain is the production one, straight out of :voice-core —
 * Silero VAD -> whisper.cpp -> number normalization -> INT8 BERT classification.
 */
class BenchActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var out: TextView

    private val wavs = listOf(
        "bp_samantha.wav", "hr_samantha.wav", "temp_samantha.wav", "spo2_samantha.wav",
        "pain_samantha.wav", "weight_samantha.wav", "rr_samantha.wav",
        "discharge_daniel.wav", "codeblue_daniel.wav", "switchpatient_daniel.wav",
        "addpatient_samantha.wav", "undolast_daniel.wav", "openchart_samantha.wav",
        "ood_daniel.wav"
    )

    private val vad = ArrayList<Double>()
    private val asr = ArrayList<Double>()
    private val norm = ArrayList<Double>()
    private val cls = ArrayList<Double>()
    private val e2e = ArrayList<Double>()

    @Volatile private var foreground = false
    private var discarded = 0
    private var status = "starting"
    private var passes = 0
    private var peakPssKb = 0
    private var residentKb = 0
    private var loadMs = 0.0
    private val startedAt = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        out = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(out)
        })
        render()
        scope.launch { runBenchmark() }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Samples are only valid while the activity is foreground with the screen on.
     * A backgrounded process is CPU-throttled hard — measured here at 3.6x the
     * foreground latency — so a run that continued behind a lock screen would
     * silently report garbage.
     */
    private suspend fun awaitForeground() {
        if (foreground) return
        status = "paused — bring the app to the foreground"
        render()
        while (!foreground) kotlinx.coroutines.delay(200)
        status = "running"
        render()
    }

    private suspend fun runBenchmark() {
        try {
            val baseline = totalPss()
            if (!BuildConfig.BUNDLED_MODEL) ensureModelDownloaded()

            status = "loading models"; render()
            val whisper = WhisperEngine(this)
            val classifier = MedIntentClassifier(this)
            val vadEngine = SileroVad(this)

            val t0 = System.nanoTime()
            whisper.initialize(false).getOrElse { fail("whisper init: ${it.message}"); return }
            classifier.initialize().getOrElse { fail("classifier init: ${it.message}"); return }
            loadMs = (System.nanoTime() - t0) / 1_000_000.0
            residentKb = totalPss() - baseline

            status = "loading fixtures"; render()
            val pcm = wavs.associateWith { loadWavPcm("tts/$it") }

            status = "warmup"; render()
            for (w in wavs) runOnce(whisper, classifier, vadEngine, pcm.getValue(w), null)

            // No fixed end: a farm session may be cut at any point, and more
            // passes only tighten the tail.
            while (true) {
                for (w in wavs) {
                    awaitForeground()
                    val s = DoubleArray(4)
                    runOnce(whisper, classifier, vadEngine, pcm.getValue(w), s)
                    // Discard anything that overlapped a trip to the background:
                    // the throttled numbers would poison the percentiles.
                    if (!foreground) { discarded++; continue }
                    vad += s[0]; asr += s[1]; norm += s[2]; cls += s[3]; e2e += s.sum()
                }
                passes++
                peakPssKb = maxOf(peakPssKb, totalPss())
                status = if (e2e.size >= 100) "running (p99 meaningful)" else "running (need ~100 samples for p99)"
                render()
                writeCsv()
                // Farms that do expose logs (and local adb) can read results
                // without the screen.
                android.util.Log.i(
                    "MedVoiceBench",
                    "pass=$passes n=${e2e.size} " +
                        "e2e_p50=${"%.1f".format(pct(e2e, .5))} " +
                        "e2e_p95=${"%.1f".format(pct(e2e, .95))} " +
                        "e2e_p99=${"%.1f".format(pct(e2e, .99))} " +
                        "asr_p50=${"%.1f".format(pct(asr, .5))} " +
                        "cls_p50=${"%.1f".format(pct(cls, .5))} " +
                        "resident_mb=${residentKb / 1024} peak_pss_mb=${peakPssKb / 1024}"
                )
            }
        } catch (t: Throwable) {
            fail(t.message ?: t.toString())
        }
    }

    private suspend fun ensureModelDownloaded() {
        val dst = File(filesDir, "ggml-base.en-q5_1.bin")
        if (dst.exists() && dst.length() > 50_000_000L) return
        withContext(Dispatchers.IO) {
            val conn = URL(BuildConfig.MODEL_URL).openConnection()
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            val total = conn.contentLengthLong
            val tmp = File(filesDir, "ggml-base.en-q5_1.bin.part")
            conn.getInputStream().use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    var got = 0L
                    var lastShown = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        got += n
                        if (got - lastShown > 4_000_000L) {
                            lastShown = got
                            val pct = if (total > 0) " (${got * 100 / total}%)" else ""
                            status = "downloading model ${got / 1_048_576} MB$pct"
                            render()
                        }
                    }
                }
            }
            if (!tmp.renameTo(dst)) throw IllegalStateException("cannot place downloaded model")
        }
    }

    private suspend fun runOnce(
        whisper: WhisperEngine,
        classifier: MedIntentClassifier,
        vadEngine: SileroVad,
        pcm: FloatArray,
        slot: DoubleArray?
    ) {
        var t = System.nanoTime()
        vadEngine.reset()
        val chunk = FloatArray(SileroVad.CHUNK)
        var i = 0
        while (i + SileroVad.CHUNK <= pcm.size) {
            System.arraycopy(pcm, i, chunk, 0, SileroVad.CHUNK)
            vadEngine.speechProbability(chunk)
            i += SileroVad.CHUNK
        }
        val tVad = ms(t); t = System.nanoTime()

        val text = whisper.transcribe(pcm)
        val tAsr = ms(t); t = System.nanoTime()

        val forCls = VitalsExtractor.sanitizeForClassification(VitalsExtractor.normalizeNumbers(text))
        val tNorm = ms(t); t = System.nanoTime()

        classifier.classify(forCls)
        val tCls = ms(t)

        slot?.let { it[0] = tVad; it[1] = tAsr; it[2] = tNorm; it[3] = tCls }
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000.0

    private fun fail(msg: String) {
        status = "FAILED: $msg"
        runOnUiThread { render() }
    }

    private fun render() = runOnUiThread {
        val elapsed = (System.currentTimeMillis() - startedAt) / 1000
        val sb = StringBuilder()
        sb.append("MedVoice on-device benchmark\n")
        sb.append("=".repeat(52)).append('\n')
        sb.append("device   ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("soc      ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"}\n")
        sb.append("android  ${Build.VERSION.RELEASE}   cores ${Runtime.getRuntime().availableProcessors()}\n")
        sb.append("build    ${BuildConfig.FLAVOR}   whisper base.en-q5_1\n")
        sb.append("elapsed  ${elapsed / 60}m ${elapsed % 60}s   passes $passes   n=${e2e.size}")
        sb.append(if (discarded > 0) "   discarded $discarded\n" else "\n")
        sb.append("status   $status\n")
        sb.append("=".repeat(52)).append('\n')
        if (e2e.isNotEmpty()) {
            sb.append(String.format("%-14s %8s %8s %8s %8s\n", "stage ms", "p50", "p90", "p95", "p99"))
            row(sb, "vad", vad); row(sb, "asr whisper", asr)
            row(sb, "normalize", norm); row(sb, "classifier", cls)
            sb.append("-".repeat(52)).append('\n')
            row(sb, "END TO END", e2e)
            sb.append('\n')
            sb.append("model load    ${"%.0f".format(loadMs)} ms\n")
            sb.append("models resident ${residentKb / 1024} MB   peak PSS ${peakPssKb / 1024} MB\n")
        }
        out.text = sb.toString()
    }

    private fun pct(v: List<Double>, q: Double): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return s[minOf(s.size - 1, Math.ceil(q * s.size).toInt().coerceAtLeast(1) - 1)]
    }

    private fun row(sb: StringBuilder, name: String, v: List<Double>) {
        sb.append(String.format("%-14s %8.1f %8.1f %8.1f %8.1f\n",
            name, pct(v, .5), pct(v, .9), pct(v, .95), pct(v, .99)))
    }

    private fun writeCsv() {
        val dir = getExternalFilesDir(null) ?: filesDir
        val f = File(dir, "medvoice_latency.csv")
        val sb = StringBuilder("stage,n,p50,p90,p95,p99,mean\n")
        for ((name, v) in listOf("vad" to vad, "asr" to asr, "normalize" to norm,
                                 "classifier" to cls, "end_to_end" to e2e)) {
            val s = v.sorted()
            fun p(q: Double) = s[minOf(s.size - 1, Math.ceil(q * s.size).toInt().coerceAtLeast(1) - 1)]
            sb.append("$name,${s.size},${p(.5)},${p(.9)},${p(.95)},${p(.99)},${s.average()}\n")
        }
        runCatching { f.writeText(sb.toString()) }
    }

    private fun totalPss(): Int {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return mi.totalPss
    }

    private fun loadWavPcm(path: String): FloatArray {
        val bytes = assets.open(path).readBytes()
        val off = findDataChunk(bytes)
        val n = (bytes.size - off) / 2
        val pcm = FloatArray(n)
        var i = off
        for (s in 0 until n) {
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
        error("no data chunk")
    }
}
