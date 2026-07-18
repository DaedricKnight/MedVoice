/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

import com.khateev.medvoice.NoteSection
import com.khateev.medvoice.VitalType

/**
 * A read-only "chart review" request: the clinician asks and the chart answers
 * by voice, instead of dictating data in. Distinct from commands — nothing is
 * written.
 */
sealed interface ChartQuery {
    /** Any alarming vitals plus the NEWS2 status — bedside triage. */
    data object CriticalValues : ChartQuery

    /** Just the NEWS2 early-warning score and its risk band. */
    data object News2 : ChartQuery

    /** Every vital currently on the chart, read in order. */
    data object LatestVitals : ChartQuery

    /** One vital's latest value with its trend against the previous reading. */
    data class Vital(val type: VitalType) : ChartQuery

    /** A SOAP note section, or the whole note when [section] is null. */
    data class Note(val section: NoteSection?) : ChartQuery

    /** A recap of the most recent chart activity from the audit log. */
    data object Activity : ChartQuery

    /** A synthesized SBAR-style spoken handover summary ("catch me up"). */
    data object Summary : ChartQuery
}

/**
 * Deterministic exact-phrase matcher for chart-review queries — no model, no
 * retraining (Phase 1). Matched in the pipeline before classification, like
 * [com.khateev.medvoice.ml.VoiceShortcuts]. Whole-utterance match keeps it
 * safe: only a standalone question triggers a read-back, never a stray word
 * inside a command.
 */
object ChartQueries {

    /** Spoken names → vital, most specific first so "resp rate" wins over "rate". */
    private val VITAL_TERMS: List<Pair<String, VitalType>> = listOf(
        "blood pressure" to VitalType.BLOOD_PRESSURE,
        "bp" to VitalType.BLOOD_PRESSURE,
        "pressure" to VitalType.BLOOD_PRESSURE,
        "heart rate" to VitalType.HEART_RATE,
        "pulse" to VitalType.HEART_RATE,
        "temperature" to VitalType.TEMPERATURE,
        "temp" to VitalType.TEMPERATURE,
        "oxygen saturation" to VitalType.OXYGEN_SATURATION,
        "oxygen" to VitalType.OXYGEN_SATURATION,
        "sats" to VitalType.OXYGEN_SATURATION,
        "spo2" to VitalType.OXYGEN_SATURATION,
        "respiratory rate" to VitalType.RESPIRATORY_RATE,
        "resp rate" to VitalType.RESPIRATORY_RATE,
        "breathing rate" to VitalType.RESPIRATORY_RATE,
        "weight" to VitalType.WEIGHT,
        "pain level" to VitalType.PAIN_LEVEL,
        "pain score" to VitalType.PAIN_LEVEL,
        "pain" to VitalType.PAIN_LEVEL,
    )

    private val VITAL_TEMPLATES = listOf(
        "latest %s", "the latest %s", "latest %s reading",
        "what is the %s", "whats the %s",
        "how is the %s", "hows the %s",
        "read the %s", "read me the %s",
        "show me the %s", "give me the %s", "tell me the %s",
        "current %s", "the %s", "%s trend", "%s please",
    )

    private val phrases: Map<String, ChartQuery> = buildMap {
        // Global queries
        listOf(
            "any critical values", "anything critical", "critical values",
            "any red flags", "any alerts", "anything abnormal", "any concerns",
        ).forEach { putIfAbsent(it, ChartQuery.CriticalValues) }

        // "news two" is normalized to "news 2" upstream — cover both.
        listOf(
            "news score", "the news score", "news 2 score", "news two score",
            "news2 score", "what is the news score", "whats the news score",
            "how is the news score",
        ).forEach { putIfAbsent(it, ChartQuery.News2) }

        listOf(
            "latest vitals", "current vitals", "the vitals", "read the vitals",
            "read me the vitals", "read vitals", "what are the vitals",
            "vitals please", "latest observations", "read the observations",
        ).forEach { putIfAbsent(it, ChartQuery.LatestVitals) }

        listOf(
            "what has been done", "what's been done", "whats been done",
            "recent activity", "recent changes", "what happened recently",
            "recap", "whats been recorded", "what's been recorded", "whats new",
        ).forEach { putIfAbsent(it, ChartQuery.Activity) }

        listOf(
            "catch me up", "patient summary", "give me a summary", "give me the summary",
            "summarize the patient", "summarise the patient", "summarize this patient",
            "summarise this patient", "sum up the patient", "brief me", "sbar",
        ).forEach { putIfAbsent(it, ChartQuery.Summary) }

        // Note sections
        for (section in NoteSection.entries) {
            val s = section.spoken
            listOf(
                "read the $s", "read me the $s", "the $s",
                "what is the $s", "whats the $s", "read the $s section",
            ).forEach { putIfAbsent(it, ChartQuery.Note(section)) }
        }
        listOf("read the note", "read me the note", "read the notes", "the note", "read the whole note")
            .forEach { putIfAbsent(it, ChartQuery.Note(null)) }

        // Per-vital targeted queries (templates × synonyms)
        for ((term, type) in VITAL_TERMS) {
            for (template in VITAL_TEMPLATES) {
                putIfAbsent(template.format(term), ChartQuery.Vital(type))
            }
        }
    }

    fun match(text: String): ChartQuery? = phrases[normalize(text)]

    private fun normalize(s: String): String =
        s.lowercase()
            .replace("'", "").replace("’", "")   // what's -> whats (not "what s")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
