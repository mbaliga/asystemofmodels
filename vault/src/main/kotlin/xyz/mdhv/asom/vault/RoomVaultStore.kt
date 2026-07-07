package xyz.mdhv.asom.vault

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

@Entity(tableName = "wrapped_data_key")
internal data class WrappedDataKeyEntity(
    @PrimaryKey val id: Int = 0, // singleton row
    val ciphertext: ByteArray,
    val nonce: ByteArray,
)

@Entity(tableName = "provider_keys")
internal data class ProviderKeyEntity(
    @PrimaryKey val providerId: String,
    val ciphertext: ByteArray,
    val nonce: ByteArray,
    val createdAt: Long,
)

@Dao
internal interface VaultDao {
    @Query("SELECT * FROM wrapped_data_key WHERE id = 0")
    fun wrappedDataKey(): WrappedDataKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveWrappedDataKey(entity: WrappedDataKeyEntity)

    @Query("SELECT * FROM provider_keys WHERE providerId = :providerId")
    fun key(providerId: String): ProviderKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveKey(entity: ProviderKeyEntity)

    @Query("DELETE FROM provider_keys WHERE providerId = :providerId")
    fun deleteKey(providerId: String)

    @Query("SELECT providerId FROM provider_keys ORDER BY providerId")
    fun providerIds(): List<String>
}

@Database(
    entities = [WrappedDataKeyEntity::class, ProviderKeyEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class VaultDatabase : RoomDatabase() {
    abstract fun dao(): VaultDao
}

/** Room-backed [VaultStore] — ciphertext + nonce only (§8). */
class RoomVaultStore(context: Context) : VaultStore {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        VaultDatabase::class.java,
        "asom-vault.db",
    ).build()

    private val dao get() = db.dao()

    override fun loadWrappedDataKey(): WrappedBlob? =
        dao.wrappedDataKey()?.let { WrappedBlob(it.ciphertext, it.nonce) }

    override fun saveWrappedDataKey(blob: WrappedBlob) =
        dao.saveWrappedDataKey(WrappedDataKeyEntity(0, blob.ciphertext, blob.nonce))

    override fun loadKey(providerId: String): WrappedBlob? =
        dao.key(providerId)?.let { WrappedBlob(it.ciphertext, it.nonce) }

    override fun saveKey(providerId: String, blob: WrappedBlob) =
        dao.saveKey(ProviderKeyEntity(providerId, blob.ciphertext, blob.nonce, System.currentTimeMillis()))

    override fun deleteKey(providerId: String) = dao.deleteKey(providerId)

    override fun listProviderIds(): List<String> = dao.providerIds()
}
