package app.zhijuan.core.backup

import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

data class AtomicBackupWriteResult(
    val target: Path,
    val replacedExisting: Boolean,
    val summary: BackupTransferSummary,
)

data class AtomicRestoreResult(
    val activeFile: Path,
    val recoveryPoint: Path?,
    val summary: BackupTransferSummary,
)

class AtomicBackupFileWriter(
    private val codec: ChunkedEncryptedBackupCodec = ChunkedEncryptedBackupCodec(),
    private val beforeCommit: () -> Unit = {},
) {
    fun write(
        source: Path,
        target: Path,
        passphrase: CharArray,
        parameters: BackupWriteParameters = BackupWriteParameters(),
    ): AtomicBackupWriteResult {
        val normalizedSource = source.toAbsolutePath().normalize()
        val normalizedTarget = target.toAbsolutePath().normalize()
        require(normalizedSource != normalizedTarget) { "Backup source and target must differ." }
        val parent = requireNotNull(normalizedTarget.parent) { "Backup target must have a parent directory." }
        Files.createDirectories(parent)
        val temporary = parent.resolve(".${normalizedTarget.fileName}.partial-${UUID.randomUUID()}")
        val replacedExisting = Files.exists(normalizedTarget)
        try {
            val writeSummary = Files.newInputStream(normalizedSource).use { input ->
                Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    codec.encrypt(input, Files.size(normalizedSource), output, passphrase, parameters)
                }
            }
            forceFile(temporary)
            val verifySummary = Files.newInputStream(temporary).use { input ->
                codec.decrypt(input, DiscardingOutputStream, passphrase)
            }
            if (
                writeSummary.plaintextBytes != verifySummary.plaintextBytes ||
                !MessageDigest.isEqual(
                    writeSummary.plaintextSha256.hexToByteArray(),
                    verifySummary.plaintextSha256.hexToByteArray(),
                )
            ) {
                throw BackupAuthenticationException("Backup self-verification did not match its source.")
            }
            beforeCommit()
            atomicReplace(temporary, normalizedTarget)
            return AtomicBackupWriteResult(normalizedTarget, replacedExisting, writeSummary)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

class AtomicBackupRestorer(
    private val codec: ChunkedEncryptedBackupCodec = ChunkedEncryptedBackupCodec(),
    private val beforeCommit: () -> Unit = {},
) {
    fun restore(
        backup: Path,
        activeFile: Path,
        recoveryDirectory: Path,
        passphrase: CharArray,
        validator: (Path) -> Unit = {},
    ): AtomicRestoreResult {
        val normalizedBackup = backup.toAbsolutePath().normalize()
        val normalizedActive = activeFile.toAbsolutePath().normalize()
        require(normalizedBackup != normalizedActive) { "Backup and active files must differ." }
        val activeParent = requireNotNull(normalizedActive.parent) { "Active file must have a parent directory." }
        Files.createDirectories(activeParent)
        Files.createDirectories(recoveryDirectory)
        val staging = activeParent.resolve(".${normalizedActive.fileName}.restore-${UUID.randomUUID()}")
        var recoveryPoint: Path? = null
        var committed = false
        try {
            val summary = Files.newInputStream(normalizedBackup).use { input ->
                Files.newOutputStream(staging, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                    codec.decrypt(input, output, passphrase)
                }
            }
            forceFile(staging)
            validator(staging)
            if (Files.exists(normalizedActive)) {
                recoveryPoint = recoveryDirectory.resolve(
                    "${normalizedActive.fileName}.pre-restore-${UUID.randomUUID()}.bak",
                )
                Files.copy(normalizedActive, recoveryPoint)
                forceFile(recoveryPoint)
            }
            beforeCommit()
            atomicReplace(staging, normalizedActive)
            committed = true
            return AtomicRestoreResult(normalizedActive, recoveryPoint, summary)
        } finally {
            Files.deleteIfExists(staging)
            if (!committed && recoveryPoint != null) {
                Files.deleteIfExists(recoveryPoint)
            }
        }
    }
}

private fun atomicReplace(source: Path, target: Path) {
    try {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (error: AtomicMoveNotSupportedException) {
        throw BackupAtomicCommitException(
            "The target storage does not support atomic replacement; current data was not replaced.",
            error,
        )
    }
}

private fun forceFile(path: Path) {
    FileChannel.open(path, StandardOpenOption.WRITE).use { channel -> channel.force(true) }
}

private object DiscardingOutputStream : OutputStream() {
    override fun write(value: Int) = Unit

    override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
}
