package app.zhijuan.core.model

import java.time.Instant
import java.time.ZoneId

enum class BudgetScope {
    REQUEST,
    BOOK,
    DAILY,
}

enum class BudgetReservationStatus {
    RESERVED,
    SETTLED,
    RELEASED,
}

/**
 * Deterministic daily budget period key for one persisted IANA zone.
 *
 * The only accepted input is a non-negative epoch millisecond plus an explicit
 * IANA zone id; the system default zone is never consulted. The output format
 * is `yyyy-MM-dd|ZoneId` and intentionally contains no data other than the
 * derived calendar date and the zone that produced it.
 */
object BudgetDailyPeriodKeyV1 {
    private const val MAX_ZONE_ID_LENGTH = 64
    private val zoneIds: Set<String> = ZoneId.getAvailableZoneIds()

    fun isSupportedZoneId(zoneId: String): Boolean =
        zoneId.isNotBlank() && zoneId.length <= MAX_ZONE_ID_LENGTH && zoneIds.contains(zoneId)

    fun create(epochMillis: Long, zoneId: String): String {
        require(epochMillis >= 0) { "Epoch millis must be non-negative." }
        require(isSupportedZoneId(zoneId)) { "Unsupported IANA zone id." }
        val zone = ZoneId.of(zoneId)
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return "$date|$zoneId"
    }
}

data class BudgetLimit(
    val maxTokens: Long,
    val maxCostMicros: Long? = null,
    val currency: String? = null,
) {
    init {
        require(maxTokens > 0) { "A token hard limit is required and must be positive." }
        require(maxCostMicros == null || maxCostMicros > 0) {
            "A monetary hard limit must be positive when present."
        }
        require((maxCostMicros == null) == (currency == null)) {
            "Monetary limits and currency must be provided together."
        }
        require(currency == null || currency.matches(Regex("[A-Z]{3}"))) {
            "Currency must be a three-letter uppercase code."
        }
    }
}

data class BudgetCounter(
    val usedTokens: Long = 0,
    val reservedTokens: Long = 0,
    val usedCostMicros: Long = 0,
    val reservedCostMicros: Long = 0,
) {
    init {
        require(usedTokens >= 0 && reservedTokens >= 0) { "Token counters cannot be negative." }
        require(usedCostMicros >= 0 && reservedCostMicros >= 0) { "Cost counters cannot be negative." }
    }
}

data class BudgetScopeSnapshot(
    val scope: BudgetScope,
    val limit: BudgetLimit,
    val counter: BudgetCounter,
)

data class RequestEstimate(
    val tokens: Long,
    val costMicros: Long? = null,
    val currency: String? = null,
) {
    init {
        require(tokens > 0) { "Estimated tokens must be positive." }
        require(costMicros == null || costMicros >= 0) { "Estimated cost cannot be negative." }
        require((costMicros == null) == (currency == null)) {
            "Estimated cost and currency must be provided together."
        }
        require(currency == null || currency.matches(Regex("[A-Z]{3}"))) {
            "Currency must be a three-letter uppercase code."
        }
    }
}

data class UsageActual(
    val tokens: Long,
    val costMicros: Long? = null,
    val currency: String? = null,
) {
    init {
        require(tokens >= 0) { "Actual tokens cannot be negative." }
        require(costMicros == null || costMicros >= 0) { "Actual cost cannot be negative." }
        require((costMicros == null) == (currency == null)) {
            "Actual cost and currency must be provided together."
        }
    }
}

data class BudgetViolation(
    val scope: BudgetScope,
    val tokenLimitExceeded: Boolean,
    val monetaryLimitExceeded: Boolean,
)

sealed interface BudgetReservationResult {
    data class Granted(
        val estimate: RequestEstimate,
        val updatedScopes: List<BudgetScopeSnapshot>,
    ) : BudgetReservationResult

    data class Denied(
        val violations: List<BudgetViolation>,
    ) : BudgetReservationResult
}

data class BudgetSettlement(
    val updatedScopes: List<BudgetScopeSnapshot>,
    val exhaustedScopes: Set<BudgetScope>,
)

