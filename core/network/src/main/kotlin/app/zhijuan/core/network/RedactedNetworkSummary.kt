package app.zhijuan.core.network

import okhttp3.HttpUrl
import okhttp3.Request

data class RedactedRequestSummary(
    val method: String,
    val url: String,
    val headerNames: Set<String>,
)

object RedactedNetworkSummary {
    fun from(request: Request, hideHost: Boolean = false): RedactedRequestSummary =
        RedactedRequestSummary(
            method = request.method,
            url = redactUrl(request.url, hideHost),
            headerNames = request.headers.names(),
        )

    fun redactUrl(url: HttpUrl, hideHost: Boolean = false): String {
        val clean = url.newBuilder()
            .username("")
            .password("")
            .query(null)
            .fragment(null)
            .build()
        if (!hideHost) return clean.toString()
        val portSuffix = if (clean.port == HttpUrl.defaultPort(clean.scheme)) "" else ":${clean.port}"
        return "${clean.scheme}://<hidden>$portSuffix${clean.encodedPath}"
    }
}
