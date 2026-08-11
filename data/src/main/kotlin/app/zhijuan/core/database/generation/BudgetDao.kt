package app.zhijuan.core.database.generation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.zhijuan.core.model.BudgetReservationStatus
import app.zhijuan.core.model.BudgetScope

/**
 * One-row aggregate over non-released reservations for one scope.
 *
 * [tokens] and [costMicros] are nullable SQL SUM results: NULL means either
 * no rows or integer overflow, and callers must treat NULL as fail-closed
 * instead of assuming an empty balance. [nullCostCount] is the number of
 * rows without a complete (cost, currency) pair and [foreignCurrencyCount]
 * the number of rows whose currency differs from the queried currency.
 */
internal data class BudgetReservationAggregate(
    val tokens: Long?,
    val costMicros: Long?,
    val nullCostCount: Int,
    val foreignCurrencyCount: Int,
)

/**
 * Minimal persistence surface for budget policy activation. All semantic
 * validation (scope identity, contiguous parent revisions, head CAS advance)
 * lives in [app.zhijuan.core.database.LibraryDatabaseGuards] and in
 * [PersistentBudgetPolicyRepository]; this DAO never fabricates rows.
 */
@Dao
internal interface BudgetDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: BudgetPolicyRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHead(head: BudgetPolicyHeadEntity)

    @Query("SELECT * FROM budget_policy_revision WHERE budget_policy_id = :policyId")
    suspend fun findRevision(policyId: String): BudgetPolicyRevisionEntity?

    @Query(
        "SELECT * FROM budget_policy_revision " +
            "WHERE scope = :scope AND scope_key = :scopeKey AND revision_no = :revisionNo",
    )
    suspend fun findRevisionByNumber(
        scope: BudgetScope,
        scopeKey: String,
        revisionNo: Int,
    ): BudgetPolicyRevisionEntity?

    @Query(
        "SELECT MAX(revision_no) FROM budget_policy_revision " +
            "WHERE scope = :scope AND scope_key = :scopeKey",
    )
    suspend fun maxRevisionNumber(scope: BudgetScope, scopeKey: String): Int?

    @Query("SELECT * FROM budget_policy_head WHERE scope = :scope AND scope_key = :scopeKey")
    suspend fun findHead(scope: BudgetScope, scopeKey: String): BudgetPolicyHeadEntity?

    @Query(
        "UPDATE budget_policy_head " +
            "SET current_budget_policy_id = :newCurrentPolicyId, updated_at = :updatedAt " +
            "WHERE scope = :scope AND scope_key = :scopeKey " +
            "AND current_budget_policy_id = :expectedCurrentPolicyId " +
            "AND updated_at <= :updatedAt",
    )
    suspend fun casAdvanceHead(
        scope: BudgetScope,
        scopeKey: String,
        expectedCurrentPolicyId: String,
        newCurrentPolicyId: String,
        updatedAt: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReservation(reservation: RequestBudgetReservationEntity)

    @Query("SELECT * FROM request_budget_reservation WHERE budget_reservation_id = :reservationId")
    suspend fun findReservation(reservationId: String): RequestBudgetReservationEntity?

    @Query("SELECT * FROM request_budget_reservation WHERE attempt_id = :attemptId")
    suspend fun findReservationByAttempt(attemptId: String): RequestBudgetReservationEntity?

    @Query(
        "SELECT " +
            "SUM(accounted_tokens) AS tokens, " +
            "SUM(accounted_cost_micros) AS costMicros, " +
            "COALESCE(SUM(" +
            "CASE WHEN accounted_cost_micros IS NULL OR accounted_currency IS NULL THEN 1 ELSE 0 END" +
            "), 0) AS nullCostCount, " +
            "COALESCE(SUM(" +
            "CASE WHEN accounted_currency IS NOT NULL AND accounted_currency <> :currency THEN 1 ELSE 0 END" +
            "), 0) AS foreignCurrencyCount " +
            "FROM request_budget_reservation " +
            "WHERE book_id = :bookId AND status != :excludedStatus",
    )
    suspend fun aggregateBookReservations(
        bookId: String,
        currency: String?,
        excludedStatus: BudgetReservationStatus,
    ): BudgetReservationAggregate?

    @Query(
        "SELECT " +
            "SUM(accounted_tokens) AS tokens, " +
            "SUM(accounted_cost_micros) AS costMicros, " +
            "COALESCE(SUM(" +
            "CASE WHEN accounted_cost_micros IS NULL OR accounted_currency IS NULL THEN 1 ELSE 0 END" +
            "), 0) AS nullCostCount, " +
            "COALESCE(SUM(" +
            "CASE WHEN accounted_currency IS NOT NULL AND accounted_currency <> :currency THEN 1 ELSE 0 END" +
            "), 0) AS foreignCurrencyCount " +
            "FROM request_budget_reservation " +
            "WHERE daily_period_key = :dailyPeriodKey AND status != :excludedStatus",
    )
    suspend fun aggregateDailyReservations(
        dailyPeriodKey: String,
        currency: String?,
        excludedStatus: BudgetReservationStatus,
    ): BudgetReservationAggregate?
}
