package app.zhijuan.core.backup

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidBackupSpikeTest {
    private lateinit var testDirectory: Path

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        testDirectory = context.noBackupFilesDir.toPath().resolve("m0-backup-spike")
        testDirectory.toFile().deleteRecursively()
        Files.createDirectories(testDirectory)
    }

    @After
    fun tearDown() {
        testDirectory.toFile().deleteRecursively()
    }

    @Test
    fun productionKdfAndAesGcmRoundTripOnAndroid() {
        val codec = ChunkedEncryptedBackupCodec()
        val plaintext = ByteArray(1024 * 1024) { index -> (index % 251).toByte() }
        val encrypted = ByteArrayOutputStream()
        val startedAt = SystemClock.elapsedRealtime()
        val writeSummary = codec.encrypt(
            ByteArrayInputStream(plaintext),
            plaintext.size.toLong(),
            encrypted,
            PASSWORD,
        )
        val restored = ByteArrayOutputStream()
        val readSummary = codec.decrypt(
            ByteArrayInputStream(encrypted.toByteArray()),
            restored,
            PASSWORD,
        )
        val elapsedMillis = SystemClock.elapsedRealtime() - startedAt

        assertArrayEquals(plaintext, restored.toByteArray())
        assertEquals(writeSummary.plaintextSha256, readSummary.plaintextSha256)
        assertEquals(DEFAULT_PBKDF2_ITERATIONS, writeSummary.pbkdf2Iterations)
        Log.i(
            "ZhijuanM0Backup",
            "bytes=${plaintext.size} iterations=$DEFAULT_PBKDF2_ITERATIONS " +
                "encryptAndDecryptMs=$elapsedMillis encryptedBytes=${encrypted.size()}",
        )
    }

    @Test
    fun atomicRestoreWorksInsideAndroidInternalStorage() {
        val codec = ChunkedEncryptedBackupCodec(
            policy = BackupCryptoPolicy(minimumPbkdf2Iterations = 1_000),
        )
        val parameters = BackupWriteParameters(pbkdf2Iterations = 1_000, chunkBytes = 4 * 1024)
        val source = testDirectory.resolve("new.db")
        val active = testDirectory.resolve("active.db")
        val backup = testDirectory.resolve("library.zjb")
        val recovery = testDirectory.resolve("recovery")
        Files.write(source, "new library".encodeToByteArray())
        Files.write(active, "old library".encodeToByteArray())

        AtomicBackupFileWriter(codec).write(source, backup, PASSWORD, parameters)
        val result = AtomicBackupRestorer(codec).restore(
            backup,
            active,
            recovery,
            PASSWORD,
        ) { staged -> assertEquals("new library", Files.readAllBytes(staged).decodeToString()) }

        assertEquals("new library", Files.readAllBytes(active).decodeToString())
        assertEquals("old library", Files.readAllBytes(requireNotNull(result.recoveryPoint)).decodeToString())
        assertTrue(Files.exists(backup))
    }

    private companion object {
        val PASSWORD = "android backup passphrase".toCharArray()
    }
}
