package app.obdforge.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vehicles")
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "My vehicle",
    val make: String = "",
    val model: String = "",
    val year: Int? = null,
    val engine: String = "",
    val engineCode: String = "",
    val transmission: String = "",
    val fuelType: String = "GASOLINE",
    val vin: String = "",
    val mileageKm: Double? = null,
    val ecuInfo: String = "",
    val adapterAddress: String = "",
    val adapterName: String = "",
    val profileId: String = "generic",
    val displacementL: Double = 2.0,
    val cylinders: Int = 4,
    val massKg: Double = 1600.0,
    val dragArea: Double = 0.75,
    val drivetrainLossPct: Double = 15.0,
    val drivetrain: String = "AWD",
    val tyreSize: String = "225/55R18",
    val turbocharged: Boolean = false,
    val boostLimitKpa: Double = 120.0,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "sessions", indices = [Index("vehicleId"), Index("startedAt")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long?,
    val title: String,
    val description: String = "",
    val startedAt: Long,
    val endedAt: Long? = null,
    /** RECORDING, COMPLETE, RECOVERED (app was killed while recording) */
    val status: String = "RECORDING",
    val simulated: Boolean = false,
    val channels: String = "",
    val sampleCount: Long = 0,
    val trigger: String = "manual",
)

/** One logged value. Times are ms since session start. */
@Entity(
    tableName = "samples",
    primaryKeys = ["sessionId", "channel", "t"],
)
data class SampleEntity(
    val sessionId: Long,
    val channel: String,
    val t: Long,
    val v: Double,
)

@Entity(tableName = "diag_records", indices = [Index("vehicleId")])
data class DiagRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long?,
    val time: Long,
    /** READ_CODES, CLEAR_CODES, MODE06, EVAP_TEST, ECU_SCAN, REPORT */
    val type: String,
    val summary: String,
    val json: String,
    val simulated: Boolean,
)

@Entity(tableName = "service_log", indices = [Index("vehicleId")])
data class ServiceLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long,
    val date: Long,
    /** REPAIR or MAINTENANCE */
    val kind: String,
    val title: String,
    val details: String = "",
    val mileageKm: Double? = null,
    val cost: Double? = null,
    val relatedCodes: String = "",
)

@Entity(tableName = "dashboards")
data class DashboardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int,
    val json: String,
    val preset: Boolean = false,
)

@Entity(tableName = "alert_events", indices = [Index("time")])
data class AlertEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val ruleId: String,
    val title: String,
    val message: String,
    val value: Double,
    val critical: Boolean,
    val vehicleId: Long?,
)

@Entity(tableName = "perf_results", indices = [Index("vehicleId")])
data class PerfResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vehicleId: Long?,
    val time: Long,
    val type: String,
    val elapsedS: Double,
    val trapSpeedKmh: Double?,
    val distanceM: Double,
    val speedSource: String,
    val peakKw: Double?,
    val peakNm: Double?,
    val json: String,
    val simulated: Boolean,
)

@Entity(tableName = "profiles")
data class ProfileEntity(@PrimaryKey val id: String, val json: String)

@Entity(tableName = "investigations")
data class InvestigationEntity(
    @PrimaryKey val vehicleId: Long,
    val observations: String,
    val testResults: String,
    val updatedAt: Long,
)

data class ChannelCount(val channel: String, @ColumnInfo(name = "n") val n: Long)

@Dao
interface VehicleDao {
    @Query("SELECT * FROM vehicles ORDER BY name") fun all(): Flow<List<VehicleEntity>>
    @Query("SELECT * FROM vehicles ORDER BY name") suspend fun list(): List<VehicleEntity>
    @Query("SELECT * FROM vehicles WHERE id = :id") suspend fun get(id: Long): VehicleEntity?
    @Query("SELECT * FROM vehicles WHERE vin = :vin LIMIT 1") suspend fun byVin(vin: String): VehicleEntity?
    @Insert suspend fun insert(v: VehicleEntity): Long
    @Update suspend fun update(v: VehicleEntity)
    @Query("DELETE FROM vehicles WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC") fun all(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC") suspend fun list(): List<SessionEntity>
    @Query("SELECT * FROM sessions WHERE id = :id") suspend fun get(id: Long): SessionEntity?
    @Query("SELECT * FROM sessions WHERE id = :id") fun observe(id: Long): Flow<SessionEntity?>
    @Query("SELECT * FROM sessions WHERE status = 'RECORDING'") suspend fun unfinished(): List<SessionEntity>
    @Insert suspend fun insert(s: SessionEntity): Long
    @Update suspend fun update(s: SessionEntity)
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: Long)
    @Query("DELETE FROM samples WHERE sessionId = :id") suspend fun deleteSamples(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSamples(s: List<SampleEntity>)
    @Query("SELECT * FROM samples WHERE sessionId = :id AND channel = :channel ORDER BY t") suspend fun samples(id: Long, channel: String): List<SampleEntity>
    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY t") suspend fun allSamples(id: Long): List<SampleEntity>
    @Query("SELECT channel, COUNT(*) AS n FROM samples WHERE sessionId = :id GROUP BY channel") suspend fun channelCounts(id: Long): List<ChannelCount>
    @Query("SELECT COUNT(*) FROM samples WHERE sessionId = :id") suspend fun count(id: Long): Long
    @Query("SELECT MAX(t) FROM samples WHERE sessionId = :id") suspend fun lastT(id: Long): Long?
}

@Dao
interface MiscDao {
    @Insert suspend fun insertDiag(r: DiagRecordEntity): Long
    @Query("SELECT * FROM diag_records ORDER BY time DESC LIMIT 200") fun diagHistory(): Flow<List<DiagRecordEntity>>
    @Query("SELECT * FROM diag_records WHERE vehicleId = :vid ORDER BY time DESC") suspend fun diagForVehicle(vid: Long): List<DiagRecordEntity>

    @Query("SELECT * FROM service_log WHERE vehicleId = :vid ORDER BY date DESC") fun serviceLog(vid: Long): Flow<List<ServiceLogEntity>>
    @Query("SELECT * FROM service_log ORDER BY date DESC") suspend fun allServiceLog(): List<ServiceLogEntity>
    @Insert suspend fun insertService(e: ServiceLogEntity): Long
    @Query("DELETE FROM service_log WHERE id = :id") suspend fun deleteService(id: Long)

    @Query("SELECT * FROM dashboards ORDER BY position") fun dashboards(): Flow<List<DashboardEntity>>
    @Query("SELECT * FROM dashboards ORDER BY position") suspend fun dashboardList(): List<DashboardEntity>
    @Insert suspend fun insertDashboard(d: DashboardEntity): Long
    @Update suspend fun updateDashboard(d: DashboardEntity)
    @Query("DELETE FROM dashboards WHERE id = :id") suspend fun deleteDashboard(id: Long)

    @Insert suspend fun insertAlert(e: AlertEventEntity): Long
    @Query("SELECT * FROM alert_events ORDER BY time DESC LIMIT 500") fun alertHistory(): Flow<List<AlertEventEntity>>
    @Query("DELETE FROM alert_events") suspend fun clearAlerts()

    @Insert suspend fun insertPerf(e: PerfResultEntity): Long
    @Query("SELECT * FROM perf_results ORDER BY time DESC") fun perfResults(): Flow<List<PerfResultEntity>>
    @Query("DELETE FROM perf_results WHERE id = :id") suspend fun deletePerf(id: Long)

    @Query("SELECT * FROM profiles") suspend fun profiles(): List<ProfileEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProfile(p: ProfileEntity)
    @Query("DELETE FROM profiles WHERE id = :id") suspend fun deleteProfile(id: String)

    @Query("SELECT * FROM investigations WHERE vehicleId = :vid") suspend fun investigation(vid: Long): InvestigationEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putInvestigation(i: InvestigationEntity)
}

@Database(
    entities = [VehicleEntity::class, SessionEntity::class, SampleEntity::class, DiagRecordEntity::class, ServiceLogEntity::class,
        DashboardEntity::class, AlertEventEntity::class, PerfResultEntity::class, ProfileEntity::class, InvestigationEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun sessions(): SessionDao
    abstract fun misc(): MiscDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "obdforge.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
