package app.zhijuan.core.database.generation

import app.zhijuan.core.database.library.ChapterEditRebuildExecutionStepType
import app.zhijuan.core.model.GenerationPhase
import app.zhijuan.core.model.GenerationStageStatus
import app.zhijuan.core.model.GenerationTargetType
import app.zhijuan.core.task.ChapterContextBudgetSpec
import app.zhijuan.core.task.ChapterContextLimitSource
import app.zhijuan.core.task.FirstChapterGenerationMode
import java.security.MessageDigest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class GenerationRunnerStageRouteResolverTest {
    @Test
    fun memoryV1AndV2ResolveToDistinctFormalAndEditRebuildRoutes() {
        val v1 = memoryStage(rebuildBinding = null)
        val v2 = memoryStage(rebuildBinding = rebuildBinding(ChapterEditRebuildExecutionStepType.EDITED_MEMORY))

        assertEquals(
            GenerationRunnerStageRoute.FORMAL_CHAPTER_MEMORY_V1,
            GenerationRunnerStageRouteResolver.resolve(v1),
        )
        assertEquals(
            GenerationRunnerStageRoute.EDIT_REBUILD_CHAPTER_MEMORY_V2,
            GenerationRunnerStageRouteResolver.resolve(v2),
        )
        assertNotEquals(v1.inputSourcesJson, v2.inputSourcesJson)
    }

    @Test
    fun trackingV1AndV2ResolveToDistinctFormalAndEditRebuildRoutes() {
        val v1 = trackingStage(rebuildBinding = null)
        val v2 = trackingStage(rebuildBinding = rebuildBinding(ChapterEditRebuildExecutionStepType.TRACKING))

        assertEquals(
            GenerationRunnerStageRoute.FORMAL_CHAPTER_TRACKING_V1,
            GenerationRunnerStageRouteResolver.resolve(v1),
        )
        assertEquals(
            GenerationRunnerStageRoute.EDIT_REBUILD_CHAPTER_TRACKING_V2,
            GenerationRunnerStageRouteResolver.resolve(v2),
        )
        assertNotEquals(v1.inputSourcesJson, v2.inputSourcesJson)
    }

    @Test
    fun fiveLegalCandidateCombinationsResolveToTheirUniqueRoutes() {
        val expected = listOf(
            candidateStage(ChapterCandidateArtifactRoleV1.BODY, GenerationPhase.DRAFT_CHAPTER) to
                GenerationRunnerStageRoute.CANDIDATE_CHAPTER_DRAFT_V1,
            candidateStage(ChapterCandidateArtifactRoleV1.MEMORY, GenerationPhase.EXTRACT_MEMORY) to
                GenerationRunnerStageRoute.CANDIDATE_CHAPTER_MEMORY_V1,
            candidateStage(ChapterCandidateArtifactRoleV1.TRACKING, GenerationPhase.EXTRACT_MEMORY) to
                GenerationRunnerStageRoute.CANDIDATE_CHAPTER_TRACKING_V1,
            candidateStage(ChapterCandidateArtifactRoleV1.CONSISTENCY, GenerationPhase.CHECK_CONSISTENCY) to
                GenerationRunnerStageRoute.CANDIDATE_CHAPTER_CONSISTENCY_V1,
            candidateStage(ChapterCandidateArtifactRoleV1.BODY, GenerationPhase.REVISE_CHAPTER) to
                GenerationRunnerStageRoute.CANDIDATE_CHAPTER_REVISION_V1,
        )

        assertEquals(5, expected.map { it.second }.toSet().size)
        expected.forEach { (stage, route) ->
            assertEquals(route, GenerationRunnerStageRouteResolver.resolve(stage))
        }
    }

    @Test
    fun legalFinalCommitV3ResolvesToTheLocalCommitRoute() {
        val commit = finalCommitStage()

        assertEquals(
            GenerationRunnerStageRoute.FINAL_CHAPTER_COMMIT_V3,
            GenerationRunnerStageRouteResolver.resolve(commit),
        )
    }

    @Test
    fun chapterContextAssemblyV1ResolvesToTheUniqueLocalAssemblyRoute() {
        val context = contextAssemblyStage()

        assertEquals(
            GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1,
            GenerationRunnerStageRouteResolver.resolve(context),
        )
    }

    @Test
    fun chapterPlanV1ResolvesToItsDistinctRemoteRoute() {
        val plan = chapterPlanStage()

        assertEquals(
            GenerationRunnerStageRoute.CHAPTER_PLAN_V1,
            GenerationRunnerStageRouteResolver.resolve(plan),
        )
    }

    @Test
    fun chapterPlanPayloadMutatedAwayFromItsIdentityFailsClosed() {
        val plan = chapterPlanStage()

        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(
                mutated(plan) {
                    it.replace(
                        CHAPTER_PLAN_POLICY,
                        "\"sourcePolicyVersion\":\"zhijuan.unknown-policy.v9\"",
                    )
                },
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(plan.copy(phase = GenerationPhase.ASSEMBLE_CONTEXT))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(plan.copy(targetId = "chapter.other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(plan.copy(inputVersionHash = "0".repeat(64)))
        }
    }

    @Test
    fun chapterContextPayloadMutatedAwayFromItsIdentityFailsClosed() {
        val context = contextAssemblyStage()

        // policy string swapped to an unknown identity.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(
                context.copy(
                    inputSourcesJson = context.inputSourcesJson.replace(
                        CONTEXT_POLICY,
                        "\"sourcePolicyVersion\":\"zhijuan.unknown-policy.v9\"",
                    ),
                ),
            )
        }
        // schema version mutated away from v1.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(
                mutated(context) { it.replace("\"schemaVersion\":1", "\"schemaVersion\":3") },
            )
        }
        // Entity phase no longer matches the frozen context binding.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(context.copy(phase = GenerationPhase.BUILD_CHAPTER_PLAN))
        }
        // Entity target no longer matches the frozen context binding.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(context.copy(targetId = "chapter.other"))
        }
        // Frozen input hash no longer matches the payload.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(context.copy(inputVersionHash = "0".repeat(64)))
        }
    }

    @Test
    fun memoryPayloadMutatedTowardTrackingIdentityFailsClosed() {
        val memory = memoryStage(rebuildBinding = null)

        // policy string swapped to the tracking identity.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(
                memory.copy(inputSourcesJson = memory.inputSourcesJson.replace(MEMORY_POLICY, TRACKING_POLICY)),
            )
        }
        // output schema id swapped to the tracking identity.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(
                memory.copy(inputSourcesJson = memory.inputSourcesJson.replace(MEMORY_SCHEMA_ID, TRACKING_SCHEMA_ID)),
            )
        }
        // Entity phase no longer matches the frozen memory binding.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(memory.copy(phase = GenerationPhase.CHECK_CONSISTENCY))
        }
        // Entity target no longer matches the frozen memory binding.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(memory.copy(targetId = "chapter.other"))
        }
        // Frozen input hash no longer matches the payload.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(memory.copy(inputVersionHash = "0".repeat(64)))
        }
    }

    @Test
    fun trackingPayloadMutatedTowardMemoryIdentityFailsClosed() {
        val tracking = trackingStage(rebuildBinding = null)

        // policy string swapped to the memory identity.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(
                tracking.copy(inputSourcesJson = tracking.inputSourcesJson.replace(TRACKING_POLICY, MEMORY_POLICY)),
            )
        }
        // output schema id swapped to the memory identity.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(
                tracking.copy(inputSourcesJson = tracking.inputSourcesJson.replace(TRACKING_SCHEMA_ID, MEMORY_SCHEMA_ID)),
            )
        }
        // Entity phase no longer matches the frozen tracking binding.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(tracking.copy(phase = GenerationPhase.DRAFT_CHAPTER))
        }
        // Entity target no longer matches the frozen tracking binding.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(tracking.copy(targetId = "chapter.other"))
        }
        // Frozen input hash no longer matches the payload.
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(tracking.copy(inputVersionHash = "0".repeat(64)))
        }
    }

    @Test
    fun unknownMissingOrMalformedPolicyPayloadsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(
                rawStage("""{"schemaVersion":1,"sourcePolicyVersion":"zhijuan.unknown-policy.v9"}"""),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(rawStage("{}"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(rawStage("[1,2,3]"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(rawStage("""{"sourcePolicyVersion":42}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(rawStage("{"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(
                rawStage("""{"sourcePolicyVersion":"zhijuan.chapter-memory-source.v1"}"""),
            )
        }
    }

    @Test
    fun unsupportedSchemaAndExtraRootFieldsFailClosed() {
        val memory = memoryStage(rebuildBinding = null)

        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(
                memory.copy(inputSourcesJson = memory.inputSourcesJson.replace("\"schemaVersion\":1", "\"schemaVersion\":3")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(memory.copy(inputSourcesJson = withExtraRootField(memory.inputSourcesJson)))
        }
    }

    @Test
    fun candidateRolePhaseMismatchFailsClosed() {
        val memoryCandidate = candidateStage(ChapterCandidateArtifactRoleV1.MEMORY, GenerationPhase.EXTRACT_MEMORY)
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(memoryCandidate.copy(phase = GenerationPhase.CHECK_CONSISTENCY))
        }
        val draftCandidate = candidateStage(ChapterCandidateArtifactRoleV1.BODY, GenerationPhase.DRAFT_CHAPTER)
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(draftCandidate.copy(phase = GenerationPhase.EXTRACT_MEMORY))
        }
    }

    @Test
    fun finalCommitWithWrongPhaseTargetOrAttemptsFailsClosed() {
        val commit = finalCommitStage()

        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(commit.copy(phase = GenerationPhase.EXTRACT_MEMORY))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(commit.copy(targetId = "chapter.other"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(commit.copy(targetType = GenerationTargetType.BOOK))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(commit.copy(maxAttempts = 2))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GenerationRunnerStageRouteResolver.resolve(commit.copy(inputVersionHash = "0".repeat(64)))
        }
    }

    @Test
    fun routeEnumToStringCarriesNoStageOrPayloadIdentity() {
        assertEquals("FINAL_CHAPTER_COMMIT_V3", GenerationRunnerStageRoute.FINAL_CHAPTER_COMMIT_V3.toString())
        assertEquals("CANDIDATE_CHAPTER_MEMORY_V1", GenerationRunnerStageRoute.CANDIDATE_CHAPTER_MEMORY_V1.toString())
        assertEquals(
            "CHAPTER_CONTEXT_ASSEMBLY_V1",
            GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1.toString(),
        )
        assertEquals("CHAPTER_PLAN_V1", GenerationRunnerStageRoute.CHAPTER_PLAN_V1.toString())
    }

    private fun memoryStage(rebuildBinding: ChapterEditRebuildStageBindingV1?): GenerationStageEntity {
        val setup = ChapterMemoryExtractionJobFactory.create(
            ChapterMemoryExtractionJobSpec(
                jobId = "job.memory.test",
                stageId = "stage.memory.test",
                bookId = "book.test",
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                source = ChapterMemoryExtractionSourceV1(
                    chapterVersionId = "chapter.version.1",
                    chapterContentHash = "a".repeat(64),
                    chapterId = "chapter.1",
                    chapterIndex = 1,
                ),
                rebuildBinding = rebuildBinding,
                createdAt = 1L,
            ),
        )
        return setup.stages.single().toEntity()
    }

    private fun trackingStage(rebuildBinding: ChapterEditRebuildStageBindingV1?): GenerationStageEntity {
        val setup = ChapterTrackingProjectionJobFactory.create(
            ChapterTrackingProjectionJobSpec(
                jobId = "job.tracking.test",
                stageId = "stage.tracking.test",
                bookId = "book.test",
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                source = ChapterTrackingProjectionSourceV1(
                    chapterVersionId = "chapter.version.1",
                    chapterContentHash = "a".repeat(64),
                    chapterId = "chapter.1",
                    chapterIndex = 1,
                    memorySnapshotHash = "b".repeat(64),
                    priorForeshadowSnapshotHash = "c".repeat(64),
                    knownEntitySnapshotHash = "d".repeat(64),
                ),
                rebuildBinding = rebuildBinding,
                createdAt = 1L,
            ),
        )
        return setup.stages.single().toEntity()
    }

    private fun rebuildBinding(stepType: ChapterEditRebuildExecutionStepType) = ChapterEditRebuildStageBindingV1(
        executionId = "execution.one",
        stableFenceHash = "e".repeat(64),
        stepOrdinal = 1,
        stepType = stepType,
        chapterIndex = 1,
        sourceChapterVersionId = "chapter.version.1",
        sourceContentHash = "a".repeat(64),
    )

    private fun candidateStage(
        role: ChapterCandidateArtifactRoleV1,
        phase: GenerationPhase,
    ): GenerationStageEntity {
        val source = ChapterCandidateStageSourceV1(
            role = role,
            candidateChapterVersionId = "candidate.version.1",
            candidateContentHash = "c".repeat(64),
            chapterId = "chapter.1",
            chapterIndex = 1,
            revisionIndex = 0,
            predecessorStageId = "stage.predecessor.1",
            routeBindingHash = if (role == ChapterCandidateArtifactRoleV1.BODY) "d".repeat(64) else null,
            requestSourceBindingHash = if (role == ChapterCandidateArtifactRoleV1.BODY) "e".repeat(64) else null,
        )
        return ChapterCandidateStageBindingV1.stageSetup(
            jobId = "job.candidate.test",
            stageId = "stage.candidate.test",
            phase = phase,
            source = source,
            maxAttempts = 1,
        ).toEntity()
    }

    private fun finalCommitStage(): GenerationStageEntity {
        val sourceBindingHash = "1".repeat(64)
        val snapshot = consistencyMappingSnapshot(sourceBindingHash)
        val source = ChapterFinalCommitStageSourceV1(
            candidateChapterVersionId = "candidate.version.1",
            candidateContentHash = "c".repeat(64),
            chapterId = "chapter.1",
            chapterIndex = 1,
            revisionIndex = 0,
            predecessorStageId = "stage.predecessor.1",
            routeBindingHash = "d".repeat(64),
            expectedCurrentVersionId = "chapter.version.1",
            maximumAutomaticRevisions = 1,
            candidateContentHashHistory = listOf("c".repeat(64)),
            consistencyRequestSourceBindingHash = sourceBindingHash,
            consistencyMappingSnapshotJson = snapshot,
            consistencyMappingSnapshotContentHash = sha256Hex(snapshot),
        )
        return ChapterFinalCommitStageBindingV1.stageSetup(
            jobId = "job.commit.test",
            stageId = "stage.commit.test",
            source = source,
        ).toEntity()
    }

    private fun consistencyMappingSnapshot(sourceBindingHash: String): String = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(1),
            "schemaId" to JsonPrimitive("zhijuan.chapter-final-consistency-mapping.v1"),
            "consistencyRequestSourceBindingHash" to JsonPrimitive(sourceBindingHash),
            "minimumBodyCodePoints" to JsonPrimitive(100),
            "totalRevisionAttemptsUsed" to JsonPrimitive(0),
            "revisionStageMaximumAttempts" to JsonPrimitive(2),
            "localReport" to JsonObject(emptyMap()),
            "expectation" to JsonObject(emptyMap()),
            "sceneContract" to JsonObject(emptyMap()),
        ),
    ).toString()

    private fun contextAssemblyStage(): GenerationStageEntity {
        val setup = ChapterContextAssemblyJobFactory.create(
            ChapterContextAssemblyJobSpec(
                jobId = "job.context.test",
                bookId = "book.test",
                chapterId = "chapter.2",
                chapterIndex = 2,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                promptBindingHash = "a".repeat(64),
                contextBudget = ChapterContextBudgetSpec(
                    contextLimitTokens = 32_768,
                    maximumOutputTokens = 4_096,
                    requestedOutputTokens = 4_096,
                    limitSource = ChapterContextLimitSource.OFFICIAL_METADATA,
                    unknownLimitConfirmed = false,
                    tokenizerFamily = "TEST",
                ),
                progressionPermit = contextPermit(),
                stageIds = ChapterContextAssemblyStageIds("stage.context.test", "stage.plan.test"),
                createdAt = 1L,
            ),
        )
        return setup.stages.first().toEntity()
    }

    private fun chapterPlanStage(): GenerationStageEntity {
        val setup = ChapterContextAssemblyJobFactory.create(
            ChapterContextAssemblyJobSpec(
                jobId = "job.context.test",
                bookId = "book.test",
                chapterId = "chapter.2",
                chapterIndex = 2,
                userIntentJson = "{}",
                budgetSnapshotJson = "{}",
                promptBindingHash = "a".repeat(64),
                contextBudget = ChapterContextBudgetSpec(
                    contextLimitTokens = 32_768,
                    maximumOutputTokens = 4_096,
                    requestedOutputTokens = 4_096,
                    limitSource = ChapterContextLimitSource.OFFICIAL_METADATA,
                    unknownLimitConfirmed = false,
                    tokenizerFamily = "TEST",
                ),
                progressionPermit = contextPermit(),
                stageIds = ChapterContextAssemblyStageIds("stage.context.test", "stage.plan.test"),
                createdAt = 1L,
            ),
        )
        return setup.stages.last().toEntity()
    }

    private fun contextPermit(): ChapterProgressionPermit {
        val base = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "policyVersion" to JsonPrimitive("zhijuan.first-chapter-progression.v1"),
                "mode" to JsonPrimitive(FirstChapterGenerationMode.FULL_PLANNING.name),
                "bookId" to JsonPrimitive("book.test"),
                "chapterId" to JsonPrimitive("chapter.2"),
                "chapterIndex" to JsonPrimitive(2),
            ),
        )
        return ChapterProgressionPermit(
            JsonObject(base + ("evidenceHash" to JsonPrimitive(sha256Hex(base.toString())))),
        )
    }

    private fun rawStage(inputSourcesJson: String) = GenerationStageEntity(
        stageId = "stage.raw",
        jobId = "job.raw",
        phase = GenerationPhase.DRAFT_CHAPTER,
        targetType = GenerationTargetType.CHAPTER,
        targetId = "chapter.1",
        status = GenerationStageStatus.PENDING,
        inputVersionHash = "a".repeat(64),
        idempotencyKey = "idempotency.raw",
        maxAttempts = 1,
        inputSourcesJson = inputSourcesJson,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun mutated(stage: GenerationStageEntity, mutate: (String) -> String): GenerationStageEntity {
        val json = mutate(stage.inputSourcesJson)
        return stage.copy(inputSourcesJson = json, inputVersionHash = sha256Hex(json))
    }

    private fun withExtraRootField(json: String): String {
        val close = json.lastIndexOf('}')
        return json.substring(0, close) + ",\"extraField\":1" + json.substring(close)
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun GenerationStageSetup.toEntity() = GenerationStageEntity(
        stageId = stageId,
        jobId = "job.test",
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

    private companion object {
        const val MEMORY_POLICY = "\"sourcePolicyVersion\":\"zhijuan.chapter-memory-source.v1\""
        const val TRACKING_POLICY = "\"sourcePolicyVersion\":\"zhijuan.chapter-tracking-source.v1\""
        const val CONTEXT_POLICY = "\"sourcePolicyVersion\":\"zhijuan.chapter-context-assembly-source.v1\""
        const val CHAPTER_PLAN_POLICY = "\"sourcePolicyVersion\":\"zhijuan.chapter-plan-source.v1\""
        const val MEMORY_SCHEMA_ID = "\"outputSchemaId\":\"chapter-memory.v1\""
        const val TRACKING_SCHEMA_ID = "\"outputSchemaId\":\"chapter-story-tracking.v1\""
    }
}
