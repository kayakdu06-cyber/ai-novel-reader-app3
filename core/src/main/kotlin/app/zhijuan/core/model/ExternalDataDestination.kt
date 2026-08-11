package app.zhijuan.core.model

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Versioned evidence for the remote origin that may receive story data.
 *
 * The request path is intentionally excluded: the user confirms the data recipient (origin), while
 * protocolId distinguishes the wire contract used at that origin. This object is evidence only; it
 * is never a network permit by itself.
 */
class ExternalDataDestinationBindingV1 private constructor(
    val normalizedDestination: String,
    val protocolId: String,
    val disclosureVersion: Int,
    val bindingHash: String,
) {
    fun matches(
        normalizedDestination: String,
        protocolId: String,
        disclosureVersion: Int,
        bindingHash: String,
    ): Boolean =
        this.normalizedDestination == normalizedDestination &&
            this.protocolId == protocolId &&
            this.disclosureVersion == disclosureVersion &&
            constantTimeEquals(this.bindingHash, bindingHash)

    override fun toString(): String =
        "ExternalDataDestinationBindingV1(disclosureVersion=$disclosureVersion, redacted=true)"

    companion object {
        const val CURRENT_DISCLOSURE_VERSION = 1
        const val POLICY_VERSION = "zhijuan.external-data-destination-binding.v1"

        private val PROTOCOL_ID_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")
        private val HEX_PATTERN = Regex("[0-9a-f]{64}")

        fun create(
            baseUrl: String,
            protocolId: String,
            disclosureVersion: Int = CURRENT_DISCLOSURE_VERSION,
        ): ExternalDataDestinationBindingV1 {
            require(disclosureVersion == CURRENT_DISCLOSURE_VERSION) {
                "Data disclosure version is not current."
            }
            require(protocolId.matches(PROTOCOL_ID_PATTERN)) { "Provider protocol id is invalid." }
            val destination = normalizeDestination(baseUrl)
            val canonical = buildString {
                append(POLICY_VERSION)
                append('\n')
                append(disclosureVersion)
                append('\n')
                append(destination)
                append('\n')
                append(protocolId)
            }
            return ExternalDataDestinationBindingV1(
                normalizedDestination = destination,
                protocolId = protocolId,
                disclosureVersion = disclosureVersion,
                bindingHash = sha256(canonical),
            )
        }

        fun requireValidStoredHash(value: String): String {
            require(value.matches(HEX_PATTERN)) { "Data disclosure binding hash is invalid." }
            return value
        }

        private fun normalizeDestination(baseUrl: String): String {
            val endpoint = runCatching { URI(baseUrl.trim()) }
                .getOrElse { throw IllegalArgumentException("Remote destination is invalid.", it) }
            val scheme = endpoint.scheme?.lowercase(Locale.ROOT)
            require(scheme == "https" || scheme == "http") { "Remote destination scheme is invalid." }
            require(endpoint.isAbsolute && endpoint.host != null) { "Remote destination is invalid." }
            require(endpoint.rawUserInfo == null) { "Remote destination contains embedded credentials." }
            require(endpoint.rawQuery == null && endpoint.rawFragment == null) {
                "Remote destination must not contain query or fragment."
            }
            require(endpoint.port == -1 || endpoint.port in 1..65535) {
                "Remote destination port is invalid."
            }
            val host = endpoint.host
                .removePrefix("[")
                .removeSuffix("]")
                .lowercase(Locale.ROOT)
                .let { value -> if (':' in value) value else value.removeSuffix(".") }
            require(host.isNotBlank()) { "Remote destination host is invalid." }
            val renderedHost = if (':' in host) "[$host]" else host
            val effectivePort = when {
                endpoint.port != -1 -> endpoint.port
                scheme == "https" -> 443
                else -> 80
            }
            return "$scheme://$renderedHost:$effectivePort"
        }

        private fun sha256(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    append(HEX_DIGITS[unsigned ushr 4])
                    append(HEX_DIGITS[unsigned and 0x0f])
                }
            }.also { digest.fill(0) }
        }

        fun constantTimeEquals(expected: String, actual: String): Boolean {
            if (expected.length != actual.length) return false
            var difference = 0
            for (index in expected.indices) {
                difference = difference or (expected[index].code xor actual[index].code)
            }
            return difference == 0
        }

        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
