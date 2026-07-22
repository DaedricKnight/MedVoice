/**
 * Copyright (c) 2026 Artem Khateev. All rights reserved.
 * Proprietary and confidential.
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 */
package com.khateev.medvoice.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "patients")
data class PatientEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mrn: String,
    val ward: String
)

@Entity(tableName = "vitals")
data class VitalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val type: String,      // VitalType.name
    val value: String,
    val timestamp: Long
)

/** One row per SOAP section (Subjective/Objective/Assessment/Plan) per patient. */
@Entity(tableName = "notes", primaryKeys = ["patientId", "section"])
data class NoteEntity(
    val patientId: Long,
    val section: String,   // NoteSection.name
    val text: String,
    val updatedAt: Long
)

/** One structured nursing assessment entry for a body system. */
@Entity(tableName = "assessments")
data class AssessmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val system: String,     // AssessmentSystem.name
    val status: String,     // "NORMAL" | "ABNORMAL"
    val finding: String?,
    val timestamp: Long
)

/** One intake or output event for fluid-balance (I/O) tracking. */
@Entity(tableName = "fluids")
data class FluidEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val patientId: Long,
    val kind: String,       // "INTAKE" | "OUTPUT"
    val category: String,   // "urine", "oral", "IV", "drain", ...
    val amountMl: Int,
    val timestamp: Long
)

/**
 * Persistent audit trail: every recognized command, rejection, confirmation and
 * chart change — with the raw transcript and model confidence. Survives process
 * death (a medical-audit requirement).
 */
@Entity(tableName = "audit")
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val patientId: Long?,
    val rawText: String?,
    val intent: String?,
    val confidence: Float?,
    val message: String,
    val kind: String       // LogKind.name
)

@Dao
interface PatientDao {
    @Query("SELECT * FROM patients ORDER BY id")
    fun observeAll(): Flow<List<PatientEntity>>

    @Query("SELECT * FROM patients ORDER BY id LIMIT 1")
    suspend fun first(): PatientEntity?

    @Query("SELECT COUNT(*) FROM patients")
    suspend fun count(): Int

    @Insert
    suspend fun insert(patient: PatientEntity): Long
}

@Dao
interface VitalDao {
    /** Latest record of each vital type for the patient. */
    @Query(
        "SELECT * FROM vitals WHERE patientId = :patientId AND id IN " +
        "(SELECT MAX(id) FROM vitals WHERE patientId = :patientId GROUP BY type)"
    )
    fun observeLatestPerType(patientId: Long): Flow<List<VitalEntity>>

    @Query("SELECT * FROM vitals WHERE patientId = :patientId ORDER BY id")
    suspend fun allForPatient(patientId: Long): List<VitalEntity>

    @Query("SELECT * FROM vitals WHERE patientId = :patientId AND type = :type ORDER BY timestamp")
    suspend fun historyFor(patientId: Long, type: String): List<VitalEntity>

    @Query("SELECT * FROM vitals WHERE patientId = :patientId ORDER BY id DESC LIMIT 1")
    suspend fun lastForPatient(patientId: Long): VitalEntity?

    @Query("SELECT * FROM vitals WHERE patientId = :patientId AND type = :type ORDER BY id DESC LIMIT 1")
    suspend fun lastOfType(patientId: Long, type: String): VitalEntity?

    @Query("DELETE FROM vitals WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insert(vital: VitalEntity)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE patientId = :patientId")
    fun observe(patientId: Long): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)
}

@Dao
interface AuditDao {
    @Query("SELECT * FROM audit ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEntity>>

    @Insert
    suspend fun insert(entry: AuditEntity)
}

@Dao
interface AssessmentDao {
    /** Latest entry per body system for the patient. */
    @Query(
        "SELECT * FROM assessments WHERE patientId = :patientId AND id IN " +
        "(SELECT MAX(id) FROM assessments WHERE patientId = :patientId GROUP BY system)"
    )
    fun observeLatestPerSystem(patientId: Long): Flow<List<AssessmentEntity>>

    @Query("SELECT * FROM assessments WHERE patientId = :patientId ORDER BY id DESC LIMIT 1")
    suspend fun lastForPatient(patientId: Long): AssessmentEntity?

    @Query("DELETE FROM assessments WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insert(assessment: AssessmentEntity)
}

@Dao
interface FluidDao {
    @Query("SELECT * FROM fluids WHERE patientId = :patientId ORDER BY id")
    fun observeForPatient(patientId: Long): Flow<List<FluidEntity>>

    @Query("SELECT * FROM fluids WHERE patientId = :patientId ORDER BY id DESC LIMIT 1")
    suspend fun lastForPatient(patientId: Long): FluidEntity?

    @Query("DELETE FROM fluids WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insert(fluid: FluidEntity)
}

@Database(
    entities = [
        PatientEntity::class, VitalEntity::class, NoteEntity::class,
        AuditEntity::class, FluidEntity::class, AssessmentEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class ChartDatabase : RoomDatabase() {
    abstract fun patients(): PatientDao
    abstract fun vitals(): VitalDao
    abstract fun notes(): NoteDao
    abstract fun audit(): AuditDao
    abstract fun fluids(): FluidDao
    abstract fun assessments(): AssessmentDao

    companion object {
        @Volatile
        private var instance: ChartDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `audit` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "`patientId` INTEGER, " +
                        "`rawText` TEXT, " +
                        "`intent` TEXT, " +
                        "`confidence` REAL, " +
                        "`message` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL)"
                )
            }
        }

        /** SOAP sections: existing free-text notes become the Subjective section. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE `notes_v3` (" +
                        "`patientId` INTEGER NOT NULL, " +
                        "`section` TEXT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`patientId`, `section`))"
                )
                db.execSQL(
                    "INSERT INTO `notes_v3` (patientId, section, text, updatedAt) " +
                        "SELECT patientId, 'SUBJECTIVE', text, updatedAt FROM `notes`"
                )
                db.execSQL("DROP TABLE `notes`")
                db.execSQL("ALTER TABLE `notes_v3` RENAME TO `notes`")
            }
        }

        /** Fluid-balance (I/O) tracking table. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `fluids` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`patientId` INTEGER NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`amountMl` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL)"
                )
            }
        }

        /** Structured nursing assessment table. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `assessments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`patientId` INTEGER NOT NULL, " +
                        "`system` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`finding` TEXT, " +
                        "`timestamp` INTEGER NOT NULL)"
                )
            }
        }

        fun get(context: Context): ChartDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChartDatabase::class.java,
                    "medvoice.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build().also { instance = it }
            }
    }
}
