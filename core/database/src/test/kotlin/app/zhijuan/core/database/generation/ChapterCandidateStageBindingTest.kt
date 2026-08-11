package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChapterCandidateStageBindingTest {
    @Test
    fun validArraySourceIsNotACandidateBinding() {
        assertNull(ChapterCandidateStageBindingV1.parseIfBound(stage("[]")))
    }

    @Test
    fun malformedJsonStillFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterCandidateStageBindingV1.parseIfBound(stage("["))
        }
    }

    @Test
    fun matchingCandidatePolicyStillRequiresTheStrictEnvelope() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterCandidateStageBindingV1.parseIfBound(
                stage(
                    """{"sourcePolicyVersion":"zhijuan.chapter-candidate-stage-source.v1"}""",
                ),
            )
        }
    }

    private fun stage(inputSourcesJson: String) = GenerationStageEntity(
        stageId = "stage.compatibility",
        jobId = "job.compatibility",
        phase = GenerationPhase.DRAFT_CHAPTER,
        targetType = GenerationTargetType.CHAPTER,
        targetId = "chapter.compatibility",
        status = GenerationStageStatus.PREPARING,
        inputVersionHash = "a".repeat(64),
        idempotencyKey = "idempotency.compatibility",
        maxAttempts = 1,
        inputSourcesJson = inputSourcesJson,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
