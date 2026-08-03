package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.task.ChapterContextBudgetPolicyV1
import app.zhijuan.core.task.ChapterContextBudgetSpec
import app.zhijuan.core.task.ChapterContextLimitSource
import app.zhijuan.core.task.FirstChapterGenerationMode
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
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
}
