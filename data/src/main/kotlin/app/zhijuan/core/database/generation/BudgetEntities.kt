package app.zhijuan.core.database.generation

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.BudgetReservationStatus
import app.zhijuan.core.model.BudgetScope

/**
 * Immutable policy revision. Validation (scope identity, contiguous parent,
 * positive limits, currency pairing, no UPDATE/DELETE) lives in
 * [app.zhijuan.core.database.LibraryDatabaseGuards].
 */
@Entity(
    tableName = "budget_policy_revision",
    foreignKeys = [
        ForeignKey(
            entity = BudgetPolicyRevisionEntity::class,
            parentColumns = ["budget_policy_id"],
            childColumns = ["parent_budget_policy_id"],
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
        Index(value = ["scope", "scope_key", "revision_no"], unique = true),
        Index(value = ["parent_budget_policy_id"], unique = true),
        Index(value = ["book_id"]),
        Index(value = ["created_at"]),
    ],
)
data class BudgetPolicyRevisionEntity(
    @PrimaryKey
    @ColumnInfo(name = "budget_policy_id")
    val budgetPolicyId: String,
    val scope: BudgetScope,
    @ColumnInfo(name = "scope_key")
    val scopeKey: String,
    @ColumnInfo(name = "revision_no")
    val revisionNo: Int,
    @ColumnInfo(name = "parent_budget_policy_id")
    val parentBudgetPolicyId: String? = null,
    @ColumnInfo(name = "book_id")
    val bookId: String? = null,
    @ColumnInfo(name = "daily_zone_id")
    val dailyZoneId: String? = null,
    @ColumnInfo(name = "max_tokens")
    val maxTokens: Long,
    @ColumnInfo(name = "max_cost_micros")
    val maxCostMicros: Long? = null,
    val currency: String? = null,
    @ColumnInfo(name = "policy_version")
    val policyVersion: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

/**
 * Current revision per (scope, scope_key) with CAS advance validation in
 * [app.zhijuan.core.database.LibraryDatabaseGuards]; DELETE is forbidden.
 */
@Entity(
    tableName = "budget_policy_head",
    primaryKeys = ["scope", "scope_key"],
    foreignKeys = [
        ForeignKey(
            entity = BudgetPolicyRevisionEntity::class,
            parentColumns = ["budget_policy_id"],
            childColumns = ["current_budget_policy_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["current_budget_policy_id"], unique = true),
    ],
)
data class BudgetPolicyHeadEntity(
    val scope: BudgetScope,
    @ColumnInfo(name = "scope_key")
    val scopeKey: String,
    @ColumnInfo(name = "current_budget_policy_id")
    val currentBudgetPolicyId: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * One immutable-per-attempt reservation. Phase 2 creates no production rows;
 * all validation lives in [app.zhijuan.core.database.LibraryDatabaseGuards].
 */
@Entity(
    tableName = "request_budget_reservation",
    foreignKeys = [
        ForeignKey(
            entity = GenerationStageEntity::class,
            parentColumns = ["job_id", "stage_id"],
            childColumns = ["job_id", "stage_id"],
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
        ForeignKey(
            entity = BudgetPolicyRevisionEntity::class,
            parentColumns = ["budget_policy_id"],
            childColumns = ["book_policy_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = BudgetPolicyRevisionEntity::class,
            parentColumns = ["budget_policy_id"],
            childColumns = ["daily_policy_id"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["attempt_id"], unique = true),
        Index(value = ["book_id", "status", "created_at"]),
        Index(value = ["daily_period_key", "status", "created_at"]),
        Index(value = ["job_id", "stage_id"]),
        Index(value = ["book_policy_id"]),
        Index(value = ["daily_policy_id"]),
        Index(value = ["status", "updated_at"]),
    ],
)
data class RequestBudgetReservationEntity(
    @PrimaryKey
    @ColumnInfo(name = "budget_reservation_id")
    val budgetReservationId: String,
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    @ColumnInfo(name = "job_id")
    val jobId: String,
    @ColumnInfo(name = "stage_id")
    val stageId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    val status: BudgetReservationStatus,
    @ColumnInfo(name = "request_max_tokens")
    val requestMaxTokens: Long,
    @ColumnInfo(name = "request_max_cost_micros")
    val requestMaxCostMicros: Long? = null,
    @ColumnInfo(name = "request_currency")
    val requestCurrency: String? = null,
    @ColumnInfo(name = "estimated_tokens")
    val estimatedTokens: Long,
    @ColumnInfo(name = "estimated_cost_micros")
    val estimatedCostMicros: Long? = null,
    @ColumnInfo(name = "estimated_currency")
    val estimatedCurrency: String? = null,
    @ColumnInfo(name = "estimate_source_version")
    val estimateSourceVersion: String? = null,
    @ColumnInfo(name = "accounted_tokens")
    val accountedTokens: Long,
    @ColumnInfo(name = "accounted_cost_micros")
    val accountedCostMicros: Long? = null,
    @ColumnInfo(name = "accounted_currency")
    val accountedCurrency: String? = null,
    @ColumnInfo(name = "book_policy_id")
    val bookPolicyId: String,
    @ColumnInfo(name = "daily_policy_id")
    val dailyPolicyId: String,
    @ColumnInfo(name = "daily_period_key")
    val dailyPeriodKey: String,
    @ColumnInfo(name = "connection_id")
    val connectionId: String,
    @ColumnInfo(name = "normalized_destination")
    val normalizedDestination: String,
    @ColumnInfo(name = "protocol_id")
    val protocolId: String,
    @ColumnInfo(name = "disclosure_version")
    val disclosureVersion: Int,
    @ColumnInfo(name = "disclosure_binding_hash")
    val disclosureBindingHash: String,
    @ColumnInfo(name = "disclosure_accepted_at")
    val disclosureAcceptedAt: Long,
    @ColumnInfo(name = "settled_at")
    val settledAt: Long? = null,
    @ColumnInfo(name = "released_at")
    val releasedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
