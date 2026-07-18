/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.domain.IntentDescriptions

/**
 * Builds the spoken + on-screen answers for voice chart review from data that
 * is actually recorded. Pure and JVM-testable.
 *
 * Hard rule: never invent. When data is absent the answer says so ("no X on
 * record") — a fabricated vital or medication read aloud at the bedside is a
 * safety hazard.
 */
object ChartAnswers {

    /** [spoken] is read by TTS; [display] backs the on-screen read-back card. */
    data class Answer(val spoken: String, val display: String)

    // --- Triage ---

    fun criticalValues(vitals: Map<VitalType, VitalValue>, news2: News2.Assessment?): Answer {
        if (vitals.isEmpty()) return same("No vitals recorded yet.")
        val flagged = VitalType.entries.filter { vitals[it]?.critical == true }
        val news = news2Clause(news2)
        return if (flagged.isEmpty()) {
            same("No critical values. $news")
        } else {
            val list = flagged.joinToString(", ") { "${IntentDescriptions.ttsName(it)} ${spoken(it, vitals[it]!!.value)}" }
            val lead = if (flagged.size == 1) "One critical value" else "${flagged.size} critical values"
            Answer(
                spoken = "$lead: $list. $news",
                display = "$lead: " + flagged.joinToString(", ") { "${it.display} ${vitals[it]!!.value}" } + "\n$news"
            )
        }
    }

    // --- Vitals ---

    fun news2(assessment: News2.Assessment?): Answer =
        same(if (assessment == null) "Not enough vitals for a NEWS2 score yet." else news2Clause(assessment))

    fun vital(type: VitalType, latest: VitalValue?, previous: String?): Answer {
        if (latest == null) return same("No ${IntentDescriptions.ttsName(type).lowercase()} on record.")
        val trend = trend(type, previous, latest.value)
        return Answer(
            spoken = "${IntentDescriptions.ttsName(type)} ${spoken(type, latest.value)}${trend?.spoken ?: ""}.",
            display = "${type.display}: ${latest.value} ${type.unit}${trend?.display ?: ""} · ${latest.time}"
        )
    }

    fun latestVitals(vitals: Map<VitalType, VitalValue>): Answer {
        val present = VitalType.entries.filter { vitals[it] != null }
        if (present.isEmpty()) return same("No vitals recorded yet.")
        return Answer(
            spoken = "Latest vitals: " + present.joinToString(", ") {
                "${IntentDescriptions.ttsName(it)} ${spoken(it, vitals[it]!!.value)}"
            } + ".",
            display = present.joinToString("\n") { "${it.display}: ${vitals[it]!!.value} ${it.unit}" }
        )
    }

    // --- Notes ---

    fun note(section: NoteSection?, notes: Map<NoteSection, String>): Answer {
        if (section != null) {
            val text = notes[section]?.takeIf { it.isNotBlank() }
                ?: return same("The ${section.display.lowercase()} section is empty.")
            return same("${section.display}. $text")
        }
        val filled = NoteSection.entries.filter { notes[it]?.isNotBlank() == true }
        if (filled.isEmpty()) return same("No clinical note recorded yet.")
        return Answer(
            spoken = filled.joinToString(" ") { "${it.display}. ${notes[it]}" },
            display = filled.joinToString("\n\n") { "${it.display}\n${notes[it]}" }
        )
    }

    // --- Activity ---

    fun activity(recentMessages: List<String>): Answer {
        val items = recentMessages.filter { it.isNotBlank() }.take(4)
        if (items.isEmpty()) return same("No recent activity on this chart.")
        return Answer(
            spoken = "Recently: " + items.joinToString("; ") + ".",
            display = items.joinToString("\n") { "· $it" }
        )
    }

    // --- SBAR handover summary ---

    fun summary(
        patientName: String,
        ward: String,
        news2: News2.Assessment?,
        vitals: Map<VitalType, VitalValue>,
        plan: String?,
        mostRecentActivity: String?
    ): Answer {
        val spoken = StringBuilder()
        val display = StringBuilder()

        val who = listOf(patientName, ward).filter { it.isNotBlank() }.joinToString(", ")
        if (who.isNotBlank()) { spoken.append("$who. "); display.append("$who\n") }

        if (news2 != null) {
            spoken.append("${news2Clause(news2)} ")
            display.append("NEWS2 ${news2.score} · ${riskPhrase(news2.risk)}\n")
        }

        // Prefer the flagged vitals; otherwise the most clinically telling few.
        val flagged = VitalType.entries.filter { vitals[it]?.critical == true }
        val shown = flagged.ifEmpty {
            listOf(VitalType.BLOOD_PRESSURE, VitalType.HEART_RATE, VitalType.OXYGEN_SATURATION)
                .filter { vitals[it] != null }
        }
        if (shown.isNotEmpty()) {
            val label = if (flagged.isNotEmpty()) "Flagged" else "Latest"
            spoken.append("$label: " + shown.joinToString(", ") {
                "${IntentDescriptions.ttsName(it)} ${spoken(it, vitals[it]!!.value)}"
            } + ". ")
            display.append("$label: " + shown.joinToString(", ") { "${it.display} ${vitals[it]!!.value}" } + "\n")
        }

        if (!plan.isNullOrBlank()) {
            spoken.append("Plan: $plan. ")
            display.append("Plan: $plan\n")
        }
        if (!mostRecentActivity.isNullOrBlank()) {
            spoken.append("Most recent: $mostRecentActivity.")
            display.append("Most recent: $mostRecentActivity")
        }

        val result = spoken.toString().trim()
        return Answer(
            spoken = result.ifEmpty { "No data recorded for this patient yet." },
            display = display.toString().trim().ifEmpty { "No data recorded for this patient yet." }
        )
    }

    // --- helpers ---

    private data class Trend(val spoken: String, val display: String)

    private fun trend(type: VitalType, previous: String?, current: String): Trend? {
        val prev = previous?.substringBefore('/')?.trim()?.toDoubleOrNull() ?: return null
        val cur = current.substringBefore('/').trim().toDoubleOrNull() ?: return null
        if (prev == cur) return null
        val dir = if (cur > prev) "up" else "down"
        val arrow = if (cur > prev) "↑" else "↓"
        return Trend(", $dir from ${spoken(type, previous)}", " $arrow from $previous")
    }

    /** Blood pressure reads far better as "120 over 80" than "120 slash 80". */
    private fun spoken(type: VitalType, value: String): String =
        if (type == VitalType.BLOOD_PRESSURE) value.replace("/", " over ") else value

    private fun news2Clause(a: News2.Assessment?): String =
        if (a == null) "No NEWS2 score yet."
        else "NEWS2 is ${a.score}, ${riskPhrase(a.risk)}."

    private fun riskPhrase(risk: News2.Risk): String = when (risk) {
        News2.Risk.LOW -> "low risk"
        News2.Risk.LOW_MEDIUM -> "low to medium risk"
        News2.Risk.MEDIUM -> "urgent review"
        News2.Risk.HIGH -> "emergency response"
    }

    private fun same(text: String) = Answer(text, text)
}
