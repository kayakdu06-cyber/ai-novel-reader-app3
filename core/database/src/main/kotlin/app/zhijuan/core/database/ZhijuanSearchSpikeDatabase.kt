package app.zhijuan.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import app.zhijuan.core.database.search.SearchDocumentDao
import app.zhijuan.core.database.search.SearchDocumentEntity
import app.zhijuan.core.database.search.SearchDocumentFtsEntity
import app.zhijuan.core.security.DatabasePassphraseStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        SearchDocumentEntity::class,
        SearchDocumentFtsEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class ZhijuanSearchSpikeDatabase : RoomDatabase() {
    abstract fun searchDocumentDao(): SearchDocumentDao
}

/**
 * Opens the M0 encrypted-search spike database and forces its first connection while the temporary
 * passphrase is still available. This is intentionally separate from the future production schema.
 */
class EncryptedSearchSpikeDatabaseFactory(
    context: Context,
    private val passphraseStore: DatabasePassphraseStore = DatabasePassphraseStore(context),
) {
    private val applicationContext = context.applicationContext

    fun open(databaseName: String): EncryptedSearchDatabaseHandle {
        require(databaseName.isNotBlank()) { "Database name must not be blank." }
        SqlCipherRuntime.load()
        val passphrase = passphraseStore.getOrCreate()
        var database: ZhijuanSearchSpikeDatabase? = null
        try {
            // SQLCipher 4.17 lazily opens pooled connections. The passphrase must therefore stay
            // available until Room closes; the returned handle owns and clears it at that point.
            val openHelperFactory = SupportOpenHelperFactory(passphrase, null, false)
            database = Room.databaseBuilder(
                applicationContext,
                ZhijuanSearchSpikeDatabase::class.java,
                databaseName,
            )
                .openHelperFactory(openHelperFactory)
                .addMigrations(ZhijuanSearchMigrations.MIGRATION_1_2)
                .build()
                .also { database -> database.openHelper.writableDatabase }
            return EncryptedSearchDatabaseHandle(database, passphrase)
        } catch (error: Exception) {
            database?.close()
            passphrase.fill(0)
            throw error
        }
    }

}

class EncryptedSearchDatabaseHandle internal constructor(
    val database: ZhijuanSearchSpikeDatabase,
    private val passphrase: ByteArray,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        try {
            database.close()
        } finally {
            passphrase.fill(0)
        }
    }
}
