package app.zhijuan.core.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

internal interface ArtifactAead {
    fun encrypt(plaintext: ByteArray, associatedData: ByteArray): EncryptedEnvelope
    fun decrypt(envelope: EncryptedEnvelope, associatedData: ByteArray): ByteArray
}

internal data class ProtectedArtifactHeader(
    val descriptor: ProtectedArtifactDescriptor,
    val chunkBytes: Int = ProtectedArtifactFileCodec.DEFAULT_CHUNK_BYTES,
)

internal data class ProtectedArtifactFileSummary(
    val header: ProtectedArtifactHeader,
    val plaintextBytes: Long,
)

internal object ProtectedArtifactFileCodec {
    const val DEFAULT_CHUNK_BYTES = 64 * 1024
    const val MAX_IN_MEMORY_BYTES = 4 * 1024 * 1024
    const val MAX_ARTIFACT_BYTES = 16L * 1024L * 1024L * 1024L

    private const val MAGIC = 0x5A4A4146 // ZJAF
    private const val FORMAT_VERSION = 1
    private const val ALGORITHM_AES_256_GCM = 1
    private const val MIN_CHUNK_BYTES = 4 * 1024
    private const val MAX_CHUNK_BYTES = 256 * 1024
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BYTES = 16
    private const val HEADER_BYTES = 60
    private const val TRAILER_PLAINTEXT_BYTES = 44
    private const val RECORD_CHUNK = 1
    private const val RECORD_TRAILER = 127
    private val HEADER_DOMAIN = "app.zhijuan.reader.protected-artifact.header.v1"
        .toByteArray(StandardCharsets.UTF_8)
    private val CHUNK_DOMAIN = "app.zhijuan.reader.protected-artifact.chunk.v1"
        .toByteArray(StandardCharsets.UTF_8)
    private val TRAILER_DOMAIN = "app.zhijuan.reader.protected-artifact.trailer.v1"
        .toByteArray(StandardCharsets.UTF_8)

    fun write(
        header: ProtectedArtifactHeader,
        plaintext: InputStream,
        encryptedOutput: OutputStream,
        cipher: ArtifactAead,
    ): ProtectedArtifactFileSummary {
        validateHeader(header)
        val headerBytes = encodeHeader(header)
        val headerHash = sha256(headerBytes)
        val output = DataOutputStream(encryptedOutput)
        val headerAuthentication = cipher.encrypt(ByteArray(0), aad(HEADER_DOMAIN, headerBytes))
        output.write(headerBytes)
        writeEnvelope(output, headerAuthentication, GCM_TAG_BYTES)

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(header.chunkBytes)
        var chunkIndex = 0
        var totalBytes = 0L
        try {
            while (true) {
                val count = readChunk(plaintext, buffer)
                if (count == 0) break
                totalBytes = Math.addExact(totalBytes, count.toLong())
                require(totalBytes <= MAX_ARTIFACT_BYTES) { "Protected artifact is too large." }
                digest.update(buffer, 0, count)
                val chunk = buffer.copyOf(count)
                val envelope = try {
                    cipher.encrypt(chunk, chunkAad(headerHash, chunkIndex, count))
                } finally {
                    chunk.fill(0)
                }
                output.writeByte(RECORD_CHUNK)
                output.writeInt(chunkIndex)
                output.writeInt(count)
                writeEnvelope(output, envelope, count + GCM_TAG_BYTES)
                chunkIndex = Math.addExact(chunkIndex, 1)
            }

            val trailerPlaintext = encodeTrailer(chunkIndex, totalBytes, digest.digest())
            val trailerEnvelope = try {
                cipher.encrypt(trailerPlaintext, aad(TRAILER_DOMAIN, headerHash))
            } finally {
                trailerPlaintext.fill(0)
            }
            output.writeByte(RECORD_TRAILER)
            writeEnvelope(output, trailerEnvelope, TRAILER_PLAINTEXT_BYTES + GCM_TAG_BYTES)
            output.flush()
            return ProtectedArtifactFileSummary(header, totalBytes)
        } finally {
            buffer.fill(0)
            headerBytes.fill(0)
            headerHash.fill(0)
        }
    }

    fun readHeader(
        encryptedInput: InputStream,
        cipher: ArtifactAead,
    ): ProtectedArtifactHeader {
        val input = DataInputStream(encryptedInput)
        val headerBytes = ByteArray(HEADER_BYTES)
        try {
            input.readFully(headerBytes)
            val header = decodeHeader(headerBytes)
            val authentication = readEnvelope(input, GCM_TAG_BYTES)
            val authenticated = cipher.decrypt(authentication, aad(HEADER_DOMAIN, headerBytes))
            try {
                require(authenticated.isEmpty()) { "Protected artifact header authentication is invalid." }
            } finally {
                authenticated.fill(0)
            }
            return header
        } finally {
            headerBytes.fill(0)
        }
    }

    fun read(
        encryptedInput: InputStream,
        plaintextOutput: OutputStream,
        cipher: ArtifactAead,
    ): ProtectedArtifactFileSummary {
        val input = DataInputStream(encryptedInput)
        val headerBytes = ByteArray(HEADER_BYTES)
        input.readFully(headerBytes)
        val header = decodeHeader(headerBytes)
        val headerHash = sha256(headerBytes)
        val digest = MessageDigest.getInstance("SHA-256")
        var expectedChunkIndex = 0
        var totalBytes = 0L
        var sawShortChunk = false
        try {
            val authentication = readEnvelope(input, GCM_TAG_BYTES)
            val authenticated = cipher.decrypt(authentication, aad(HEADER_DOMAIN, headerBytes))
            try {
                require(authenticated.isEmpty()) { "Protected artifact header authentication is invalid." }
            } finally {
                authenticated.fill(0)
            }

            while (true) {
                val recordType = try {
                    input.readUnsignedByte()
                } catch (error: EOFException) {
                    throw IllegalArgumentException("Protected artifact is truncated before its trailer.", error)
                }
                when (recordType) {
                    RECORD_CHUNK -> {
                        require(!sawShortChunk) { "Protected artifact has data after a short final chunk." }
                        val chunkIndex = input.readInt()
                        require(chunkIndex == expectedChunkIndex) { "Protected artifact chunk order is invalid." }
                        val plaintextBytes = input.readInt()
                        require(plaintextBytes in 1..header.chunkBytes) {
                            "Protected artifact chunk length is invalid."
                        }
                        val envelope = readEnvelope(input, plaintextBytes + GCM_TAG_BYTES)
                        val plaintext = cipher.decrypt(
                            envelope,
                            chunkAad(headerHash, chunkIndex, plaintextBytes),
                        )
                        try {
                            require(plaintext.size == plaintextBytes) {
                                "Protected artifact chunk plaintext length is invalid."
                            }
                            totalBytes = Math.addExact(totalBytes, plaintextBytes.toLong())
                            require(totalBytes <= MAX_ARTIFACT_BYTES) { "Protected artifact is too large." }
                            digest.update(plaintext)
                            plaintextOutput.write(plaintext)
                        } finally {
                            plaintext.fill(0)
                        }
                        sawShortChunk = plaintextBytes < header.chunkBytes
                        expectedChunkIndex = Math.addExact(expectedChunkIndex, 1)
                    }

                    RECORD_TRAILER -> {
                        val trailerEnvelope = readEnvelope(
                            input,
                            TRAILER_PLAINTEXT_BYTES + GCM_TAG_BYTES,
                        )
                        val trailer = cipher.decrypt(trailerEnvelope, aad(TRAILER_DOMAIN, headerHash))
                        try {
                            val expected = decodeTrailer(trailer)
                            require(expected.chunkCount == expectedChunkIndex) {
                                "Protected artifact trailer chunk count does not match."
                            }
                            require(expected.plaintextBytes == totalBytes) {
                                "Protected artifact trailer length does not match."
                            }
                            val actualHash = digest.digest()
                            try {
                                require(MessageDigest.isEqual(expected.sha256, actualHash)) {
                                    "Protected artifact trailer hash does not match."
                                }
                            } finally {
                                actualHash.fill(0)
                                expected.sha256.fill(0)
                            }
                        } finally {
                            trailer.fill(0)
                        }
                        require(input.read() == -1) { "Protected artifact contains trailing data." }
                        plaintextOutput.flush()
                        return ProtectedArtifactFileSummary(header, totalBytes)
                    }

                    else -> throw IllegalArgumentException("Protected artifact record type is invalid.")
                }
            }
        } finally {
            headerBytes.fill(0)
            headerHash.fill(0)
        }
    }

    private fun validateHeader(header: ProtectedArtifactHeader) {
        val descriptor = header.descriptor
        require(descriptor.artifactRefId.matches(ARTIFACT_REF_PATTERN)) {
            "Protected artifact reference format is invalid."
        }
        require(descriptor.revision > 0) { "Protected artifact revision must be positive." }
        require(descriptor.keyVersion == 1) { "Unsupported protected artifact key version." }
        require(descriptor.createdAt >= 0 && descriptor.updatedAt >= descriptor.createdAt) {
            "Protected artifact timestamps are invalid."
        }
        require(header.chunkBytes in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) {
            "Protected artifact chunk size is invalid."
        }
    }

    private fun encodeHeader(header: ProtectedArtifactHeader): ByteArray {
        val descriptor = header.descriptor
        val uuid = UUID.fromString(descriptor.artifactRefId)
        return ByteBuffer.allocate(HEADER_BYTES)
            .putInt(MAGIC)
            .put(FORMAT_VERSION.toByte())
            .put(ALGORITHM_AES_256_GCM.toByte())
            .put((descriptor.type.ordinal + 1).toByte())
            .put(0)
            .putInt(descriptor.keyVersion)
            .putInt(descriptor.revision)
            .putLong(descriptor.createdAt)
            .putLong(descriptor.updatedAt)
            .putInt(header.chunkBytes)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .putLong(0L)
            .array()
    }

    private fun decodeHeader(bytes: ByteArray): ProtectedArtifactHeader {
        require(bytes.size == HEADER_BYTES) { "Protected artifact header length is invalid." }
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.int == MAGIC) { "Protected artifact magic is invalid." }
        require(buffer.get().toInt() and 0xff == FORMAT_VERSION) {
            "Unsupported protected artifact format version."
        }
        require(buffer.get().toInt() and 0xff == ALGORITHM_AES_256_GCM) {
            "Unsupported protected artifact encryption algorithm."
        }
        val typeId = buffer.get().toInt() and 0xff
        val type = ProtectedArtifactType.entries.getOrNull(typeId - 1)
            ?: throw IllegalArgumentException("Protected artifact type is invalid.")
        require(buffer.get().toInt() == 0) { "Protected artifact header flags are invalid." }
        val keyVersion = buffer.int
        val revision = buffer.int
        val createdAt = buffer.long
        val updatedAt = buffer.long
        val chunkBytes = buffer.int
        val artifactRefId = UUID(buffer.long, buffer.long).toString()
        require(buffer.long == 0L) { "Protected artifact reserved header bytes are invalid." }
        return ProtectedArtifactHeader(
            descriptor = ProtectedArtifactDescriptor(
                artifactRefId = artifactRefId,
                type = type,
                revision = revision,
                keyVersion = keyVersion,
                createdAt = createdAt,
                updatedAt = updatedAt,
            ),
            chunkBytes = chunkBytes,
        ).also(::validateHeader)
    }

    private fun writeEnvelope(
        output: DataOutputStream,
        envelope: EncryptedEnvelope,
        expectedCiphertextBytes: Int,
    ) {
        require(envelope.version == 1) { "Unsupported encrypted envelope version." }
        require(envelope.initializationVector.size == GCM_IV_BYTES) {
            "Protected artifact IV length is invalid."
        }
        require(envelope.ciphertext.size == expectedCiphertextBytes) {
            "Protected artifact ciphertext length is invalid."
        }
        output.writeByte(envelope.initializationVector.size)
        output.write(envelope.initializationVector)
        output.writeInt(envelope.ciphertext.size)
        output.write(envelope.ciphertext)
    }

    private fun readEnvelope(input: DataInputStream, expectedCiphertextBytes: Int): EncryptedEnvelope {
        val ivBytes = input.readUnsignedByte()
        require(ivBytes == GCM_IV_BYTES) { "Protected artifact IV length is invalid." }
        val iv = ByteArray(ivBytes).also(input::readFully)
        val ciphertextBytes = input.readInt()
        require(ciphertextBytes == expectedCiphertextBytes) {
            "Protected artifact ciphertext length is invalid."
        }
        val ciphertext = ByteArray(ciphertextBytes).also(input::readFully)
        return EncryptedEnvelope(1, iv, ciphertext)
    }

    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            when {
                count < 0 -> break
                count == 0 -> {
                    val one = input.read()
                    if (one < 0) break
                    buffer[offset++] = one.toByte()
                }
                else -> offset += count
            }
        }
        return offset
    }

    private fun encodeTrailer(chunkCount: Int, plaintextBytes: Long, hash: ByteArray): ByteArray {
        require(hash.size == 32)
        return ByteArrayOutputStream(TRAILER_PLAINTEXT_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(chunkCount)
                output.writeLong(plaintextBytes)
                output.write(hash)
            }
            bytes.toByteArray()
        }
    }

    private fun decodeTrailer(bytes: ByteArray): Trailer {
        require(bytes.size == TRAILER_PLAINTEXT_BYTES) { "Protected artifact trailer is invalid." }
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val chunkCount = input.readInt()
            val plaintextBytes = input.readLong()
            val hash = ByteArray(32).also(input::readFully)
            require(chunkCount >= 0 && plaintextBytes in 0..MAX_ARTIFACT_BYTES) {
                "Protected artifact trailer bounds are invalid."
            }
            Trailer(chunkCount, plaintextBytes, hash)
        }
    }

    private fun chunkAad(headerHash: ByteArray, chunkIndex: Int, plaintextBytes: Int): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(CHUNK_DOMAIN)
                output.write(headerHash)
                output.writeInt(chunkIndex)
                output.writeInt(plaintextBytes)
            }
            bytes.toByteArray()
        }

    private fun aad(domain: ByteArray, value: ByteArray): ByteArray =
        ByteArray(domain.size + value.size).also { result ->
            domain.copyInto(result)
            value.copyInto(result, domain.size)
        }

    private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    private data class Trailer(
        val chunkCount: Int,
        val plaintextBytes: Long,
        val sha256: ByteArray,
    )

    val ARTIFACT_REF_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
}
