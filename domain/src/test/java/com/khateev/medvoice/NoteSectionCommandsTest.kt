/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice
import com.khateev.medvoice.domain.model.NoteSection

import com.khateev.medvoice.domain.NoteSectionCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteSectionCommandsTest {

    @Test
    fun navigationPhrases_matchTheirSections() {
        assertEquals(NoteSection.PLAN, NoteSectionCommands.match("go to plan"))
        assertEquals(NoteSection.PLAN, NoteSectionCommands.match("Go to the plan."))
        assertEquals(NoteSection.ASSESSMENT, NoteSectionCommands.match("start assessment"))
        assertEquals(NoteSection.OBJECTIVE, NoteSectionCommands.match("switch to objective"))
        assertEquals(NoteSection.SUBJECTIVE, NoteSectionCommands.match("subjective"))
        assertEquals(NoteSection.PLAN, NoteSectionCommands.match("open the plan section"))
    }

    @Test
    fun whisperPunctuationAndCase_areIgnored() {
        assertEquals(NoteSection.PLAN, NoteSectionCommands.match(" Plan. "))
        assertEquals(NoteSection.ASSESSMENT, NoteSectionCommands.match("Start Assessment!"))
    }

    @Test
    fun dictatedContentMentioningSectionWords_doesNotSwitch() {
        assertNull(NoteSectionCommands.match("the patient plans to walk today"))
        assertNull(NoteSectionCommands.match("objective findings include mild swelling"))
        assertNull(NoteSectionCommands.match("we will assess the wound tomorrow"))
    }
}
