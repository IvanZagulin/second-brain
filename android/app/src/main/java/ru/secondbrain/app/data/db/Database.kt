package ru.secondbrain.app.data.db

import android.content.Context
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
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Offline mirror of the server, stored as JSON documents keyed by (entity, id) with a few indexed
 * columns. One generic table keeps sync simple; typed views are parsed in the repository layer.
 */
@Entity(
    tableName = "records",
    primaryKeys = ["entity", "id"],
    indices = [Index("entity", "parentId"), Index("entity", "sortAt")],
)
data class RecordEntity(
    val entity: String,
    val id: String,
    val data: String,
    val parentId: String? = null,
    val sortAt: String? = null,
    val version: Int = 0,
    val serverSeq: Long = 0,
    val deleted: Boolean = false,
    val dirty: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** Local changes waiting to be pushed. One row per record (later edits replace earlier ones). */
@Entity(tableName = "outbox", indices = [Index(value = ["entity", "recordId"], unique = true)])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val entity: String,
    val recordId: String,
    val op: String,
    val data: String,
    val baseVersion: Int?,
    val clientUpdatedAt: String,
    val attempts: Int = 0,
)

/** Chat messages written while offline (or failed); sent by the sync worker. */
@Entity(tableName = "pending_messages")
data class PendingMessageEntity(
    @PrimaryKey val clientMessageId: String,
    val conversationId: String,
    val text: String,
    val attachmentIds: String = "[]",
    val mode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "pending",
    val error: String? = null,
)

/** Captured bank / Mir Pay notifications waiting for upload. */
@Entity(tableName = "bank_notifications", indices = [Index(value = ["dedupeKey"], unique = true)])
data class BankNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String?,
    val text: String,
    val postedAt: Long,
    val dedupeKey: String,
    val uploaded: Boolean = false,
    val result: String? = null,
)

/** Notifications already shown (FCM or polling) so the other path doesn't repeat them. */
@Entity(tableName = "shown_notifications")
data class ShownNotificationEntity(@PrimaryKey val id: String, val shownAt: Long = System.currentTimeMillis())

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE entity = :entity AND deleted = 0 ORDER BY sortAt DESC")
    fun observe(entity: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE entity = :entity AND parentId = :parentId AND deleted = 0 ORDER BY sortAt ASC")
    fun observeChildren(entity: String, parentId: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE entity = :entity AND id = :id")
    fun observeOne(entity: String, id: String): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE entity = :entity AND deleted = 0")
    suspend fun all(entity: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE entity = :entity AND parentId = :parentId AND deleted = 0")
    suspend fun children(entity: String, parentId: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE entity = :entity AND id = :id")
    suspend fun get(entity: String, id: String): RecordEntity?

    @Upsert
    suspend fun upsert(record: RecordEntity)

    @Upsert
    suspend fun upsertAll(records: List<RecordEntity>)

    @Query("UPDATE records SET version = :version, serverSeq = :serverSeq, dirty = 0 WHERE entity = :entity AND id = :id")
    suspend fun markSynced(entity: String, id: String, version: Int, serverSeq: Long)

    @Query("UPDATE records SET version = :version, serverSeq = :serverSeq WHERE entity = :entity AND id = :id")
    suspend fun setVersion(entity: String, id: String, version: Int, serverSeq: Long)

    @Query("SELECT COUNT(*) FROM records")
    suspend fun count(): Int

    @Query("DELETE FROM records")
    suspend fun clear()
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY seq LIMIT :limit")
    suspend fun next(limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE entity = :entity AND recordId = :recordId")
    suspend fun find(entity: String, recordId: String): OutboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: OutboxEntity)

    @Query("DELETE FROM outbox WHERE seq = :seq")
    suspend fun remove(seq: Long)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE seq = :seq")
    suspend fun failed(seq: Long)

    @Query("SELECT COUNT(*) FROM outbox")
    fun pendingCount(): Flow<Int>

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface PendingMessageDao {
    @Query("SELECT * FROM pending_messages WHERE conversationId = :conversationId ORDER BY createdAt")
    fun observe(conversationId: String): Flow<List<PendingMessageEntity>>

    @Query("SELECT * FROM pending_messages WHERE status != 'sending' ORDER BY createdAt")
    suspend fun sendable(): List<PendingMessageEntity>

    @Upsert
    suspend fun upsert(item: PendingMessageEntity)

    @Query("DELETE FROM pending_messages WHERE clientMessageId = :id")
    suspend fun remove(id: String)

    @Query("UPDATE pending_messages SET status = :status, error = :error WHERE clientMessageId = :id")
    suspend fun setStatus(id: String, status: String, error: String?)
}

@Dao
interface BankNotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: BankNotificationEntity): Long

    @Query("SELECT * FROM bank_notifications WHERE uploaded = 0 ORDER BY postedAt LIMIT 100")
    suspend fun pending(): List<BankNotificationEntity>

    @Query("UPDATE bank_notifications SET uploaded = 1, result = :result WHERE id = :id")
    suspend fun markUploaded(id: Long, result: String?)

    @Query("SELECT * FROM bank_notifications ORDER BY postedAt DESC LIMIT 50")
    fun recent(): Flow<List<BankNotificationEntity>>

    @Query("DELETE FROM bank_notifications WHERE uploaded = 1 AND postedAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface ShownNotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun mark(item: ShownNotificationEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM shown_notifications WHERE id = :id)")
    suspend fun wasShown(id: String): Boolean
}

@Database(
    entities = [RecordEntity::class, OutboxEntity::class, PendingMessageEntity::class, BankNotificationEntity::class,
        ShownNotificationEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun outbox(): OutboxDao
    abstract fun pendingMessages(): PendingMessageDao
    abstract fun bankNotifications(): BankNotificationDao
    abstract fun shown(): ShownNotificationDao

    companion object {
        fun create(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AppDatabase::class.java, "second_brain.db")
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .fallbackToDestructiveMigrationOnDowngrade(true)
                .build()
        }
    }
}
