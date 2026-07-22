/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.domain

/**
 * Short spoken phrases handled BEFORE intent classification: dialog
 * confirmation ("confirm"/"cancel") and undo of the last recorded vital.
 * Matched exactly against the normalized transcript — a longer sentence like
 * "cancel the appointment" is NOT a shortcut and goes to the intent model.
 */
object VoiceShortcuts {

    private val CONFIRM = setOf(
        "confirm", "confirmed", "yes", "yes confirm", "i confirm", "confirm it", "do it"
    )

    private val CANCEL = setOf(
        "cancel", "no", "no cancel", "cancel it", "abort", "dismiss", "never mind", "nevermind",
        // accent-robust canonical form: 96% cross-accent vs 64% for bare "cancel"
        "negative"
    )

    private val UNDO = setOf(
        "undo", "undo that", "undo it", "undo last", "undo last entry",
        "scratch that", "cancel that", "delete that", "remove that"
    )

    fun isConfirm(text: String): Boolean = normalize(text) in CONFIRM

    fun isCancel(text: String): Boolean = normalize(text) in CANCEL

    fun isUndo(text: String): Boolean = normalize(text) in UNDO

    private fun normalize(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
}
