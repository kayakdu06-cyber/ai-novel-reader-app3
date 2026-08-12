package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationJobType
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.PromptBundleCatalogV1
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ChapterPlanV2FrozenSourcesTest {
    @Test
    fun `freeze canonicalizes manifests and binds authority hashes`() {
        val first = freeze(
            expectation = """{"contextEvidenceHash":"$HASH_C","policyCompilationHash":"$HASH_B","activationHash":"$HASH_A","chapterId":"chapter-2"}""",
            activation = """{"capabilities":["core-narrative","character-continuity"],"activationHash":"$HASH_A"}""",
            policy = """{"selected":["policy.core-narrative.v1"],"policyCompilationHash":"$HASH_B"}""",
        )
        val second = freeze(
            expectation = """{"chapterId":"chapter-2","activationHash":"$HASH_A","policyCompilationHash":"$HASH_B","contextEvidenceHash":"$HASH_C"}""",
            activation = """{"activationHash":"$HASH_A","capabilities":["core-narrative","character-continuity"]}""",
            policy = """{"policyCompilationHash":"$HASH_B","selected":["policy.core-narrative.v1"]}""",
        )
        assertEquals(first.expectationHash, second.expectationHash)
        assertEquals(first.activationManifestHash, second.activationManifestHash)
        assertEquals(first.policyManifestHash, second.policyManifestHash)
    }

    @Test
    fun `freeze rejects a manifest that disagrees with authority hash`() {
        assertThrows(IllegalArgumentException::class.java) {
            freeze(
                expectation = """{"activationHash":"$HASH_A","policyCompilationHash":"$HASH_B","contextEvidenceHash":"$HASH_C"}""",
                activation = """{"activationHash":"$HASH_D"}""",
                policy = """{"policyCompilationHash":"$HASH_B"}""",
            )
        }
    }

    @Test
    fun `v2 route verifies every frozen manifest and retains v1 compatibility`() {
        val frozen = freeze(
            expectation = """{"activationHash":"$HASH_A","policyCompilationHash":"$HASH_B","contextEvidenceHash":"$HASH_C"}""",
            activation = """{"activationHash":"$HASH_A"}""",
            policy = """{"policyCompilationHash":"$HASH_B"}""",
        )
        val v1Source = JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "sourcePolicyVersion" to JsonPrimitive(ChapterContextAssemblyJobFactory.CHAPTER_PLAN_SOURCE_POLICY_VERSION),
            "promptBundleVersion" to JsonPrimitive(PromptBundleCatalogV1.BUNDLE_VERSION),
            "outputSchemaId" to JsonPrimitive(ChapterContextAssemblyJobFactory.CHAPTER_PLAN_SCHEMA_ID),
            "dependencyStageIds" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("context-stage"))),
            "contextAssemblyStageId" to JsonPrimitive("context-stage"),
            "contextInputVersionHash" to JsonPrimitive(HASH_D),
            "contextPolicyVersion" to JsonPrimitive(app.zhijuan.core.task.ChapterContextBudgetPolicyV1.POLICY_VERSION),
            "contextManifestSchemaId" to JsonPrimitive(app.zhijuan.core.task.ChapterContextBudgetPolicyV1.MANIFEST_SCHEMA_ID),
            "chapterProgressionGate" to progression(),
        )).toString()
        val setup = GenerationJobSetup(
            jobId = "job-2", bookId = "book-1", jobType = GenerationJobType.CONTINUE_BOOK,
            userIntentJson = "{}", budgetSnapshotJson = "{}",
            promptBundleVersion = PromptBundleCatalogV1.BUNDLE_VERSION,
            stages = listOf(stageSetup(v1Source)), createdAt = 10L,
        )
        val upgraded = ChapterPlanV2StageBinding.bind(setup, frozen).stages.single()
        assertEquals(GenerationRunnerStageRoute.CHAPTER_PLAN_V2, GenerationRunnerStageRouteResolver.resolve(entity(upgraded)))
        assertEquals(GenerationRunnerStageRoute.CHAPTER_PLAN_V1, GenerationRunnerStageRouteResolver.resolve(entity(setup.stages.single())))

        val tampered = upgraded.copy(inputSourcesJson = upgraded.inputSourcesJson.replace(HASH_A, HASH_D))
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(entity(tampered))
        }
    }

    private fun freeze(expectation: String, activation: String, policy: String) =
        ChapterPlanV2FrozenSources.freeze(
            expectationJson = expectation,
            activationManifestJson = activation,
            activationHash = HASH_A,
            policyManifestJson = policy,
            policyCompilationHash = HASH_B,
            contextEvidenceHash = HASH_C,
        )

    private fun progression(): JsonObject {
        val base = linkedMapOf<String, kotlinx.serialization.json.JsonElement>(
            "mode" to JsonPrimitive("FULL_PLANNING"),
            "chapterId" to JsonPrimitive("chapter-2"),
            "chapterIndex" to JsonPrimitive(2),
        )
        return JsonObject(base + ("evidenceHash" to JsonPrimitive(sha256(JsonObject(base).toString()))))
    }

    private fun stageSetup(source: String): GenerationStageSetup = GenerationStageSetup(
        stageId = "plan-stage", phase = GenerationPhase.BUILD_CHAPTER_PLAN,
        targetType = GenerationTargetType.CHAPTER, targetId = "chapter-2",
        inputVersionHash = sha256(source), idempotencyKey = "idempotency-plan", maxAttempts = 2,
        inputSourcesJson = source,
    )

    private fun entity(stage: GenerationStageSetup) = GenerationStageEntity(
        stageId = stage.stageId, jobId = "job-2", phase = stage.phase, targetType = stage.targetType,
        targetId = stage.targetId, status = GenerationStageStatus.PENDING,
        inputVersionHash = stage.inputVersionHash, idempotencyKey = stage.idempotencyKey,
        maxAttempts = stage.maxAttempts, inputSourcesJson = stage.inputSourcesJson,
        createdAt = 10L, updatedAt = 10L,
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
