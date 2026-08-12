package app.zhijuan.core.database.generation

import app.zhijuan.core.model.GenerationPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Finite route identity of one frozen derived-chain Stage. The enum name is the
 * only identity; it carries no Stage, Job, target, hash, or payload.
 */
enum class GenerationRunnerStageRoute {
    FORMAL_CHAPTER_MEMORY_V1,
    EDIT_REBUILD_CHAPTER_MEMORY_V2,
    FORMAL_CHAPTER_TRACKING_V1,
    EDIT_REBUILD_CHAPTER_TRACKING_V2,
    CANDIDATE_CHAPTER_DRAFT_V1,
    CANDIDATE_CHAPTER_MEMORY_V1,
    CANDIDATE_CHAPTER_TRACKING_V1,
    CANDIDATE_CHAPTER_CONSISTENCY_V1,
    CANDIDATE_CHAPTER_REVISION_V1,
    CHAPTER_CONTEXT_ASSEMBLY_V1,
    CHAPTER_PLAN_V1,
    CHAPTER_PLAN_V2,
    FINAL_CHAPTER_COMMIT_V3,
}

internal val CHAPTER_PLAN_ROUTES = setOf(
    GenerationRunnerStageRoute.CHAPTER_PLAN_V1,
    GenerationRunnerStageRoute.CHAPTER_PLAN_V2,
)

/**
 * Resolves the route identity of a frozen derived-chain Stage without any
 * database, file, or provider side effect. Only [sourcePolicyVersion] is read
 * here to choose the authoritative parser; every field-level check (schema,
 * root keys, phase/target, targetId, inputVersionHash, binding/hash) is
 * delegated to the frozen policy parser, whose failure propagates unchanged.
 * Unknown, malformed, or conflicting inputs fail closed.
 */
internal object GenerationRunnerStageRouteResolver {
    fun resolve(stage: GenerationStageEntity): GenerationRunnerStageRoute {
        val sourcePolicyVersion = sourcePolicyVersion(stage)
        return when (sourcePolicyVersion) {
            ChapterMemoryExtractionJobFactory.SOURCE_POLICY_VERSION -> {
                ChapterMemoryExtractionJobFactory.parseAndVerify(stage)
                if (ChapterMemoryExtractionJobFactory.parseRebuildBindingIfPresent(stage) != null) {
                    GenerationRunnerStageRoute.EDIT_REBUILD_CHAPTER_MEMORY_V2
                } else {
                    GenerationRunnerStageRoute.FORMAL_CHAPTER_MEMORY_V1
                }
            }
            ChapterTrackingProjectionJobFactory.SOURCE_POLICY_VERSION -> {
                ChapterTrackingProjectionJobFactory.parseAndVerify(stage)
                if (ChapterTrackingProjectionJobFactory.parseRebuildBindingIfPresent(stage) != null) {
                    GenerationRunnerStageRoute.EDIT_REBUILD_CHAPTER_TRACKING_V2
                } else {
                    GenerationRunnerStageRoute.FORMAL_CHAPTER_TRACKING_V1
                }
            }
            ChapterCandidateStageBindingV1.SOURCE_POLICY_VERSION -> {
                val source = ChapterCandidateStageBindingV1.parseAndVerify(stage)
                when {
                    source.role == ChapterCandidateArtifactRoleV1.BODY &&
                        stage.phase == GenerationPhase.DRAFT_CHAPTER ->
                        GenerationRunnerStageRoute.CANDIDATE_CHAPTER_DRAFT_V1
                    source.role == ChapterCandidateArtifactRoleV1.MEMORY &&
                        stage.phase == GenerationPhase.EXTRACT_MEMORY ->
                        GenerationRunnerStageRoute.CANDIDATE_CHAPTER_MEMORY_V1
                    source.role == ChapterCandidateArtifactRoleV1.TRACKING &&
                        stage.phase == GenerationPhase.EXTRACT_MEMORY ->
                        GenerationRunnerStageRoute.CANDIDATE_CHAPTER_TRACKING_V1
                    source.role == ChapterCandidateArtifactRoleV1.CONSISTENCY &&
                        stage.phase == GenerationPhase.CHECK_CONSISTENCY ->
                        GenerationRunnerStageRoute.CANDIDATE_CHAPTER_CONSISTENCY_V1
                    source.role == ChapterCandidateArtifactRoleV1.BODY &&
                        stage.phase == GenerationPhase.REVISE_CHAPTER ->
                        GenerationRunnerStageRoute.CANDIDATE_CHAPTER_REVISION_V1
                    else -> throw IllegalArgumentException(
                        "Candidate Stage role and phase are not a supported route.",
                    )
                }
            }
            ChapterFinalCommitStageBindingV1.SOURCE_POLICY_VERSION -> {
                ChapterFinalCommitStageBindingV1.parseAndVerify(stage)
                GenerationRunnerStageRoute.FINAL_CHAPTER_COMMIT_V3
            }
            ChapterContextAssemblyJobFactory.SOURCE_POLICY_VERSION -> {
                ChapterContextAssemblyJobFactory.parseAndVerify(stage)
                GenerationRunnerStageRoute.CHAPTER_CONTEXT_ASSEMBLY_V1
            }
            ChapterContextAssemblyJobFactory.CHAPTER_PLAN_SOURCE_POLICY_VERSION -> {
                ChapterContextAssemblyJobFactory.parseAndVerifyChapterPlan(stage)
                GenerationRunnerStageRoute.CHAPTER_PLAN_V1
            }
            ChapterPlanV2StageBinding.SOURCE_POLICY_VERSION -> {
                ChapterPlanV2StageBinding.parseAndVerify(stage)
                GenerationRunnerStageRoute.CHAPTER_PLAN_V2
            }
            else -> throw IllegalArgumentException(
                "Generation Stage source policy is not a supported route.",
            )
        }
    }

    private fun sourcePolicyVersion(stage: GenerationStageEntity): String {
        val root = runCatching { STRICT_JSON.parseToJsonElement(stage.inputSourcesJson) }
            .getOrElse {
                throw IllegalArgumentException(
                    "Generation Stage input sources are not a strict JSON object.",
                )
            }
        if (root !is JsonObject) {
            throw IllegalArgumentException("Generation Stage input sources are not a strict JSON object.")
        }
        return (root["sourcePolicyVersion"] as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: throw IllegalArgumentException(
                "Generation Stage source policy version is missing or invalid.",
            )
    }

    private val STRICT_JSON = Json { isLenient = false }
}
