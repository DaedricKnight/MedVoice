/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.data

import kotlinx.coroutines.flow.Flow

class ChartRepository(private val db: ChartDatabase) {

    fun patients(): Flow<List<PatientEntity>> = db.patients().observeAll()

    fun latestVitals(patientId: Long): Flow<List<VitalEntity>> =
        db.vitals().observeLatestPerType(patientId)

    fun notes(patientId: Long): Flow<List<NoteEntity>> = db.notes().observe(patientId)

    suspend fun recordVital(patientId: Long, type: String, value: String) {
        db.vitals().insert(
            VitalEntity(
                patientId = patientId,
                type = type,
                value = value,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveNote(patientId: Long, section: String, text: String) {
        db.notes().upsert(
            NoteEntity(
                patientId = patientId,
                section = section,
                text = text,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun addPatient(name: String, mrn: String, ward: String): Long =
        db.patients().insert(PatientEntity(name = name, mrn = mrn, ward = ward))

    suspend fun vitalHistory(patientId: Long, type: String): List<VitalEntity> =
        db.vitals().historyFor(patientId, type)

    suspend fun lastVital(patientId: Long): VitalEntity? =
        db.vitals().lastForPatient(patientId)

    suspend fun lastVitalOfType(patientId: Long, type: String): VitalEntity? =
        db.vitals().lastOfType(patientId, type)

    suspend fun deleteVital(id: Long) = db.vitals().deleteById(id)

    fun recentAudit(limit: Int = 100): Flow<List<AuditEntity>> = db.audit().observeRecent(limit)

    suspend fun audit(entry: AuditEntity) = db.audit().insert(entry)

    // --- Fluid balance (I/O) ---

    fun fluids(patientId: Long): Flow<List<FluidEntity>> = db.fluids().observeForPatient(patientId)

    suspend fun recordFluid(patientId: Long, kind: String, category: String, amountMl: Int) {
        db.fluids().insert(
            FluidEntity(
                patientId = patientId,
                kind = kind,
                category = category,
                amountMl = amountMl,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun lastFluid(patientId: Long): FluidEntity? = db.fluids().lastForPatient(patientId)

    suspend fun deleteFluid(id: Long) = db.fluids().deleteById(id)

    // --- Structured nursing assessments ---

    fun assessments(patientId: Long): Flow<List<AssessmentEntity>> =
        db.assessments().observeLatestPerSystem(patientId)

    suspend fun recordAssessment(patientId: Long, system: String, status: String, finding: String?) {
        db.assessments().insert(
            AssessmentEntity(
                patientId = patientId,
                system = system,
                status = status,
                finding = finding,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun lastAssessment(patientId: Long): AssessmentEntity? =
        db.assessments().lastForPatient(patientId)

    suspend fun deleteAssessment(id: Long) = db.assessments().deleteById(id)

    /**
     * Demo patients so the prototype is usable out of the box. "Jane Roe" and
     * "Jane Doe" are deliberately confusable — they exercise the wrong-patient
     * confirmation guard on a voice "open chart for Jane …".
     */
    suspend fun seedIfEmpty(): Long {
        if (db.patients().count() == 0) {
            db.patients().insert(PatientEntity(name = "John Doe", mrn = "000-123-456", ward = "Ward B, Room 12"))
            db.patients().insert(PatientEntity(name = "Jane Roe", mrn = "000-789-012", ward = "Ward A, Room 3"))
            db.patients().insert(PatientEntity(name = "Jane Doe", mrn = "000-789-034", ward = "Ward A, Room 7"))
            db.patients().insert(PatientEntity(name = "Robert Miles", mrn = "000-345-678", ward = "ICU, Bed 2"))
        }
        return db.patients().first()?.id ?: 0L
    }
}
