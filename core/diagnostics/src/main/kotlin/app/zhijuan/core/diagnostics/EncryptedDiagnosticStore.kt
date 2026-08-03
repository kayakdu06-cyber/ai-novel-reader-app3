package app.zhijuan.core.diagnostics

import android.content.Context
import android.util.AtomicFile
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import java.io.File
import java.nio.charset.StandardCharsets

enum class DiagnosticWriteOutcome {
    APPENDED,
    FAILED_CLOSED,
}

sealed interface DiagnosticSnapshotResult {
    data class Available(val events: List<DiagnosticEvent>) : DiagnosticSnapshotResult
    data object Unavailable : DiagnosticSnapshotResult
}

class EncryptedDiagnosticStore(
    context: Context,
    private val artifactStore: AndroidProtectedArtifactStore = AndroidProtectedArtifactStore(context),
    private val maximumEvents: Int = DEFAULT_MAXIMUM_EVENTS,
    private val maximumEncodedBytes: Int = DEFAULT_MAXIMUM_ENCODED_BYTES,
) {
    private val indexFile = AtomicFile(File(context.noBackupFilesDir, INDEX_RELATIVE_PATH))

    init {
        require(maximumEvents in 1..MAXIMUM_EVENTS_LIMIT)
        require(maximumEncodedBytes in MINIMUM_ENCODED_BYTES..DiagnosticEventCodec.MAX_ENCODED_BYTES)
    }

    fun append(event: DiagnosticEvent, now: Long): DiagnosticWriteOutcome = synchronized(STORE_LOCK) {
        try {
            require(now >= 0) { "Diagnostic write timestamp is invalid." }
            val artifactRefId = readArtifactRef()
            if (artifactRefId == null) {
                val encoded = encodeBounded(listOf(event))
                val created = artifactStore.createAndClear(
                    ProtectedArtifactType.DIAGNOSTIC_LOG,
                    encoded,
                    now,
                )
                writeArtifactRef(created.descriptor.artifactRefId)
            } else {
                val lease = artifactStore.readBytes(
                    artifactRefId,
                    ProtectedArtifactType.DIAGNOSTIC_LOG,
                    maximumBytes = maximumEncodedBytes,
                )
                val descriptor = lease.descriptor
                val current = lease.use { value ->
                    value.withBytes(DiagnosticEventCodec::decode)
                }
                val encoded = encodeBounded(current + event)
                artifactStore.replaceAndClear(
                    artifactRefId = artifactRefId,
                    expectedType = ProtectedArtifactType.DIAGNOSTIC_LOG,
                    expectedRevision = descriptor.revision,
                    plaintext = encoded,
                    now = now,
                )
            }
            DiagnosticWriteOutcome.APPENDED
        } catch (_: Exception) {
            DiagnosticWriteOutcome.FAILED_CLOSED
        }
    }

    fun snapshot(): DiagnosticSnapshotResult = synchronized(STORE_LOCK) {
        try {
            val artifactRefId = readArtifactRef()
                ?: return@synchronized DiagnosticSnapshotResult.Available(emptyList())
            val lease = artifactStore.readBytes(
                artifactRefId,
                ProtectedArtifactType.DIAGNOSTIC_LOG,
                maximumBytes = maximumEncodedBytes,
            )
            DiagnosticSnapshotResult.Available(
                lease.use { value -> value.withBytes(DiagnosticEventCodec::decode) },
            )
        } catch (_: Exception) {
            DiagnosticSnapshotResult.Unavailable
        }
    }

    fun clear(): Boolean = synchronized(STORE_LOCK) {
        try {
            readArtifactRef()?.let(artifactStore::delete)
            indexFile.delete()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun encodeBounded(input: List<DiagnosticEvent>): ByteArray {
        val events = input.takeLast(maximumEvents).toMutableList()
        while (events.isNotEmpty()) {
            val encoded = runCatching { DiagnosticEventCodec.encode(events) }.getOrNull()
            if (encoded != null && encoded.size <= maximumEncodedBytes) return encoded
            events.removeAt(0)
        }
        error("A single structured diagnostic event exceeds the configured limit.")
    }

    private fun readArtifactRef(): String? {
        if (!indexFile.baseFile.exists()) return null
        val reference = indexFile.openRead().use { input ->
            input.readBytes().toString(StandardCharsets.US_ASCII)
        }
        require(reference.matches(ARTIFACT_REF_PATTERN)) { "Diagnostic index is invalid." }
        return reference
    }

    private fun writeArtifactRef(artifactRefId: String) {
        require(artifactRefId.matches(ARTIFACT_REF_PATTERN))
        indexFile.baseFile.parentFile?.let { directory ->
            check(directory.exists() || directory.mkdirs()) {
                "Unable to create diagnostic index directory."
            }
        }
        val output = indexFile.startWrite()
        try {
            output.write(artifactRefId.toByteArray(StandardCharsets.US_ASCII))
            output.fd.sync()
            indexFile.finishWrite(output)
        } catch (error: Exception) {
            indexFile.failWrite(output)
            throw error
        }
    }

    companion object {
        const val DEFAULT_MAXIMUM_EVENTS = 512
        const val DEFAULT_MAXIMUM_ENCODED_BYTES = 512 * 1024
        private const val MAXIMUM_EVENTS_LIMIT = 2_048
        private const val MINIMUM_ENCODED_BYTES = 4 * 1024
        internal const val INDEX_RELATIVE_PATH = "diagnostics/current-log.ref"
        private val ARTIFACT_REF_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        )
        private val STORE_LOCK = Any()
    }
}
