package app.zhijuan.reader.generation

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Singleton
internal class ForegroundGenerationTimeoutCoordinator @Inject constructor(
    private val gateway: ForegroundGenerationGateway,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun persistTimeoutPause(
        jobId: String,
        requestedAt: Long,
        completed: () -> Unit,
    ) {
        scope.launch {
            runCatching {
                gateway.requestSystemTimeoutPause(jobId, requestedAt)
            }
            completed()
        }
    }
}
