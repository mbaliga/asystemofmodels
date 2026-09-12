package xyz.mdhv.asom.ledger

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Room row mirroring [xyz.mdhv.asom.contract.RouteRecord] (brief §9). */
@Entity(tableName = "route_log")
data class RouteLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val callerPkg: String,
    val requestedModel: String,
    val servedProvider: String?,
    val servedModel: String?,
    val egress: String,
    val bytesOut: Long,
    val tokensIn: Long?,
    val tokensOut: Long?,
    val costEst: Double?,
    val costBasis: String,
    val latencyMs: Long,
    val status: Int,
)

/**
 * Opt-in verbose mode rows (§9): request/response bodies, 24h TTL purge,
 * persistent notification while active. NEVER on by default.
 */
@Entity(tableName = "verbose_log")
data class VerboseLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val callerPkg: String,
    val requestBody: String,
    val responseBody: String,
)

@Dao
interface LedgerDao {
    @Insert
    fun insert(row: RouteLogEntity)

    @Query("SELECT * FROM route_log ORDER BY ts DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<RouteLogEntity>>

    @Query("SELECT * FROM route_log ORDER BY ts")
    fun allForExport(): List<RouteLogEntity>

    @Query("SELECT COUNT(*) FROM route_log")
    fun count(): Flow<Long>

    @Query("SELECT COALESCE(SUM(costEst), 0.0) FROM route_log WHERE costEst IS NOT NULL")
    fun totalCost(): Flow<Double>

    @Insert
    fun insertVerbose(row: VerboseLogEntity)

    @Query("SELECT * FROM verbose_log ORDER BY ts DESC LIMIT :limit")
    fun recentVerbose(limit: Int): Flow<List<VerboseLogEntity>>

    /** 24h TTL purge (§9). Returns rows deleted. */
    @Query("DELETE FROM verbose_log WHERE ts < :cutoffTs")
    fun purgeVerboseOlderThan(cutoffTs: Long): Int

    @Query("DELETE FROM verbose_log")
    fun clearVerbose(): Int
}

@Database(
    entities = [RouteLogEntity::class, VerboseLogEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        fun open(context: Context): LedgerDatabase = Room.databaseBuilder(
            context.applicationContext,
            LedgerDatabase::class.java,
            "asom-ledger.db",
        ).build()
    }
}
