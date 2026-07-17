/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.ml

/**
 * Resolves a patient mentioned in a dictated phrase against the known patient
 * list ("open patient chart for jane roe" -> Jane Roe). Uses exact full-name
 * containment first, then per-token matching with Levenshtein tolerance 1 for
 * ASR slips ("row" -> "roe"). Ambiguity returns null — the UI falls back to
 * the patient picker rather than guessing.
 */
object PatientMatcher {

    data class Candidate(val id: Long, val name: String)

    /**
     * A resolved patient plus whether the roster holds a look-alike name a
     * mishearing could have meant — the caller confirms before switching charts.
     */
    data class Match(val patient: Candidate, val ambiguous: Boolean)

    /**
     * Like [match], but also flags the wrong-chart risk: when the resolved
     * patient has a [confusable] name-twin in the roster (e.g. "Jane Roe" and
     * "Jane Doe"), [Match.ambiguous] is true so the UI can require confirmation
     * instead of switching silently. Null still means "no resolvable name".
     */
    fun resolve(rawText: String, patients: List<Candidate>): Match? {
        val best = match(rawText, patients) ?: return null
        val ambiguous = patients.any { it.id != best.id && confusable(it.name, best.name) }
        return Match(best, ambiguous)
    }

    fun match(rawText: String, patients: List<Candidate>): Candidate? {
        val text = normalize(rawText)
        if (text.isBlank()) return null

        val fullMatches = patients.filter { text.contains(normalize(it.name)) }
        if (fullMatches.size == 1) return fullMatches[0]
        if (fullMatches.size > 1) return null

        val words = text.split(" ")
        val scored = patients.mapNotNull { patient ->
            val tokens = normalize(patient.name).split(" ")
            val hits = tokens.count { token -> words.any { word -> close(word, token) } }
            if (hits > 0) patient to hits else null
        }
        val bestScore = scored.maxOfOrNull { it.second } ?: return null
        val best = scored.filter { it.second == bestScore }
        return if (best.size == 1) best[0].first else null
    }

    /**
     * Removes known patient-name tokens before intent classification: names are
     * out-of-distribution for the intent model (same issue as numbers) —
     * "open chart for robert miles" scores 55% while "open chart for" is 100%.
     */
    fun stripNames(text: String, patients: List<Candidate>): String {
        val nameTokens = patients
            .flatMap { normalize(it.name).split(" ") }
            .filter { it.length >= 3 }
            .toSet()
        if (nameTokens.isEmpty()) return text
        return text.split(Regex("\\s+"))
            .filter { it.lowercase().trim('.', ',', '!', '?') !in nameTokens }
            .joinToString(" ")
    }

    /**
     * True for two roster names a mishearing could swap: same token count and
     * exactly one token differing by a single edit ("jane roe" vs "jane doe",
     * "jon doe" vs "john doe"). Identical names are duplicates, not confusable.
     */
    fun confusable(a: String, b: String): Boolean {
        val ta = normalize(a).split(" ").filter { it.isNotEmpty() }
        val tb = normalize(b).split(" ").filter { it.isNotEmpty() }
        if (ta.isEmpty() || ta.size != tb.size || ta == tb) return false
        var diffs = 0
        for (i in ta.indices) {
            when (levenshtein(ta[i], tb[i])) {
                0 -> {}
                1 -> diffs++
                else -> return false
            }
        }
        return diffs == 1
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (b.length < 4 || a.length < 3) return false
        return levenshtein(a, b) <= 1
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(
                    dp[j] + 1,
                    dp[j - 1] + 1,
                    prev + if (a[i - 1] == b[j - 1]) 0 else 1
                )
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
