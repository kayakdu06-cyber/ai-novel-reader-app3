package app.zhijuan.core.network

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLException
import okhttp3.HttpUrl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import app.zhijuan.core.model.StandardErrorCode

object SecureOkHttpClientFactory {
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
}

enum class RedirectRejectionReason {
    MISSING_OR_INVALID_LOCATION,
    NON_IDEMPOTENT_REQUEST,
    CLEARTEXT_OR_DOWNGRADE,
    CROSS_ORIGIN,
    LOOP,
    TOO_MANY_REDIRECTS,
}

class RedirectRejectedException(
    val reason: RedirectRejectionReason,
    message: String,
) : IOException(message)

class SecureRedirectExecutor(
    private val client: OkHttpClient,
    private val endpointPolicy: EndpointPolicy = EndpointPolicy(),
    private val maximumRedirects: Int = DEFAULT_MAXIMUM_REDIRECTS,
) {
    init {
        require(!client.followRedirects) { "OkHttp automatic redirects must be disabled." }
        require(!client.followSslRedirects) { "OkHttp automatic SSL redirects must be disabled." }
        require(!client.retryOnConnectionFailure) { "Automatic connection retries must be disabled." }
        require(maximumRedirects >= 0)
    }

    fun execute(
        request: Request,
        allowExplicitLocalCleartext: Boolean = false,
        onCallCreated: (Call) -> Unit = {},
    ): Response {
        endpointPolicy.validateRequestUrl(request.url, allowExplicitLocalCleartext)
        val visited = linkedSetOf(request.url.toString())
        var currentRequest = request
        var followedRedirects = 0
        while (true) {
            val call = client.newCall(currentRequest)
            onCallCreated(call)
            val response = call.execute()
            if (response.code !in REDIRECT_STATUS_CODES) return response
            val location = response.header(LOCATION)
            val target = location?.let(currentRequest.url::resolve)
            if (target == null) {
                response.close()
                throw RedirectRejectedException(
                    RedirectRejectionReason.MISSING_OR_INVALID_LOCATION,
                    "Redirect location is missing or invalid.",
                )
            }
            if (!isIdempotent(currentRequest.method)) {
                response.close()
                throw RedirectRejectedException(
                    RedirectRejectionReason.NON_IDEMPOTENT_REQUEST,
                    "Redirects are not followed for requests that may create paid work.",
                )
            }
            if (target.scheme != HTTPS) {
                response.close()
                throw RedirectRejectedException(
                    RedirectRejectionReason.CLEARTEXT_OR_DOWNGRADE,
                    "Redirect to a cleartext endpoint was rejected.",
                )
            }
            try {
                endpointPolicy.validateRequestUrl(target)
            } catch (error: EndpointRejectedException) {
                response.close()
                throw RedirectRejectedException(
                    RedirectRejectionReason.MISSING_OR_INVALID_LOCATION,
                    "Redirect target is not a valid secure endpoint.",
                )
            }
            if (!sameOrigin(currentRequest.url, target)) {
                response.close()
                throw RedirectRejectedException(
                    RedirectRejectionReason.CROSS_ORIGIN,
                    "Cross-origin redirect was rejected; save and retest the final endpoint instead.",
                )
            }
            if (!visited.add(target.toString())) {
                response.close()
                throw RedirectRejectedException(RedirectRejectionReason.LOOP, "Redirect loop was rejected.")
            }
            if (followedRedirects >= maximumRedirects) {
                response.close()
                throw RedirectRejectedException(
                    RedirectRejectionReason.TOO_MANY_REDIRECTS,
                    "Redirect limit was exceeded.",
                )
            }
            response.close()
            followedRedirects += 1
            currentRequest = currentRequest.newBuilder().url(target).build()
        }
    }

    private fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port

    private fun isIdempotent(method: String): Boolean = method == GET || method == HEAD

    private companion object {
        const val HTTPS = "https"
        const val LOCATION = "Location"
        const val GET = "GET"
        const val HEAD = "HEAD"
        const val DEFAULT_MAXIMUM_REDIRECTS = 3
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }
}

object NetworkFailureClassifier {
    fun classify(error: IOException): StandardErrorCode = when {
        error.containsThrowable<SSLException>() -> StandardErrorCode.TLS_FAILED
        error.containsThrowable<UnknownHostException>() -> StandardErrorCode.DNS_FAILED
        error.containsThrowable<ProtocolException>() -> StandardErrorCode.PROTOCOL_MISMATCH
        error.containsThrowable<ConnectException>() ||
            error.containsThrowable<NoRouteToHostException>() ||
            error.containsThrowable<SocketTimeoutException>() ||
            error.containsThrowable<SocketException>() -> StandardErrorCode.NETWORK_OFFLINE
        else -> StandardErrorCode.UNKNOWN_RESULT
    }

    private inline fun <reified T : Throwable> Throwable.containsThrowable(): Boolean {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        pending.add(this)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            if (current is T) return true
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
        return false
    }
}
