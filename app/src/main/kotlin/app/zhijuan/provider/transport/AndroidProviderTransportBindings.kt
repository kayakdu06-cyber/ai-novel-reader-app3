package app.zhijuan.provider.transport

import app.zhijuan.core.diagnostics.DiagnosticCategory
import app.zhijuan.core.diagnostics.DiagnosticCode
import app.zhijuan.core.diagnostics.DiagnosticCorrelationKind
import app.zhijuan.core.diagnostics.DiagnosticEventFactory
import app.zhijuan.core.diagnostics.DiagnosticOperation
import app.zhijuan.core.diagnostics.DiagnosticProtocol
import app.zhijuan.core.diagnostics.DiagnosticSeverity
import app.zhijuan.core.diagnostics.EncryptedDiagnosticStore
import app.zhijuan.core.security.AndroidSecretStore
import app.zhijuan.core.security.SecretPurpose
import app.zhijuan.provider.common.ProviderProtocol

class AndroidProviderSecretMaterialSource(
    private val store: AndroidSecretStore,
) : ProviderSecretMaterialSource {
    override fun <T> withSecret(
        secretRefId: String,
        purpose: ProviderSecretPurpose,
        now: Long,
        block: (ByteArray) -> T,
    ): T {
        val secretPurpose = when (purpose) {
            ProviderSecretPurpose.API_KEY -> SecretPurpose.API_KEY
            ProviderSecretPurpose.SENSITIVE_HEADER -> SecretPurpose.SENSITIVE_HEADER
        }
        val lease = try {
            store.read(secretRefId, secretPurpose, now)
        } catch (error: Exception) {
            throw SecretMaterialUnavailableException(error)
        }
        return lease.use { it.withBytes(block) }
    }
}

class EncryptedProviderTransportDiagnosticSink(
    private val store: EncryptedDiagnosticStore,
    private val factory: DiagnosticEventFactory = DiagnosticEventFactory(),
    private val androidApiLevel: Int? = null,
) : ProviderTransportDiagnosticSink {
    override fun record(diagnostic: ProviderTransportDiagnostic) {
        runCatching {
            val correlations = diagnostic.withCorrelations { connectionId, endpoint, requestId ->
                mapOf(
                    DiagnosticCorrelationKind.CONNECTION to connectionId,
                    DiagnosticCorrelationKind.ENDPOINT to endpoint,
                    DiagnosticCorrelationKind.ATTEMPT to requestId,
                )
            }
            val error = diagnostic.withError { it }
            val event = factory.create(
                timestampEpochMillis = diagnostic.timestampEpochMillis,
                severity = diagnostic.severity(),
                category = DiagnosticCategory.NETWORK,
                code = diagnostic.code.toDiagnosticCode(),
                operation = DiagnosticOperation.GENERATION_REQUEST,
                standardErrorCode = diagnostic.standardErrorCode,
                protocol = diagnostic.protocol.toDiagnosticProtocol(),
                httpStatus = diagnostic.httpStatus,
                retryable = diagnostic.standardErrorCode?.retryDisposition?.isRetryable(),
                elapsedMillis = diagnostic.elapsedMillis,
                androidApiLevel = androidApiLevel,
                correlations = correlations,
                error = error,
            )
            store.append(event, diagnostic.timestampEpochMillis)
        }
    }

    private fun ProviderTransportDiagnostic.severity(): DiagnosticSeverity = when (code) {
        ProviderTransportDiagnosticCode.REQUEST_STARTED,
        ProviderTransportDiagnosticCode.RESPONSE_OPENED,
        -> DiagnosticSeverity.INFO
        ProviderTransportDiagnosticCode.REQUEST_CANCELLED -> DiagnosticSeverity.WARNING
        ProviderTransportDiagnosticCode.REQUEST_FAILED -> DiagnosticSeverity.ERROR
    }

    private fun ProviderTransportDiagnosticCode.toDiagnosticCode(): DiagnosticCode = when (this) {
        ProviderTransportDiagnosticCode.REQUEST_STARTED -> DiagnosticCode.REQUEST_STARTED
        ProviderTransportDiagnosticCode.RESPONSE_OPENED -> DiagnosticCode.RESPONSE_OPENED
        ProviderTransportDiagnosticCode.REQUEST_FAILED -> DiagnosticCode.REQUEST_FAILED
        ProviderTransportDiagnosticCode.REQUEST_CANCELLED -> DiagnosticCode.REQUEST_CANCELLED
    }

    private fun ProviderProtocol.toDiagnosticProtocol(): DiagnosticProtocol = when (this) {
        ProviderProtocol.OPENAI_CHAT_COMPAT -> DiagnosticProtocol.OPENAI_CHAT_COMPATIBLE
    }

    private fun app.zhijuan.core.model.RetryDisposition.isRetryable(): Boolean = when (this) {
        app.zhijuan.core.model.RetryDisposition.WAIT_FOR_CONDITION,
        app.zhijuan.core.model.RetryDisposition.LIMITED_AUTOMATIC_RETRY,
        app.zhijuan.core.model.RetryDisposition.REPAIR_ONCE,
        app.zhijuan.core.model.RetryDisposition.CONTINUE_OUTPUT,
        -> true
        app.zhijuan.core.model.RetryDisposition.USER_CONFIRMATION_REQUIRED,
        app.zhijuan.core.model.RetryDisposition.NEVER_AUTOMATICALLY,
        -> false
    }
}
