package app.zhijuan.core.security

enum class ProtectedArtifactType {
    STREAM_DRAFT,
    DATABASE_RECOVERY_POINT,
    DIAGNOSTIC_LOG,
}

data class ProtectedArtifactDescriptor(
    val artifactRefId: String,
    val type: ProtectedArtifactType,
    val revision: Int,
    val keyVersion: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ProtectedArtifactTransfer(
    val descriptor: ProtectedArtifactDescriptor,
    val plaintextBytes: Long,
)

class ProtectedArtifactStoreLockedException :
    IllegalStateException("Protected artifact store is locked.")

class ProtectedArtifactUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class ProtectedArtifactTypeMismatchException :
    IllegalArgumentException("Protected artifact type does not match the requested use.")

class StaleProtectedArtifactRevisionException :
    IllegalStateException("Protected artifact was already replaced by a newer revision.")

class ProtectedArtifactLease internal constructor(
    val descriptor: ProtectedArtifactDescriptor,
    private val value: ByteArray,
    private val onClose: (ProtectedArtifactLease) -> Unit,
) : AutoCloseable {
    @Volatile
    private var closed = false

    fun <T> withBytes(block: (ByteArray) -> T): T {
        check(!closed) { "Protected artifact lease is closed." }
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
