package app.zhijuan.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import app.zhijuan.core.database.capability.ProviderCapabilityDao
import app.zhijuan.core.database.capability.ProviderCapabilityEntity
import app.zhijuan.core.database.connection.ConnectionDao
import app.zhijuan.core.database.connection.ConnectionProfileEntity
import app.zhijuan.core.database.connection.CurrentConnectionSelectionEntity
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionDao
import app.zhijuan.core.database.library.ChapterEditRebuildExecutionEntity
import app.zhijuan.core.database.library.ChapterEditRebuildStepEntity
import app.zhijuan.core.database.library.ChapterEditRebuildTrackingRetirementEntity
import app.zhijuan.core.database.library.ChapterVersionEntity
import app.zhijuan.core.database.library.LibraryDao
import app.zhijuan.core.database.library.LibraryTypeConverters
import app.zhijuan.core.database.generation.BudgetDao
import app.zhijuan.core.database.generation.BudgetPolicyHeadEntity
import app.zhijuan.core.database.generation.BudgetPolicyRevisionEntity
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.generation.GenerationDao
import app.zhijuan.core.database.generation.GenerationTimingDao
import app.zhijuan.core.database.generation.GenerationTimingEventEntity
import app.zhijuan.core.database.generation.RequestBudgetReservationEntity
import app.zhijuan.core.database.generation.RequestAttemptEntity
import app.zhijuan.core.database.generation.UsageLedgerEntity
import app.zhijuan.core.database.memory.AggregateStateProjectionEntity
import app.zhijuan.core.database.memory.BookMemoryHeadEntity
import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.ChapterTrackingProjectionEntity
import app.zhijuan.core.database.memory.ConsistencyReportEntity
import app.zhijuan.core.database.memory.ContextSnapshotEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.ForeshadowProjectionRevisionEntity
import app.zhijuan.core.database.memory.ForeshadowProjectionRewindEntity
import app.zhijuan.core.database.memory.ForeshadowTransitionEntity
import app.zhijuan.core.database.memory.MemoryDao
import app.zhijuan.core.database.memory.OutlineNodeEntity
import app.zhijuan.core.database.memory.OutlineRevisionEntity
import app.zhijuan.core.database.memory.StoryBibleRevisionEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.database.search.MemorySearchDao
import app.zhijuan.core.database.search.MemorySearchBackfillStateDao
import app.zhijuan.core.database.search.MemorySearchBackfillStateEntity
import app.zhijuan.core.database.search.MemorySearchDocumentEntity
import app.zhijuan.core.database.search.MemorySearchDocumentFtsEntity
import app.zhijuan.core.database.template.TemplateDao
import app.zhijuan.core.database.template.TemplateEntity
import app.zhijuan.core.database.template.TemplateRevisionEntity
import app.zhijuan.core.database.template.TemplateTagEntity
import app.zhijuan.core.database.template.TemplateUseSnapshotEntity
import app.zhijuan.core.security.DatabasePassphraseStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

internal const val ZHIJUAN_DATABASE_SCHEMA_VERSION = 17

@Database(
    entities = [
        BookCreationSnapshotEntity::class,
        BookEntity::class,
        ChapterEntity::class,
        ChapterVersionEntity::class,
        ChapterEditRebuildExecutionEntity::class,
        ChapterEditRebuildStepEntity::class,
        ChapterEditRebuildTrackingRetirementEntity::class,
        GenerationJobEntity::class,
        GenerationStageEntity::class,
        RequestAttemptEntity::class,
        UsageLedgerEntity::class,
        GenerationTimingEventEntity::class,
        StoryBibleRevisionEntity::class,
        OutlineRevisionEntity::class,
        OutlineNodeEntity::class,
        BookMemoryHeadEntity::class,
        ChapterSummaryEntity::class,
        StoryEntity::class,
        EntityEventEntity::class,
        CanonFactEntity::class,
        TimelineEventEntity::class,
        ForeshadowItemEntity::class,
        ChapterTrackingProjectionEntity::class,
        ForeshadowTransitionEntity::class,
        ForeshadowProjectionRevisionEntity::class,
        ForeshadowProjectionRewindEntity::class,
        ContextSnapshotEntity::class,
        ConsistencyReportEntity::class,
        AggregateStateProjectionEntity::class,
        TemplateEntity::class,
        TemplateRevisionEntity::class,
        TemplateUseSnapshotEntity::class,
        TemplateTagEntity::class,
        ProviderCapabilityEntity::class,
        ConnectionProfileEntity::class,
        CurrentConnectionSelectionEntity::class,
        BudgetPolicyRevisionEntity::class,
        BudgetPolicyHeadEntity::class,
        RequestBudgetReservationEntity::class,
        MemorySearchDocumentEntity::class,
        MemorySearchDocumentFtsEntity::class,
        MemorySearchBackfillStateEntity::class,
    ],
    version = ZHIJUAN_DATABASE_SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(LibraryTypeConverters::class)
abstract class ZhijuanDatabase : RoomDatabase() {
    internal abstract fun libraryDao(): LibraryDao
    internal abstract fun chapterEditRebuildExecutionDao(): ChapterEditRebuildExecutionDao
    internal abstract fun generationDao(): GenerationDao
    internal abstract fun generationTimingDao(): GenerationTimingDao
    internal abstract fun budgetDao(): BudgetDao
    internal abstract fun memoryDao(): MemoryDao
    internal abstract fun memorySearchDao(): MemorySearchDao
    internal abstract fun memorySearchBackfillStateDao(): MemorySearchBackfillStateDao
    internal abstract fun templateDao(): TemplateDao
    abstract fun providerCapabilityDao(): ProviderCapabilityDao
    abstract fun connectionDao(): ConnectionDao
}

class EncryptedZhijuanDatabaseFactory(
    context: Context,
    private val passphraseStore: DatabasePassphraseStore = DatabasePassphraseStore(context),
) {
    private val applicationContext = context.applicationContext

    fun open(databaseName: String): EncryptedZhijuanDatabaseHandle {
        require(databaseName.isNotBlank()) { "Database name must not be blank." }
        SqlCipherRuntime.load()
        val passphrase = passphraseStore.getOrCreate()
        var database: ZhijuanDatabase? = null
        try {
            database = Room.databaseBuilder(
                applicationContext,
                ZhijuanDatabase::class.java,
                databaseName,
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase, null, false))
                .addCallback(LibraryDatabaseGuards.callback)
                .addMigrations(*ZhijuanMigrations.ALL)
                .build()
                .also { it.openHelper.writableDatabase }
            return EncryptedZhijuanDatabaseHandle(database, passphrase)
        } catch (error: Exception) {
            database?.close()
            passphrase.fill(0)
            throw error
        }
    }
}

class EncryptedZhijuanDatabaseHandle internal constructor(
    val database: ZhijuanDatabase,
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
