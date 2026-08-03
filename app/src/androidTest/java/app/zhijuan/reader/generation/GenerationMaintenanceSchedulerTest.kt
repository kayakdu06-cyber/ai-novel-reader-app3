package app.zhijuan.reader.generation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationMaintenanceSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun schedulerKeepsOneStartupAndOnePeriodicWorkerWithNoNetworkConstraint() {
        val manager = WorkManager.getInstance(context)
        try {
            GenerationMaintenanceScheduler.ensureScheduled(context)
            GenerationMaintenanceScheduler.ensureScheduled(context)

            val startup = manager.getWorkInfosForUniqueWork(
                GenerationMaintenanceScheduler.STARTUP_WORK_NAME,
            ).get(10L, TimeUnit.SECONDS)
            val periodic = manager.getWorkInfosForUniqueWork(
                GenerationMaintenanceScheduler.PERIODIC_WORK_NAME,
            ).get(10L, TimeUnit.SECONDS)

            assertEquals(1, startup.size)
            assertEquals(1, periodic.size)
            assertEquals(NetworkType.NOT_REQUIRED, startup.single().constraints.requiredNetworkType)
            assertEquals(NetworkType.NOT_REQUIRED, periodic.single().constraints.requiredNetworkType)
            assertTrue(periodic.single().constraints.requiresBatteryNotLow())
            assertTrue(periodic.single().constraints.requiresStorageNotLow())
        } finally {
            manager.cancelUniqueWork(GenerationMaintenanceScheduler.STARTUP_WORK_NAME)
            manager.cancelUniqueWork(GenerationMaintenanceScheduler.PERIODIC_WORK_NAME)
        }
    }
}
