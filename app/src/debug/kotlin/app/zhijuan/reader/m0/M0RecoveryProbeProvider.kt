package app.zhijuan.reader.m0

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/** Simulates the process-start reconciliation hook that will later inspect persisted Room jobs. */
class M0RecoveryProbeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val appContext = context?.applicationContext ?: return false
        if (M0RecoveryProbeState.current(appContext) == M0RecoveryProbeState.STATE_RUNNING) {
            M0RecoveryProbeState.mark(
                context = appContext,
                state = M0RecoveryProbeState.STATE_RECOVERY_REQUIRED,
                event = "PROCESS_START_FOUND_STALE_RUNNING_STATE",
            )
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
