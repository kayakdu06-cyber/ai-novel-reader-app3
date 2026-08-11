package app.zhijuan.core.task

import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.ConsistencyRepairActionV1
import java.nio.ByteBuffer
import java.security.MessageDigest

data class ChapterRevisionIssueRefV1(
    val issueId: String,
    val code: ConsistencyIssueCode,
    val severity: ConsistencyIssueSeverity,
    val startCodePointInclusive: Int,
    val endCodePointExclusive: Int,
    val repairAction: ConsistencyRepairActionV1,
    val relatedEntityIds: List<String> = emptyList(),
    val relatedForeshadowItemIds: List<String> = emptyList(),
    val relatedRequiredProcessNodeIds: List<String> = emptyList(),
) {
    init {
        require(IDENTIFIER.matches(issueId))
        require(startCodePointInclusive >= 0 && endCodePointExclusive >= startCodePointInclusive)
        listOf(relatedEntityIds, relatedForeshadowItemIds, relatedRequiredProcessNodeIds).forEach { ids ->
            require(ids.size <= 64 && ids.all(IDENTIFIER::matches) && ids.distinct().size == ids.size)
        }
    }
}

data class ChapterRevisionPolicyInputV1(
    val currentCandidateContentHash: String,
    val candidateContentHashHistory: List<String>,
    val bodyCodePointCount: Int,
    val minimumBodyCodePoints: Int,
    val completedAutomaticRevisions: Int,
    val totalRevisionAttemptsUsed: Int,
    val stageMaximumAttempts: Int,
    val sceneContract: ChapterSceneConsistencyContractV1,
    val issues: List<ChapterRevisionIssueRefV1>,
) {
    init {
        require(HASH.matches(currentCandidateContentHash))
        require(candidateContentHashHistory.isNotEmpty() && candidateContentHashHistory.all(HASH::matches))
        require(candidateContentHashHistory.last() == currentCandidateContentHash)
        require(candidateContentHashHistory.distinct().size == candidateContentHashHistory.size) {
            "Candidate history already contains a revision cycle."
        }
        require(completedAutomaticRevisions == candidateContentHashHistory.size - 1)
        require(bodyCodePointCount >= 0 && minimumBodyCodePoints in 1..1_000_000)
        require(totalRevisionAttemptsUsed >= completedAutomaticRevisions)
        require(stageMaximumAttempts in 1..16)
        require(issues.size <= 128 && issues.map { it.issueId }.distinct().size == issues.size)
        require(issues.all { it.endCodePointExclusive <= bodyCodePointCount })
    }
}

enum class ChapterRevisionNeedsActionReasonV1 {
    AUTOMATIC_REVISION_LIMIT_REACHED,
    STAGE_ATTEMPT_LIMIT_REACHED,
    REVISED_BODY_BELOW_MINIMUM,
    REVISED_CANDIDATE_UNCHANGED,
    REVISED_CANDIDATE_CYCLE,
}

sealed interface ChapterRevisionPolicyDecisionV1 {
    data class AcceptCandidate(
        val candidateContentHash: String,
        val maximumAutomaticRevisions: Int,
    ) : ChapterRevisionPolicyDecisionV1

    class ReviseAutomatically internal constructor(
        val revisionIndex: Int,
        val maximumAutomaticRevisions: Int,
        val sourceCandidateContentHash: String,
        val sceneContractHash: String,
        val repairPlanHash: String,
        val issues: List<ChapterRevisionIssueRefV1>,
        val priorCandidateContentHashes: List<String>,
        internal val minimumBodyCodePoints: Int,
    ) : ChapterRevisionPolicyDecisionV1 {
        override fun toString(): String =
            "ReviseAutomatically(index=$revisionIndex, maximum=$maximumAutomaticRevisions, " +
                "issueCount=${issues.size}, content=redacted)"
    }

    data class NeedsAction(
        val reason: ChapterRevisionNeedsActionReasonV1,
        val automaticRevisionsUsed: Int,
        val automaticRevisionLimit: Int,
    ) : ChapterRevisionPolicyDecisionV1
}

sealed interface ChapterRevisionResultDecisionV1 {
    data class ContinueWithCandidate(
        val revisedCandidateContentHash: String,
        val completedAutomaticRevisions: Int,
    ) : ChapterRevisionResultDecisionV1

    data class NeedsAction(
        val reason: ChapterRevisionNeedsActionReasonV1,
    ) : ChapterRevisionResultDecisionV1
}

object ChapterRevisionPolicyV1 {
    const val POLICY_VERSION = "zhijuan.chapter-revision-policy.v1"
    const val MAXIMUM_AUTOMATIC_REVISIONS = 2

    fun evaluate(input: ChapterRevisionPolicyInputV1): ChapterRevisionPolicyDecisionV1 {
        val limit = automaticRevisionLimit(input.sceneContract.mode)
        val actionableIssues = input.issues.filter {
            it.severity == ConsistencyIssueSeverity.BLOCKER ||
                it.severity == ConsistencyIssueSeverity.MAJOR
        }
        if (actionableIssues.isEmpty()) {
            return ChapterRevisionPolicyDecisionV1.AcceptCandidate(
                candidateContentHash = input.currentCandidateContentHash,
                maximumAutomaticRevisions = limit,
            )
        }
        if (input.completedAutomaticRevisions >= limit || input.totalRevisionAttemptsUsed >= limit) {
            return ChapterRevisionPolicyDecisionV1.NeedsAction(
                ChapterRevisionNeedsActionReasonV1.AUTOMATIC_REVISION_LIMIT_REACHED,
                input.completedAutomaticRevisions,
                limit,
            )
        }
        if (input.totalRevisionAttemptsUsed >= input.stageMaximumAttempts) {
            return ChapterRevisionPolicyDecisionV1.NeedsAction(
                ChapterRevisionNeedsActionReasonV1.STAGE_ATTEMPT_LIMIT_REACHED,
                input.completedAutomaticRevisions,
                limit,
            )
        }
        val orderedIssues = actionableIssues.sortedWith(
            compareBy<ChapterRevisionIssueRefV1>(
                { it.startCodePointInclusive },
                { it.endCodePointExclusive },
                { it.code.ordinal },
                { it.issueId },
            ),
        )
        return ChapterRevisionPolicyDecisionV1.ReviseAutomatically(
            revisionIndex = input.completedAutomaticRevisions + 1,
            maximumAutomaticRevisions = limit,
            sourceCandidateContentHash = input.currentCandidateContentHash,
            sceneContractHash = input.sceneContract.contractHash,
            repairPlanHash = repairPlanHash(input, orderedIssues, limit),
            issues = orderedIssues,
            priorCandidateContentHashes = input.candidateContentHashHistory.toList(),
            minimumBodyCodePoints = input.minimumBodyCodePoints,
        )
    }

    private fun automaticRevisionLimit(mode: ChapterSceneConsistencyModeV1): Int = when (mode) {
        ChapterSceneConsistencyModeV1.STRICT -> MAXIMUM_AUTOMATIC_REVISIONS
        ChapterSceneConsistencyModeV1.NOT_APPLICABLE,
        ChapterSceneConsistencyModeV1.PROPORTIONAL,
        -> 1
    }

    /** Stable, content-free commitment to the complete input and resulting finite route. */
    fun routingBindingHash(input: ChapterRevisionPolicyInputV1): String {
        val decision = evaluate(input)
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: Any?) {
            val bytes = (value?.toString() ?: "<null>").toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
            bytes.fill(0)
        }
        put(POLICY_VERSION)
        put(input.currentCandidateContentHash)
        input.candidateContentHashHistory.forEach(::put)
        put(input.bodyCodePointCount)
        put(input.minimumBodyCodePoints)
        put(input.completedAutomaticRevisions)
        put(input.totalRevisionAttemptsUsed)
        put(input.stageMaximumAttempts)
        with(input.sceneContract) {
            put(mode.name)
            put(intimacyDetailLevel)
            put(fadePolicy?.name)
            put(requiredKeyProcessCoveragePercent)
            put(fadeSubstitutionAllowed)
            put(requiresStateContinuity)
            put(requiresRelevantAftermath)
            requiredProcessNodeIds.forEach(::put)
            expectedCriteria.forEach { put(it.name) }
            put(contractHash)
        }
        input.issues.sortedWith(
            compareBy<ChapterRevisionIssueRefV1>(
                { it.startCodePointInclusive },
                { it.endCodePointExclusive },
                { it.code.ordinal },
                { it.severity.ordinal },
                { it.issueId },
            ),
        ).forEach { issue ->
            put(issue.issueId)
            put(issue.code.name)
            put(issue.severity.name)
            put(issue.startCodePointInclusive)
            put(issue.endCodePointExclusive)
            put(issue.repairAction.name)
            issue.relatedEntityIds.sorted().forEach(::put)
            issue.relatedForeshadowItemIds.sorted().forEach(::put)
            issue.relatedRequiredProcessNodeIds.sorted().forEach(::put)
        }
        when (decision) {
            is ChapterRevisionPolicyDecisionV1.AcceptCandidate -> {
                put("ACCEPT")
                put(decision.candidateContentHash)
            }
            is ChapterRevisionPolicyDecisionV1.ReviseAutomatically -> {
                put("REVISE")
                put(decision.revisionIndex)
                put(decision.maximumAutomaticRevisions)
                put(decision.repairPlanHash)
            }
            is ChapterRevisionPolicyDecisionV1.NeedsAction -> {
                put("NEEDS_ACTION")
                put(decision.reason.name)
                put(decision.automaticRevisionsUsed)
                put(decision.automaticRevisionLimit)
            }
        }
        return digest.digest().toHex()
    }

    fun evaluateRevisedCandidate(
        plan: ChapterRevisionPolicyDecisionV1.ReviseAutomatically,
        revisedCandidateContentHash: String,
        revisedBodyCodePointCount: Int,
    ): ChapterRevisionResultDecisionV1 {
        require(HASH.matches(revisedCandidateContentHash))
        require(revisedBodyCodePointCount >= 0)
        if (revisedBodyCodePointCount < plan.minimumBodyCodePoints) {
            return ChapterRevisionResultDecisionV1.NeedsAction(
                ChapterRevisionNeedsActionReasonV1.REVISED_BODY_BELOW_MINIMUM,
            )
        }
        if (revisedCandidateContentHash == plan.sourceCandidateContentHash) {
            return ChapterRevisionResultDecisionV1.NeedsAction(
                ChapterRevisionNeedsActionReasonV1.REVISED_CANDIDATE_UNCHANGED,
            )
        }
        if (revisedCandidateContentHash in plan.priorCandidateContentHashes) {
            return ChapterRevisionResultDecisionV1.NeedsAction(
                ChapterRevisionNeedsActionReasonV1.REVISED_CANDIDATE_CYCLE,
            )
        }
        return ChapterRevisionResultDecisionV1.ContinueWithCandidate(
            revisedCandidateContentHash = revisedCandidateContentHash,
            completedAutomaticRevisions = plan.revisionIndex,
        )
    }

    private fun repairPlanHash(
        input: ChapterRevisionPolicyInputV1,
        issues: List<ChapterRevisionIssueRefV1>,
        limit: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(value: Any?) {
            val bytes = (value?.toString() ?: "<null>").toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
            bytes.fill(0)
        }
        put(POLICY_VERSION)
        put(input.currentCandidateContentHash)
        put(input.sceneContract.contractHash)
        put(input.completedAutomaticRevisions + 1)
        put(limit)
        issues.forEach { issue ->
            put(issue.issueId)
            put(issue.code.name)
            put(issue.severity.name)
            put(issue.startCodePointInclusive)
            put(issue.endCodePointExclusive)
            put(issue.repairAction.name)
            issue.relatedEntityIds.sorted().forEach(::put)
            issue.relatedForeshadowItemIds.sorted().forEach(::put)
            issue.relatedRequiredProcessNodeIds.sorted().forEach(::put)
        }
        return digest.digest().toHex()
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
