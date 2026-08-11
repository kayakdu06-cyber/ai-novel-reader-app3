package app.zhijuan.core.security

data class StreamingDraftPolicy(
    val checkpointIntervalMillis: Long = DEFAULT_CHECKPOINT_INTERVAL_MILLIS,
    val checkpointPendingBytes: Int = DEFAULT_CHECKPOINT_PENDING_BYTES,
    val maximumPlaintextBytes: Int = ProtectedArtifactFileCodec.MAX_IN_MEMORY_BYTES,
) {
    init {
        require(checkpointIntervalMillis in 100L..60_000L) {
            "Streaming draft checkpoint interval is invalid."
        }
        require(checkpointPendingBytes in 1_024..ProtectedArtifactFileCodec.MAX_IN_MEMORY_BYTES) {
            "Streaming draft checkpoint byte threshold is invalid."
        }
        require(maximumPlaintextBytes in checkpointPendingBytes..ProtectedArtifactFileCodec.MAX_IN_MEMORY_BYTES) {
            "Streaming draft maximum size is invalid."
        }
    }

    companion object {
        const val DEFAULT_CHECKPOINT_INTERVAL_MILLIS = 2_000L
        const val DEFAULT_CHECKPOINT_PENDING_BYTES = 32 * 1_024
    }
}

enum class StreamingDraftWriteOutcome {
    UNCHANGED,
    BUFFERED,
    CHECKPOINTED,
}

data class StreamingDraftWriteResult(
    val outcome: StreamingDraftWriteOutcome,
    val revision: Int,
    val plaintextBytes: Int,
    val persistedBytes: Int,
) {
    init {
        require(revision > 0)
        require(plaintextBytes >= 0 && persistedBytes in 0..plaintextBytes)
    }
}

class StreamingDraftBuffer internal constructor(
    private val store: AndroidProtectedArtifactStore,
    initialDescriptor: ProtectedArtifactDescriptor,
    initialPlaintext: ByteArray,
    private val policy: StreamingDraftPolicy,
) : AutoCloseable {
    private val lock = Any()
    private val plaintext = ClearingByteAccumulator(
        maximumBytes = policy.maximumPlaintextBytes,
        initialValue = initialPlaintext,
    )

    private var currentDescriptor = initialDescriptor
    private var persistedBytes = initialPlaintext.size
    private var lastObservedAt = initialDescriptor.updatedAt
    private var closed = false

    val descriptor: ProtectedArtifactDescriptor
        get() = synchronized(lock) {
            requireOpen()
            currentDescriptor
        }

    val state: StreamingDraftWriteResult
        get() = synchronized(lock) {
            requireOpen()
            result(StreamingDraftWriteOutcome.UNCHANGED)
        }

    fun appendUtf8(
        fragment: String,
        now: Long,
    ): StreamingDraftWriteResult {
        val encoded = fragment.toByteArray(Charsets.UTF_8)
        return appendAndClear(encoded, now)
    }

    fun appendAndClear(
        fragment: ByteArray,
        now: Long,
    ): StreamingDraftWriteResult = try {
        synchronized(lock) {
            requireOpen()
            requireMonotonicTime(now)
            if (fragment.isEmpty()) return@synchronized result(StreamingDraftWriteOutcome.UNCHANGED)
            plaintext.append(fragment)
            if (checkpointDue(now)) {
                checkpointLocked(now)
            } else {
                result(StreamingDraftWriteOutcome.BUFFERED)
            }
        }
    } finally {
        fragment.fill(0)
    }

    fun flush(now: Long): StreamingDraftWriteResult = synchronized(lock) {
        requireOpen()
        requireMonotonicTime(now)
        if (persistedBytes == plaintext.size) {
            result(StreamingDraftWriteOutcome.UNCHANGED)
        } else {
            checkpointLocked(now)
        }
    }

    fun <T> withSnapshotBytes(block: (ByteArray) -> T): T {
        val snapshot = synchronized(lock) {
            requireOpen()
            plaintext.snapshot()
        }
        return try {
            block(snapshot)
        } finally {
            snapshot.fill(0)
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        plaintext.clear()
    }

    private fun checkpointDue(now: Long): Boolean {
        val pendingBytes = plaintext.size - persistedBytes
        return pendingBytes >= policy.checkpointPendingBytes ||
            now - currentDescriptor.updatedAt >= policy.checkpointIntervalMillis
    }

    private fun checkpointLocked(now: Long): StreamingDraftWriteResult {
        val snapshot = plaintext.snapshot()
        val updated = try {
            store.replaceAndClear(
                artifactRefId = currentDescriptor.artifactRefId,
                expectedType = ProtectedArtifactType.STREAM_DRAFT,
                expectedRevision = currentDescriptor.revision,
                plaintext = snapshot,
                now = now,
            )
        } catch (error: StaleProtectedArtifactRevisionException) {
            closed = true
            plaintext.clear()
            throw error
        }
        currentDescriptor = updated.descriptor
        persistedBytes = Math.toIntExact(updated.plaintextBytes)
        return result(StreamingDraftWriteOutcome.CHECKPOINTED)
    }

    private fun requireMonotonicTime(now: Long) {
        require(now >= 0L && now >= lastObservedAt) {
            "Streaming draft time cannot move backwards."
        }
        lastObservedAt = now
    }

    private fun requireOpen() {
        check(!closed) { "Streaming draft buffer is closed." }
    }

    private fun result(outcome: StreamingDraftWriteOutcome) = StreamingDraftWriteResult(
        outcome = outcome,
        revision = currentDescriptor.revision,
        plaintextBytes = plaintext.size,
        persistedBytes = persistedBytes,
    )

    override fun toString(): String = synchronized(lock) {
        "StreamingDraftBuffer(closed=$closed, revision=${currentDescriptor.revision}, " +
            "plaintextBytes=${if (closed) 0 else plaintext.size}, content=redacted)"
    }
}

fun AndroidProtectedArtifactStore.createStreamingDraftBuffer(
    now: Long,
    policy: StreamingDraftPolicy = StreamingDraftPolicy(),
): StreamingDraftBuffer {
    val initial = ByteArray(0)
    val created = createAndClear(ProtectedArtifactType.STREAM_DRAFT, initial, now)
    return StreamingDraftBuffer(
        store = this,
        initialDescriptor = created.descriptor,
        initialPlaintext = ByteArray(0),
        policy = policy,
    )
}

fun AndroidProtectedArtifactStore.resumeStreamingDraftBuffer(
    artifactRefId: String,
    policy: StreamingDraftPolicy = StreamingDraftPolicy(),
): StreamingDraftBuffer = readBytes(
    artifactRefId = artifactRefId,
    expectedType = ProtectedArtifactType.STREAM_DRAFT,
    maximumBytes = policy.maximumPlaintextBytes,
).use { lease ->
    lease.withBytes { persisted ->
        StreamingDraftBuffer(
            store = this,
            initialDescriptor = lease.descriptor,
            initialPlaintext = persisted,
            policy = policy,
        )
    }
}

private class ClearingByteAccumulator(
    private val maximumBytes: Int,
    initialValue: ByteArray,
) {
    private var value = ByteArray(maxOf(MINIMUM_CAPACITY, initialValue.size))

    var size: Int = initialValue.size
        private set

    init {
        require(initialValue.size <= maximumBytes) { "Streaming draft exceeds the allowed size." }
        initialValue.copyInto(value)
    }

    fun append(fragment: ByteArray) {
        val targetSize = Math.addExact(size, fragment.size)
        require(targetSize <= maximumBytes) { "Streaming draft exceeds the allowed size." }
        ensureCapacity(targetSize)
        fragment.copyInto(value, destinationOffset = size)
        size = targetSize
    }

    fun snapshot(): ByteArray = value.copyOf(size)

    fun clear() {
        value.fill(0)
        size = 0
    }

    private fun ensureCapacity(targetSize: Int) {
        if (targetSize <= value.size) return
        var nextSize = value.size
        while (nextSize < targetSize) {
            nextSize = minOf(maximumBytes, Math.multiplyExact(nextSize, 2))
        }
        val replacement = ByteArray(nextSize)
        value.copyInto(replacement, endIndex = size)
        value.fill(0)
        value = replacement
    }

    private companion object {
        const val MINIMUM_CAPACITY = 1_024
    }
}
