package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationStageStatus

data class GenerationLeaseToken(
    val ownerId: String,
    val acquiredAt: Long,
) {
    init {
        require(ownerId.isNotBlank()) { "Lease owner id must not be blank." }
        require(acquiredAt >= 0L) { "Lease acquisition time must not be negative." }
    }
}

data class GenerationLeasePolicy(
    val heartbeatIntervalMillis: Long = DEFAULT_HEARTBEAT_INTERVAL_MILLIS,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        require(heartbeatIntervalMillis > 0L) { "Heartbeat interval must be positive." }
        require(heartbeatIntervalMillis <= Long.MAX_VALUE / MIN_MISSED_HEARTBEATS) {
            "Heartbeat interval is too large."
        }
        require(timeoutMillis >= heartbeatIntervalMillis * MIN_MISSED_HEARTBEATS) {
            "Lease timeout must allow at least $MIN_MISSED_HEARTBEATS heartbeat intervals."
        }
    }

    fun isExpired(heartbeatAt: Long, now: Long): Boolean {
        require(heartbeatAt >= 0L && now >= 0L) { "Lease timestamps must not be negative." }
        require(now >= heartbeatAt) { "Lease time cannot move backwards." }
        return now - heartbeatAt >= timeoutMillis
    }

    companion object {
        const val DEFAULT_HEARTBEAT_INTERVAL_MILLIS = 15_000L
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        private const val MIN_MISSED_HEARTBEATS = 3L
    }
}

enum class ExpiredStageLeaseDisposition {
    ACTIVE,
    REQUEUED_BEFORE_REQUEST,
    RECOVERY_AUDIT_REQUIRED,
}

data class ExpiredStageLeaseResult(
    val disposition: ExpiredStageLeaseDisposition,
    val stage: StoredGenerationStageState,
)

internal fun GenerationStageStatus.canSafelyRequeueAfterLeaseExpiry(): Boolean =
    this == GenerationStageStatus.PREPARING
