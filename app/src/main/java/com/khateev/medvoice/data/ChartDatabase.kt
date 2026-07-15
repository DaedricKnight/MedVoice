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

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val patientId: Long,
    val text: String,
    val updatedAt: Long
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

    @Query("DELETE FROM vitals WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insert(vital: VitalEntity)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE patientId = :patientId")
    fun observe(patientId: Long): Flow<NoteEntity?>

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

@Database(
    entities = [PatientEntity::class, VitalEntity::class, NoteEntity::class, AuditEntity::class],
    version = 2,
    exportSchema = false
)
abstract class ChartDatabase : RoomDatabase() {
    abstract fun patients(): PatientDao
    abstract fun vitals(): VitalDao
    abstract fun notes(): NoteDao
    abstract fun audit(): AuditDao

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

        fun get(context: Context): ChartDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChartDatabase::class.java,
                    "medvoice.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
