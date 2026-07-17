/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.speech

/**
 * Turns a continuous stream of 512-sample audio chunks into discrete utterances
 * using VAD probabilities: keeps a pre-roll so the first syllable isn't clipped,
 * starts an utterance when speech probability rises, ends it after sustained
 * silence, and enforces min/max utterance lengths.
 *
 * Pure state machine over injected VAD — unit/instrumentation testable without
 * a microphone (feed chunks from a WAV file).
 */
class UtteranceSegmenter(
    private val vad: SileroVad,
    private val onUtterance: (FloatArray) -> Unit,
    private val onSpeechStateChange: ((Boolean) -> Unit)? = null
) {
    companion object {
        private const val START_THRESHOLD = 0.5f
        private const val END_THRESHOLD = 0.35f
        private const val CHUNK_MS = 32                       // 512 samples @ 16 kHz
        private const val PRE_ROLL_CHUNKS = 10                // ~320 ms before speech onset
        private const val END_SILENCE_CHUNKS = 25             // ~800 ms of silence ends utterance
        private const val MIN_UTTERANCE_CHUNKS = 10           // ignore blips < ~320 ms
        private const val MAX_UTTERANCE_CHUNKS = 12_000 / CHUNK_MS  // hard cap 12 s
    }

    private val preRoll = ArrayDeque<FloatArray>()
    private val speech = mutableListOf<FloatArray>()
    private var inSpeech = false
    private var silenceRun = 0

    fun reset() {
        vad.reset()
        preRoll.clear()
        speech.clear()
        inSpeech = false
        silenceRun = 0
    }

    fun accept(chunk: FloatArray) {
        val prob = vad.speechProbability(chunk)

        if (!inSpeech) {
            preRoll.addLast(chunk.copyOf())
            if (preRoll.size > PRE_ROLL_CHUNKS) preRoll.removeFirst()

            if (prob >= START_THRESHOLD) {
                inSpeech = true
                silenceRun = 0
                speech.addAll(preRoll)
                preRoll.clear()
                onSpeechStateChange?.invoke(true)
            }
            return
        }

        speech.add(chunk.copyOf())
        silenceRun = if (prob < END_THRESHOLD) silenceRun + 1 else 0

        if (silenceRun >= END_SILENCE_CHUNKS || speech.size >= MAX_UTTERANCE_CHUNKS) {
            endUtterance()
        }
    }

    /** Flushes an in-progress utterance (e.g. when the user stops the mic mid-phrase). */
    fun finish() {
        if (inSpeech) endUtterance()
    }

    private fun endUtterance() {
        inSpeech = false
        onSpeechStateChange?.invoke(false)
        val chunks = speech.toList()
        speech.clear()
        silenceRun = 0
        if (chunks.size < MIN_UTTERANCE_CHUNKS) return

        val pcm = FloatArray(chunks.size * SileroVad.CHUNK)
        chunks.forEachIndexed { i, c -> c.copyInto(pcm, i * SileroVad.CHUNK) }
        onUtterance(pcm)
    }
}
