package app.zhijuan.core.security

enum class SecretPurpose {
    API_KEY,
    SENSITIVE_HEADER,
}

enum class SecretRecordState {
    ACTIVE,
    REVOKED,
}

data class SecretDescriptor(
    val secretRefId: String,
    val purpose: SecretPurpose,
    val lastFour: String,
    val state: SecretRecordState,
    val keyVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long?,
)

internal data class StoredSecretRecord(
    val descriptor: SecretDescriptor,
    val envelope: EncryptedEnvelope?,
)

class SecretStoreLockedException : IllegalStateException("Secret store is locked.")

class SecretUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class SecretPurposeMismatchException : IllegalArgumentException("Secret purpose does not match the requested use.")

class SecretLease internal constructor(
    private val value: ByteArray,
    private val onClose: (SecretLease) -> Unit,
) : AutoCloseable {
    @Volatile
    private var closed = false

    fun <T> withBytes(block: (ByteArray) -> T): T {
        check(!closed) { "Secret lease is closed." }
        return block(value)
    }

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            value.fill(0)
        }
        onClose(this)
    }
}
