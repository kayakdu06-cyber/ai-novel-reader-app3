package app.zhijuan.core.backup

open class BackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class BackupFormatException(
    message: String,
    cause: Throwable? = null,
) : BackupException(message, cause)

class BackupAuthenticationException(
    message: String,
    cause: Throwable? = null,
) : BackupException(message, cause)

class BackupAtomicCommitException(
    message: String,
    cause: Throwable? = null,
) : BackupException(message, cause)
