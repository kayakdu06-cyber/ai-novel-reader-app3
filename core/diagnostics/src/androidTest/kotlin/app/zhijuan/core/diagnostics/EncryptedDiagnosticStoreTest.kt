package app.zhijuan.core.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import app.zhijuan.core.security.ProtectedArtifactType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class EncryptedDiagnosticStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var artifactStore: AndroidProtectedArtifactStore
    private lateinit var store: EncryptedDiagnosticStore

    @Before
    fun setUp() {
        artifactStore = AndroidProtectedArtifactStore(context)
        cleanDiagnostics()
        store = EncryptedDiagnosticStore(context, artifactStore)
    }

    @After
    fun tearDown() {
        cleanDiagnostics()
    }

    @Test
    fun encryptedStoreNeverPersistsSecretNovelOrThrowableMessage() {
        val event = DiagnosticEventFactory().create(
            timestampEpochMillis = 10,
            severity = DiagnosticSeverity.ERROR,
            category = DiagnosticCategory.NETWORK,
            code = DiagnosticCode.REQUEST_FAILED,
            operation = DiagnosticOperation.GENERATION_REQUEST,
            httpStatus = 401,
            correlations = mapOf(
                DiagnosticCorrelationKind.CONNECTION to SECRET_CANARY,
                DiagnosticCorrelationKind.BOOK to NOVEL_CANARY,
            ),
            error = IllegalStateException("$SECRET_CANARY $NOVEL_CANARY"),
        )

        assertEquals(DiagnosticWriteOutcome.APPENDED, store.append(event, now = 10))
        val snapshot = store.snapshot()
        assertTrue(snapshot is DiagnosticSnapshotResult.Available)
        val events = (snapshot as DiagnosticSnapshotResult.Available).events
        assertEquals(1, events.size)

        val decodedBytes = DiagnosticEventCodec.encode(events)
        assertFalse(decodedBytes.containsSubsequence(SECRET_CANARY.toByteArray()))
        assertFalse(decodedBytes.containsSubsequence(NOVEL_CANARY.toByteArray()))
        context.noBackupFilesDir.walkTopDown()
            .filter(File::isFile)
            .forEach { file ->
                val bytes = file.readBytes()
                assertFalse(
                    "Secret found in " + file.name,
                    bytes.containsSubsequence(SECRET_CANARY.toByteArray()),
                )
                assertFalse(
                    "Novel text found in " + file.name,
                    bytes.containsSubsequence(NOVEL_CANARY.toByteArray()),
                )
            }
    }

    @Test
    fun rollingStoreKeepsOnlyTheNewestConfiguredEvents() {
        store = EncryptedDiagnosticStore(
            context = context,
            artifactStore = artifactStore,
            maximumEvents = 3,
        )
        repeat(5) { index ->
            val event = DiagnosticEventFactory().create(
                timestampEpochMillis = index.toLong(),
                severity = DiagnosticSeverity.INFO,
                category = DiagnosticCategory.APPLICATION,
                code = DiagnosticCode.APP_STARTED,
                operation = DiagnosticOperation.STARTUP,
            )
            assertEquals(DiagnosticWriteOutcome.APPENDED, store.append(event, now = index.toLong()))
        }

        val events = (store.snapshot() as DiagnosticSnapshotResult.Available).events
        assertEquals(listOf(2L, 3L, 4L), events.map(DiagnosticEvent::timestampEpochMillis))
    }

    @Test
    fun corruptedCiphertextFailsClosedWithoutCreatingPlaintextFallback() {
        val first = DiagnosticEventFactory().create(
            timestampEpochMillis = 1,
            severity = DiagnosticSeverity.WARNING,
            category = DiagnosticCategory.STORAGE,
            code = DiagnosticCode.DATABASE_OPEN_FAILED,
            operation = DiagnosticOperation.DATABASE_OPEN,
            correlations = mapOf(DiagnosticCorrelationKind.BOOK to NOVEL_CANARY),
        )
        assertEquals(DiagnosticWriteOutcome.APPENDED, store.append(first, now = 1))

        val descriptor = artifactStore.listDescriptors()
            .single { it.type == ProtectedArtifactType.DIAGNOSTIC_LOG }
        val artifactFile = File(
            context.noBackupFilesDir,
            "content/protected-artifacts/artifact-" + descriptor.artifactRefId + ".zjaf",
        )
        RandomAccessFile(artifactFile, "rw").use { file ->
            file.seek(file.length() - 1)
            val original = file.read()
            file.seek(file.length() - 1)
            file.write(original xor 0x01)
        }

        val second = DiagnosticEventFactory().create(
            timestampEpochMillis = 2,
            severity = DiagnosticSeverity.ERROR,
            category = DiagnosticCategory.STORAGE,
            code = DiagnosticCode.UNEXPECTED_FAILURE,
            operation = DiagnosticOperation.DATABASE_OPEN,
            correlations = mapOf(DiagnosticCorrelationKind.BOOK to SECRET_CANARY),
        )
        assertEquals(DiagnosticWriteOutcome.FAILED_CLOSED, store.append(second, now = 2))
        assertTrue(store.snapshot() is DiagnosticSnapshotResult.Unavailable)
        assertFalse(
            context.noBackupFilesDir.walkTopDown()
                .filter(File::isFile)
                .any { it.extension in setOf("log", "txt", "jsonl") },
        )
    }

    private fun cleanDiagnostics() {
        runCatching {
            artifactStore.listDescriptors()
                .filter { it.type == ProtectedArtifactType.DIAGNOSTIC_LOG }
                .forEach { artifactStore.delete(it.artifactRefId) }
        }
        File(context.noBackupFilesDir, EncryptedDiagnosticStore.INDEX_RELATIVE_PATH).delete()
        File(context.noBackupFilesDir, EncryptedDiagnosticStore.INDEX_RELATIVE_PATH + ".bak").delete()
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
        const val SECRET_CANARY = "ZHIJUAN_ENCRYPTED_SENSITIVE_VALUE_CANARY_018"
        const val NOVEL_CANARY = "ZHIJUAN_ENCRYPTED_DIAGNOSTIC_NOVEL_CANARY_018"
    }
}
