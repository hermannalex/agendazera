package br.com.agendazera.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AppointmentDao {
    @Insert
    suspend fun insert(a: Appointment): Long

    @Query("UPDATE appointments SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("SELECT * FROM appointments WHERE id = :id")
    suspend fun getById(id: Long): Appointment?

    @Query("UPDATE appointments SET calendarEventId = :eventId WHERE id = :id")
    suspend fun setEventId(id: Long, eventId: Long?)

    /** Consultas confirmadas de um profissional que se sobrepõem ao intervalo [from, to). */
    @Query(
        "SELECT * FROM appointments WHERE status = 'CONFIRMADO' AND professionalId = :professionalId " +
            "AND startMillis < :to AND (startMillis + durationMin * 60000) > :from ORDER BY startMillis"
    )
    suspend fun activeBetweenFor(professionalId: Long, from: Long, to: Long): List<Appointment>

    @Query(
        "SELECT * FROM appointments WHERE status = 'CONFIRMADO' AND contactKey = :key " +
            "AND startMillis >= :now ORDER BY startMillis"
    )
    suspend fun upcomingFor(key: String, now: Long): List<Appointment>

    @Query(
        "SELECT * FROM appointments WHERE status = 'CONFIRMADO' AND startMillis >= :now " +
            "AND calendarEventId IS NULL AND professionalId = :professionalId ORDER BY startMillis"
    )
    suspend fun futureWithoutEvent(professionalId: Long, now: Long): List<Appointment>

    @Query("SELECT COUNT(*) FROM appointments WHERE professionalId = :professionalId")
    suspend fun countFor(professionalId: Long): Int

    /** Dados antigos (antes do plano Pro) passam para o primeiro profissional. */
    @Query("UPDATE appointments SET professionalId = :professionalId WHERE professionalId IS NULL")
    suspend fun assignOrphans(professionalId: Long)

    @Query("SELECT * FROM appointments WHERE startMillis >= :from AND startMillis < :to ORDER BY startMillis")
    fun observeDay(from: Long, to: Long): Flow<List<Appointment>>
}

@Dao
interface ProfessionalDao {
    @Query("SELECT * FROM professionals ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Professional>>

    @Query("SELECT * FROM professionals ORDER BY sortOrder, id")
    suspend fun all(): List<Professional>

    @Query("SELECT * FROM professionals WHERE id = :id")
    suspend fun getById(id: Long): Professional?

    @Query("SELECT COUNT(*) FROM professionals")
    suspend fun count(): Int

    @Insert
    suspend fun insert(p: Professional): Long

    @Update
    suspend fun update(p: Professional)

    @Delete
    suspend fun delete(p: Professional)
}

@Dao
interface ServiceDao {
    @Query("SELECT * FROM services ORDER BY name")
    fun observeAll(): Flow<List<Service>>

    @Query("SELECT * FROM services ORDER BY name")
    suspend fun all(): List<Service>

    @Query("SELECT * FROM services WHERE id = :id")
    suspend fun getById(id: Long): Service?

    @Upsert
    suspend fun upsert(s: Service): Long

    @Delete
    suspend fun delete(s: Service)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE contactKey = :key")
    suspend fun get(key: String): ConversationState?

    @Upsert
    suspend fun upsert(state: ConversationState)

    @Query("SELECT * FROM conversations WHERE pausedUntil > 0 ORDER BY updatedAt DESC")
    fun observePaused(): Flow<List<ConversationState>>

    @Query("UPDATE conversations SET pausedUntil = 0, step = 'MENU' WHERE contactKey = :key")
    suspend fun resume(key: String)
}

@Database(
    entities = [Appointment::class, ConversationState::class, Professional::class, Service::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appointments(): AppointmentDao
    abstract fun conversations(): ConversationDao
    abstract fun professionals(): ProfessionalDao
    abstract fun services(): ServiceDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE appointments ADD COLUMN calendarEventId INTEGER")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE appointments ADD COLUMN professionalId INTEGER")
                db.execSQL("ALTER TABLE appointments ADD COLUMN serviceName TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN serviceId INTEGER")
                db.execSQL("ALTER TABLE conversations ADD COLUMN professionalId INTEGER")
                db.execSQL("ALTER TABLE conversations ADD COLUMN pendingProfessionalId INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `professionals` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`specialty` TEXT NOT NULL, `active` INTEGER NOT NULL, `workDays` TEXT NOT NULL, " +
                        "`startMinutes` INTEGER NOT NULL, `endMinutes` INTEGER NOT NULL, " +
                        "`lunchStart` INTEGER NOT NULL, `lunchEnd` INTEGER NOT NULL, `slotMinutes` INTEGER NOT NULL, " +
                        "`calendarId` INTEGER NOT NULL, `calendarName` TEXT NOT NULL, " +
                        "`calendarBlocks` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `services` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`durationMin` INTEGER NOT NULL, `priceCents` INTEGER, " +
                        "`professionalIds` TEXT NOT NULL, `active` INTEGER NOT NULL)"
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "agenda.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
