package app.zhijuan.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.generation.GenerationJobEntity
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.generation.PersistentBudgetPolicyRepository
import app.zhijuan.core.database.generation.RequestAttemptEntity
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.BudgetDailyPeriodKeyV1
import app.zhijuan.core.model.BudgetLimit
import app.zhijuan.core.model.BudgetScope
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.TitleSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PersistentBudgetPolicyDatabaseTest {
    @get:Rule
    val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: ZhijuanDatabase
    private lateinit var repository: PersistentBudgetPolicyRepository

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, ZhijuanDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(LibraryDatabaseGuards.callback)
            .build()
            .also { it.openHelper.writableDatabase }
        seedBook(BOOK_ID, "snapshot-budget-main")
        seedBook(OTHER_BOOK_ID, "snapshot-budget-other")
        repository = PersistentBudgetPolicyRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun activatesBookAndDailyPoliciesAndAdvancesOnlyToDirectChildren() = runBlocking {
        val firstBook = repository.activateBookPolicy(
            policyId = BOOK_POLICY_1,
            bookId = BOOK_ID,
            limit = BudgetLimit(maxTokens = 10_000, maxCostMicros = 500, currency = "USD"),
            activatedAt = 10,
        )
        val firstDaily = repository.activateDailyPolicy(
            policyId = DAILY_POLICY_1,
            zoneId = "Asia/Shanghai",
            limit = BudgetLimit(maxTokens = 20_000),
            activatedAt = 11,
        )
        val secondBook = repository.activateBookPolicy(
            policyId = BOOK_POLICY_2,
            bookId = BOOK_ID,
            limit = BudgetLimit(maxTokens = 12_000),
            activatedAt = 12,
        )

        assertEquals(BudgetScope.BOOK, firstBook.scope)
        assertEquals(1, firstBook.revisionNo)
        assertEquals(BudgetScope.DAILY, firstDaily.scope)
        assertEquals(1, firstDaily.revisionNo)
        assertEquals(2, secondBook.revisionNo)
        assertEquals(2, repository.currentBookPolicy(BOOK_ID)?.revisionNo)
        assertEquals(12_000L, repository.currentBookPolicy(BOOK_ID)?.maxTokens)
        assertEquals(1, repository.currentDailyPolicy("Asia/Shanghai")?.revisionNo)
        assertEquals(null, repository.currentDailyPolicy("UTC"))
        assertFalse(firstBook.toString().contains(BOOK_POLICY_1))
        assertFalse(firstBook.toString().contains(BOOK_ID))

        database.openHelper.writableDatabase.query(
            "SELECT parent_budget_policy_id FROM budget_policy_revision WHERE budget_policy_id = ?",
            arrayOf(BOOK_POLICY_2),
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(BOOK_POLICY_1, cursor.getString(0))
        }
        database.openHelper.writableDatabase.query(
            "SELECT current_budget_policy_id FROM budget_policy_head WHERE scope = 'BOOK' AND scope_key = ?",
            arrayOf(BOOK_ID),
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals(BOOK_POLICY_2, cursor.getString(0))
        }
    }

    @Test
    fun invalidActivationAndDirectPolicyMutationRollBackWithoutMovingHeads() = runBlocking {
        repository.activateBookPolicy(BOOK_POLICY_1, BOOK_ID, BudgetLimit(10_000), 10)
        repository.activateDailyPolicy(DAILY_POLICY_1, "UTC", BudgetLimit(20_000), 10)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.activateBookPolicy("policy-missing-book", "missing-book", BudgetLimit(1_000), 11)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.activateDailyPolicy(DAILY_POLICY_2, "Asia/Shanghai", BudgetLimit(20_000), 11)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.activateBookPolicy("policy-book-backwards", BOOK_ID, BudgetLimit(11_000), 9)
            }
        }
        repository.activateBookPolicy(BOOK_POLICY_2, BOOK_ID, BudgetLimit(11_000), 11)
        assertThrows(RuntimeException::class.java) {
            runBlocking {
                repository.activateBookPolicy(BOOK_POLICY_2, OTHER_BOOK_ID, BudgetLimit(8_000), 12)
            }
        }
        assertSqlRejected(
            """
            INSERT INTO budget_policy_revision (
                budget_policy_id, scope, scope_key, revision_no,
                parent_budget_policy_id, book_id, daily_zone_id,
                max_tokens, max_cost_micros, currency, policy_version, created_at
            ) VALUES (?, 'BOOK', ?, 2, ?, ?, NULL, 12000, NULL, NULL, ?, 12)
            """.trimIndent(),
            arrayOf(
                "policy-book-fork",
                BOOK_ID,
                BOOK_POLICY_1,
                BOOK_ID,
                "zhijuan.budget-policy.v1",
            ),
        )
        assertSqlRejected(
            "UPDATE budget_policy_revision SET max_tokens = 1 WHERE budget_policy_id = ?",
            arrayOf(BOOK_POLICY_1),
        )
        assertSqlRejected(
            "DELETE FROM budget_policy_head WHERE scope = 'BOOK' AND scope_key = ?",
            arrayOf(BOOK_ID),
        )

        assertEquals(2, repository.currentBookPolicy(BOOK_ID)?.revisionNo)
        assertEquals(11_000L, repository.currentBookPolicy(BOOK_ID)?.maxTokens)
        assertEquals(null, repository.currentBookPolicy(OTHER_BOOK_ID))
        assertEquals(1, repository.currentDailyPolicy("UTC")?.revisionNo)
        assertEquals(3, rowCount("budget_policy_revision"))
    }

    @Test
    fun reservationGuardsBindTheJobBookAndAllowOnlyAuditedForwardTransitions() = runBlocking {
        repository.activateBookPolicy(BOOK_POLICY_1, BOOK_ID, BudgetLimit(10_000), 10)
        repository.activateBookPolicy(OTHER_BOOK_POLICY, OTHER_BOOK_ID, BudgetLimit(10_000), 10)
        repository.activateDailyPolicy(DAILY_POLICY_1, "UTC", BudgetLimit(20_000), 10)
        seedJobAndStage()

        assertSqlRejected(
            reservationSql(),
            reservationArgs(
                reservationId = "reservation-direct-settled",
                attemptId = "attempt-direct-settled",
                status = "SETTLED",
                settledAt = 100,
            ),
        )
        assertSqlRejected(
            reservationSql(),
            reservationArgs(
                reservationId = "reservation-wrong-book",
                attemptId = "attempt-wrong-book",
                bookId = OTHER_BOOK_ID,
                bookPolicyId = OTHER_BOOK_POLICY,
            ),
        )
        assertSqlRejected(
            reservationSql(),
            reservationArgs(
                reservationId = "reservation-invalid-currency",
                attemptId = "attempt-invalid-currency",
                requestCurrency = "123",
            ),
        )

        database.openHelper.writableDatabase.execSQL(
            reservationSql(),
            reservationArgs(RESERVATION_ID, ATTEMPT_ID),
        )
        database.generationDao().insertAttempt(attempt(ATTEMPT_ID, 1, RESERVATION_ID))
        assertSqlRejected(
            "UPDATE request_budget_reservation SET connection_id = 'changed' WHERE budget_reservation_id = ?",
            arrayOf(RESERVATION_ID),
        )
        assertSqlRejected(
            "DELETE FROM request_budget_reservation WHERE budget_reservation_id = ?",
            arrayOf(RESERVATION_ID),
        )
        assertThrows(RuntimeException::class.java) {
            runBlocking {
                database.generationDao().insertAttempt(attempt("attempt-missing-reservation", 2, "missing"))
            }
        }

        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE request_budget_reservation
            SET status = 'RELEASED', accounted_tokens = 0,
                accounted_cost_micros = NULL, accounted_currency = NULL,
                released_at = 130, updated_at = 130
            WHERE budget_reservation_id = ?
            """.trimIndent(),
            arrayOf(RESERVATION_ID),
        )
        assertSqlRejected(
            """
            UPDATE request_budget_reservation
            SET status = 'RESERVED', accounted_tokens = estimated_tokens,
                accounted_cost_micros = estimated_cost_micros,
                accounted_currency = estimated_currency,
                released_at = NULL, updated_at = 131
            WHERE budget_reservation_id = ?
            """.trimIndent(),
            arrayOf(RESERVATION_ID),
        )
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE request_budget_reservation
            SET status = 'SETTLED', accounted_tokens = 600,
                accounted_cost_micros = 30, accounted_currency = 'USD',
                settled_at = 140, updated_at = 140
            WHERE budget_reservation_id = ?
            """.trimIndent(),
            arrayOf(RESERVATION_ID),
        )
        assertSqlRejected(
            """
            UPDATE request_budget_reservation
            SET status = 'RELEASED', accounted_tokens = 0,
                accounted_cost_micros = NULL, accounted_currency = NULL,
                settled_at = NULL, updated_at = 150
            WHERE budget_reservation_id = ?
            """.trimIndent(),
            arrayOf(RESERVATION_ID),
        )

        database.openHelper.writableDatabase.query(
            "SELECT status, accounted_tokens, released_at, settled_at FROM request_budget_reservation WHERE budget_reservation_id = ?",
            arrayOf(RESERVATION_ID),
        ).use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            assertEquals("SETTLED", cursor.getString(0))
            assertEquals(600L, cursor.getLong(1))
            assertEquals(130L, cursor.getLong(2))
            assertEquals(140L, cursor.getLong(3))
        }
    }

    private suspend fun seedBook(bookId: String, snapshotId: String) {
        database.libraryDao().createBook(
            BookCreationSnapshotEntity(
                snapshotId = snapshotId,
                rawInputJson = "{}",
                normalizedInputJson = "{}",
                inferenceProvenanceJson = "{}",
                genrePayloadJson = "{}",
                presentationProfileJson = "{}",
                modelPreferenceJson = "{}",
                schemaVersion = 1,
                promptBundleVersion = "prompt-v1",
                contentControlSchemaVersion = 1,
                contentHash = "hash-$snapshotId",
                createdAt = 1,
            ),
            BookEntity(
                bookId = bookId,
                creationSnapshotId = snapshotId,
                title = "Budget test book",
                titleSource = TitleSource.USER,
                status = BookStatus.DRAFT,
                lengthMode = BookLengthMode.LONG,
                targetCharacters = 500_000,
                targetChapters = 500,
                minimumChapters = 301,
                lengthPolicySchemaVersion = 1,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
    }

    private suspend fun seedJobAndStage() {
        database.generationDao().createJob(
            GenerationJobEntity(
                jobId = JOB_ID,
                bookId = BOOK_ID,
                jobType = GenerationJobType.CREATE_BOOK,
                status = GenerationJobStatus.CREATED,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                promptBundleVersion = "prompt-v1",
                createdAt = 20,
                updatedAt = 20,
            ),
            listOf(
                GenerationStageEntity(
                    stageId = STAGE_ID,
                    jobId = JOB_ID,
                    phase = GenerationPhase.BUILD_CHAPTER_PLAN,
                    targetType = GenerationTargetType.BOOK,
                    targetId = BOOK_ID,
                    status = GenerationStageStatus.PENDING,
                    inputVersionHash = "input-v1",
                    idempotencyKey = "budget-stage-idempotency",
                    maxAttempts = 3,
                    inputSourcesJson = "[]",
                    createdAt = 20,
                    updatedAt = 20,
                ),
            ),
        )
    }

    private fun attempt(attemptId: String, attemptNo: Int, reservationId: String) = RequestAttemptEntity(
        attemptId = attemptId,
        jobId = JOB_ID,
        stageId = STAGE_ID,
        attemptNo = attemptNo,
        status = RequestAttemptStatus.INTENT_RECORDED,
        requestIntentAt = 100,
        connectionSnapshotJson = "{}",
        modelSnapshotJson = "{}",
        protocolSnapshotJson = "{}",
        inputHash = "a".repeat(64),
        budgetEnforcementVersion = 1,
        budgetReservationId = reservationId,
        createdAt = 100,
        updatedAt = 100,
    )

    private fun reservationSql(): String =
        """
        INSERT INTO request_budget_reservation (
            budget_reservation_id, attempt_id, job_id, stage_id, book_id, status,
            request_max_tokens, request_max_cost_micros, request_currency,
            estimated_tokens, estimated_cost_micros, estimated_currency, estimate_source_version,
            accounted_tokens, accounted_cost_micros, accounted_currency,
            book_policy_id, daily_policy_id, daily_period_key,
            connection_id, normalized_destination, protocol_id,
            disclosure_version, disclosure_binding_hash, disclosure_accepted_at,
            settled_at, released_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    private fun reservationArgs(
        reservationId: String,
        attemptId: String,
        status: String = "RESERVED",
        bookId: String = BOOK_ID,
        bookPolicyId: String = BOOK_POLICY_1,
        requestCurrency: String = "USD",
        settledAt: Long? = null,
    ): Array<Any?> = arrayOf(
        reservationId,
        attemptId,
        JOB_ID,
        STAGE_ID,
        bookId,
        status,
        1_000L,
        50L,
        requestCurrency,
        500L,
        25L,
        "USD",
        "estimate-v1",
        500L,
        25L,
        "USD",
        bookPolicyId,
        DAILY_POLICY_1,
        BudgetDailyPeriodKeyV1.create(100, "UTC"),
        "connection-budget",
        "https://api.example.com:443",
        "OPENAI_CHAT_COMPAT",
        1,
        "a".repeat(64),
        90L,
        settledAt,
        null,
        100L,
        100L,
    )

    private fun assertSqlRejected(sql: String, args: Array<Any?> = emptyArray()) {
        assertThrows(RuntimeException::class.java) {
            database.openHelper.writableDatabase.execSQL(sql, args)
        }
    }

    private fun rowCount(table: String): Int {
        assertNotNull(table)
        database.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertEquals(true, cursor.moveToFirst())
            return cursor.getInt(0)
        }
    }

    private companion object {
        const val BOOK_ID = "book-budget-main"
        const val OTHER_BOOK_ID = "book-budget-other"
        const val BOOK_POLICY_1 = "policy-book-1"
        const val BOOK_POLICY_2 = "policy-book-2"
        const val OTHER_BOOK_POLICY = "policy-book-other"
        const val DAILY_POLICY_1 = "policy-daily-1"
        const val DAILY_POLICY_2 = "policy-daily-2"
        const val JOB_ID = "job-budget"
        const val STAGE_ID = "stage-budget"
        const val RESERVATION_ID = "reservation-budget"
        const val ATTEMPT_ID = "attempt-budget"
    }
}
