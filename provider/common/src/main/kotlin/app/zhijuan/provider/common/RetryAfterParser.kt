package app.zhijuan.provider.common

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

object RetryAfterParser {
    const val MAXIMUM_RETRY_AFTER_MILLIS = 24L * 60 * 60 * 1_000

    fun parse(
        value: String?,
        nowEpochMillis: Long,
    ): Long? {
        require(nowEpochMillis >= 0) { "Current time is invalid." }
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (normalized.any(Char::isISOControl)) return null
        normalized.toLongOrNull()?.let { seconds ->
            if (seconds < 0) return null
            return seconds.coerceAtMost(MAXIMUM_RETRY_AFTER_MILLIS / 1_000) * 1_000
        }
        val target = try {
            ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (_: DateTimeParseException) {
            return null
        }
        val now = Instant.ofEpochMilli(nowEpochMillis)
        val delay = runCatching { target.toEpochMilli() - now.toEpochMilli() }.getOrNull() ?: return null
        return delay.coerceIn(0, MAXIMUM_RETRY_AFTER_MILLIS)
    }
}
