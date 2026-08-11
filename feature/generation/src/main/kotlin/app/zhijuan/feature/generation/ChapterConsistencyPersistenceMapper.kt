package app.zhijuan.feature.generation

import app.zhijuan.core.database.memory.ConsistencyReportEntity
import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.ConsistencyRepairActionV1
import app.zhijuan.core.model.ConsistencyStatus
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.task.ChapterLocalConsistencyReport
import app.zhijuan.core.task.ChapterSceneConsistencyContractV1
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ConsistencyIssueSourceV1 {
    LOCAL,
    MODEL,
}

enum class ChapterConsistencyGateDecisionV1 {
    ACCEPT_CANDIDATE,
    REVISE_CANDIDATE,
}

data class CombinedConsistencyIssueV1(
    val source: ConsistencyIssueSourceV1,
    val issueId: String,
    val code: ConsistencyIssueCode,
    val severity: ConsistencyIssueSeverity,
    val criterion: ConsistencyCriterionV1,
    val startCodePointInclusive: Int,
    val endCodePointExclusive: Int,
    val relatedEntityIds: List<String>,
    val relatedForeshadowItemIds: List<String>,
    val relatedRequiredProcessNodeIds: List<String>,
    val repairAction: ConsistencyRepairActionV1,
)

data class ChapterConsistencyGateResultV1(
    val decision: ChapterConsistencyGateDecisionV1,
    val issues: List<CombinedConsistencyIssueV1>,
) {
    val blockerCount: Int = issues.count { it.severity == ConsistencyIssueSeverity.BLOCKER }
    val majorCount: Int = issues.count { it.severity == ConsistencyIssueSeverity.MAJOR }
    val minorCount: Int = issues.count { it.severity == ConsistencyIssueSeverity.MINOR }
    val chapterConsistencyStatus: ConsistencyStatus = when (decision) {
        ChapterConsistencyGateDecisionV1.ACCEPT_CANDIDATE -> ConsistencyStatus.VALID
        ChapterConsistencyGateDecisionV1.REVISE_CANDIDATE -> ConsistencyStatus.ISSUES
    }

    override fun toString(): String =
        "ChapterConsistencyGateResultV1(decision=$decision, issueCount=${issues.size}, content=redacted)"
}

object ChapterConsistencyAcceptanceGateV1 {
    fun evaluate(
        local: ChapterLocalConsistencyReport,
        model: ChapterConsistencyReportV1,
        expectation: ChapterConsistencyExpectation,
        scene: ChapterSceneConsistencyContractV1,
    ): ChapterConsistencyGateResultV1 {
        require(local.contentHash == expectation.sourceChapterContentHash)
        require(local.bodyCodePointCount == expectation.bodyCodePointCount)
        require(model.sourceChapterVersionId == expectation.sourceChapterVersionId)
        require(model.sourceChapterContentHash == expectation.sourceChapterContentHash)
        require(model.chapterId == expectation.chapterId && model.chapterIndex == expectation.chapterIndex)
        require(model.checkSourceSnapshotHash == expectation.checkSourceSnapshotHash)
        require(model.sceneContractHash == scene.contractHash && model.sceneContractHash == expectation.sceneContractHash)
        require(model.criterionResults.map { it.criterion } == scene.expectedCriteria)
        require(expectation.expectedCriteria == scene.expectedCriteria)
        require(expectation.requiredProcessNodeIds == scene.requiredProcessNodeIds.toSet())
        require(ChapterConsistencyCrossValidator.validate(model, expectation).isEmpty())
        val localIssues = local.issues.map { issue ->
            CombinedConsistencyIssueV1(
                source = ConsistencyIssueSourceV1.LOCAL,
                issueId = issue.issueId,
                code = issue.code,
                severity = issue.severity,
                criterion = issue.criterion,
                startCodePointInclusive = issue.evidenceRange.startCodePointInclusive,
                endCodePointExclusive = issue.evidenceRange.endCodePointExclusive,
                relatedEntityIds = emptyList(),
                relatedForeshadowItemIds = emptyList(),
                relatedRequiredProcessNodeIds = emptyList(),
                repairAction = issue.repairAction,
            )
        }
        val modelIssues = model.issues.map { issue ->
            CombinedConsistencyIssueV1(
                source = ConsistencyIssueSourceV1.MODEL,
                issueId = issue.issueId,
                code = issue.code,
                severity = issue.severity,
                criterion = ConsistencyIssuePolicyV1.criterionFor(issue.code),
                startCodePointInclusive = issue.startCodePointInclusive,
                endCodePointExclusive = issue.endCodePointExclusive,
                relatedEntityIds = issue.relatedEntityIds,
                relatedForeshadowItemIds = issue.relatedForeshadowItemIds,
                relatedRequiredProcessNodeIds = issue.relatedRequiredProcessNodeIds,
                repairAction = issue.repairAction,
            )
        }
        val issues = localIssues + modelIssues
        val mustRevise = issues.any {
            it.severity == ConsistencyIssueSeverity.BLOCKER || it.severity == ConsistencyIssueSeverity.MAJOR
        }
        return ChapterConsistencyGateResultV1(
            decision = if (mustRevise) {
                ChapterConsistencyGateDecisionV1.REVISE_CANDIDATE
            } else {
                ChapterConsistencyGateDecisionV1.ACCEPT_CANDIDATE
            },
            issues = issues,
        )
    }
}

data class ChapterConsistencyMappingSpecV1(
    val bookId: String,
    val generationStageId: String?,
    val modelSnapshotJson: String,
    val createdAt: Long,
) {
    init {
        require(IDENTIFIER.matches(bookId))
        require(generationStageId == null || IDENTIFIER.matches(generationStageId))
        require(modelSnapshotJson.isNotBlank() && modelSnapshotJson.length <= 65_536)
        parseObject(modelSnapshotJson, "Consistency model snapshot")
        require(createdAt >= 0L)
    }
}

data class ChapterConsistencyDerivedDraftV1(
    val report: ConsistencyReportEntity,
    val gate: ChapterConsistencyGateResultV1,
    val reportContentHash: String,
) {
    override fun toString(): String =
        "ChapterConsistencyDerivedDraftV1(decision=${gate.decision}, issueCount=${gate.issues.size}, content=redacted)"
}

object ChapterConsistencyPersistenceMapperV1 {
    const val CHECKER_VERSION = "zhijuan.consistency-combined.v1"

    fun map(
        local: ChapterLocalConsistencyReport,
        model: ChapterConsistencyReportV1,
        expectation: ChapterConsistencyExpectation,
        scene: ChapterSceneConsistencyContractV1,
        spec: ChapterConsistencyMappingSpecV1,
    ): ChapterConsistencyDerivedDraftV1 {
        val gate = ChapterConsistencyAcceptanceGateV1.evaluate(local, model, expectation, scene)
        val issuesJson = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "checkerVersion" to JsonPrimitive(CHECKER_VERSION),
                "localCheckerVersion" to JsonPrimitive(local.checkerVersion),
                "modelOutputSchemaId" to JsonPrimitive(ChapterConsistencyOutputContractV1.schemaId),
                "sourceChapterVersionId" to JsonPrimitive(model.sourceChapterVersionId),
                "sourceChapterContentHash" to JsonPrimitive(model.sourceChapterContentHash),
                "chapterId" to JsonPrimitive(model.chapterId),
                "chapterIndex" to JsonPrimitive(model.chapterIndex),
                "checkSourceSnapshotHash" to JsonPrimitive(model.checkSourceSnapshotHash),
                "sceneContractHash" to JsonPrimitive(model.sceneContractHash),
                "decision" to JsonPrimitive(gate.decision.name),
                "chapterConsistencyStatus" to JsonPrimitive(gate.chapterConsistencyStatus.name),
                "blockerCount" to JsonPrimitive(gate.blockerCount),
                "majorCount" to JsonPrimitive(gate.majorCount),
                "minorCount" to JsonPrimitive(gate.minorCount),
                "modelSnapshot" to canonicalize(parseObject(spec.modelSnapshotJson, "Consistency model snapshot")),
                "criterionResults" to JsonArray(model.criterionResults.map { result ->
                    JsonObject(
                        linkedMapOf(
                            "criterion" to JsonPrimitive(result.criterion.name),
                            "status" to JsonPrimitive(result.status.name),
                            "issueIds" to JsonArray(result.issueIds.map(::JsonPrimitive)),
                        ),
                    )
                }),
                "requiredProcessResults" to JsonArray(model.requiredProcessResults.map { result ->
                    JsonObject(
                        linkedMapOf(
                            "requiredProcessNodeId" to JsonPrimitive(result.requiredProcessNodeId),
                            "status" to JsonPrimitive(result.status.name),
                            "issueId" to (result.issueId?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    )
                }),
                "issues" to JsonArray(gate.issues.map { it.toJson() }),
            ),
        ).toString()
        require(issuesJson.length <= MAX_REPORT_CHARACTERS)
        val reportHash = sha256(issuesJson)
        val stableSource = spec.generationStageId ?: "no-stage"
        val report = ConsistencyReportEntity(
            consistencyReportId = "consistency.report.${sha256(
                listOf(stableSource, model.sourceChapterVersionId, model.checkSourceSnapshotHash, model.contentHash)
                    .joinToString("\u0000"),
            ).take(32)}",
            bookId = spec.bookId,
            targetChapterVersionId = model.sourceChapterVersionId,
            targetChapterIndex = model.chapterIndex,
            generationStageId = spec.generationStageId,
            checkerVersion = CHECKER_VERSION,
            issuesJson = issuesJson,
            status = DerivedDataStatus.VALID,
            createdAt = spec.createdAt,
            updatedAt = spec.createdAt,
        )
        return ChapterConsistencyDerivedDraftV1(report, gate, reportHash)
    }

    private fun CombinedConsistencyIssueV1.toJson() = JsonObject(
        linkedMapOf(
            "source" to JsonPrimitive(source.name),
            "issueId" to JsonPrimitive(issueId),
            "code" to JsonPrimitive(code.name),
            "severity" to JsonPrimitive(severity.name),
            "criterion" to JsonPrimitive(criterion.name),
            "startCodePointInclusive" to JsonPrimitive(startCodePointInclusive),
            "endCodePointExclusive" to JsonPrimitive(endCodePointExclusive),
            "relatedEntityIds" to JsonArray(relatedEntityIds.map(::JsonPrimitive)),
            "relatedForeshadowItemIds" to JsonArray(relatedForeshadowItemIds.map(::JsonPrimitive)),
            "relatedRequiredProcessNodeIds" to JsonArray(relatedRequiredProcessNodeIds.map(::JsonPrimitive)),
            "repairAction" to JsonPrimitive(repairAction.name),
        ),
    )
}

private fun parseObject(value: String, label: String): JsonObject =
    runCatching { STRICT_JSON.parseToJsonElement(value) as JsonObject }
        .getOrElse { throw IllegalArgumentException("$label must be a JSON object.") }

private fun canonicalize(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(value.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) })
    is JsonArray -> JsonArray(value.map(::canonicalize))
    else -> value
}

private fun sha256(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    } finally {
        bytes.fill(0)
    }
}

private val STRICT_JSON = Json { isLenient = false; ignoreUnknownKeys = false }
private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private const val MAX_REPORT_CHARACTERS = 512 * 1_024
