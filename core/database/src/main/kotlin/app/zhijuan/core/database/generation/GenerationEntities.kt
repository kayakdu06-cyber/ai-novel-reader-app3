package app.zhijuan.core.database.generation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.model.UsageSource

@Entity(
    tableName = "generation_job",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = GenerationStageEntity::class,
            parentColumns = ["job_id", "stage_id"],
            childColumns = ["job_id", "current_stage_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["book_id", "created_at"]),
        Index(value = ["job_id", "current_stage_id"]),
        Index(value = ["status", "updated_at"]),
    ],
)
data class GenerationJobEntity(
    @PrimaryKey
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "job_type")
    val jobType: GenerationJobType,
    val status: GenerationJobStatus,
    @ColumnInfo(name = "user_intent_json")
    val userIntentJson: String,
    @ColumnInfo(name = "budget_snapshot_json")
    val budgetSnapshotJson: String,
    @ColumnInfo(name = "prompt_bundle_version")
    val promptBundleVersion: String,
    @ColumnInfo(name = "current_stage_id")
    val currentStageId: String? = null,
    @ColumnInfo(name = "pause_or_stop_reason")
    val pauseOrStopReason: String? = null,
    @ColumnInfo(name = "lease_owner_id")
    val leaseOwnerId: String? = null,
    @ColumnInfo(name = "lease_acquired_at")
    val leaseAcquiredAt: Long? = null,
    @ColumnInfo(name = "lease_heartbeat_at")
    val leaseHeartbeatAt: Long? = null,
    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "generation_stage",
    foreignKeys = [
        ForeignKey(
            entity = GenerationJobEntity::class,
            parentColumns = ["job_id"],
            childColumns = ["job_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["job_id", "stage_id"], unique = true),
        Index(value = ["idempotency_key"], unique = true),
        Index(value = ["job_id", "phase", "target_id"]),
        Index(value = ["status", "updated_at"]),
        Index(value = ["next_retry_at"]),
    ],
)
data class GenerationStageEntity(
    @PrimaryKey
    @ColumnInfo(name = "stage_id")
    val stageId: String,
    @ColumnInfo(name = "job_id")
    val jobId: String,
    val phase: GenerationPhase,
    @ColumnInfo(name = "target_type")
    val targetType: GenerationTargetType,
    @ColumnInfo(name = "target_id")
    val targetId: String,
    val status: GenerationStageStatus,
    @ColumnInfo(name = "input_version_hash")
    val inputVersionHash: String,
    @ColumnInfo(name = "idempotency_key")
    val idempotencyKey: String,
    @ColumnInfo(name = "attempt_count")
    val attemptCount: Int = 0,
    @ColumnInfo(name = "max_attempts")
    val maxAttempts: Int,
    @ColumnInfo(name = "input_sources_json")
    val inputSourcesJson: String,
    @ColumnInfo(name = "output_reference_json")
    val outputReferenceJson: String? = null,
    @ColumnInfo(name = "standard_error_code")
    val standardErrorCode: StandardErrorCode? = null,
    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long? = null,
    @ColumnInfo(name = "lease_owner_id")
    val leaseOwnerId: String? = null,
    @ColumnInfo(name = "lease_acquired_at")
    val leaseAcquiredAt: Long? = null,
    @ColumnInfo(name = "lease_heartbeat_at")
    val leaseHeartbeatAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "request_attempt",
    foreignKeys = [
        ForeignKey(
            entity = GenerationStageEntity::class,
            parentColumns = ["job_id", "stage_id"],
            childColumns = ["job_id", "stage_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = RequestAttemptEntity::class,
            parentColumns = ["stage_id", "attempt_id"],
            childColumns = ["stage_id", "retry_parent_attempt_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["job_id", "stage_id"]),
        Index(value = ["stage_id", "attempt_id"], unique = true),
        Index(value = ["stage_id", "attempt_no"], unique = true),
        Index(value = ["stage_id", "retry_parent_attempt_id"]),
        Index(value = ["job_id", "created_at"]),
        Index(value = ["provider_request_id"]),
        Index(value = ["status", "updated_at"]),
    ],
)
data class RequestAttemptEntity(
    @PrimaryKey
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "stage_id")
    val stageId: String,
    @ColumnInfo(name = "attempt_no")
    val attemptNo: Int,
    val status: RequestAttemptStatus,
    @ColumnInfo(name = "request_intent_at")
    val requestIntentAt: Long,
    @ColumnInfo(name = "sent_at")
    val sentAt: Long? = null,
    @ColumnInfo(name = "finished_at")
    val finishedAt: Long? = null,
    @ColumnInfo(name = "provider_request_id")
    val providerRequestId: String? = null,
    @ColumnInfo(name = "connection_snapshot_json")
    val connectionSnapshotJson: String,
    @ColumnInfo(name = "model_snapshot_json")
    val modelSnapshotJson: String,
    @ColumnInfo(name = "protocol_snapshot_json")
    val protocolSnapshotJson: String,
    @ColumnInfo(name = "standard_error_code")
    val standardErrorCode: StandardErrorCode? = null,
    @ColumnInfo(name = "http_status")
    val httpStatus: Int? = null,
    @ColumnInfo(name = "input_hash")
    val inputHash: String,
    @ColumnInfo(name = "output_hash")
    val outputHash: String? = null,
    @ColumnInfo(name = "stream_draft_ref")
    val streamDraftRef: String? = null,
    @ColumnInfo(name = "retry_parent_attempt_id")
    val retryParentAttemptId: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "usage_ledger",
    foreignKeys = [
        ForeignKey(
            entity = RequestAttemptEntity::class,
            parentColumns = ["attempt_id"],
            childColumns = ["attempt_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["attempt_id"], unique = true),
        Index(value = ["book_id", "created_at"]),
        Index(value = ["daily_period_key", "created_at"]),
        Index(value = ["status", "updated_at"]),
    ],
)
data class UsageLedgerEntity(
    @PrimaryKey
    @ColumnInfo(name = "usage_ledger_id")
    val usageLedgerId: String,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    val source: UsageSource,
    val status: UsageLedgerStatus,
    @ColumnInfo(name = "input_tokens")
    val inputTokens: Long?,
    @ColumnInfo(name = "output_tokens")
    val outputTokens: Long?,
    @ColumnInfo(name = "cached_tokens")
    val cachedTokens: Long?,
    @ColumnInfo(name = "reasoning_tokens")
    val reasoningTokens: Long?,
    @ColumnInfo(name = "total_tokens")
    val totalTokens: Long?,
    val currency: String?,
    @ColumnInfo(name = "estimated_cost_micros")
    val estimatedCostMicros: Long?,
    @ColumnInfo(name = "price_catalog_version")
    val priceCatalogVersion: String?,
    @ColumnInfo(name = "daily_period_key")
    val dailyPeriodKey: String,
    @ColumnInfo(name = "finalized_at")
    val finalizedAt: Long?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
