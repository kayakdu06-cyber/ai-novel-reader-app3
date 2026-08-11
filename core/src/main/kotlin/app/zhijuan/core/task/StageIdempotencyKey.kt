package app.zhijuan.core.task

import app.zhijuan.core.model.GenerationPhase
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@JvmInline
value class StageIdempotencyKey private constructor(
    val value: String,
) {
    companion object {
        fun create(
            jobId: String,
            phase: GenerationPhase,
            targetId: String,
            inputVersionHash: String,
        ): StageIdempotencyKey {
            val canonical = listOf(jobId, phase.name, targetId, inputVersionHash)
                .onEach { require(it.isNotBlank()) { "Idempotency key components cannot be blank." } }
                .joinToString(separator = "") { value -> "${value.length}:$value" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(StandardCharsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
            return StageIdempotencyKey(digest)
        }
    }
}

