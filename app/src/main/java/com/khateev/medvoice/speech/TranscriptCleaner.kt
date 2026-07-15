/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.speech

object TranscriptCleaner {

    /**
     * Whisper emits meta-annotations for non-speech audio: "(speaking in foreign
     * language)", "(music)", "[BLANK_AUDIO]", "♪ ... ♪". These are not dictation —
     * left in, they classify as real commands ("(speaking in foreign language)"
     * hit gen_call_interpreter at 100%). Strip them; an empty result means the
     * segment contained no usable English speech.
     */
    fun stripNonSpeech(text: String): String =
        text.replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("♪[^♪]*♪"), " ")
            .replace("♪", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * With reduced audio_ctx the whisper decoder occasionally emits the utterance
     * twice ("log heart rate 72 log heart rate 72"). Exact whole-text doubling
     * carries no information — collapse it (repeatedly, so 4x also folds).
     */
    fun collapseRepetitions(text: String): String {
        fun norm(w: String) = w.lowercase().trim('.', ',', '!', '?', ';', ':')
        var words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        while (words.size >= 4 && words.size % 2 == 0) {
            val half = words.size / 2
            val doubled = (0 until half).all { norm(words[it]) == norm(words[it + half]) }
            if (!doubled) break
            words = words.subList(0, half)
        }
        return words.joinToString(" ")
    }
}
