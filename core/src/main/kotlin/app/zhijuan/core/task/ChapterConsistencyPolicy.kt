package app.zhijuan.core.task

import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.model.RelevantSceneBlockReason
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class ChapterSceneConsistencyModeV1 {
    NOT_APPLICABLE,
    PROPORTIONAL,
    STRICT,
}

data class ChapterSceneConsistencyContractV1(
    val mode: ChapterSceneConsistencyModeV1,
    val intimacyDetailLevel: Int?,
    val fadePolicy: FadePolicy?,
    val requiredKeyProcessCoveragePercent: Int?,
    val fadeSubstitutionAllowed: Boolean,
    val requiresStateContinuity: Boolean,
    val requiresRelevantAftermath: Boolean,
    val requiredProcessNodeIds: List<String>,
    val expectedCriteria: List<ConsistencyCriterionV1>,
    val contractHash: String,
) {
    val strictBodyAndSensoryContinuity: Boolean
        get() = mode == ChapterSceneConsistencyModeV1.STRICT

    init {
        require(intimacyDetailLevel == null || intimacyDetailLevel in 0..4)
        require(requiredKeyProcessCoveragePercent == null || requiredKeyProcessCoveragePercent in 0..100)
        require(requiredProcessNodeIds.size <= 64)
        require(requiredProcessNodeIds.all(IDENTIFIER::matches))
        require(requiredProcessNodeIds.distinct().size == requiredProcessNodeIds.size)
        require(requiredProcessNodeIds == requiredProcessNodeIds.sorted())
        require(expectedCriteria.isNotEmpty() && expectedCriteria.distinct().size == expectedCriteria.size)
        require(expectedCriteria == expectedCriteria.sortedBy { it.ordinal })
        require(HASH.matches(contractHash))
        when (mode) {
            ChapterSceneConsistencyModeV1.NOT_APPLICABLE -> require(
                intimacyDetailLevel == null && fadePolicy == null &&
                    requiredKeyProcessCoveragePercent == null && requiredProcessNodeIds.isEmpty(),
            )
            ChapterSceneConsistencyModeV1.PROPORTIONAL -> require(
                intimacyDetailLevel != null && fadePolicy != null &&
                    requiredKeyProcessCoveragePercent == null && requiredProcessNodeIds.isEmpty(),
            )
            ChapterSceneConsistencyModeV1.STRICT -> require(
                intimacyDetailLevel != null && fadePolicy != null &&
                    requiredKeyProcessCoveragePercent == 100 && !fadeSubstitutionAllowed &&
                    requiresStateContinuity && requiresRelevantAftermath && requiredProcessNodeIds.isNotEmpty(),
            )
        }
    }

    override fun toString(): String =
        "ChapterSceneConsistencyContractV1(mode=$mode, criterionCount=${expectedCriteria.size}, " +
            "requiredProcessCount=${requiredProcessNodeIds.size}, hash=redacted)"
}

sealed interface ChapterConsistencyPolicyDecisionV1 {
    data class Ready(
        val contract: ChapterSceneConsistencyContractV1,
    ) : ChapterConsistencyPolicyDecisionV1

    data class Blocked(
        val reason: RelevantSceneBlockReason,
    ) : ChapterConsistencyPolicyDecisionV1
}

object ChapterConsistencyPolicyV1 {
    const val POLICY_VERSION = "zhijuan.chapter-consistency-policy.v1"

    fun resolve(
        scene: SceneExecutionContract,
        requiredProcessNodeIds: Collection<String> = emptyList(),
    ): ChapterConsistencyPolicyDecisionV1 {
        val nodes = requiredProcessNodeIds.toList().sorted()
        require(nodes.size <= 64 && nodes.all(IDENTIFIER::matches) && nodes.distinct().size == nodes.size)
        return when (scene) {
            SceneExecutionContract.NotApplicable -> {
                require(nodes.isEmpty()) { "A non-scene consistency contract cannot own required process nodes." }
                ready(
                    mode = ChapterSceneConsistencyModeV1.NOT_APPLICABLE,
                    detail = null,
                    fadePolicy = null,
                    coverage = null,
                    fadeAllowed = true,
                    stateContinuity = false,
                    aftermath = false,
                    nodes = nodes,
                )
            }
            is SceneExecutionContract.Blocked -> ChapterConsistencyPolicyDecisionV1.Blocked(scene.reason)
            is SceneExecutionContract.Allowed -> {
                if (scene.strictBodyAndSensoryContinuity) {
                    require(nodes.isNotEmpty()) {
                        "Strict scene consistency requires frozen plan process nodes before checking."
                    }
                } else {
                    require(nodes.isEmpty()) {
                        "Only a strict scene contract may require complete process-node coverage."
                    }
                }
                ready(
                    mode = if (scene.strictBodyAndSensoryContinuity) {
                        ChapterSceneConsistencyModeV1.STRICT
                    } else {
                        ChapterSceneConsistencyModeV1.PROPORTIONAL
                    },
                    detail = scene.intimacyDetailLevel,
                    fadePolicy = scene.fadePolicy,
                    coverage = scene.requiredKeyProcessCoveragePercent,
                    fadeAllowed = scene.fadeSubstitutionAllowed,
                    stateContinuity = scene.requiresStateContinuity,
                    aftermath = scene.requiresRelevantAftermath,
                    nodes = nodes,
                )
            }
        }
    }

    private fun ready(
        mode: ChapterSceneConsistencyModeV1,
        detail: Int?,
        fadePolicy: FadePolicy?,
        coverage: Int?,
        fadeAllowed: Boolean,
        stateContinuity: Boolean,
        aftermath: Boolean,
        nodes: List<String>,
    ): ChapterConsistencyPolicyDecisionV1.Ready {
        val criteria = buildList {
            addAll(BASE_CRITERIA)
            if (aftermath) add(ConsistencyCriterionV1.RELEVANT_AFTERMATH)
            if (mode == ChapterSceneConsistencyModeV1.STRICT) {
                add(ConsistencyCriterionV1.REQUIRED_PROCESS_COVERAGE)
                add(ConsistencyCriterionV1.NO_FADE_SUBSTITUTION)
                add(ConsistencyCriterionV1.SENSORY_CONTINUITY)
                add(ConsistencyCriterionV1.NON_MECHANICAL_DETAIL)
            }
        }.distinct().sortedBy { it.ordinal }
        val hash = stableHash(
            POLICY_VERSION,
            mode.name,
            detail,
            fadePolicy?.name,
            coverage,
            fadeAllowed,
            stateContinuity,
            aftermath,
            nodes.joinToString("\u0001"),
            criteria.joinToString("\u0001") { it.name },
        )
        return ChapterConsistencyPolicyDecisionV1.Ready(
            ChapterSceneConsistencyContractV1(
                mode = mode,
                intimacyDetailLevel = detail,
                fadePolicy = fadePolicy,
                requiredKeyProcessCoveragePercent = coverage,
                fadeSubstitutionAllowed = fadeAllowed,
                requiresStateContinuity = stateContinuity,
                requiresRelevantAftermath = aftermath,
                requiredProcessNodeIds = nodes,
                expectedCriteria = criteria,
                contractHash = hash,
            ),
        )
    }

    private fun stableHash(vararg values: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = (value?.toString() ?: "<null>").toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
            bytes.fill(0)
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private val BASE_CRITERIA = ConsistencyCriterionV1.entries.filter {
        it !in setOf(
            ConsistencyCriterionV1.REQUIRED_PROCESS_COVERAGE,
            ConsistencyCriterionV1.NO_FADE_SUBSTITUTION,
            ConsistencyCriterionV1.SENSORY_CONTINUITY,
            ConsistencyCriterionV1.RELEVANT_AFTERMATH,
            ConsistencyCriterionV1.NON_MECHANICAL_DETAIL,
        )
    }
}

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
