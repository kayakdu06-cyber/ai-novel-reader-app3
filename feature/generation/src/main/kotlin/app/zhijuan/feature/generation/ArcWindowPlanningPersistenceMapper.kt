package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.ArcWindowPlanningCommitDraft
import app.zhijuan.core.database.memory.OutlineNodeEntity
import app.zhijuan.core.database.memory.OutlineRevisionEntity
import app.zhijuan.core.model.OutlineNodeType
import app.zhijuan.core.model.RevisionSource
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class ArcWindowPlanningPersistenceIds(
    val bookId: String,
    val masterOutlineRevisionId: String,
    val parentOutlineRevisionId: String,
    val parentRevisionNo: Int,
    val outlineRevisionId: String,
    val generationStageId: String,
)

object ArcWindowPlanningPersistenceMapper {
    fun map(
        plan: ArcWindowPlanV1,
        chapterContracts: List<ArcWindowChapterContractV2> = emptyList(),
        expected: ArcWindowPlanningExpectation,
        ids: ArcWindowPlanningPersistenceIds,
        committedAt: Long,
        schemaId: String = ArcWindowPlanOutputContractV1.schemaId,
        policyVersion: String = plan.policyVersion,
        canonicalPlanJson: String = plan.canonicalJson,
        canonicalPlanHash: String = plan.contentHash,
    ): ArcWindowPlanningCommitDraft {
        require(
            ArcWindowPlanningValidator.validate(plan, expected) is ArcWindowPlanningValidationResult.Valid,
        ) { "Arc-window plan does not match the frozen local selection." }
        require(
            listOf(
                ids.bookId,
                ids.masterOutlineRevisionId,
                ids.parentOutlineRevisionId,
                ids.outlineRevisionId,
                ids.generationStageId,
            ).all(IDENTIFIER::matches),
        ) { "Arc-window persistence identifiers are invalid." }
        require(ids.parentRevisionNo >= 1 && committedAt >= 0L)
        require(ids.outlineRevisionId != ids.parentOutlineRevisionId)
        require(sha256(canonicalPlanJson) == canonicalPlanHash)
        val contractByChapter = chapterContracts.associateBy(ArcWindowChapterContractV2::chapterIndex)
        require(contractByChapter.size == chapterContracts.size)
        require(contractByChapter.keys.all { chapterIndex -> plan.chapters.any { it.chapterIndex == chapterIndex } })

        val revision = OutlineRevisionEntity(
            outlineRevisionId = ids.outlineRevisionId,
            bookId = ids.bookId,
            revisionNo = ids.parentRevisionNo + 1,
            parentRevisionId = ids.parentOutlineRevisionId,
            source = RevisionSource.AI_GENERATED,
            schemaVersion = 1,
            summaryJson = canonicalPlanJson,
            contentHash = canonicalPlanHash,
            generationStageId = ids.generationStageId,
            createdAt = committedAt,
        )
        val rootId = stableId("window-root", ids.bookId, ids.outlineRevisionId)
        val arcId = stableId("arc-node", ids.bookId, ids.outlineRevisionId, plan.arcId)
        val arcJson = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "policyVersion" to JsonPrimitive(policyVersion),
                "arcId" to JsonPrimitive(plan.arcId),
                "startChapter" to JsonPrimitive(plan.arcStartChapter),
                "endChapter" to JsonPrimitive(plan.arcEndChapter),
                "title" to JsonPrimitive(plan.title),
                "dramaticQuestion" to JsonPrimitive(plan.dramaticQuestion),
                "openingState" to JsonPrimitive(plan.openingState),
                "closingState" to JsonPrimitive(plan.closingState),
                "milestones" to JsonArray(
                    plan.milestones.map { milestone ->
                        JsonObject(
                            linkedMapOf(
                                "milestoneId" to JsonPrimitive(milestone.milestoneId),
                                "chapterIndex" to JsonPrimitive(milestone.chapterIndex),
                                "purpose" to JsonPrimitive(milestone.purpose),
                                "consequence" to JsonPrimitive(milestone.consequence),
                            ),
                        )
                    },
                ),
                "continuityConstraints" to JsonArray(plan.continuityConstraints.map(::JsonPrimitive)),
            ),
        ).toString()
        val root = OutlineNodeEntity(
            outlineNodeId = rootId,
            outlineRevisionId = ids.outlineRevisionId,
            parentNodeId = null,
            nodeType = OutlineNodeType.BOOK,
            orderKey = 0L,
            plannedChapterIndex = null,
            title = "规划窗口 ${plan.windowStartChapter}–${plan.windowEndChapter}",
            planJson = canonicalPlanJson,
            contentHash = canonicalPlanHash,
            createdAt = committedAt,
        )
        val arc = OutlineNodeEntity(
            outlineNodeId = arcId,
            outlineRevisionId = ids.outlineRevisionId,
            parentNodeId = rootId,
            nodeType = OutlineNodeType.ARC,
            orderKey = 1_000L,
            plannedChapterIndex = null,
            title = plan.title,
            planJson = arcJson,
            contentHash = sha256(arcJson),
            createdAt = committedAt,
        )
        val chapters = plan.chapters.mapIndexed { offset, chapter ->
            val contract = contractByChapter[chapter.chapterIndex]
            val chapterPayload = linkedMapOf(
                    "schemaVersion" to JsonPrimitive(1),
                    "windowId" to JsonPrimitive(plan.windowId),
                    "chapterIndex" to JsonPrimitive(chapter.chapterIndex),
                    "title" to JsonPrimitive(chapter.title),
                    "goal" to JsonPrimitive(chapter.goal),
                    "conflict" to JsonPrimitive(chapter.conflict),
                    "turn" to JsonPrimitive(chapter.turn),
                    "outcome" to JsonPrimitive(chapter.outcome),
                    "hook" to JsonPrimitive(chapter.hook),
                    "continuityCarry" to JsonArray(chapter.continuityCarry.map(::JsonPrimitive)),
                )
            if (contract != null) {
                chapterPayload["objective"] = JsonPrimitive(contract.objective)
                chapterPayload["capabilityHints"] = JsonArray(contract.capabilityHints.map(::JsonPrimitive))
                chapterPayload["obligationIds"] = JsonArray(contract.obligationIds.map(::JsonPrimitive))
                chapterPayload["prohibitedRepetitions"] = JsonArray(
                    contract.prohibitedRepetitions.map(::JsonPrimitive),
                )
            }
            val chapterJson = JsonObject(chapterPayload).toString()
            OutlineNodeEntity(
                outlineNodeId = stableId(
                    "chapter-node",
                    ids.bookId,
                    ids.outlineRevisionId,
                    chapter.chapterIndex.toString(),
                ),
                outlineRevisionId = ids.outlineRevisionId,
                parentNodeId = arcId,
                nodeType = OutlineNodeType.CHAPTER,
                orderKey = 2_000L + offset,
                plannedChapterIndex = chapter.chapterIndex,
                title = chapter.title,
                planJson = chapterJson,
                contentHash = sha256(chapterJson),
                createdAt = committedAt,
            )
        }
        return ArcWindowPlanningCommitDraft(
            schemaId = schemaId,
            policyVersion = policyVersion,
            masterOutlineRevisionId = ids.masterOutlineRevisionId,
            masterOutlineContentHash = expected.masterOutlineContentHash,
            parentOutlineContentHash = expected.parentOutlineContentHash,
            revision = revision,
            nodes = listOf(root, arc) + chapters,
            committedAt = committedAt,
        )
    }

    private fun stableId(kind: String, vararg values: String): String =
        "$kind.${sha256(values.joinToString("\u0000")).take(32)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
}
