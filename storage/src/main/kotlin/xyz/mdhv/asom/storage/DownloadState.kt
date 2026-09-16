package xyz.mdhv.asom.storage

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
import kotlinx.coroutines.flow.Flow

enum class DownloadStatus { NOT_DOWNLOADED, DOWNLOADING, VERIFYING, DOWNLOADED, FAILED }

@Entity(tableName = "model_download_state")
data class ModelDownloadEntity(
    @PrimaryKey val modelId: String,
    val status: String,
    val bytesDownloaded: Long = 0,
    val bytesTotal: Long = 0,
    val pinned: Boolean = false,
    val error: String? = null,
    val updatedAt: Long = 0,
)

@Dao
interface ModelDownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: ModelDownloadEntity)

    @Query("SELECT * FROM model_download_state WHERE modelId = :modelId")
    fun get(modelId: String): ModelDownloadEntity?

    @Query("SELECT * FROM model_download_state ORDER BY modelId")
    fun observeAll(): Flow<List<ModelDownloadEntity>>

    @Query("UPDATE model_download_state SET pinned = :pinned WHERE modelId = :modelId")
    fun setPinned(modelId: String, pinned: Boolean)

    @Query("DELETE FROM model_download_state WHERE modelId = :modelId")
    fun delete(modelId: String)
}

@Database(entities = [ModelDownloadEntity::class], version = 1, exportSchema = false)
abstract class StorageDatabase : RoomDatabase() {
    abstract fun dao(): ModelDownloadDao

    companion object {
        @Volatile
        private var instance: StorageDatabase? = null

        fun open(context: Context): StorageDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    StorageDatabase::class.java,
                    "asom-storage.db",
                ).build().also { instance = it }
            }
    }
}
