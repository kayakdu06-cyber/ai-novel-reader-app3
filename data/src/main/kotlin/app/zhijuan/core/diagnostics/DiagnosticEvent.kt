package app.zhijuan.core.diagnostics

import app.zhijuan.core.model.StandardErrorCode
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID

enum class DiagnosticSeverity {
    DEBUG,
    INFO,
    WARNING,
    ERROR,
}

enum class DiagnosticCategory {
    APPLICATION,
    NETWORK,
    GENERATION,
    STORAGE,
    BACKUP,
    SECURITY,
}

enum class DiagnosticCode {
    APP_STARTED,
    CONNECTION_TEST_STARTED,
    CONNECTION_TEST_FAILED,
    REQUEST_FAILED,
    STREAM_PROTOCOL_ANOMALY,
    GENERATION_RECOVERY_REQUIRED,
    DATABASE_OPEN_FAILED,
    DATABASE_MIGRATION_FAILED,
    BACKUP_FAILED,
    KEYSTORE_UNAVAILABLE,
    UNEXPECTED_FAILURE,
    REQUEST_STARTED,
    RESPONSE_OPENED,
    REQUEST_CANCELLED,
}

enum class DiagnosticOperation {
    STARTUP,
    CONNECTION_TEST,
    GENERATION_REQUEST,
    STREAM_READ,
    DATABASE_OPEN,
    DATABASE_MIGRATION,
    BACKUP_CREATE,
    BACKUP_RESTORE,
    SECRET_READ,
}

enum class DiagnosticProtocol {
    OPENAI_CHAT_COMPATIBLE,
    UNKNOWN,
}

enum class DiagnosticCorrelationKind {
    CONNECTION,
    ENDPOINT,
    MODEL,
    BOOK,
    JOB,
    STAGE,
    ATTEMPT,
}

@ConsistentCopyVisibility
data class DiagnosticEvent internal constructor(
    val eventId: String,
    val timestampEpochMillis: Long,
    val severity: DiagnosticSeverity,
    val category: DiagnosticCategory,
    val code: DiagnosticCode,
    val operation: DiagnosticOperation,
    val standardErrorCode: StandardErrorCode?,
    val protocol: DiagnosticProtocol?,
    val httpStatus: Int?,
    val retryable: Boolean?,
    val elapsedMillis: Long?,
    val androidApiLevel: Int?,
    val correlationHashes: Map<DiagnosticCorrelationKind, String>,
    val errorTypes: List<String>,
) {
    init {
        require(eventId.matches(EVENT_ID_PATTERN)) { "Diagnostic event id is invalid." }
        require(timestampEpochMillis >= 0) { "Diagnostic timestamp is invalid." }
        require(httpStatus == null || httpStatus in 100..599) { "HTTP status is invalid." }
        require(elapsedMillis == null || elapsedMillis >= 0) { "Elapsed time is invalid." }
        require(androidApiLevel == null || androidApiLevel in 1..1_000) { "Android API level is invalid." }
        require(correlationHashes.size <= DiagnosticCorrelationKind.entries.size)
        require(correlationHashes.values.all { it.matches(CORRELATION_HASH_PATTERN) }) {
            "Diagnostic correlation hash is invalid."
        }
        require(errorTypes.size <= MAX_ERROR_TYPES)
        require(errorTypes.all { it.matches(ERROR_TYPE_PATTERN) }) {
            "Diagnostic error type is invalid."
        }
    }

    companion object {
        internal const val MAX_ERROR_TYPES = 4
        internal val EVENT_ID_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        )
        internal val CORRELATION_HASH_PATTERN = Regex("[0-9a-f]{24}")
        internal val ERROR_TYPE_PATTERN = Regex("[A-Za-z0-9_.$]{1,160}")
    }
}

class DiagnosticEventFactory(
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun create(
        timestampEpochMillis: Long,
        severity: DiagnosticSeverity,
        category: DiagnosticCategory,
        code: DiagnosticCode,
        operation: DiagnosticOperation,
        standardErrorCode: StandardErrorCode? = null,
        protocol: DiagnosticProtocol? = null,
        httpStatus: Int? = null,
        retryable: Boolean? = null,
        elapsedMillis: Long? = null,
        androidApiLevel: Int? = null,
        correlations: Map<DiagnosticCorrelationKind, String> = emptyMap(),
        error: Throwable? = null,
    ): DiagnosticEvent = DiagnosticEvent(
        eventId = eventIdFactory(),
        timestampEpochMillis = timestampEpochMillis,
        severity = severity,
        category = category,
        code = code,
        operation = operation,
        standardErrorCode = standardErrorCode,
        protocol = protocol,
        httpStatus = httpStatus,
        retryable = retryable,
        elapsedMillis = elapsedMillis,
        androidApiLevel = androidApiLevel,
        correlationHashes = correlations
            .filterValues(String::isNotEmpty)
            .mapValues { (kind, rawValue) -> correlationHash(kind, rawValue) }
            .toSortedMap(compareBy(DiagnosticCorrelationKind::ordinal)),
        errorTypes = errorTypes(error),
    )

    private fun correlationHash(kind: DiagnosticCorrelationKind, rawValue: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(HASH_DOMAIN)
        digest.update(0.toByte())
        digest.update(kind.name.toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(rawValue.toByteArray(StandardCharsets.UTF_8))
        return digest.digest()
            .take(HASH_BYTES)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun errorTypes(error: Throwable?): List<String> {
        if (error == null) return emptyList()
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>().apply { add(error) }
        val result = mutableListOf<String>()
        while (pending.isNotEmpty() && result.size < DiagnosticEvent.MAX_ERROR_TYPES) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            result += current.javaClass.name.takeIf(DiagnosticEvent.ERROR_TYPE_PATTERN::matches)
                ?: Throwable::class.java.name
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
        return result
    }

    private companion object {
        val HASH_DOMAIN = "app.zhijuan.diagnostics.correlation.v1".toByteArray(StandardCharsets.UTF_8)
        const val HASH_BYTES = 12
    }
}
