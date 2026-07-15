/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.khateev.medvoice.data.AuditEntity
import com.khateev.medvoice.data.ChartDatabase
import com.khateev.medvoice.data.ChartRepository
import com.khateev.medvoice.data.NoteEntity
import com.khateev.medvoice.data.PatientEntity
import com.khateev.medvoice.data.VitalEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChartDatabaseTest {

    private lateinit var db: ChartDatabase
    private lateinit var repo: ChartRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ChartDatabase::class.java).build()
        repo = ChartRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun latestVitalPerType_returnsNewestAndIsPatientScoped() = runBlocking {
        val p1 = db.patients().insert(PatientEntity(name = "A", mrn = "1", ward = "W1"))
        val p2 = db.patients().insert(PatientEntity(name = "B", mrn = "2", ward = "W2"))

        db.vitals().insert(VitalEntity(patientId = p1, type = "HEART_RATE", value = "70", timestamp = 1))
        db.vitals().insert(VitalEntity(patientId = p1, type = "HEART_RATE", value = "85", timestamp = 2))
        db.vitals().insert(VitalEntity(patientId = p1, type = "WEIGHT", value = "80", timestamp = 3))
        db.vitals().insert(VitalEntity(patientId = p2, type = "HEART_RATE", value = "99", timestamp = 4))

        val latestP1 = db.vitals().observeLatestPerType(p1).first()
        assertEquals(2, latestP1.size)
        assertEquals("85", latestP1.first { it.type == "HEART_RATE" }.value)
        assertEquals("80", latestP1.first { it.type == "WEIGHT" }.value)

        val latestP2 = db.vitals().observeLatestPerType(p2).first()
        assertEquals(1, latestP2.size)
        assertEquals("99", latestP2[0].value)
    }

    @Test
    fun noteUpsert_overwritesPerPatient() = runBlocking {
        val p1 = db.patients().insert(PatientEntity(name = "A", mrn = "1", ward = "W1"))
        db.notes().upsert(NoteEntity(patientId = p1, text = "first", updatedAt = 1))
        db.notes().upsert(NoteEntity(patientId = p1, text = "first second", updatedAt = 2))

        assertEquals("first second", db.notes().observe(p1).first()?.text)
    }

    @Test
    fun vitalsAndNote_surviveDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("persistence-test.db")

        var fileDb = Room.databaseBuilder(context, ChartDatabase::class.java, "persistence-test.db").build()
        val pid = fileDb.patients().insert(PatientEntity(name = "P", mrn = "42", ward = "W"))
        fileDb.vitals().insert(VitalEntity(patientId = pid, type = "HEART_RATE", value = "72", timestamp = 1))
        fileDb.notes().upsert(NoteEntity(patientId = pid, text = "persisted note", updatedAt = 1))
        fileDb.close()

        // Reopen from disk — simulates app process death and restart
        fileDb = Room.databaseBuilder(context, ChartDatabase::class.java, "persistence-test.db").build()
        val vitals = fileDb.vitals().observeLatestPerType(pid).first()
        assertEquals("72", vitals.single { it.type == "HEART_RATE" }.value)
        assertEquals("persisted note", fileDb.notes().observe(pid).first()?.text)
        fileDb.close()
        context.deleteDatabase("persistence-test.db")
        Unit
    }

    @Test
    fun undoSemantics_lastVitalIsDeletedAndHistoryOrdered() = runBlocking {
        val pid = db.patients().insert(PatientEntity(name = "A", mrn = "1", ward = "W"))
        db.vitals().insert(VitalEntity(patientId = pid, type = "HEART_RATE", value = "70", timestamp = 1))
        db.vitals().insert(VitalEntity(patientId = pid, type = "HEART_RATE", value = "85", timestamp = 2))
        db.vitals().insert(VitalEntity(patientId = pid, type = "WEIGHT", value = "80", timestamp = 3))

        // history is chronological per type
        val history = db.vitals().historyFor(pid, "HEART_RATE")
        assertEquals(listOf("70", "85"), history.map { it.value })

        // undo removes the most recent record regardless of type
        val last = db.vitals().lastForPatient(pid)
        assertEquals("WEIGHT", last?.type)
        db.vitals().deleteById(last!!.id)

        assertEquals("85", db.vitals().lastForPatient(pid)?.value)
        assertEquals(1, db.vitals().observeLatestPerType(pid).first().count { it.type == "HEART_RATE" })
        Unit
    }

    @Test
    fun auditTrail_persistsAndOrdersNewestFirst() = runBlocking {
        db.audit().insert(AuditEntity(timestamp = 1, patientId = 1, rawText = "log heart rate 72", intent = "vitals_log_heart_rate", confidence = 1.0f, message = "Heart Rate recorded: 72 bpm", kind = "OK"))
        db.audit().insert(AuditEntity(timestamp = 2, patientId = null, rawText = null, intent = null, confidence = null, message = "Model loaded", kind = "INFO"))

        val recent = db.audit().observeRecent(10).first()
        assertEquals(2, recent.size)
        assertEquals("Model loaded", recent[0].message)          // newest first
        assertEquals("vitals_log_heart_rate", recent[1].intent)
        assertEquals(1.0f, recent[1].confidence)
        assertEquals("log heart rate 72", recent[1].rawText)
    }

    @Test
    fun seedIfEmpty_insertsDemoPatientsOnce() = runBlocking {
        val firstId = repo.seedIfEmpty()
        val patients = repo.patients().first()
        assertEquals(3, patients.size)
        assertEquals(firstId, patients.first().id)

        repo.seedIfEmpty() // second call must not duplicate
        assertEquals(3, repo.patients().first().size)
        assertTrue(patients.any { it.name == "John Doe" })
    }
}
