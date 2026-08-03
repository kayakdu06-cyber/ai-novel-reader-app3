package app.zhijuan.provider.common

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Collections

class SensitiveProviderText private constructor(
    private val value: String,
) {
    val characterCount: Int
        get() = value.length

    fun <T> withValue(block: (String) -> T): T = block(value)

    override fun toString(): String = "<sensitive-text characters=$characterCount>"

    companion object {
        const val MAX_CHARACTERS = 2_000_000

        fun from(value: String): SensitiveProviderText {
            require(value.length <= MAX_CHARACTERS) { "Provider text exceeds the allowed size." }
            return SensitiveProviderText(value)
        }
    }
}

class ProviderModelId private constructor(
    private val value: String,
) {
    fun <T> withValue(block: (String) -> T): T = block(value)

    override fun toString(): String = "<model-id>"

    override fun equals(other: Any?): Boolean =
        other is ProviderModelId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    companion object {
        fun from(value: String): ProviderModelId {
            require(value.isNotBlank() && value.length <= 256) { "Provider model id is invalid." }
            require(value.none(Char::isISOControl)) { "Provider model id contains control characters." }
            return ProviderModelId(value)
        }
    }
}

class ProviderRemoteRequestId private constructor(
    private val value: String,
) {
    fun <T> withValue(block: (String) -> T): T = block(value)

    override fun toString(): String = "<remote-request-id>"

    companion object {
        fun from(value: String): ProviderRemoteRequestId {
            require(value.isNotBlank() && value.length <= 512) { "Remote request id is invalid." }
            require(value.none(Char::isISOControl)) { "Remote request id contains control characters." }
            return ProviderRemoteRequestId(value)
        }
    }
}

class ProviderConnectionProfile private constructor(
    val connectionId: String,
    val protocol: ProviderProtocol,
    private val baseUrl: String,
    val primarySecretRefId: String?,
    private val sensitiveHeaderSecretRefs: Map<String, String>,
    val explicitLocalCleartext: Boolean,
) {
    fun <T> withBaseUrl(block: (String) -> T): T = block(baseUrl)

    fun <T> withSensitiveHeaderSecretRefs(block: (Map<String, String>) -> T): T =
        block(sensitiveHeaderSecretRefs)

    override fun toString(): String =
        "ProviderConnectionProfile(protocol=" + protocol.name +
            ", hasPrimarySecret=" + (primarySecretRefId != null) +
            ", sensitiveHeaderCount=" + sensitiveHeaderSecretRefs.size +
            ", explicitLocalCleartext=" + explicitLocalCleartext + ")"

    companion object {
        private val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val SECRET_REF_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        )
        private val HEADER_NAME_PATTERN = Regex("[!#$%&'*+.^_A-Za-z0-9|~-]{1,128}")
        private val ALWAYS_SENSITIVE_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "x-api-key",
            "api-key",
            "x-goog-api-key",
        )

        fun create(
            connectionId: String,
            protocol: ProviderProtocol,
            baseUrl: String,
            primarySecretRefId: String? = null,
            sensitiveHeaderSecretRefs: Map<String, String> = emptyMap(),
            explicitLocalCleartext: Boolean = false,
        ): ProviderConnectionProfile {
            require(connectionId.matches(IDENTIFIER_PATTERN)) { "Connection id is invalid." }
            primarySecretRefId?.let {
                require(it.matches(SECRET_REF_PATTERN)) { "Primary secret reference is invalid." }
            }
            val endpoint = runCatching { URI(baseUrl.trim()) }
                .getOrElse { throw IllegalArgumentException("Provider base URL is invalid.", it) }
            require(endpoint.isAbsolute && endpoint.host != null) { "Provider base URL is invalid." }
            require(endpoint.rawUserInfo == null) { "Provider base URL contains embedded credentials." }
            require(endpoint.rawQuery == null && endpoint.rawFragment == null) {
                "Provider base URL must not contain query or fragment."
            }
            val isHttps = endpoint.scheme.equals("https", ignoreCase = true)
            val isConfirmedLocalHttp = endpoint.scheme.equals("http", ignoreCase = true) &&
                explicitLocalCleartext &&
                isLiteralLocalAddress(endpoint.host)
            require(isHttps || isConfirmedLocalHttp) { "Provider base URL transport is not allowed." }
            val normalizedHeaders = sensitiveHeaderSecretRefs.mapKeys { (name, _) ->
                name.lowercase().also {
                    require(name.matches(HEADER_NAME_PATTERN)) { "Sensitive header name is invalid." }
                }
            }
            require(normalizedHeaders.size == sensitiveHeaderSecretRefs.size) {
                "Sensitive header names collide after normalization."
            }
            normalizedHeaders.forEach { (name, secretRef) ->
                require(name in ALWAYS_SENSITIVE_HEADERS || name.startsWith("x-")) {
                    "Custom sensitive header must use a recognized or x- name."
                }
                require(secretRef.matches(SECRET_REF_PATTERN)) { "Sensitive header secret reference is invalid." }
            }
            val normalizedUrl = baseUrl.trim().trimEnd('/')
            return ProviderConnectionProfile(
                connectionId = connectionId,
                protocol = protocol,
                baseUrl = normalizedUrl,
                primarySecretRefId = primarySecretRefId,
                sensitiveHeaderSecretRefs = Collections.unmodifiableMap(normalizedHeaders.toSortedMap()),
                explicitLocalCleartext = explicitLocalCleartext,
            )
        }

        private fun isLiteralLocalAddress(host: String): Boolean {
            if (host.equals("localhost", ignoreCase = true)) return true
            parseIpv4(host)?.let { bytes ->
                val first = bytes[0]
                val second = bytes[1]
                return first == 127 || first == 10 ||
                    first == 172 && second in 16..31 ||
                    first == 192 && second == 168 ||
                    first == 169 && second == 254
            }
            if (':' !in host) return false
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
            return address is Inet6Address &&
                (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress)
        }

        private fun parseIpv4(host: String): IntArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            return IntArray(4).also { values ->
                parts.forEachIndexed { index, part ->
                    if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
                    values[index] = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
                }
            }
        }
    }
}
