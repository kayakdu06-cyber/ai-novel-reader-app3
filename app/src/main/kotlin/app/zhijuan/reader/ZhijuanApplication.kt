package app.zhijuan.reader

import android.app.Application
import app.zhijuan.reader.generation.GenerationMaintenanceScheduler
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ZhijuanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.AUTO_SCHEDULE_MAINTENANCE) {
            GenerationMaintenanceScheduler.ensureScheduled(this)
        }
    }
}
