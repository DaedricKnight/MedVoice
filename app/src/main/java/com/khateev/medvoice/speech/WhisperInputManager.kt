/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fully local voice input: AudioRecord -> Silero VAD segmentation -> whisper.cpp.
 * Replaces Android SpeechRecognizer so that no audio or text ever leaves the device.
 *
 * Continuous session: while active, every VAD-delimited utterance is transcribed
 * and delivered via [onResult]; the clinician can dictate several commands in a row.
 */
class WhisperInputManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onStateChange: (Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    val engine = WhisperEngine(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var audioThread: Thread? = null
    @Volatile
    private var prompt: String = WhisperEngine.MEDICAL_PROMPT

    /** While the app itself is speaking (TTS), the mic must not hear it. */
    @Volatile
    private var paused = false

    fun setPaused(value: Boolean) {
        paused = value
    }

    companion object {
        private const val TAG = "WhisperInputManager"
    }

    fun initialize(onReady: (Result<Unit>) -> Unit) {
        scope.launch {
            val result = engine.initialize()
            onReady(result)
        }
    }

    /** Switches between fast (base.en) and accuracy (small.en) ASR models. */
    fun setAccuracyMode(enabled: Boolean, onDone: (Result<Unit>) -> Unit) {
        stopListening()
        scope.launch {
            val result = engine.initialize(useAccuracyModel = enabled)
            onDone(result)
        }
    }

    val isListening: Boolean get() = running.get()

    @SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
    fun startListening(transcriptionPrompt: String = WhisperEngine.MEDICAL_PROMPT) {
        if (!engine.isReady) {
            onError("Whisper model is still loading, try again in a moment")
            return
        }
        if (!running.compareAndSet(false, true)) return
        prompt = transcriptionPrompt

        onStateChange(true)
        audioThread = Thread({ audioLoop() }, "whisper-audio").also { it.start() }
    }

    fun stopListening() {
        if (!running.compareAndSet(true, false)) return
        audioThread?.join(1000)
        audioThread = null
        onStateChange(false)
    }

    private fun audioLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            WhisperEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                WhisperEngine.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, SileroVad.CHUNK * 8)
            )
        } catch (e: Exception) {
            fail("Microphone unavailable: ${e.message}")
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            fail("Microphone failed to initialize")
            return
        }

        val vad = try {
            SileroVad(context)
        } catch (e: Exception) {
            recorder.release()
            fail("VAD failed to load: ${e.message}")
            return
        }

        val segmenter = UtteranceSegmenter(
            vad = vad,
            onUtterance = { pcm -> transcribeAsync(pcm) },
            onSpeechStateChange = { speaking ->
                if (speaking) onStatus("Speech detected…")
            }
        )

        val shortBuf = ShortArray(SileroVad.CHUNK)
        val floatBuf = FloatArray(SileroVad.CHUNK)

        try {
            recorder.startRecording()
            onStatus("Listening (offline whisper)…")

            var droppedWhilePaused = false
            while (running.get()) {
                var filled = 0
                while (filled < shortBuf.size && running.get()) {
                    val n = recorder.read(shortBuf, filled, shortBuf.size - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < shortBuf.size) continue

                // Keep draining the recorder while TTS speaks, but discard the
                // audio so whisper never transcribes the app's own voice.
                if (paused) {
                    droppedWhilePaused = true
                    continue
                }
                if (droppedWhilePaused) {
                    segmenter.reset()
                    droppedWhilePaused = false
                }

                for (i in shortBuf.indices) floatBuf[i] = shortBuf[i] / 32768f
                segmenter.accept(floatBuf)
            }
            segmenter.finish()
        } catch (e: Exception) {
            Log.e(TAG, "Audio loop error: ${e.message}", e)
            fail("Audio capture error: ${e.message}")
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
            vad.close()
        }
    }

    private fun transcribeAsync(pcm: FloatArray) {
        scope.launch {
            onStatus("Transcribing…")
            val text = engine.transcribe(pcm, prompt)
            Log.d(TAG, "Transcript (${pcm.size / 16000.0}s audio): \"$text\"")
            if (text.isNotBlank()) {
                onResult(text)
            } else {
                onStatus("(no speech recognized)")
            }
        }
    }

    private fun fail(message: String) {
        running.set(false)
        onStateChange(false)
        onError(message)
    }

    fun close() {
        stopListening()
        scope.cancel()
        engine.close()
    }
}
