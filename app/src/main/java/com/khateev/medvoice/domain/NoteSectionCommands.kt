/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

import com.khateev.medvoice.domain.model.NoteSection

/**
 * Voice navigation between SOAP note sections during note dictation.
 *
 * Deliberately exact-phrase matching (like VoiceShortcuts), not the intent
 * model: it needs no retraining, and free dictated text that merely mentions
 * "plan" ("the patient plans to walk") must never switch sections — only a
 * standalone phrase like "go to plan" or "start assessment" does.
 */
object NoteSectionCommands {

    private val VERBS = listOf("", "go to", "start", "switch to", "open")
    private val PHRASES: Map<String, NoteSection> = buildMap {
        for (section in NoteSection.entries) {
            for (verb in VERBS) {
                for (article in listOf("", "the")) {
                    for (suffix in listOf("", "section")) {
                        val phrase = listOf(verb, article, section.spoken, suffix)
                            .filter { it.isNotEmpty() }
                            .joinToString(" ")
                        put(phrase, section)
                    }
                }
            }
        }
    }

    fun match(text: String): NoteSection? =
        PHRASES[text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()]
}
