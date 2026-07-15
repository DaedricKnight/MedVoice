/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Fully offline speech-to-text: whisper.cpp with the base.en (INT5 quantized) model.
 * No network access — audio never leaves the device.
 */
class WhisperEngine(private val context: Context) {

    private var ctxPtr: Long = 0

    companion object {
        private const val TAG = "WhisperEngine"
        private const val MODEL_ASSET = "ggml-base.en-q5_1.bin"
        const val SAMPLE_RATE = 16_000

        /** Whisper's initial prompt biases decoding toward the clinical vocabulary.
         *  Verb forms matter: without "log ..." the decoder heard "lock pain level". */
        const val MEDICAL_PROMPT =
            "Clinical voice commands: log pain level, log heart rate, log temperature, " +
            "record blood pressure, record weight, oxygen saturation, respiratory rate, " +
            "prescribe medication, discharge patient, code blue, defibrillator, " +
            "sepsis protocol, triage."

        /** Free-text note dictation is prose, not commands — bias toward clinical narrative. */
        const val NOTE_PROMPT =
            "Clinical note. The patient presents with chest pain, dyspnea and dizziness. " +
            "History of hypertension and diabetes mellitus. Auscultation unremarkable, " +
            "abdomen soft, no peripheral edema. Plan: ECG, troponin, chest X-ray."

        /** Whisper needs a minimum window; short commands are padded with silence.
         *  A generous tail helps the decoder emit its end token instead of looping
         *  when audio_ctx is reduced. */
        private const val MIN_SAMPLES = (1.5 * SAMPLE_RATE).toInt()
        private const val TAIL_SILENCE = (0.5 * SAMPLE_RATE).toInt()

        init {
            System.loadLibrary("medvoice_whisper")
        }
    }

    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val modelFile = File(context.filesDir, MODEL_ASSET)
            val assetSize = context.assets.openFd(MODEL_ASSET).use { it.length }
            if (!modelFile.exists() || modelFile.length() != assetSize) {
                context.assets.open(MODEL_ASSET).use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            ctxPtr = nativeInit(modelFile.absolutePath)
            if (ctxPtr == 0L) {
                Result.failure(IllegalStateException("whisper_init failed"))
            } else {
                Log.d(TAG, "Whisper base.en-q5_1 initialized")
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Whisper init failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    val isReady: Boolean get() = ctxPtr != 0L

    /** Transcribes 16 kHz mono float PCM in [-1, 1]. Blocking; call off the main thread. */
    suspend fun transcribe(pcm: FloatArray, prompt: String = MEDICAL_PROMPT): String =
        withContext(Dispatchers.Default) {
            if (ctxPtr == 0L) return@withContext ""
            val padded = if (pcm.size < MIN_SAMPLES) {
                FloatArray(MIN_SAMPLES).also { pcm.copyInto(it) }
            } else {
                FloatArray(pcm.size + TAIL_SILENCE).also { pcm.copyInto(it) }
            }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val raw = nativeTranscribe(ctxPtr, padded, prompt, threads).trim()
            TranscriptCleaner.collapseRepetitions(TranscriptCleaner.stripNonSpeech(raw))
        }

    fun close() {
        if (ctxPtr != 0L) {
            nativeFree(ctxPtr)
            ctxPtr = 0
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(ctxPtr: Long, pcm: FloatArray, prompt: String, nThreads: Int): String
    private external fun nativeFree(ctxPtr: Long)
}
