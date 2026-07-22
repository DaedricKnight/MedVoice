/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

/**
 * Splits one spoken utterance into independent command segments, so a nurse
 * can dictate a whole round in one breath: "BP 120 over 80, pulse 72, temp
 * is 36.8, sats 97" becomes four commands.
 *
 * Split points are whisper's own punctuation: commas, semicolons and
 * sentence-ending periods. Decimals ("36.8") and BP slashes ("120/80") are
 * never split. "and" is deliberately NOT a separator: it appears inside
 * legitimate single commands ("chest pain and shortness of breath").
 */
object UtteranceSplitter {

    // A period splits only when followed by whitespace/end AND not between digits
    // ("36.8" stays intact; "sats 97. heart rate 72" splits).
    private val SEPARATOR = Regex("""[,;]|(?<!\d)\.(?=\s|$)|(?<=\d)\.(?=\s+\p{L})""")

    fun split(text: String): List<String> =
        text.split(SEPARATOR)
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotEmpty() }
}
