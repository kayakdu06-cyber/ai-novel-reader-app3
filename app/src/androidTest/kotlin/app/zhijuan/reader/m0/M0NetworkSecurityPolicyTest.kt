package app.zhijuan.reader.m0

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class M0NetworkSecurityPolicyTest {
    @Test
    fun remoteCleartextIsRejectedByAndroidPolicy() {
        val policy = NetworkSecurityPolicy.getInstance()

        assertFalse(policy.isCleartextTrafficPermitted)
        assertFalse(policy.isCleartextTrafficPermitted("api.example.com"))
    }
}
