package app.zhijuan.reader.m0

import android.content.Context

internal object M0RecoveryProbeState {
    const val PREFERENCES_NAME = "m0-recovery-probe"
    const val STATE_IDLE = "IDLE"
    const val STATE_RUNNING = "RUNNING"
    const val STATE_PAUSED_BY_SYSTEM_TIMEOUT = "PAUSED_BY_SYSTEM_TIMEOUT"
    const val STATE_RECOVERY_REQUIRED = "RECOVERY_REQUIRED"

    fun current(context: Context): String = preferences(context)
        .getString(KEY_STATE, STATE_IDLE)
        ?: STATE_IDLE

    fun mark(
        context: Context,
        state: String,
        event: String,
        foregroundServiceType: Int? = null,
    ) {
        val preferences = preferences(context)
        val editor = preferences.edit()
            .putString(KEY_STATE, state)
            .putString(KEY_LAST_EVENT, event)
            .putLong(KEY_LAST_EVENT_AT_EPOCH_MILLIS, System.currentTimeMillis())
            .putInt(KEY_EVENT_COUNT, preferences.getInt(KEY_EVENT_COUNT, 0) + 1)
        if (foregroundServiceType != null) {
            editor.putInt(KEY_FOREGROUND_SERVICE_TYPE, foregroundServiceType)
        }
        check(editor.commit()) { "Unable to persist the M0 recovery probe checkpoint." }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private const val KEY_STATE = "state"
    private const val KEY_LAST_EVENT = "last_event"
    private const val KEY_LAST_EVENT_AT_EPOCH_MILLIS = "last_event_at_epoch_millis"
    private const val KEY_EVENT_COUNT = "event_count"
    private const val KEY_FOREGROUND_SERVICE_TYPE = "foreground_service_type"
}
