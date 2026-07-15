/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.ml

/**
 * Rule-based value extraction for dictated vitals.
 *
 * The intent model only classifies WHICH command was spoken; the numeric payload
 * (BP 120/80, temp 38.5, ...) is extracted here. Numbers are a closed, structured
 * domain with known physiological ranges, so regex + range validation is more
 * predictable than a learned extractor: it either parses correctly or fails
 * loudly so the app can re-prompt.
 *
 * ASR usually normalizes numerals already ("one twenty over eighty" -> "120 over 80"),
 * but [normalizeNumbers] covers the spoken-word fallback.
 */
object VitalsExtractor {

    data class BloodPressure(val systolic: Int, val diastolic: Int)

    private val UNITS = mapOf(
        "zero" to 0, "oh" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9
    )
    private val TEENS = mapOf(
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
        "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    /**
     * Converts spoken number words to digits:
     * "one twenty over eighty" -> "120 over 80"
     * "ninety eight point six"  -> "98.6"
     * "one hundred twenty five" -> "125"
     */
    fun normalizeNumbers(text: String): String {
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = mutableListOf<String>()
        var i = 0

        while (i < words.size) {
            val word = words[i].trim(',', '.', '?', '!')
            if (word !in UNITS && word !in TEENS && word !in TENS) {
                out.add(words[i])
                i++
                continue
            }

            // Parse a number word sequence
            var value = 0
            var consumed = 0
            var j = i
            while (j < words.size) {
                val w = words[j].trim(',', '.', '?', '!')
                when {
                    w == "hundred" && consumed > 0 -> { value *= 100; j++; consumed++ }
                    w in UNITS -> {
                        val u = UNITS[w]!!
                        when {
                            consumed == 0 -> value = u
                            // "one twenty" style handled below via TENS; unit after tens: "twenty five"
                            value % 10 == 0 && value % 100 != 0 && u != 0 -> value += u
                            value in 1..9 && u != 0 -> value = value * 10 + u // "one two zero" digit dictation
                            value % 100 == 0 -> value += u // "one hundred five"
                            else -> break
                        }
                        j++; consumed++
                    }
                    w in TEENS -> {
                        val t = TEENS[w]!!
                        when {
                            consumed == 0 -> value = t
                            value in 1..9 -> value = value * 100 + t   // "one nineteen" -> 119
                            value % 100 == 0 -> value += t             // "one hundred nineteen"
                            else -> break
                        }
                        j++; consumed++
                    }
                    w in TENS -> {
                        val t = TENS[w]!!
                        when {
                            consumed == 0 -> value = t
                            value in 1..9 -> value = value * 100 + t   // "one twenty" -> 120
                            value % 100 == 0 -> value += t             // "one hundred twenty"
                            else -> break
                        }
                        j++; consumed++
                    }
                    else -> break
                }
                // Stop after a completed unit unless "hundred"/tens continuation follows
                if (consumed > 0 && j < words.size) {
                    val next = words[j].trim(',', '.', '?', '!')
                    val continues = next == "hundred" || next in UNITS || next in TEENS || next in TENS
                    if (!continues) break
                }
            }

            // Decimal: "<int> point <digits>"
            if (j < words.size && words[j].trim(',', '.') == "point") {
                val fracWords = mutableListOf<Int>()
                var k = j + 1
                while (k < words.size) {
                    val w = words[k].trim(',', '.', '?', '!')
                    val d = UNITS[w] ?: TEENS[w] // "point five", "point sixteen" (rare)
                    if (d != null && d < 10) { fracWords.add(d); k++ } else break
                }
                if (fracWords.isNotEmpty()) {
                    out.add("$value.${fracWords.joinToString("")}")
                    i = k
                    continue
                }
            }

            out.add(value.toString())
            i = j
        }
        return out.joinToString(" ")
    }

    /**
     * Light cleanup before intent classification. The v2 model is trained with
     * digits, decimals, "/" and trailing periods (so numbers stay in the phrase —
     * the old strip-numbers workaround is gone); only separator punctuation that
     * whisper inserts mid-sentence is removed.
     */
    fun sanitizeForClassification(text: String): String =
        text.replace(Regex("[,!?;:'\"“”]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun extractBloodPressure(rawText: String): BloodPressure? {
        val text = normalizeNumbers(rawText)
        val bp = Regex("(\\d{2,3})\\s*(?:over|/|on)\\s*(\\d{2,3})").find(text)
        if (bp != null) {
            val sys = bp.groupValues[1].toInt()
            val dia = bp.groupValues[2].toInt()
            if (sys in 60..260 && dia in 30..160 && sys > dia) {
                return BloodPressure(sys, dia)
            }
        }
        // Fallback: two plausible standalone numbers ("bp 120 80")
        val nums = Regex("\\d{2,3}").findAll(text).map { it.value.toInt() }.toList()
        if (nums.size >= 2) {
            val (sys, dia) = nums[0] to nums[1]
            if (sys in 60..260 && dia in 30..160 && sys > dia) {
                return BloodPressure(sys, dia)
            }
        }
        return null
    }

    /** Extracts the first number within [min, max] from the phrase. */
    fun extractInRange(rawText: String, min: Double, max: Double): Double? {
        val text = normalizeNumbers(rawText)
        return Regex("\\d{1,3}(?:\\.\\d{1,2})?").findAll(text)
            .map { it.value.toDouble() }
            .firstOrNull { it in min..max }
    }

    fun extractHeartRate(text: String): Int? = extractInRange(text, 20.0, 260.0)?.toInt()

    fun extractTemperature(text: String): Double? {
        // Celsius first, then Fahrenheit converted to Celsius
        extractInRange(text, 30.0, 45.0)?.let { return it }
        extractInRange(text, 90.0, 110.0)?.let { return ((it - 32) * 5 / 9 * 10).toInt() / 10.0 }
        return null
    }

    fun extractOxygenSaturation(text: String): Int? = extractInRange(text, 50.0, 100.0)?.toInt()

    fun extractRespiratoryRate(text: String): Int? = extractInRange(text, 4.0, 60.0)?.toInt()

    fun extractWeight(text: String): Double? = extractInRange(text, 1.0, 400.0)

    fun extractPainLevel(text: String): Int? = extractInRange(text, 0.0, 10.0)?.toInt()
}
