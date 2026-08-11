package app.zhijuan.core.database.generation

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.diagnostics.GenerationTimingEvent
import app.zhijuan.core.diagnostics.GenerationTimingEventFactory
import app.zhijuan.core.diagnostics.GenerationTimingMark
import app.zhijuan.core.diagnostics.GenerationTimingMilestone
import app.zhijuan.core.diagnostics.GenerationTimingOutcome
import app.zhijuan.core.diagnostics.GenerationTimingPhase

@Entity(
    tableName = "generation_timing_event",
    indices = [
        Index(value = ["run_fingerprint", "occurred_elapsed_realtime_millis"]),
        Index(value = ["stage_fingerprint", "phase", "milestone"]),
        Index(value = ["attempt_fingerprint", "phase", "milestone"]),
        Index(value = ["boot_fingerprint", "occurred_elapsed_realtime_millis"]),
    ],
)
internal data class GenerationTimingEventEntity(
    @PrimaryKey
    @ColumnInfo(name = "event_id")
    val eventId: String,
    val phase: GenerationTimingPhase,
    val milestone: GenerationTimingMilestone,
    val outcome: GenerationTimingOutcome?,
    @ColumnInfo(name = "occurred_epoch_millis")
    val occurredEpochMillis: Long,
    @ColumnInfo(name = "occurred_elapsed_realtime_millis")
    val occurredElapsedRealtimeMillis: Long,
    @ColumnInfo(name = "boot_fingerprint")
    val bootFingerprint: String,
    @ColumnInfo(name = "run_fingerprint")
    val runFingerprint: String,
    @ColumnInfo(name = "book_fingerprint")
    val bookFingerprint: String,
    @ColumnInfo(name = "job_fingerprint")
    val jobFingerprint: String?,
    @ColumnInfo(name = "stage_fingerprint")
    val stageFingerprint: String?,
    @ColumnInfo(name = "attempt_fingerprint")
    val attemptFingerprint: String?,
    @ColumnInfo(name = "attempt_no")
    val attemptNo: Int?,
    @ColumnInfo(name = "character_count")
    val characterCount: Long?,
    @ColumnInfo(name = "input_token_count")
    val inputTokenCount: Long?,
    @ColumnInfo(name = "output_token_count")
    val outputTokenCount: Long?,
    @ColumnInfo(name = "total_token_count")
    val totalTokenCount: Long?,
    @ColumnInfo(name = "connection_fingerprint")
    val connectionFingerprint: String?,
    @ColumnInfo(name = "model_fingerprint")
    val modelFingerprint: String?,
)

@Dao
internal interface GenerationTimingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: GenerationTimingEventEntity): Long

    @Query("SELECT * FROM generation_timing_event WHERE event_id = :eventId")
    suspend fun findById(eventId: String): GenerationTimingEventEntity?

    @Query(
        """
        SELECT * FROM generation_timing_event
        WHERE run_fingerprint = :runFingerprint
        ORDER BY occurred_epoch_millis, occurred_elapsed_realtime_millis, event_id
        """,
    )
    suspend fun eventsForRun(runFingerprint: String): List<GenerationTimingEventEntity>

    @Query("SELECT COUNT(*) FROM generation_timing_event WHERE run_fingerprint = :runFingerprint")
    suspend fun countForRun(runFingerprint: String): Long
}

enum class GenerationTimingWriteDisposition {
    INSERTED,
    REPLAYED,
}

data class GenerationTimingWriteResult(
    val disposition: GenerationTimingWriteDisposition,
    val event: GenerationTimingEvent,
)

class GenerationTimingRepository(
    private val database: ZhijuanDatabase,
    private val eventFactory: GenerationTimingEventFactory = GenerationTimingEventFactory(),
) {
    suspend fun record(event: GenerationTimingEvent): GenerationTimingWriteResult =
        database.withTransaction {
            val dao = database.generationTimingDao()
            val entity = event.toEntity()
            dao.findById(event.eventId)?.let { existing ->
                val restored = existing.toDomain(eventFactory)
                require(restored == event) {
                    "Generation timing replay conflicts with immutable evidence."
                }
                return@withTransaction GenerationTimingWriteResult(
                    disposition = GenerationTimingWriteDisposition.REPLAYED,
                    event = restored,
                )
            }
            val inserted = dao.insert(entity)
            if (inserted == -1L) {
                val raced = requireNotNull(dao.findById(event.eventId)) {
                    "Generation timing insert lost without replay evidence."
                }.toDomain(eventFactory)
                require(raced == event) {
                    "Generation timing insert raced with conflicting evidence."
                }
                return@withTransaction GenerationTimingWriteResult(
                    disposition = GenerationTimingWriteDisposition.REPLAYED,
                    event = raced,
                )
            }
            val persisted = requireNotNull(dao.findById(event.eventId)) {
                "Generation timing insert did not persist."
            }.toDomain(eventFactory)
            require(persisted == event) { "Generation timing evidence changed while persisting." }
            GenerationTimingWriteResult(
                disposition = GenerationTimingWriteDisposition.INSERTED,
                event = persisted,
            )
        }

    suspend fun eventsForRun(runId: String): List<GenerationTimingEvent> {
        require(runId.isNotEmpty()) { "Generation timing run identity is required." }
        val fingerprint = eventFactory.fingerprint(
            app.zhijuan.core.diagnostics.GenerationTimingFingerprintKind.RUN,
            runId,
        )
        return database.generationTimingDao().eventsForRun(fingerprint).map { it.toDomain(eventFactory) }
    }

    suspend fun countForRun(runId: String): Long {
        require(runId.isNotEmpty()) { "Generation timing run identity is required." }
        val fingerprint = eventFactory.fingerprint(
            app.zhijuan.core.diagnostics.GenerationTimingFingerprintKind.RUN,
            runId,
        )
        return database.generationTimingDao().countForRun(fingerprint)
    }
}

private fun GenerationTimingEvent.toEntity() = GenerationTimingEventEntity(
    eventId = eventId,
    phase = phase,
    milestone = milestone,
    outcome = outcome,
    occurredEpochMillis = mark.epochMillis,
    occurredElapsedRealtimeMillis = mark.elapsedRealtimeMillis,
    bootFingerprint = mark.bootFingerprint,
    runFingerprint = correlations.runFingerprint,
    bookFingerprint = correlations.bookFingerprint,
    jobFingerprint = correlations.jobFingerprint,
    stageFingerprint = correlations.stageFingerprint,
    attemptFingerprint = correlations.attemptFingerprint,
    attemptNo = attemptNo,
    characterCount = characterCount,
    inputTokenCount = inputTokenCount,
    outputTokenCount = outputTokenCount,
    totalTokenCount = totalTokenCount,
    connectionFingerprint = connectionFingerprint,
    modelFingerprint = modelFingerprint,
)

private fun GenerationTimingEventEntity.toDomain(factory: GenerationTimingEventFactory) = factory.restore(
    eventId = eventId,
    phase = phase,
    milestone = milestone,
    outcome = outcome,
    mark = GenerationTimingMark(
        epochMillis = occurredEpochMillis,
        elapsedRealtimeMillis = occurredElapsedRealtimeMillis,
        bootFingerprint = bootFingerprint,
    ),
    runFingerprint = runFingerprint,
    bookFingerprint = bookFingerprint,
    jobFingerprint = jobFingerprint,
    stageFingerprint = stageFingerprint,
    attemptFingerprint = attemptFingerprint,
    attemptNo = attemptNo,
    characterCount = characterCount,
    inputTokenCount = inputTokenCount,
    outputTokenCount = outputTokenCount,
    totalTokenCount = totalTokenCount,
    connectionFingerprint = connectionFingerprint,
    modelFingerprint = modelFingerprint,
)
