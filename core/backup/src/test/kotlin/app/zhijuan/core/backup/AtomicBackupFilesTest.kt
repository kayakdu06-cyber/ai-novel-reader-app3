package app.zhijuan.core.backup

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AtomicBackupFilesTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val codec = ChunkedEncryptedBackupCodec(
        policy = BackupCryptoPolicy(minimumPbkdf2Iterations = 1_000),
    )
    private val parameters = BackupWriteParameters(pbkdf2Iterations = 1_000, chunkBytes = 4 * 1024)

    @Test
    fun `verified backup replaces target only after commit`() {
        val source = write("source.db", "new library".encodeToByteArray())
        val target = write("library.zjb", "previous backup".encodeToByteArray())

        val result = AtomicBackupFileWriter(codec).write(source, target, PASSWORD, parameters)

        assertTrue(result.replacedExisting)
        val restored = temporaryDirectory.resolve("verified.db")
        Files.newInputStream(target).use { input ->
            Files.newOutputStream(restored).use { output -> codec.decrypt(input, output, PASSWORD) }
        }
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(restored))
        assertNoTemporaryFiles("partial")
    }

    @Test
    fun `backup commit failure preserves previous target`() {
        val source = write("source.db", "new library".encodeToByteArray())
        val target = write("library.zjb", "previous backup".encodeToByteArray())
        val expected = Files.readAllBytes(target)
        val writer = AtomicBackupFileWriter(codec) { error("simulated stop before commit") }

        assertThrows(IllegalStateException::class.java) {
            writer.write(source, target, PASSWORD, parameters)
        }
        assertArrayEquals(expected, Files.readAllBytes(target))
        assertNoTemporaryFiles("partial")
    }

    @Test
    fun `successful restore replaces active file and keeps recovery point`() {
        val newLibrary = write("new.db", "new library".encodeToByteArray())
        val backup = temporaryDirectory.resolve("library.zjb")
        AtomicBackupFileWriter(codec).write(newLibrary, backup, PASSWORD, parameters)
        val active = write("active.db", "old library".encodeToByteArray())
        val recoveryDirectory = temporaryDirectory.resolve("recovery")

        val result = AtomicBackupRestorer(codec).restore(
            backup,
            active,
            recoveryDirectory,
            PASSWORD,
        ) { staged ->
            assertEquals("new library", Files.readAllBytes(staged).decodeToString())
        }

        assertEquals("new library", Files.readAllBytes(active).decodeToString())
        assertEquals("old library", Files.readAllBytes(requireNotNull(result.recoveryPoint)).decodeToString())
        assertNoTemporaryFiles("restore")
    }

    @Test
    fun `wrong password leaves current library unchanged`() {
        val fixture = restoreFixture()

        assertThrows(BackupAuthenticationException::class.java) {
            AtomicBackupRestorer(codec).restore(
                fixture.backup,
                fixture.active,
                fixture.recoveryDirectory,
                "wrong".toCharArray(),
            )
        }
        assertEquals("old library", Files.readAllBytes(fixture.active).decodeToString())
        assertDirectoryEmptyOrMissing(fixture.recoveryDirectory)
        assertNoTemporaryFiles("restore")
    }

    @Test
    fun `damaged backup leaves current library unchanged`() {
        val fixture = restoreFixture()
        val damaged = Files.readAllBytes(fixture.backup)
        damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
        Files.write(fixture.backup, damaged)

        assertThrows(BackupAuthenticationException::class.java) {
            AtomicBackupRestorer(codec).restore(
                fixture.backup,
                fixture.active,
                fixture.recoveryDirectory,
                PASSWORD,
            )
        }
        assertEquals("old library", Files.readAllBytes(fixture.active).decodeToString())
        assertDirectoryEmptyOrMissing(fixture.recoveryDirectory)
        assertNoTemporaryFiles("restore")
    }

    @Test
    fun `validator failure leaves current library unchanged`() {
        val fixture = restoreFixture()

        assertThrows(IllegalArgumentException::class.java) {
            AtomicBackupRestorer(codec).restore(
                fixture.backup,
                fixture.active,
                fixture.recoveryDirectory,
                PASSWORD,
            ) { throw IllegalArgumentException("invalid schema") }
        }
        assertEquals("old library", Files.readAllBytes(fixture.active).decodeToString())
        assertDirectoryEmptyOrMissing(fixture.recoveryDirectory)
        assertNoTemporaryFiles("restore")
    }

    @Test
    fun `stop immediately before restore commit leaves current library unchanged`() {
        val fixture = restoreFixture()
        val restorer = AtomicBackupRestorer(codec) { error("simulated process stop") }

        assertThrows(IllegalStateException::class.java) {
            restorer.restore(
                fixture.backup,
                fixture.active,
                fixture.recoveryDirectory,
                PASSWORD,
            )
        }
        assertEquals("old library", Files.readAllBytes(fixture.active).decodeToString())
        assertTrue(Files.list(fixture.recoveryDirectory).use { stream -> stream.findAny().isEmpty })
        assertNoTemporaryFiles("restore")
    }

    private fun restoreFixture(): RestoreFixture {
        val newLibrary = write("new.db", "new library".encodeToByteArray())
        val backup = temporaryDirectory.resolve("library.zjb")
        AtomicBackupFileWriter(codec).write(newLibrary, backup, PASSWORD, parameters)
        return RestoreFixture(
            backup = backup,
            active = write("active.db", "old library".encodeToByteArray()),
            recoveryDirectory = temporaryDirectory.resolve("recovery"),
        )
    }

    private fun write(name: String, bytes: ByteArray): Path =
        temporaryDirectory.resolve(name).also { path -> Files.write(path, bytes) }

    private fun assertNoTemporaryFiles(marker: String) {
        assertTrue(
            Files.list(temporaryDirectory).use { stream ->
                stream.noneMatch { path -> path.fileName.toString().contains(marker) }
            },
        )
    }

    private fun assertDirectoryEmptyOrMissing(directory: Path) {
        assertTrue(
            !Files.exists(directory) || Files.list(directory).use { stream -> stream.findAny().isEmpty },
        )
    }

    private data class RestoreFixture(
        val backup: Path,
        val active: Path,
        val recoveryDirectory: Path,
    )

    private companion object {
        val PASSWORD = "backup passphrase".toCharArray()
    }
}
