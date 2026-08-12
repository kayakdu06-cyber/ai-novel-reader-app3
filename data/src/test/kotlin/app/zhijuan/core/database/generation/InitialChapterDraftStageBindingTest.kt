package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test

class InitialChapterDraftStageBindingTest {
    @Test
    fun `route accepts an exact request-preexisting initial draft source`() {
        val stage = stage(source())
        val parsed = InitialChapterDraftStageBinding.parseAndVerify(stage)
        assertEquals("plan-stage", parsed.planStageId)
        assertEquals(GenerationRunnerStageRoute.INITIAL_CHAPTER_DRAFT_V1, GenerationRunnerStageRouteResolver.resolve(stage))
    }

    @ParameterizedTest
    @ValueSource(strings = ["source", "plan", "context"])
    fun `source plan and context drift fail before route execution`(drift: String) {
        val original = source()
        val changed = when (drift) {
            "source" -> original.replace("chapter-draft.v1", "chapter-draft.v2")
            "plan" -> original.replace(HASH_A, HASH_D)
            else -> original.replace(HASH_C, HASH_D)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(stage(changed).copy(inputVersionHash = sha256(changed)))
        }
    }

    private fun source(): String {
        val plan = JsonObject(linkedMapOf(
            "activationHash" to JsonPrimitive(HASH_A),
            "chapterId" to JsonPrimitive("chapter-2"),
            "chapterIndex" to JsonPrimitive(2),
            "contextContentHash" to JsonPrimitive(HASH_D),
            "contextEvidenceHash" to JsonPrimitive(HASH_C),
            "contextSourceManifestHash" to JsonPrimitive(HASH_D),
            "policyCompilationHash" to JsonPrimitive(HASH_B),
        ))
        return JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourcePolicyVersion" to JsonPrimitive(InitialChapterDraftStageBinding.SOURCE_POLICY_VERSION),
            "outputSchemaId" to JsonPrimitive(InitialChapterDraftStageBinding.OUTPUT_SCHEMA_ID),
            "dependencyStageIds" to JsonArray(listOf(JsonPrimitive("plan-stage"))),
            "planStageId" to JsonPrimitive("plan-stage"),
            "planAttemptId" to JsonPrimitive("plan-attempt"),
            "planArtifactRefId" to JsonPrimitive("11111111-1111-1111-1111-111111111111"),
            "planArtifactRevision" to JsonPrimitive(1),
            "planRawOutputHash" to JsonPrimitive(HASH_D),
            "canonicalPlanHash" to JsonPrimitive(sha256(plan.toString())),
            "canonicalPlan" to plan,
            "requestBindingHash" to JsonPrimitive(HASH_D),
            "expectationHash" to JsonPrimitive(HASH_D),
            "activationManifestHash" to JsonPrimitive(HASH_D),
            "activationHash" to JsonPrimitive(HASH_A),
            "policyManifestHash" to JsonPrimitive(HASH_D),
            "policyCompilationHash" to JsonPrimitive(HASH_B),
            "contextEvidenceHash" to JsonPrimitive(HASH_C),
        )).toString()
    }

    private fun stage(source: String) = GenerationStageEntity(
        stageId = "draft-stage", jobId = "job-2", phase = GenerationPhase.DRAFT_CHAPTER,
        targetType = GenerationTargetType.CHAPTER, targetId = "chapter-2",
        status = GenerationStageStatus.READY, inputVersionHash = sha256(source),
        idempotencyKey = "draft-idempotency", maxAttempts = 2,
        inputSourcesJson = source, createdAt = 10L, updatedAt = 10L,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val HASH_A = "a".repeat(64)
        val HASH_B = "b".repeat(64)
        val HASH_C = "c".repeat(64)
        val HASH_D = "d".repeat(64)
    }
}
