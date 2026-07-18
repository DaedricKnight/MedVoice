/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

/**
 * Detects a fluid intake/output (I/O) log entry — "urine output 300",
 * "oral intake 200 mL", "IV fluids 500", "drain 50 mils" — deterministically,
 * no model. A category keyword plus an amount are both required, so review
 * questions ("fluid balance", "urine output") without a number fall through to
 * [ChartQueries], and med orders ("order IV fluids", no amount) fall through to
 * the classifier.
 */
object FluidMatcher {

    enum class Kind { INTAKE, OUTPUT }

    data class Reading(val kind: Kind, val category: String, val amountMl: Int)

    // keyword -> category. Multi-word keys are matched as phrases.
    private val OUTPUT = listOf(
        "urine" to "urine", "urinary" to "urine", "voided" to "urine", "catheter" to "urine",
        "drain" to "drain", "drainage" to "drain",
        "emesis" to "emesis", "vomit" to "emesis", "vomited" to "emesis",
        "stool" to "stool", "bowel" to "stool",
        "output" to "output",
    )
    private val INTAKE = listOf(
        "oral" to "oral", "by mouth" to "oral", "drank" to "oral", "drinking" to "oral",
        "iv" to "IV", "intravenous" to "IV",
        "tube feed" to "tube feed", "enteral" to "tube feed", "ng feed" to "tube feed",
        "fluid intake" to "intake", "intake" to "intake",
    )

    private const val MAX_ML = 20_000

    fun match(rawText: String): Reading? {
        val text = " " + normalize(rawText) + " "
        // "order IV fluids" / "prescribe" is a medication order, not an I/O log.
        if (" order " in text || " prescribe " in text) return null

        val amount = amountMl(text) ?: return null

        // Pick the category keyword that appears earliest in the phrase, so
        // "urine output 300" logs urine (specific) not the generic "output".
        var best: Pair<Kind, String>? = null
        var bestIndex = Int.MAX_VALUE
        for ((keyword, category) in OUTPUT) {
            val idx = text.indexOf(" $keyword ")
            if (idx in 0 until bestIndex) { bestIndex = idx; best = Kind.OUTPUT to category }
        }
        for ((keyword, category) in INTAKE) {
            val idx = text.indexOf(" $keyword ")
            if (idx in 0 until bestIndex) { bestIndex = idx; best = Kind.INTAKE to category }
        }
        val (kind, category) = best ?: return null
        return Reading(kind, category, amount)
    }

    /** Amount in millilitres: litres and "thousand" are scaled up; else the first integer. */
    private fun amountMl(text: String): Int? {
        Regex("""(\d+(?:\.\d+)?)\s*(?:l|liter|litre|liters|litres)\b""").find(text)?.let {
            val ml = (it.groupValues[1].toDouble() * 1000).toInt()
            return ml.takeIf { v -> v in 1..MAX_ML }
        }
        Regex("""(\d+)\s*thousand""").find(text)?.let {
            val ml = it.groupValues[1].toInt() * 1000
            return ml.takeIf { v -> v in 1..MAX_ML }
        }
        Regex("""\d{1,5}""").find(text)?.let {
            return it.value.toInt().takeIf { v -> v in 1..MAX_ML }
        }
        return null
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9. ]"), " ").replace(Regex("\\s+"), " ").trim()
}
