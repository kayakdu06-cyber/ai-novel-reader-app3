package app.zhijuan.provider.transport

import app.zhijuan.core.model.StandardErrorCode
import app.zhijuan.provider.common.ProviderCallFailure
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderProtocol
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Collections

enum class ProviderHttpMethod {
    GET,
    POST,
}

enum class PrimarySecretScheme {
    RAW,
    BEARER,
}

data class PrimarySecretHeader(
    val name: String,
    val scheme: PrimarySecretScheme,
) {
    init {
        require(name.matches(HEADER_NAME_PATTERN)) { "Primary secret header name is invalid." }
        require(isPrimaryCredentialHeader(name)) {
            "Primary secret must use a recognized provider credential header name."
        }
    }
}

class PublicQueryParameter(
    val name: String,
    private val value: String,
) {
    init {
        require(name.matches(QUERY_NAME_PATTERN)) { "Public query parameter name is invalid." }
        require(!isSensitiveQueryName(name)) { "Sensitive values must not be placed in a query." }
        require(value.length <= MAX_QUERY_VALUE_LENGTH && value.none(Char::isISOControl)) {
            "Public query parameter value is invalid."
        }
    }

    fun <T> withValue(block: (String) -> T): T = block(value)

    override fun toString(): String = "PublicQueryParameter(name=$name, characters=${value.length})"
}

class SensitiveHttpBody private constructor(
    private val bytes: ByteArray,
) : AutoCloseable {
    @Volatile
    private var closed = false

    val byteCount: Int
        get() = bytes.size

    fun <T> withBytes(block: (ByteArray) -> T): T {
        check(!closed) { "Sensitive HTTP body is closed." }
        return block(bytes)
    }

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            bytes.fill(0)
        }
    }

    override fun toString(): String = "<sensitive-http-body bytes=$byteCount>"

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024

        fun fromUtf8AndClear(source: CharArray): SensitiveHttpBody {
            var encoded: ByteBuffer? = null
            var result: ByteArray? = null
            var transferred = false
            try {
                require(source.isNotEmpty() && source.size <= MAX_BYTES) {
                    "Sensitive HTTP body size is invalid."
                }
                encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(source))
                val output = ByteArray(encoded.remaining())
                result = output
                encoded.get(output)
                require(output.size in 1..MAX_BYTES) { "Sensitive HTTP body size is invalid." }
                transferred = true
                return SensitiveHttpBody(output)
            } finally {
                source.fill('\u0000')
                encoded?.takeIf(ByteBuffer::hasArray)?.array()?.fill(0)
                if (!transferred) result?.fill(0)
            }
        }

        fun fromBytesAndClear(source: ByteArray): SensitiveHttpBody {
            try {
                require(source.size in 1..MAX_BYTES) { "Sensitive HTTP body size is invalid." }
                return SensitiveHttpBody(source.copyOf())
            } finally {
                source.fill(0)
            }
        }
    }
}

class ProviderHttpRequestSpec(
    val requestId: String,
    val profile: ProviderConnectionProfile,
    val method: ProviderHttpMethod,
    pathSegments: List<String>,
    queryParameters: List<PublicQueryParameter> = emptyList(),
    publicHeaders: Map<String, String> = emptyMap(),
    val primarySecretHeader: PrimarySecretHeader? = null,
    val body: SensitiveHttpBody? = null,
    val maximumResponseBytes: Long = DEFAULT_MAXIMUM_RESPONSE_BYTES,
) : AutoCloseable {
    private val pathSegments = Collections.unmodifiableList(pathSegments.toList())
    private val queryParameters = Collections.unmodifiableList(queryParameters.toList())
    private val publicHeaders: Map<String, String>

    init {
        try {
            require(requestId.matches(IDENTIFIER_PATTERN)) { "Provider HTTP request id is invalid." }
            require(pathSegments.size <= MAX_PATH_SEGMENTS) { "Provider HTTP path has too many segments." }
            require(pathSegments.all(::isValidPathSegment)) { "Provider HTTP path segment is invalid." }
            require(queryParameters.size <= MAX_QUERY_PARAMETERS) { "Provider HTTP query has too many parameters." }
            require(maximumResponseBytes in 1..MAXIMUM_RESPONSE_BYTES_LIMIT) {
                "Provider HTTP response byte limit is invalid."
            }
            require(method != ProviderHttpMethod.GET || body == null) { "GET requests must not contain a body." }
            require(method != ProviderHttpMethod.POST || body != null) { "POST requests must contain a body." }
            require(primarySecretHeader == null || profile.primarySecretRefId != null) {
                "Primary secret header requires a primary secret reference."
            }

            val profileSensitiveNames = profile.withSensitiveHeaderSecretRefs { it.keys.toSet() }
            val normalized = linkedMapOf<String, String>()
            publicHeaders.forEach { (rawName, value) ->
                require(rawName.matches(HEADER_NAME_PATTERN)) { "Public header name is invalid." }
                val name = rawName.lowercase()
                require(name !in normalized) { "Public header names collide after normalization." }
                require(name !in FORBIDDEN_TRANSPORT_HEADERS) {
                    "Transport-controlled header cannot be overridden."
                }
                require(!isSensitiveName(name) && name !in profileSensitiveNames) {
                    "Sensitive header values must come from the Secret Store."
                }
                require(value.length <= MAX_HEADER_VALUE_LENGTH && value.all(::isVisibleAsciiOrSpace)) {
                    "Public header value is invalid."
                }
                normalized[name] = value
            }
            val secretNames = buildList {
                primarySecretHeader?.let { add(it.name.lowercase()) }
                addAll(profileSensitiveNames)
            }
            require(secretNames.distinct().size == secretNames.size) { "Secret header bindings collide." }
            require(secretNames.none(normalized::containsKey)) { "Public and secret header bindings collide." }
            this.publicHeaders = Collections.unmodifiableMap(normalized)
        } catch (error: Throwable) {
            body?.close()
            throw error
        }
    }

    fun <T> withPathSegments(block: (List<String>) -> T): T = block(pathSegments)

    fun <T> withQueryParameters(block: (List<PublicQueryParameter>) -> T): T = block(queryParameters)

    fun <T> withPublicHeaders(block: (Map<String, String>) -> T): T = block(publicHeaders)

    override fun close() {
        body?.close()
    }

    override fun toString(): String =
        "ProviderHttpRequestSpec(requestId=$requestId, protocol=${profile.protocol.name}, " +
            "method=${method.name}, pathSegments=${pathSegments.size}, queryParameters=${queryParameters.size}, " +
            "publicHeaders=${publicHeaders.size}, primarySecret=${primarySecretHeader != null}, " +
            "bodyBytes=${body?.byteCount ?: 0}, maximumResponseBytes=$maximumResponseBytes)"

    companion object {
        const val DEFAULT_MAXIMUM_RESPONSE_BYTES = 64L * 1024 * 1024
        const val MAXIMUM_RESPONSE_BYTES_LIMIT = 256L * 1024 * 1024
        private const val MAX_PATH_SEGMENTS = 32
        private const val MAX_QUERY_PARAMETERS = 32
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}

enum class ProviderSecretPurpose {
    API_KEY,
    SENSITIVE_HEADER,
}

class SecretMaterialUnavailableException(
    cause: Throwable? = null,
) : IllegalStateException("Requested secret material is unavailable.", cause)

interface ProviderSecretMaterialSource {
    fun <T> withSecret(
        secretRefId: String,
        purpose: ProviderSecretPurpose,
        now: Long,
        block: (ByteArray) -> T,
    ): T
}

enum class ProviderTransportDiagnosticCode {
    REQUEST_STARTED,
    RESPONSE_OPENED,
    REQUEST_FAILED,
    REQUEST_CANCELLED,
}

class ProviderTransportDiagnostic(
    val timestampEpochMillis: Long,
    val code: ProviderTransportDiagnosticCode,
    val protocol: ProviderProtocol,
    val standardErrorCode: StandardErrorCode? = null,
    val httpStatus: Int? = null,
    val elapsedMillis: Long? = null,
    private val connectionId: String,
    private val endpoint: String,
    private val requestId: String,
    private val error: Throwable? = null,
) {
    init {
        require(timestampEpochMillis >= 0)
        require(httpStatus == null || httpStatus in 100..599)
        require(elapsedMillis == null || elapsedMillis >= 0)
    }

    fun <T> withCorrelations(block: (connectionId: String, endpoint: String, requestId: String) -> T): T =
        block(connectionId, endpoint, requestId)

    fun <T> withError(block: (Throwable?) -> T): T = block(error)

    override fun toString(): String =
        "ProviderTransportDiagnostic(code=${code.name}, protocol=${protocol.name}, " +
            "standardErrorCode=${standardErrorCode?.name}, httpStatus=$httpStatus, " +
            "elapsedMillis=$elapsedMillis, errorType=${error?.javaClass?.name})"
}

fun interface ProviderTransportDiagnosticSink {
    fun record(diagnostic: ProviderTransportDiagnostic)
}

object NoOpProviderTransportDiagnosticSink : ProviderTransportDiagnosticSink {
    override fun record(diagnostic: ProviderTransportDiagnostic) = Unit
}

sealed interface ProviderHttpOpenResult {
    data class Opened(val response: ProviderHttpResponseLease) : ProviderHttpOpenResult
    data class Failed(val failure: ProviderCallFailure) : ProviderHttpOpenResult
    data object Cancelled : ProviderHttpOpenResult
    data object AlreadyActive : ProviderHttpOpenResult
}

enum class ProviderTransportCancellationResult {
    CANCELLATION_REQUESTED,
    NOT_ACTIVE,
    ALREADY_REQUESTED,
}

internal val HEADER_NAME_PATTERN = Regex("[!#$%&'*+.^_A-Za-z0-9|~-]{1,128}")
private val QUERY_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._~-]{0,127}")
private const val MAX_QUERY_VALUE_LENGTH = 2_048
private const val MAX_HEADER_VALUE_LENGTH = 4_096
private val FORBIDDEN_TRANSPORT_HEADERS = setOf(
    "content-length",
    "host",
    "connection",
    "transfer-encoding",
)

internal fun isSensitiveName(name: String): Boolean {
    val normalized = name.lowercase()
    return normalized in setOf(
        "authorization",
        "proxy-authorization",
        "x-api-key",
        "api-key",
        "x-goog-api-key",
        "cookie",
        "set-cookie",
    ) || listOf("token", "secret", "credential").any(normalized::contains)
}

private fun isPrimaryCredentialHeader(name: String): Boolean = name.lowercase() in setOf(
    "authorization",
    "x-api-key",
    "api-key",
    "x-goog-api-key",
)

private fun isSensitiveQueryName(name: String): Boolean {
    val normalized = name.lowercase()
    return normalized in setOf("key", "api_key", "api-key", "apikey", "access_key", "access-key") ||
        listOf("token", "secret", "credential", "authorization", "auth").any(normalized::contains)
}

private fun isValidPathSegment(segment: String): Boolean =
    segment.isNotBlank() &&
        segment.length <= 256 &&
        segment != "." &&
        segment != ".." &&
        segment.none { it.isISOControl() || it in "/\\?#" }

private fun isVisibleAsciiOrSpace(character: Char): Boolean = character.code in 0x20..0x7e
