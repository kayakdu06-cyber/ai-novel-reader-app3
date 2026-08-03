package app.zhijuan.core.network

import java.net.Inet6Address
import java.net.InetAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class EndpointRejectionReason {
    MALFORMED,
    UNSUPPORTED_SCHEME,
    CLEARTEXT_REMOTE,
    CLEARTEXT_LOCAL_NOT_CONFIRMED,
    EMBEDDED_CREDENTIALS,
    BASE_URL_QUERY,
    BASE_URL_FRAGMENT,
    REQUEST_URL_FRAGMENT,
}

class EndpointRejectedException(
    val reason: EndpointRejectionReason,
    message: String,
) : IllegalArgumentException(message)

data class ValidatedEndpoint(
    val url: HttpUrl,
    val isExplicitLocalCleartext: Boolean,
)

class EndpointPolicy {
    fun validateBaseUrl(
        rawUrl: String,
        allowExplicitLocalCleartext: Boolean = false,
    ): ValidatedEndpoint {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw EndpointRejectedException(EndpointRejectionReason.MALFORMED, "Endpoint URL is invalid.")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw EndpointRejectedException(
                EndpointRejectionReason.EMBEDDED_CREDENTIALS,
                "Endpoint URL must not contain embedded credentials.",
            )
        }
        if (url.query != null) {
            throw EndpointRejectedException(
                EndpointRejectionReason.BASE_URL_QUERY,
                "Base URL must not contain a query.",
            )
        }
        if (url.fragment != null) {
            throw EndpointRejectedException(
                EndpointRejectionReason.BASE_URL_FRAGMENT,
                "Base URL must not contain a fragment.",
            )
        }
        return validateTransport(url, allowExplicitLocalCleartext)
    }

    fun validateRequestUrl(
        url: HttpUrl,
        allowExplicitLocalCleartext: Boolean = false,
    ): ValidatedEndpoint {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw EndpointRejectedException(
                EndpointRejectionReason.EMBEDDED_CREDENTIALS,
                "Request URL must not contain embedded credentials.",
            )
        }
        if (url.fragment != null) {
            throw EndpointRejectedException(
                EndpointRejectionReason.REQUEST_URL_FRAGMENT,
                "Request URL must not contain a fragment.",
            )
        }
        return validateTransport(url, allowExplicitLocalCleartext)
    }

    private fun validateTransport(url: HttpUrl, allowExplicitLocalCleartext: Boolean): ValidatedEndpoint =
        when (url.scheme) {
            HTTPS -> ValidatedEndpoint(url, isExplicitLocalCleartext = false)
            HTTP -> {
                if (!isLiteralLocalAddress(url.host)) {
                    throw EndpointRejectedException(
                        EndpointRejectionReason.CLEARTEXT_REMOTE,
                        "Remote endpoints must use HTTPS.",
                    )
                }
                if (!allowExplicitLocalCleartext) {
                    throw EndpointRejectedException(
                        EndpointRejectionReason.CLEARTEXT_LOCAL_NOT_CONFIRMED,
                        "Local cleartext endpoint requires explicit confirmation and a platform allowlist.",
                    )
                }
                ValidatedEndpoint(url, isExplicitLocalCleartext = true)
            }
            else -> throw EndpointRejectedException(
                EndpointRejectionReason.UNSUPPORTED_SCHEME,
                "Only HTTPS remote endpoints are supported.",
            )
        }

    private fun isLiteralLocalAddress(host: String): Boolean {
        if (host.equals(LOCALHOST, ignoreCase = true)) return true
        parseIpv4(host)?.let { bytes ->
            val first = bytes[0]
            val second = bytes[1]
            return first == 127 || first == 10 ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                (first == 169 && second == 254)
        }
        if (':' in host) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull()
            return address is Inet6Address &&
                (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress)
        }
        return false
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != IPV4_PARTS) return null
        val values = IntArray(IPV4_PARTS)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            values[index] = value
        }
        return values
    }

    private companion object {
        const val HTTPS = "https"
        const val HTTP = "http"
        const val LOCALHOST = "localhost"
        const val IPV4_PARTS = 4
    }
}
