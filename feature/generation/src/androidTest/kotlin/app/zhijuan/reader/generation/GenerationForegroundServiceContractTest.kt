package app.zhijuan.reader.generation

import android.app.Notification
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import app.zhijuan.core.model.GenerationJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationForegroundServiceContractTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun productionServiceIsPrivateAndDeclaresOnlyTheDataSyncForegroundType() {
        val packageManager = context.packageManager
        val component = ComponentName(context, GenerationForegroundService::class.java)
        val serviceInfo = serviceInfo(packageManager, component)
        val requestedPermissions = packageInfo(packageManager).requestedPermissions.orEmpty().toSet()

        assertFalse(serviceInfo.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, serviceInfo.foregroundServiceType)
        assertTrue(android.Manifest.permission.FOREGROUND_SERVICE in requestedPermissions)
        assertTrue(android.Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC in requestedPermissions)
        assertTrue(android.Manifest.permission.POST_NOTIFICATIONS in requestedPermissions)
    }

    @Test
    fun notificationIsPrivateOngoingGenericAndProvidesOnlyPauseAndStop() {
        val jobId = "job-notification-contract"
        val notification = GenerationForegroundNotificationFactory(context).create(
            jobId = jobId,
            status = GenerationJobStatus.RUNNING,
        )

        assertEquals(Notification.CATEGORY_PROGRESS, notification.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertNotNull(notification.contentIntent)
        assertEquals(2, notification.actions.size)
        assertEquals("暂停", notification.actions[0].title.toString())
        assertEquals("停止", notification.actions[1].title.toString())
        assertEquals(context.packageName, notification.actions[0].actionIntent.creatorPackage)
        assertEquals(context.packageName, notification.actions[1].actionIntent.creatorPackage)
        val visibleText = listOf(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(separator = " ")
        assertFalse(visibleText.contains(jobId))
    }

    @Suppress("DEPRECATION")
    private fun serviceInfo(
        packageManager: PackageManager,
        component: ComponentName,
    ): ServiceInfo = if (Build.VERSION.SDK_INT >= 33) {
        packageManager.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(0L))
    } else {
        packageManager.getServiceInfo(component, 0)
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager) =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
}
