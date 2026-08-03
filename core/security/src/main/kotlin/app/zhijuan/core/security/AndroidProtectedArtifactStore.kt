package app.zhijuan.core.security

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class AndroidProtectedArtifactStore(
    context: Context,
    initiallyUnlocked: Boolean = true,
) {
    private val directory = File(context.noBackupFilesDir, ARTIFACT_DIRECTORY)
    private val activeLeases = mutableMapOf<ProtectedArtifactLease, String>()

    @Volatile
    private var unlocked = initiallyUnlocked

    fun createAndClear(
        type: ProtectedArtifactType,
        plaintext: ByteArray,
        now: Long,
    ): ProtectedArtifactTransfer = try {
        create(type, ByteArrayInputStream(plaintext), now)
    } finally {
        plaintext.fill(0)
    }

    fun create(
        type: ProtectedArtifactType,
        plaintext: InputStream,
        now: Long,
    ): ProtectedArtifactTransfer = synchronized(STORE_LOCK) {
        requireUnlocked()
        require(now >= 0) { "Protected artifact timestamp is invalid." }
        ensureDirectory()
        repeat(MAX_ID_ATTEMPTS) {
            val artifactRefId = UUID.randomUUID().toString()
            val file = artifactFile(artifactRefId)
            if (file.baseFile.exists()) return@repeat
            val cipher = aeadFor(artifactRefId)
            val descriptor = ProtectedArtifactDescriptor(
                artifactRefId = artifactRefId,
                type = type,
                revision = 1,
                keyVersion = KEY_VERSION,
                createdAt = now,
                updatedAt = now,
            )
            try {
                val summary = write(file, descriptor, plaintext, cipher)
                return@synchronized ProtectedArtifactTransfer(descriptor, summary.plaintextBytes)
            } catch (error: Exception) {
                file.delete()
                cipher.deleteKey()
                throw protectFailure("Unable to create protected artifact.", error)
            }
        }
        error("Unable to allocate a unique protected artifact reference.")
    }

    fun replaceAndClear(
        artifactRefId: String,
        expectedType: ProtectedArtifactType,
        expectedRevision: Int,
        plaintext: ByteArray,
        now: Long,
    ): ProtectedArtifactTransfer = try {
        replace(
            artifactRefId,
            expectedType,
            expectedRevision,
            ByteArrayInputStream(plaintext),
            now,
        )
    } finally {
        plaintext.fill(0)
    }

    fun replace(
        artifactRefId: String,
        expectedType: ProtectedArtifactType,
        expectedRevision: Int,
        plaintext: InputStream,
        now: Long,
    ): ProtectedArtifactTransfer = synchronized(STORE_LOCK) {
        requireUnlocked()
        require(now >= 0) { "Protected artifact timestamp is invalid." }
        val file = artifactFile(artifactRefId)
        val cipher = aeadFor(artifactRefId)
        val current = readDescriptor(file, artifactRefId, cipher)
        requireType(current, expectedType)
        if (current.revision != expectedRevision) throw StaleProtectedArtifactRevisionException()
        val updated = current.copy(
            revision = Math.addExact(current.revision, 1),
            updatedAt = maxOf(current.updatedAt, now),
        )
        try {
            val summary = write(file, updated, plaintext, cipher)
            closeLeases(artifactRefId)
            ProtectedArtifactTransfer(updated, summary.plaintextBytes)
        } catch (error: Exception) {
            throw protectFailure("Unable to replace protected artifact.", error)
        }
    }

    fun readTo(
        artifactRefId: String,
        expectedType: ProtectedArtifactType,
        plaintextOutput: OutputStream,
    ): ProtectedArtifactTransfer = synchronized(STORE_LOCK) {
        requireUnlocked()
        val file = artifactFile(artifactRefId)
        val cipher = aeadFor(artifactRefId)
        requireType(readDescriptor(file, artifactRefId, cipher), expectedType)
        val summary = try {
            file.openRead().use { input ->
                ProtectedArtifactFileCodec.read(input, plaintextOutput, cipher)
            }
        } catch (error: ProtectedArtifactUnavailableException) {
            throw error
        } catch (error: Exception) {
            throw ProtectedArtifactUnavailableException(
                "Protected artifact key is missing, invalidated, or the file was changed.",
                error,
            )
        }
        verifyIdentityAndType(summary.header.descriptor, artifactRefId, expectedType)
        ProtectedArtifactTransfer(summary.header.descriptor, summary.plaintextBytes)
    }

    fun readBytes(
        artifactRefId: String,
        expectedType: ProtectedArtifactType,
        maximumBytes: Int = ProtectedArtifactFileCodec.MAX_IN_MEMORY_BYTES,
    ): ProtectedArtifactLease = synchronized(STORE_LOCK) {
        requireUnlocked()
        require(maximumBytes in 0..ProtectedArtifactFileCodec.MAX_IN_MEMORY_BYTES) {
            "In-memory protected artifact limit is invalid."
        }
        val output = ClearingBoundedOutputStream(maximumBytes)
        try {
            val transfer = readTo(artifactRefId, expectedType, output)
            val plaintext = output.detach()
            ProtectedArtifactLease(transfer.descriptor, plaintext) { lease ->
                synchronized(STORE_LOCK) { activeLeases.remove(lease) }
            }.also { lease -> activeLeases[lease] = artifactRefId }
        } catch (error: Exception) {
            output.clear()
            throw error
        }
    }

    fun verify(
        artifactRefId: String,
        expectedType: ProtectedArtifactType,
    ): ProtectedArtifactTransfer = readTo(artifactRefId, expectedType, DiscardingOutputStream)

    fun descriptor(artifactRefId: String): ProtectedArtifactDescriptor = synchronized(STORE_LOCK) {
        requireUnlocked()
        readDescriptor(artifactFile(artifactRefId), artifactRefId, aeadFor(artifactRefId))
    }

    fun listDescriptors(): List<ProtectedArtifactDescriptor> = synchronized(STORE_LOCK) {
        requireUnlocked()
        artifactReferenceIds()
            .map { artifactRefId ->
                readDescriptor(artifactFile(artifactRefId), artifactRefId, aeadFor(artifactRefId))
            }
            .sortedWith(compareBy(ProtectedArtifactDescriptor::createdAt, ProtectedArtifactDescriptor::artifactRefId))
    }

    fun listArtifactReferenceIds(): List<String> = synchronized(STORE_LOCK) {
        requireUnlocked()
        artifactReferenceIds()
    }

    private fun artifactReferenceIds(): List<String> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles()
            ?.asSequence()
            ?.filter(File::isFile)
            ?.mapNotNull { file ->
                ARTIFACT_FILE_PATTERN.matchEntire(file.name)?.groupValues?.get(1)
            }
            ?.distinct()
            ?.sorted()
            ?.toList()
            .orEmpty()
    }

    fun delete(artifactRefId: String) = synchronized(STORE_LOCK) {
        requireUnlocked()
        val file = artifactFile(artifactRefId)
        closeLeases(artifactRefId)
        aeadFor(artifactRefId).deleteKey()
        file.delete()
    }

    fun lock() = synchronized(STORE_LOCK) {
        unlocked = false
        activeLeases.keys.toList().forEach(ProtectedArtifactLease::close)
    }

    fun unlockAfterAuthentication() = synchronized(STORE_LOCK) {
        unlocked = true
    }

    private fun write(
        file: AtomicFile,
        descriptor: ProtectedArtifactDescriptor,
        plaintext: InputStream,
        cipher: KeystoreArtifactAead,
    ): ProtectedArtifactFileSummary {
        val stream = file.startWrite()
        try {
            val summary = ProtectedArtifactFileCodec.write(
                ProtectedArtifactHeader(descriptor),
                plaintext,
                stream,
                cipher,
            )
            stream.fd.sync()
            file.finishWrite(stream)
            return summary
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun readDescriptor(
        file: AtomicFile,
        expectedArtifactRefId: String,
        cipher: KeystoreArtifactAead,
    ): ProtectedArtifactDescriptor {
        val descriptor = try {
            file.openRead().use { input -> ProtectedArtifactFileCodec.readHeader(input, cipher).descriptor }
        } catch (error: Exception) {
            throw ProtectedArtifactUnavailableException(
                "Protected artifact key is missing, invalidated, or its header was changed.",
                error,
            )
        }
        if (descriptor.artifactRefId != expectedArtifactRefId) {
            throw ProtectedArtifactUnavailableException(
                "Protected artifact identity does not match its reference.",
            )
        }
        return descriptor
    }

    private fun verifyIdentityAndType(
        descriptor: ProtectedArtifactDescriptor,
        expectedArtifactRefId: String,
        expectedType: ProtectedArtifactType,
    ) {
        if (descriptor.artifactRefId != expectedArtifactRefId) {
            throw ProtectedArtifactUnavailableException(
                "Protected artifact identity does not match its reference.",
            )
        }
        requireType(descriptor, expectedType)
    }

    private fun requireType(
        descriptor: ProtectedArtifactDescriptor,
        expectedType: ProtectedArtifactType,
    ) {
        if (descriptor.type != expectedType) throw ProtectedArtifactTypeMismatchException()
    }

    private fun closeLeases(artifactRefId: String) {
        activeLeases.filterValues { it == artifactRefId }.keys.toList()
            .forEach(ProtectedArtifactLease::close)
    }

    private fun requireUnlocked() {
        if (!unlocked) throw ProtectedArtifactStoreLockedException()
    }

    private fun artifactFile(artifactRefId: String): AtomicFile {
        require(artifactRefId.matches(ProtectedArtifactFileCodec.ARTIFACT_REF_PATTERN)) {
            "Protected artifact reference format is invalid."
        }
        return AtomicFile(File(directory, "$FILE_PREFIX$artifactRefId$FILE_SUFFIX"))
    }

    private fun ensureDirectory() {
        check(directory.exists() || directory.mkdirs()) {
            "Unable to create protected artifact directory."
        }
    }

    private fun aeadFor(artifactRefId: String): KeystoreArtifactAead =
        KeystoreArtifactAead(AndroidKeystoreAesGcm(keyAliasFor(artifactRefId)))

    private fun protectFailure(message: String, error: Exception): Exception =
        when (error) {
            is ProtectedArtifactUnavailableException,
            is ProtectedArtifactTypeMismatchException,
            is StaleProtectedArtifactRevisionException -> error
            else -> ProtectedArtifactUnavailableException(message, error)
        }

    companion object {
        private const val ARTIFACT_DIRECTORY = "content/protected-artifacts"
        private const val FILE_PREFIX = "artifact-"
        private const val FILE_SUFFIX = ".zjaf"
        private const val KEY_VERSION = 1
        private const val MAX_ID_ATTEMPTS = 8
        private val ARTIFACT_FILE_PATTERN = Regex(
            "artifact-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})" +
                "\\.zjaf(?:\\.bak|\\.new)?",
        )
        private val STORE_LOCK = Any()

        internal fun keyAliasFor(artifactRefId: String): String =
            "app.zhijuan.reader.protected-artifact.$artifactRefId.v$KEY_VERSION"
    }
}

private class KeystoreArtifactAead(
    private val cipher: AndroidKeystoreAesGcm,
) : ArtifactAead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedEnvelope =
        cipher.encrypt(plaintext, associatedData)

    override fun decrypt(envelope: EncryptedEnvelope, associatedData: ByteArray): ByteArray =
        cipher.decrypt(envelope, associatedData)

    fun deleteKey() = cipher.deleteKey()
}

private class ClearingBoundedOutputStream(
    private val maximumBytes: Int,
) : ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024)) {
    private var detached = false

    override fun write(value: Int) {
        ensureCapacityFor(1)
        super.write(value)
    }

    override fun write(value: ByteArray, offset: Int, length: Int) {
        ensureCapacityFor(length)
        super.write(value, offset, length)
    }

    fun detach(): ByteArray {
        check(!detached)
        val result = toByteArray()
        clear()
        detached = true
        return result
    }

    fun clear() {
        buf.fill(0)
        reset()
    }

    private fun ensureCapacityFor(incomingBytes: Int) {
        require(count.toLong() + incomingBytes.toLong() <= maximumBytes.toLong()) {
            "Protected artifact exceeds the in-memory read limit."
        }
    }
}

private object DiscardingOutputStream : OutputStream() {
    override fun write(value: Int) = Unit
    override fun write(value: ByteArray, offset: Int, length: Int) = Unit
}
