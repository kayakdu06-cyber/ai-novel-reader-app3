package app.zhijuan.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class AndroidProtectedArtifactStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory: File get() = File(context.noBackupFilesDir, "content/protected-artifacts")
    private lateinit var store: AndroidProtectedArtifactStore

    @Before
    fun setUp() {
        cleanArtifactsAndKeys()
        store = AndroidProtectedArtifactStore(context)
    }

    @After
    fun tearDown() {
        runCatching { store.unlockAfterAuthentication() }
        cleanArtifactsAndKeys()
    }

    @Test
    fun draftCheckpointIsEncryptedVersionedAndConsumesCallerBuffers() {
        val first = novelBytes("第一份流式草稿，包含不可出现在文件中的正文标记。")
        val firstCopy = first.copyOf()
        val created = store.createAndClear(ProtectedArtifactType.STREAM_DRAFT, first, 10)

        assertTrue(first.all { it == 0.toByte() })
        assertEquals(1, created.descriptor.revision)
        assertFilesDoNotContain(firstCopy)

        val borrowed = store.readBytes(
            created.descriptor.artifactRefId,
            ProtectedArtifactType.STREAM_DRAFT,
        )
        val borrowedArray = borrowed.withBytes { it }
        assertArrayEquals(firstCopy, borrowedArray)

        val replacement = novelBytes("第二份草稿检查点，应原子替换上一份。")
        val replacementCopy = replacement.copyOf()
        val updated = store.replaceAndClear(
            created.descriptor.artifactRefId,
            ProtectedArtifactType.STREAM_DRAFT,
            expectedRevision = 1,
            plaintext = replacement,
            now = 20,
        )

        assertTrue(replacement.all { it == 0.toByte() })
        assertEquals(2, updated.descriptor.revision)
        assertThrows(IllegalStateException::class.java) { borrowed.withBytes { it.size } }
        assertTrue(borrowedArray.all { it == 0.toByte() })
        assertFilesDoNotContain(firstCopy)
        assertFilesDoNotContain(replacementCopy)
        store.readBytes(
            created.descriptor.artifactRefId,
            ProtectedArtifactType.STREAM_DRAFT,
        ).use { lease ->
            lease.withBytes { assertArrayEquals(replacementCopy, it) }
        }
        firstCopy.fill(0)
        replacementCopy.fill(0)
    }

    @Test
    fun failedAndStaleReplacementPreserveLatestCompleteCheckpoint() {
        val created = store.createAndClear(
            ProtectedArtifactType.STREAM_DRAFT,
            novelBytes("可恢复的第一版草稿"),
            10,
        )
        val second = novelBytes("已经成功提交的第二版草稿")
        val secondCopy = second.copyOf()
        store.replaceAndClear(
            created.descriptor.artifactRefId,
            ProtectedArtifactType.STREAM_DRAFT,
            1,
            second,
            20,
        )

        assertThrows(StaleProtectedArtifactRevisionException::class.java) {
            store.replaceAndClear(
                created.descriptor.artifactRefId,
                ProtectedArtifactType.STREAM_DRAFT,
                1,
                novelBytes("过期写入不能覆盖第二版"),
                30,
            )
        }
        assertThrows(ProtectedArtifactUnavailableException::class.java) {
            store.replace(
                created.descriptor.artifactRefId,
                ProtectedArtifactType.STREAM_DRAFT,
                2,
                ThrowingInputStream(novelBytes("写到一半发生磁盘或进程错误"), 8),
                40,
            )
        }

        store.readBytes(
            created.descriptor.artifactRefId,
            ProtectedArtifactType.STREAM_DRAFT,
        ).use { lease ->
            assertEquals(2, lease.descriptor.revision)
            lease.withBytes { assertArrayEquals(secondCopy, it) }
        }
        secondCopy.fill(0)
    }

    @Test
    fun recoveryPointStreamsInBoundedChunksAndLeavesNoPlaintextFile() {
        val plaintext = ByteArray(2 * 1024 * 1024 + 31) { index ->
            if (index < RECOVERY_CANARY.size) RECOVERY_CANARY[index] else (index * 17).toByte()
        }
        val source = TrackingAndroidInputStream(plaintext)
        val created = store.create(ProtectedArtifactType.DATABASE_RECOVERY_POINT, source, 10)

        assertEquals(plaintext.size.toLong(), created.plaintextBytes)
        assertEquals(ProtectedArtifactFileCodec.DEFAULT_CHUNK_BYTES, source.maximumRequestedBytes)
        assertFilesDoNotContain(RECOVERY_CANARY)

        val restored = ByteArrayOutputStream()
        val verified = store.readTo(
            created.descriptor.artifactRefId,
            ProtectedArtifactType.DATABASE_RECOVERY_POINT,
            restored,
        )
        assertEquals(plaintext.size.toLong(), verified.plaintextBytes)
        assertArrayEquals(plaintext, restored.toByteArray())
        plaintext.fill(0)
    }

    @Test
    fun wrongTypeMissingKeyAndTamperingFailClosed() {
        val created = store.createAndClear(
            ProtectedArtifactType.STREAM_DRAFT,
            novelBytes("用途、密钥与认证都必须正确"),
            10,
        )
        val ref = created.descriptor.artifactRefId
        val file = onlyArtifactFile()
        val original = file.readBytes()

        assertThrows(ProtectedArtifactTypeMismatchException::class.java) {
            store.readTo(ref, ProtectedArtifactType.DATABASE_RECOVERY_POINT, ByteArrayOutputStream())
        }
        assertArrayEquals(original, file.readBytes())

        file.writeBytes(original.copyOf().also { bytes ->
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        })
        assertThrows(ProtectedArtifactUnavailableException::class.java) {
            store.verify(ref, ProtectedArtifactType.STREAM_DRAFT)
        }

        file.writeBytes(original)
        AndroidKeystoreAesGcm(AndroidProtectedArtifactStore.keyAliasFor(ref)).deleteKey()
        assertThrows(ProtectedArtifactUnavailableException::class.java) {
            store.descriptor(ref)
        }
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun lockingClosesLeasesAndBlocksMetadataAndContentAccess() {
        val created = store.createAndClear(
            ProtectedArtifactType.STREAM_DRAFT,
            novelBytes("锁定后不能继续读取的正文"),
            10,
        )
        val lease = store.readBytes(
            created.descriptor.artifactRefId,
            ProtectedArtifactType.STREAM_DRAFT,
        )
        val borrowed = lease.withBytes { it }
        store.lock()

        assertTrue(borrowed.all { it == 0.toByte() })
        assertThrows(IllegalStateException::class.java) { lease.withBytes { it.size } }
        assertThrows(ProtectedArtifactStoreLockedException::class.java) { store.listDescriptors() }
        assertThrows(ProtectedArtifactStoreLockedException::class.java) {
            store.verify(created.descriptor.artifactRefId, ProtectedArtifactType.STREAM_DRAFT)
        }

        store.unlockAfterAuthentication()
        assertEquals(created.descriptor, store.descriptor(created.descriptor.artifactRefId))
    }

    @Test
    fun listingExposesOnlyDescriptorsAndDeleteRemovesFileAndKeystoreKey() {
        val draft = store.createAndClear(
            ProtectedArtifactType.STREAM_DRAFT,
            novelBytes("只通过随机引用发现草稿"),
            10,
        )
        val recovery = store.createAndClear(
            ProtectedArtifactType.DATABASE_RECOVERY_POINT,
            novelBytes("只通过随机引用发现恢复点"),
            20,
        )

        assertEquals(
            setOf(draft.descriptor, recovery.descriptor),
            store.listDescriptors().toSet(),
        )
        val alias = AndroidProtectedArtifactStore.keyAliasFor(draft.descriptor.artifactRefId)
        assertTrue(AndroidKeystoreAesGcm(alias).keyExists())

        store.delete(draft.descriptor.artifactRefId)

        assertFalse(AndroidKeystoreAesGcm(alias).keyExists())
        assertEquals(listOf(recovery.descriptor), store.listDescriptors())
        assertThrows(ProtectedArtifactUnavailableException::class.java) {
            store.descriptor(draft.descriptor.artifactRefId)
        }
    }

    @Test
    fun streamingBufferCheckpointsByBytesOrTimeAndClearsSnapshots() {
        val policy = StreamingDraftPolicy(
            checkpointIntervalMillis = 2_000,
            checkpointPendingBytes = 1_024,
            maximumPlaintextBytes = 4_096,
        )
        val buffer = store.createStreamingDraftBuffer(now = 10, policy = policy)
        val ref = buffer.descriptor.artifactRefId

        val first = ByteArray(1_023) { 65 }
        assertEquals(
            StreamingDraftWriteOutcome.BUFFERED,
            buffer.appendAndClear(first, now = 100).outcome,
        )
        assertTrue(first.all { it == 0.toByte() })
        assertEquals(1, buffer.descriptor.revision)

        val threshold = byteArrayOf(66)
        val thresholdResult = buffer.appendAndClear(threshold, now = 101)
        assertEquals(StreamingDraftWriteOutcome.CHECKPOINTED, thresholdResult.outcome)
        assertEquals(2, thresholdResult.revision)
        assertEquals(1_024, thresholdResult.persistedBytes)

        assertEquals(
            StreamingDraftWriteOutcome.BUFFERED,
            buffer.appendUtf8("中", now = 102).outcome,
        )
        val timed = buffer.appendUtf8("文", now = 2_101)
        assertEquals(StreamingDraftWriteOutcome.CHECKPOINTED, timed.outcome)
        assertEquals(3, timed.revision)
        assertFilesDoNotContain("中文".toByteArray(Charsets.UTF_8))

        var borrowed: ByteArray? = null
        buffer.withSnapshotBytes { snapshot ->
            borrowed = snapshot
            assertEquals(1_030, snapshot.size)
        }
        assertTrue(requireNotNull(borrowed).all { it == 0.toByte() })

        buffer.close()
        assertThrows(IllegalStateException::class.java) { buffer.state }
        store.readBytes(ref, ProtectedArtifactType.STREAM_DRAFT).use { lease ->
            lease.withBytes { assertEquals(1_030, it.size) }
        }
    }

    @Test
    fun staleStreamingWriterIsFencedAndCannotOverwriteNewerCheckpoint() {
        val policy = StreamingDraftPolicy(
            checkpointIntervalMillis = 2_000,
            checkpointPendingBytes = 1_024,
            maximumPlaintextBytes = 4_096,
        )
        val first = store.createStreamingDraftBuffer(now = 10, policy = policy)
        val ref = first.descriptor.artifactRefId
        val stale = store.resumeStreamingDraftBuffer(ref, policy)

        first.appendUtf8("newest", now = 20)
        first.flush(now = 20)
        stale.appendUtf8("stale", now = 21)
        assertThrows(StaleProtectedArtifactRevisionException::class.java) {
            stale.flush(now = 21)
        }
        assertThrows(IllegalStateException::class.java) { stale.state }

        store.readBytes(ref, ProtectedArtifactType.STREAM_DRAFT).use { lease ->
            lease.withBytes { assertEquals("newest", it.toString(Charsets.UTF_8)) }
        }
        first.close()
    }

    @Test
    fun streamingBufferFailsClosedOnClockRollbackAndMaximumSize() {
        val buffer = store.createStreamingDraftBuffer(
            now = 10,
            policy = StreamingDraftPolicy(
                checkpointIntervalMillis = 2_000,
                checkpointPendingBytes = 1_024,
                maximumPlaintextBytes = 1_024,
            ),
        )
        buffer.appendUtf8("ok", now = 20)
        assertThrows(IllegalArgumentException::class.java) {
            buffer.appendUtf8("clock rollback", now = 19)
        }
        val oversized = ByteArray(1_023) { 1 }
        assertThrows(IllegalArgumentException::class.java) {
            buffer.appendAndClear(oversized, now = 21)
        }
        assertTrue(oversized.all { it == 0.toByte() })
        assertEquals(2, buffer.state.plaintextBytes)
        buffer.close()
    }

    @Test
    fun interruptedAtomicBackupRemainsDiscoverableAndRecoverable() {
        val created = store.createAndClear(
            ProtectedArtifactType.STREAM_DRAFT,
            novelBytes("原子替换留下的备份仍必须能被恢复扫描发现"),
            10L,
        )
        val base = onlyArtifactFile()
        val backup = File(base.parentFile, "${base.name}.bak")
        assertTrue(base.renameTo(backup))

        assertEquals(listOf(created.descriptor.artifactRefId), store.listArtifactReferenceIds())
        assertEquals(created.descriptor, store.descriptor(created.descriptor.artifactRefId))
        assertTrue(base.exists())
        store.delete(created.descriptor.artifactRefId)
        assertTrue(store.listArtifactReferenceIds().isEmpty())
    }

    private fun novelBytes(value: String): ByteArray = value.toByteArray(Charsets.UTF_8)

    private fun onlyArtifactFile(): File = directory.listFiles().orEmpty()
        .single { it.name.endsWith(".zjaf") }

    private fun assertFilesDoNotContain(needle: ByteArray) {
        assertTrue(needle.isNotEmpty())
        directory.listFiles().orEmpty().forEach { file ->
            assertFalse("Plaintext found in ${file.name}", file.readBytes().containsSubsequence(needle))
        }
    }

    private fun cleanArtifactsAndKeys() {
        directory.listFiles().orEmpty().forEach { file ->
            val match = ARTIFACT_FILE_REGEX.matchEntire(file.name) ?: return@forEach
            AndroidKeystoreAesGcm(
                AndroidProtectedArtifactStore.keyAliasFor(match.groupValues[1]),
            ).deleteKey()
        }
        directory.deleteRecursively()
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
    }

    private companion object {
        val RECOVERY_CANARY = "ZHIJUAN_RECOVERY_POINT_PLAINTEXT_CANARY_015"
            .toByteArray(Charsets.UTF_8)
        val ARTIFACT_FILE_REGEX = Regex("artifact-([0-9a-f-]{36})\\.zjaf(?:\\.bak|\\.new)?")
    }
}

private class TrackingAndroidInputStream(
    bytes: ByteArray,
) : ByteArrayInputStream(bytes) {
    var maximumRequestedBytes = 0
        private set

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        maximumRequestedBytes = maxOf(maximumRequestedBytes, length)
        return super.read(target, offset, length)
    }
}

private class ThrowingInputStream(
    bytes: ByteArray,
    private val failAfterBytes: Int,
) : ByteArrayInputStream(bytes) {
    private var delivered = 0

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (delivered >= failAfterBytes) throw IOException("Injected write failure")
        val allowed = minOf(length, failAfterBytes - delivered)
        return super.read(target, offset, allowed).also { count ->
            if (count > 0) delivered += count
        }
    }
}
