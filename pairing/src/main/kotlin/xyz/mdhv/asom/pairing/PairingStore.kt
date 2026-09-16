package xyz.mdhv.asom.pairing

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One pairing per (package, certHash) (§5.7). Only the token's SHA-256 is
 * ever persisted. Tokens never expire; revocation is the only invalidation.
 */
@Entity(tableName = "pairings", primaryKeys = ["packageName", "certHash"])
data class PairingEntity(
    val packageName: String,
    val certHash: String,
    val label: String,
    /** Lowercase hex SHA-256 of the bearer token; null while PENDING. */
    val tokenHash: String?,
    /** PairingStatusCode int. */
    val status: Int,
    val createdAt: Long,
    val approvedAt: Long?,
)

@Dao
interface PairingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: PairingEntity)

    @Query("SELECT * FROM pairings WHERE packageName = :pkg AND certHash = :certHash")
    fun find(pkg: String, certHash: String): PairingEntity?

    @Query("SELECT * FROM pairings ORDER BY createdAt DESC")
    fun all(): List<PairingEntity>

    @Query("SELECT * FROM pairings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<PairingEntity>>

    @Query("SELECT * FROM pairings WHERE tokenHash IS NOT NULL")
    fun allWithTokens(): List<PairingEntity>

    @Query("UPDATE pairings SET status = :status WHERE packageName = :pkg AND certHash = :certHash")
    fun setStatus(pkg: String, certHash: String, status: Int)

    @Query("DELETE FROM pairings WHERE packageName = :pkg AND certHash = :certHash")
    fun delete(pkg: String, certHash: String)
}

@Database(entities = [PairingEntity::class], version = 1, exportSchema = false)
abstract class PairingDatabase : RoomDatabase() {
    abstract fun dao(): PairingDao

    companion object {
        @Volatile
        private var instance: PairingDatabase? = null

        fun open(context: Context): PairingDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PairingDatabase::class.java,
                    "asom-pairing.db",
                ).build().also { instance = it }
            }
    }
}
