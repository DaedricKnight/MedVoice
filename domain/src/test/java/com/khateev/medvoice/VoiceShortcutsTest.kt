/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import com.khateev.medvoice.domain.VoiceShortcuts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceShortcutsTest {

    @Test
    fun `confirm phrases match with whisper punctuation`() {
        assertTrue(VoiceShortcuts.isConfirm("Confirm."))
        assertTrue(VoiceShortcuts.isConfirm("Yes, confirm!"))
        assertTrue(VoiceShortcuts.isConfirm("yes"))
        assertFalse(VoiceShortcuts.isConfirm("confirm patient identity"))
    }

    @Test
    fun `cancel phrases match`() {
        assertTrue(VoiceShortcuts.isCancel("Cancel."))
        assertTrue(VoiceShortcuts.isCancel("No."))
        assertTrue(VoiceShortcuts.isCancel("never mind"))
        assertTrue(VoiceShortcuts.isCancel("Negative."))
        // full sentences are intents, not shortcuts
        assertFalse(VoiceShortcuts.isCancel("cancel the appointment"))
        assertFalse(VoiceShortcuts.isCancel("negative lab results"))
    }

    @Test
    fun `undo phrases match`() {
        assertTrue(VoiceShortcuts.isUndo("Undo."))
        assertTrue(VoiceShortcuts.isUndo("undo that"))
        assertTrue(VoiceShortcuts.isUndo("Scratch that!"))
        assertTrue(VoiceShortcuts.isUndo("cancel that"))
        assertFalse(VoiceShortcuts.isUndo("undo the medication changes please"))
        assertFalse(VoiceShortcuts.isUndo("record blood pressure 120 over 80"))
    }
}
