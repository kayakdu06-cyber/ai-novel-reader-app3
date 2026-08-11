package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.ChapterContextBudgetPolicyV1
import app.zhijuan.core.task.ChapterContextBudgetSpec
import app.zhijuan.core.task.ChapterContextLimitSource
import app.zhijuan.core.task.FirstChapterGenerationMode
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterContextAssemblyJobFactoryTest {
    @Test
    fun `creates local context then remote chapter-plan stages with one frozen gate`() {
        val setup = ChapterContextAssemblyJobFactory.create(spec())

        assertEquals(
            listOf(GenerationPhase.ASSEMBLE_CONTEXT, GenerationPhase.BUILD_CHAPTER_PLAN),
            setup.stages.map { it.phase },
        )
        assertEquals(1, setup.stages.first().maxAttempts)
        assertTrue(setup.stages.all { it.inputSourcesJson.contains("\"chapterProgressionGate\"") })
        assertTrue(setup.stages.first().inputSourcesJson.contains(ChapterContextBudgetPolicyV1.POLICY_VERSION))
        assertTrue(setup.stages.last().inputSourcesJson.contains("\"contextAssemblyStageId\":\"context-stage\""))
    }

    @Test
    fun `budget or user addition changes context and downstream plan idempotency`() {
        val first = ChapterContextAssemblyJobFactory.create(spec())
        val changedBudget = ChapterContextAssemblyJobFactory.create(
            spec().copy(contextBudget = budget().copy(contextLimitTokens = 65_536)),
        )
        val changedUserText = ChapterContextAssemblyJobFactory.create(
            spec().copy(userAddition = "本章增加一场雨"),
        )

        first.stages.indices.forEach { index ->
            assertNotEquals(first.stages[index].idempotencyKey, changedBudget.stages[index].idempotencyKey)
            assertNotEquals(first.stages[index].idempotencyKey, changedUserText.stages[index].idempotencyKey)
        }
    }

    @Test
    fun `invalid prompt hash or oversized user addition fails before persistence`() {
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.create(spec().copy(promptBindingHash = "bad"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.create(spec().copy(userAddition = "字".repeat(6_000)))
        }
    }

    @Test
    fun `context and chapter plan stages carry distinct source policies`() {
        val setup = ChapterContextAssemblyJobFactory.create(spec())
        val contextStage = setup.stages.first().toEntity()
        val planStage = setup.stages.last().toEntity()

        assertTrue(
            contextStage.inputSourcesJson.contains(
                "\"sourcePolicyVersion\":\"${ChapterContextAssemblyJobFactory.SOURCE_POLICY_VERSION}\"",
            ),
        )
        assertTrue(
            planStage.inputSourcesJson.contains(
                "\"sourcePolicyVersion\":\"${ChapterContextAssemblyJobFactory.CHAPTER_PLAN_SOURCE_POLICY_VERSION}\"",
            ),
        )
        assertFalse(planStage.inputSourcesJson.contains(ChapterContextAssemblyJobFactory.SOURCE_POLICY_VERSION))
    }

    @Test
    fun `frozen context source parses and resolves to the unique route`() {
        val stage = ChapterContextAssemblyJobFactory.create(spec()).stages.first().toEntity()

        val source = ChapterContextAssemblyJobFactory.parseAndVerify(stage)
        assertEquals(2, source.targetChapterIndex)
        assertEquals("a".repeat(64), source.promptBindingHash)
        assertEquals(32_768, source.budget.contextLimitTokens)
        assertEquals(ChapterContextLimitSource.OFFICIAL_METADATA, source.budget.limitSource)
        assertFalse(source.toString().contains("TEST"))
        assertFalse(source.toString().contains("32768"))
        assertEquals(
            GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1,
            GenerationRunnerStageRouteResolver.resolve(stage),
        )
    }

    @Test
    fun `corrupted frozen context contracts fail closed`() {
        val base = ChapterContextAssemblyJobFactory.create(spec()).stages.first().toEntity()
        val mutated = { mutate: (String) -> String ->
            val json = mutate(base.inputSourcesJson)
            base.copy(inputSourcesJson = json, inputVersionHash = hash(json))
        }

        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(base.copy(phase = GenerationPhase.DRAFT_CHAPTER))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(base.copy(targetType = GenerationTargetType.BOOK))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(base.copy(maxAttempts = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(base.copy(inputVersionHash = "0".repeat(64)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(
                mutated { it.replace("\"schemaVersion\":1", "\"schemaVersion\":2") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(
                mutated {
                    it.replace(
                        "\"sourcePolicyVersion\":\"${ChapterContextAssemblyJobFactory.SOURCE_POLICY_VERSION}\"",
                        "\"sourcePolicyVersion\":\"zhijuan.unknown-source.v9\"",
                    )
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(
                mutated { it.replace("\"dependencyStageIds\":[]", "\"dependencyStageIds\":[\"context-stage\"]") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(
                mutated { it.replace("\"userAddition\":null}", "\"userAddition\":null,\"extraKey\":1}") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(
                mutated {
                    it.replace("\"targetPhase\":\"BUILD_CHAPTER_PLAN\"", "\"targetPhase\":\"DRAFT_CHAPTER\"")
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(
                mutated { it.replace("\"contextLimitTokens\":32768", "\"contextLimitTokens\":512") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(
                mutatedWithRehashedProgression(base) {
                    it.replace("\"chapterIndex\":2", "\"chapterIndex\":3")
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(
                mutated { it.replace(EVIDENCE_HASH_JSON, "\"evidenceHash\":\"" + "g".repeat(64) + "\"") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(
                mutated { it.replace(EVIDENCE_HASH_JSON, "\"evidenceHash\":\"" + "0".repeat(64) + "\"") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerify(mutated { withExtraRootField(it) })
        }
    }

    @Test
    fun `frozen chapter plan source parses and resolves to its unregistered route`() {
        val stage = ChapterContextAssemblyJobFactory.create(spec()).stages.last().toEntity()

        val source = ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(stage)

        assertEquals("context-stage", source.contextAssemblyStageId)
        assertEquals(2, source.targetChapterIndex)
        assertEquals(64, source.contextInputVersionHash.length)
        assertEquals(64, source.progressionEvidenceHash.length)
        assertFalse(source.toString().contains(source.contextAssemblyStageId))
        assertFalse(source.toString().contains(source.contextInputVersionHash))
        assertEquals(
            GenerationRunnerStageRoute.CHAPTER_PLAN_V1,
            GenerationRunnerStageRouteResolver.resolve(stage),
        )
    }

    @Test
    fun `corrupted frozen chapter plan contracts fail closed`() {
        val base = ChapterContextAssemblyJobFactory.create(spec()).stages.last().toEntity()
        val mutated = { mutate: (String) -> String ->
            val json = mutate(base.inputSourcesJson)
            base.copy(inputSourcesJson = json, inputVersionHash = hash(json))
        }

        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                base.copy(phase = GenerationPhase.DRAFT_CHAPTER),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                base.copy(targetType = GenerationTargetType.BOOK),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(base.copy(maxAttempts = 5))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                base.copy(inputVersionHash = "0".repeat(64)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                mutated { it.replace("\"schemaVersion\":1", "\"schemaVersion\":2") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                mutated {
                    it.replace(
                        "\"sourcePolicyVersion\":\"${ChapterContextAssemblyJobFactory.CHAPTER_PLAN_SOURCE_POLICY_VERSION}\"",
                        "\"sourcePolicyVersion\":\"zhijuan.unknown-source.v9\"",
                    )
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                mutated { it.replace("\"dependencyStageIds\":[\"context-stage\"]", "\"dependencyStageIds\":[]") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                mutated { it.replace("\"contextAssemblyStageId\":\"context-stage\"", "\"contextAssemblyStageId\":\"other-stage\"") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                mutated { it.replace("\"contextInputVersionHash\":\"", "\"contextInputVersionHash\":\"bad") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                mutated { withExtraRootField(it) },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                mutatedWithRehashedProgression(base) { it.replace("\"chapterId\":\"chapter-2\"", "\"chapterId\":\"chapter-3\"") },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(
                mutatedWithRehashedProgression(base) { it.replace("\"chapterIndex\":2", "\"chapterIndex\":0") },
            )
        }
    }

    private fun spec() = ChapterContextAssemblyJobSpec(
        jobId = "chapter-context-job",
        bookId = "book-1",
        chapterId = "chapter-2",
        chapterIndex = 2,
        userIntentJson = "{}",
        budgetSnapshotJson = "{}",
        promptBindingHash = "a".repeat(64),
        contextBudget = budget(),
        progressionPermit = permit(),
        stageIds = ChapterContextAssemblyStageIds("context-stage", "chapter-plan-stage"),
        createdAt = 20L,
    )

    private fun budget() = ChapterContextBudgetSpec(
        contextLimitTokens = 32_768,
        maximumOutputTokens = 4_096,
        requestedOutputTokens = 4_096,
        limitSource = ChapterContextLimitSource.OFFICIAL_METADATA,
        unknownLimitConfirmed = false,
        tokenizerFamily = "TEST",
    )

    private fun permit(): ChapterProgressionPermit {
        val base = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "policyVersion" to JsonPrimitive("zhijuan.first-chapter-progression.v1"),
                "mode" to JsonPrimitive(FirstChapterGenerationMode.FULL_PLANNING.name),
                "bookId" to JsonPrimitive("book-1"),
                "chapterId" to JsonPrimitive("chapter-2"),
                "chapterIndex" to JsonPrimitive(2),
            ),
        )
        return ChapterProgressionPermit(
            JsonObject(base + ("evidenceHash" to JsonPrimitive(hash(base.toString())))),
        )
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun GenerationStageSetup.toEntity() = GenerationStageEntity(
        stageId = stageId,
        jobId = "chapter-context-job",
        phase = phase,
        targetType = targetType,
        targetId = targetId,
        status = GenerationStageStatus.PENDING,
        inputVersionHash = inputVersionHash,
        idempotencyKey = idempotencyKey,
        maxAttempts = maxAttempts,
        inputSourcesJson = inputSourcesJson,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun withExtraRootField(json: String): String {
        val close = json.lastIndexOf('}')
        return json.substring(0, close) + ",\"extraField\":1" + json.substring(close)
    }

    private fun mutatedWithRehashedProgression(
        stage: GenerationStageEntity,
        mutate: (String) -> String,
    ): GenerationStageEntity {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(stage.inputSourcesJson) as JsonObject
        val progression = root.getValue("chapterProgressionGate") as JsonObject
        val withoutHash = JsonObject(progression - "evidenceHash").toString()
        val changedWithoutHash =
            kotlinx.serialization.json.Json.parseToJsonElement(mutate(withoutHash)) as JsonObject
        val changedProgression = JsonObject(
            changedWithoutHash + ("evidenceHash" to JsonPrimitive(hash(changedWithoutHash.toString()))),
        )
        val changedInput = JsonObject(root + ("chapterProgressionGate" to changedProgression)).toString()
        return stage.copy(inputSourcesJson = changedInput, inputVersionHash = hash(changedInput))
    }

    private companion object {
        val EVIDENCE_HASH_JSON = Regex("\"evidenceHash\":\"[0-9a-f]{64}\"")
    }
}
