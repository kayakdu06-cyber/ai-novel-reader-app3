package app.zhijuan.core.database.generation

import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.GenerationJobStatus
import app.zhijuan.core.model.RequestAttemptStatus
import app.zhijuan.core.model.UsageLedgerStatus
import app.zhijuan.core.model.UsageSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class RequestIntentDraft(
    val attemptId: String,
    val usageLedgerId: String,
    val stageId: String,
    val retryParentAttemptId: String?,
    val connectionSnapshotJson: String,
    val modelSnapshotJson: String,
    val protocolSnapshotJson: String,
    val inputHash: String,
    val streamDraftRef: String?,
    val dailyPeriodKey: String,
    val createdAt: Long,
) {
    override fun toString(): String =
        "RequestIntentDraft(stageId=$stageId, snapshots=redacted, inputHash=redacted)"
}

class PersistedRequestSendPermit internal constructor(
    val attemptId: String,
    val stageId: String,
    val attemptNo: Int,
    val inputHash: String,
    val leaseToken: GenerationLeaseToken,
    val intentRecordedAt: Long,
) {
    private val claimed = AtomicBoolean(false)

    internal fun claimAfterPersistedLeaseValidation(validatedAt: Long): ClaimedRequestSend {
        check(claimed.compareAndSet(false, true)) {
            "A persisted request send permit can be claimed only once."
        }
        return ClaimedRequestSend(
            attemptId = attemptId,
            stageId = stageId,
            attemptNo = attemptNo,
            inputHash = inputHash,
            leaseToken = leaseToken,
            intentRecordedAt = intentRecordedAt,
            leaseValidatedAt = validatedAt,
        )
    }

    override fun toString(): String = "PersistedRequestSendPermit(claimed=${claimed.get()})"
}

class ClaimedRequestSend internal constructor(
    val attemptId: String,
    val stageId: String,
    val attemptNo: Int,
    val inputHash: String,
    val leaseToken: GenerationLeaseToken,
    val intentRecordedAt: Long,
    val leaseValidatedAt: Long,
) {
    override fun toString(): String = "ClaimedRequestSend(audit=redacted)"
}

data class StoredRequestAttemptAudit(
    val attemptId: String,
    val jobId: String,
    val stageId: String,
    val attemptNo: Int,
    val status: RequestAttemptStatus,
    val requestIntentAt: Long,
    val sentAt: Long?,
    val finishedAt: Long?,
    val retryParentAttemptId: String?,
    val updatedAt: Long,
)

data class StoredUsageLedgerAudit(
    val usageLedgerId: String,
    val attemptId: String,
    val bookId: String,
    val source: UsageSource,
    val status: UsageLedgerStatus,
    val totalTokens: Long?,
    val estimatedCostMicros: Long?,
    val finalizedAt: Long?,
    val updatedAt: Long,
)

data class PersistedRequestAudit(
    val permit: PersistedRequestSendPermit,
    val attempt: StoredRequestAttemptAudit,
    val usage: StoredUsageLedgerAudit,
)

class GenerationRequestAuditRepository(
    private val database: ZhijuanDatabase,
    private val leasePolicy: GenerationLeasePolicy = GenerationLeasePolicy(),
) {
    internal suspend fun persistBeforeSend(
        draft: RequestIntentDraft,
        leaseToken: GenerationLeaseToken,
    ): PersistedRequestAudit {
        RequestIntentDraftPolicy.validate(draft)
        val dao = database.generationDao()
        val attempt = dao.recordRequestIntent(draft.toInternal(), leaseToken)
        val usage = requireNotNull(dao.findUsageForAttempt(attempt.attemptId)) {
            "A persisted request intent must own a usage ledger before sending."
        }
        check(attempt.status == RequestAttemptStatus.INTENT_RECORDED)
        check(usage.source == UsageSource.UNKNOWN && usage.status == UsageLedgerStatus.PROVISIONAL)
        check(usage.totalTokens == null && usage.estimatedCostMicros == null)
        return PersistedRequestAudit(
            permit = PersistedRequestSendPermit(
                attemptId = attempt.attemptId,
                stageId = attempt.stageId,
                attemptNo = attempt.attemptNo,
                inputHash = attempt.inputHash,
                leaseToken = leaseToken,
                intentRecordedAt = attempt.requestIntentAt,
            ),
            attempt = attempt.toStoredAudit(),
            usage = usage.toStoredAudit(),
        )
    }

    internal suspend fun claimForProviderOpen(
        permit: PersistedRequestSendPermit,
        validatedAt: Long,
    ): ClaimedRequestSend {
        val dao = database.generationDao()
        database.withTransaction {
            val attempt = validatePermitEvidence(
                attemptId = permit.attemptId,
                stageId = permit.stageId,
                attemptNo = permit.attemptNo,
                inputHash = permit.inputHash,
                intentRecordedAt = permit.intentRecordedAt,
            )
            requireJobAllowsProviderOpen(attempt, validatedAt)
            dao.heartbeatStageLease(
                stageId = permit.stageId,
                leaseToken = permit.leaseToken,
                now = validatedAt,
                policy = leasePolicy,
            )
        }
        return permit.claimAfterPersistedLeaseValidation(validatedAt)
    }

    internal suspend fun markRequestSent(
        claimedSend: ClaimedRequestSend,
        providerRequestId: String?,
        sentAt: Long,
    ): StoredRequestAttemptAudit {
        require(sentAt >= claimedSend.leaseValidatedAt) {
            "Request send time cannot precede send authorization."
        }
        require(providerRequestId == null || (providerRequestId.isNotBlank() && providerRequestId.length <= 1_024)) {
            "Provider request id is empty or too long."
        }
        val dao = database.generationDao()
        val attempt = validatePermitEvidence(
            attemptId = claimedSend.attemptId,
            stageId = claimedSend.stageId,
            attemptNo = claimedSend.attemptNo,
            inputHash = claimedSend.inputHash,
            intentRecordedAt = claimedSend.intentRecordedAt,
        )
        return dao.recordRequestSent(
            attemptId = attempt.attemptId,
            providerRequestId = providerRequestId,
            sentAt = sentAt,
            leaseToken = claimedSend.leaseToken,
        ).toStoredAudit()
    }

    internal suspend fun markStreamStarted(
        claimedSend: ClaimedRequestSend,
        startedAt: Long,
    ): StoredRequestAttemptAudit {
        require(startedAt >= claimedSend.leaseValidatedAt) {
            "Stream-start time cannot precede send authorization."
        }
        return database.generationDao().recordStreamStarted(
            attemptId = claimedSend.attemptId,
            updatedAt = startedAt,
            leaseToken = claimedSend.leaseToken,
        ).toStoredAudit()
    }

    suspend fun findAttempt(attemptId: String): StoredRequestAttemptAudit? =
        database.generationDao().findAttempt(attemptId)?.toStoredAudit()

    suspend fun findUsageForAttempt(attemptId: String): StoredUsageLedgerAudit? =
        database.generationDao().findUsageForAttempt(attemptId)?.toStoredAudit()

    private suspend fun validatePermitEvidence(
        attemptId: String,
        stageId: String,
        attemptNo: Int,
        inputHash: String,
        intentRecordedAt: Long,
    ): RequestAttemptEntity {
        val dao = database.generationDao()
        val attempt = requireNotNull(dao.findAttempt(attemptId)) {
            "Persisted request attempt no longer exists."
        }
        val usage = requireNotNull(dao.findUsageForAttempt(attemptId)) {
            "Persisted request usage ledger no longer exists."
        }
        if (
            attempt.status != RequestAttemptStatus.INTENT_RECORDED ||
            attempt.stageId != stageId ||
            attempt.attemptNo != attemptNo ||
            attempt.inputHash != inputHash ||
            attempt.requestIntentAt != intentRecordedAt ||
            usage.attemptId != attempt.attemptId ||
            usage.status != UsageLedgerStatus.PROVISIONAL
        ) {
            throw StaleGenerationStateException("Request send permit no longer matches persisted audit evidence.")
        }
        return attempt
    }

    private suspend fun requireJobAllowsProviderOpen(
        attempt: RequestAttemptEntity,
        validatedAt: Long,
    ) {
        val dao = database.generationDao()
        val job = requireNotNull(dao.findJob(attempt.jobId)) {
            "Owning generation job no longer exists."
        }
        if (job.status != GenerationJobStatus.RUNNING || job.currentStageId != attempt.stageId) {
            throw StaleGenerationStateException(
                "A paused, stopping, or superseded job cannot open a Provider request.",
            )
        }
        require(validatedAt >= job.updatedAt) { "Provider-open validation time cannot move backwards." }
        val stage = requireNotNull(dao.findStage(attempt.stageId)) {
            "Provider-open generation stage no longer exists."
        }
        ChapterProgressionGateRepository(database).requireProviderOpenAllowed(stage, job)
        ChapterContextAssemblyRepository(database).requireProviderOpenAllowedIfBound(stage, job)
        ChapterMemoryExtractionSourceGuard(database).requireProviderOpenAllowedIfBound(stage, job)
        ChapterTrackingProjectionSourceGuard(database).requireProviderOpenAllowedIfBound(stage, job)
    }
}

internal object RequestIntentDraftPolicy {
    private const val MAX_SNAPSHOT_CHARS = 65_536
    private const val MAX_REFERENCE_CHARS = 1_024
    private val identifier = Regex("[A-Za-z0-9._:-]{1,128}")
    private val sha256 = Regex("[0-9a-f]{64}")
    private val protectedArtifactRef = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    )
    private val strictJson = Json {
        isLenient = false
    }
    private val forbiddenNormalizedKeys = setOf(
        "apikey",
        "xapikey",
        "xgoogapikey",
        "authorization",
        "password",
        "cookie",
        "setcookie",
        "accesstoken",
        "refreshtoken",
        "idtoken",
        "clientsecret",
        "credential",
        "secret",
    )

    fun validate(draft: RequestIntentDraft) {
        require(identifier.matches(draft.attemptId)) { "Attempt id is invalid." }
        require(identifier.matches(draft.usageLedgerId)) { "Usage ledger id is invalid." }
        require(identifier.matches(draft.stageId)) { "Stage id is invalid." }
        require(draft.retryParentAttemptId == null || identifier.matches(draft.retryParentAttemptId)) {
            "Retry parent attempt id is invalid."
        }
        require(sha256.matches(draft.inputHash)) { "Input hash must be lowercase SHA-256." }
        require(draft.createdAt >= 0L) { "Request intent time must not be negative." }
        require(draft.dailyPeriodKey.isNotBlank() && draft.dailyPeriodKey.length <= 128) {
            "Daily period key is invalid."
        }
        require(
            draft.streamDraftRef != null &&
                draft.streamDraftRef.length <= MAX_REFERENCE_CHARS &&
                protectedArtifactRef.matches(draft.streamDraftRef),
        ) {
            "Stream draft reference must be a protected artifact UUID."
        }
        validateSnapshot("Connection", draft.connectionSnapshotJson)
        validateSnapshot("Model", draft.modelSnapshotJson)
        validateSnapshot("Protocol", draft.protocolSnapshotJson)
    }

    private fun validateSnapshot(label: String, json: String) {
        require(json.isNotBlank() && json.length <= MAX_SNAPSHOT_CHARS) {
            "$label snapshot is empty or too large."
        }
        val parsed = try {
            strictJson.parseToJsonElement(json)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("$label snapshot must be valid JSON.")
        }
        require(parsed is JsonObject) { "$label snapshot must be a JSON object." }
        require(!parsed.containsForbiddenSecretKey()) {
            "$label snapshot contains a forbidden secret-bearing field."
        }
    }

    private fun JsonElement.containsForbiddenSecretKey(): Boolean = when (this) {
        is JsonObject -> entries.any { (key, value) ->
            key.lowercase().filter(Char::isLetterOrDigit) in forbiddenNormalizedKeys ||
                value.containsForbiddenSecretKey()
        }
        is JsonArray -> any { it.containsForbiddenSecretKey() }
        else -> false
    }
}

private fun RequestIntentDraft.toInternal() = NewRequestIntent(
    attemptId = attemptId,
    usageLedgerId = usageLedgerId,
    stageId = stageId,
    retryParentAttemptId = retryParentAttemptId,
    connectionSnapshotJson = connectionSnapshotJson,
    modelSnapshotJson = modelSnapshotJson,
    protocolSnapshotJson = protocolSnapshotJson,
    inputHash = inputHash,
    streamDraftRef = streamDraftRef,
    dailyPeriodKey = dailyPeriodKey,
    createdAt = createdAt,
)

private fun RequestAttemptEntity.toStoredAudit() = StoredRequestAttemptAudit(
    attemptId = attemptId,
    jobId = jobId,
    stageId = stageId,
    attemptNo = attemptNo,
    status = status,
    requestIntentAt = requestIntentAt,
    sentAt = sentAt,
    finishedAt = finishedAt,
    retryParentAttemptId = retryParentAttemptId,
    updatedAt = updatedAt,
)

private fun UsageLedgerEntity.toStoredAudit() = StoredUsageLedgerAudit(
    usageLedgerId = usageLedgerId,
    attemptId = attemptId,
    bookId = bookId,
    source = source,
    status = status,
    totalTokens = totalTokens,
    estimatedCostMicros = estimatedCostMicros,
    finalizedAt = finalizedAt,
    updatedAt = updatedAt,
)
