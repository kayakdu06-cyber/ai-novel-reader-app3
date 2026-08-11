package app.zhijuan.core.database

internal object SqlCipherRuntime {
    private val loadLock = Any()

    @Volatile
    private var loaded = false

    fun load() = synchronized(loadLock) {
        if (!loaded) {
            System.loadLibrary("sqlcipher")
            loaded = true
        }
    }
}
