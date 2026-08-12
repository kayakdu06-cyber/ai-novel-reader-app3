package app.zhijuan.feature.generation

import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.ConsistencyRepairActionV1
import app.zhijuan.core.model.FadePolicy
import app.zhijuan.core.task.ChapterLocalConsistencyCheckerV1
import app.zhijuan.core.task.ChapterLocalConsistencyReport
import app.zhijuan.core.task.ChapterSceneConsistencyContractV1
import app.zhijuan.core.task.ChapterSceneConsistencyModeV1
import app.zhijuan.core.task.ConsistencyEvidenceRange
import app.zhijuan.core.task.DeterministicConsistencyIssue
import app.zhijuan.core.database.memory.NarrativeObligationV1
import app.zhijuan.core.database.memory.StoryStateKeyV1
import app.zhijuan.core.database.memory.StoryStateNamespaceV1
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The minimum consistency-mapping inputs that must survive a process restart.
 * It intentionally excludes chapter text, names, evidence payloads and provider data.
 */
data class ChapterFinalConsistencyMappingSnapshotV1(
    val consistencyRequestSourceBindingHash: String,
    val localReport: ChapterLocalConsistencyReport,
    val expectation: ChapterConsistencyExpectation,
    val sceneContract: ChapterSceneConsistencyContractV1,
    val minimumBodyCodePoints: Int,
    val totalRevisionAttemptsUsed: Int,
    val revisionStageMaximumAttempts: Int,
    val narrativeExpectation: ChapterPostAnalysisNarrativeExpectationV1? = null,
) {
    override fun toString(): String =
        "ChapterFinalConsistencyMappingSnapshotV1(chapterIndex=${expectation.chapterIndex}, " +
            "revisionAttempts=$totalRevisionAttemptsUsed, localIssueCount=${localReport.issues.size}, " +
            "content=redacted)"
}

/** Strict and deterministic JSON codec for [ChapterFinalConsistencyMappingSnapshotV1]. */
object ChapterFinalConsistencyMappingSnapshotCodecV1 {
    const val SCHEMA_ID = "zhijuan.chapter-final-consistency-mapping.v1"
    const val POST_ANALYSIS_SCHEMA_ID = "zhijuan.chapter-final-post-analysis-mapping.v2"

    // The snapshot is nested inside a GenerationStage source envelope whose total limit is 64 KiB.
    private const val MAX_SNAPSHOT_BYTES = 49_152
    private const val MAX_BODY_CODE_POINTS = 4_194_304
    private const val MAX_BODY_BYTES = 4_194_304

    private val STRICT_JSON = Json {
        isLenient = false
        ignoreUnknownKeys = false
    }

    private val ROOT_KEYS_V1 = linkedSetOf(
        "schemaVersion",
        "schemaId",
        "consistencyRequestSourceBindingHash",
        "minimumBodyCodePoints",
        "totalRevisionAttemptsUsed",
        "revisionStageMaximumAttempts",
        "localReport",
        "expectation",
        "sceneContract",
    )
    private val ROOT_KEYS_V2 = ROOT_KEYS_V1 + "narrativeExpectation"
    private val NARRATIVE_KEYS = setOf("activeNamespaces", "priorObligations", "currentStateValues")
    private val OBLIGATION_KEYS = setOf("obligationId", "description", "dueChapterIndex")
    private val STATE_VALUE_KEYS = setOf(
        "namespace", "entityId", "attribute", "relatedEntityId", "valueJson",
    )

    private val LOCAL_REPORT_KEYS = linkedSetOf(
        "checkerVersion",
        "contentHash",
        "bodyCodePointCount",
        "bodyByteCount",
        "checkedCriteria",
        "issues",
    )

    private val ISSUE_KEYS = linkedSetOf(
        "issueId",
        "code",
        "severity",
        "criterion",
        "startCodePointInclusive",
        "endCodePointExclusive",
        "repairAction",
    )

    private val EXPECTATION_KEYS = linkedSetOf(
        "sourceChapterVersionId",
        "sourceChapterContentHash",
        "chapterId",
        "chapterIndex",
        "checkSourceSnapshotHash",
        "sceneContractHash",
        "bodyCodePointCount",
        "expectedCriteria",
        "knownEntityIds",
        "knownForeshadowItemIds",
        "requiredProcessNodeIds",
    )

    private val SCENE_CONTRACT_KEYS = linkedSetOf(
        "mode",
        "intimacyDetailLevel",
        "fadePolicy",
        "requiredKeyProcessCoveragePercent",
        "fadeSubstitutionAllowed",
        "requiresStateContinuity",
        "requiresRelevantAftermath",
        "requiredProcessNodeIds",
        "expectedCriteria",
        "contractHash",
    )

    fun capture(
        boundRequest: BoundChapterConsistencyCheckRequest,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ): String {
        verifyBoundMatchesSpec(boundRequest, spec)
        val snapshot = ChapterFinalConsistencyMappingSnapshotV1(
            consistencyRequestSourceBindingHash = boundRequest.sourceBindingHash,
            localReport = boundRequest.localReport,
            expectation = boundRequest.expectation,
            sceneContract = boundRequest.sceneContract,
            minimumBodyCodePoints = spec.minimumBodyCodePoints,
            totalRevisionAttemptsUsed = spec.totalRevisionAttemptsUsed,
            revisionStageMaximumAttempts = spec.revisionStageMaximumAttempts,
        )
        verifyCrossObject(snapshot)
        val encoded = encode(snapshot)
        require(utf8ByteCount(encoded) <= MAX_SNAPSHOT_BYTES) {
            "Snapshot must not exceed $MAX_SNAPSHOT_BYTES UTF-8 bytes."
        }
        parseAndVerify(encoded)
        return encoded
    }

    fun capturePostAnalysis(
        boundRequest: BoundChapterPostAnalysisRequestV1,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ): String {
        verifyExpectationMatchesSpec(boundRequest.expectation.consistency, boundRequest.localReport, spec)
        val snapshot = ChapterFinalConsistencyMappingSnapshotV1(
            consistencyRequestSourceBindingHash = boundRequest.sourceBindingHash,
            localReport = boundRequest.localReport,
            expectation = boundRequest.expectation.consistency,
            sceneContract = boundRequest.sceneContract,
            minimumBodyCodePoints = spec.minimumBodyCodePoints,
            totalRevisionAttemptsUsed = spec.totalRevisionAttemptsUsed,
            revisionStageMaximumAttempts = spec.revisionStageMaximumAttempts,
            narrativeExpectation = boundRequest.expectation.narrative,
        )
        verifyCrossObject(snapshot)
        return encode(snapshot).also { encoded ->
            require(utf8ByteCount(encoded) <= MAX_SNAPSHOT_BYTES)
            parseAndVerify(encoded)
        }
    }

    fun parseAndVerify(value: String): ChapterFinalConsistencyMappingSnapshotV1 {
        require(utf8ByteCount(value) <= MAX_SNAPSHOT_BYTES) {
            "Snapshot must not exceed $MAX_SNAPSHOT_BYTES UTF-8 bytes."
        }
        val root = STRICT_JSON.parseToJsonElement(value) as? JsonObject
            ?: throw IllegalArgumentException("Snapshot root must be a JSON object.")
        val schemaVersion = requireInt(root.getValue("schemaVersion"), "schemaVersion")
        val schemaId = requireString(root.getValue("schemaId"), "schemaId")
        require(
            (schemaVersion == 1 && schemaId == SCHEMA_ID && root.keys == ROOT_KEYS_V1) ||
                (schemaVersion == 2 && schemaId == POST_ANALYSIS_SCHEMA_ID && root.keys == ROOT_KEYS_V2),
        ) { "Snapshot schema identity or root keys are invalid." }
        val snapshot = ChapterFinalConsistencyMappingSnapshotV1(
            consistencyRequestSourceBindingHash = requireString(
                root.getValue("consistencyRequestSourceBindingHash"),
                "consistencyRequestSourceBindingHash",
            ),
            localReport = decodeLocalReport(requireObject(root.getValue("localReport"), "localReport")),
            expectation = decodeExpectation(requireObject(root.getValue("expectation"), "expectation")),
            sceneContract = decodeSceneContract(requireObject(root.getValue("sceneContract"), "sceneContract")),
            minimumBodyCodePoints = requireInt(root.getValue("minimumBodyCodePoints"), "minimumBodyCodePoints"),
            totalRevisionAttemptsUsed = requireInt(root.getValue("totalRevisionAttemptsUsed"), "totalRevisionAttemptsUsed"),
            revisionStageMaximumAttempts = requireInt(
                root.getValue("revisionStageMaximumAttempts"),
                "revisionStageMaximumAttempts",
            ),
            narrativeExpectation = root["narrativeExpectation"]?.let {
                decodeNarrativeExpectation(requireObject(it, "narrativeExpectation"))
            },
        )
        verifyCrossObject(snapshot)
        return snapshot
    }

    fun contentHash(value: String): String {
        val snapshot = parseAndVerify(value)
        val canonical = encode(snapshot)
        require(utf8ByteCount(canonical) <= MAX_SNAPSHOT_BYTES) {
            "Canonical snapshot must not exceed $MAX_SNAPSHOT_BYTES UTF-8 bytes."
        }
        return sha256(canonical)
    }

    private fun verifyBoundMatchesSpec(
        boundRequest: BoundChapterConsistencyCheckRequest,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ) {
        verifyExpectationMatchesSpec(boundRequest.expectation, boundRequest.localReport, spec)
    }

    private fun verifyExpectationMatchesSpec(
        expectation: ChapterConsistencyExpectation,
        localReport: ChapterLocalConsistencyReport,
        spec: ChapterCandidateConsistencyRoutingSpecV1,
    ) {
        require(expectation.sourceChapterVersionId == spec.candidate.chapterVersionId) {
            "Bound expectation source chapter version must match the routing candidate."
        }
        require(expectation.sourceChapterContentHash == spec.candidate.contentHash) {
            "Bound expectation source content hash must match the routing candidate."
        }
        require(expectation.chapterId == spec.candidate.chapterId) {
            "Bound expectation chapter id must match the routing candidate."
        }
        require(expectation.chapterIndex == spec.candidate.chapterIndex) {
            "Bound expectation chapter index must match the routing candidate."
        }
        require(
            expectation.bodyCodePointCount ==
                spec.candidateContent.codePointCount(0, spec.candidateContent.length),
        ) {
            "Bound expectation body code point count must match the routing candidate content."
        }
        require(localReport.contentHash == expectation.sourceChapterContentHash)
    }

    private fun verifyCrossObject(snapshot: ChapterFinalConsistencyMappingSnapshotV1) {
        require(HASH.matches(snapshot.consistencyRequestSourceBindingHash)) {
            "Snapshot consistencyRequestSourceBindingHash must be a lowercase SHA-256."
        }
        require(snapshot.localReport.contentHash == snapshot.expectation.sourceChapterContentHash) {
            "Snapshot local report content hash must equal the expectation source content hash."
        }
        require(snapshot.localReport.bodyCodePointCount == snapshot.expectation.bodyCodePointCount) {
            "Snapshot local report body code point count must equal the expectation body count."
        }
        require(snapshot.expectation.sceneContractHash == snapshot.sceneContract.contractHash) {
            "Snapshot expectation scene contract hash must equal the scene contract hash."
        }
        require(snapshot.expectation.expectedCriteria == snapshot.sceneContract.expectedCriteria) {
            "Snapshot expectation expected criteria must equal the scene contract expected criteria."
        }
        require(snapshot.expectation.requiredProcessNodeIds == snapshot.sceneContract.requiredProcessNodeIds.toSet()) {
            "Snapshot expectation required process set must equal the scene contract required process set."
        }
        require(ConsistencyCriterionV1.SOURCE_INTEGRITY in snapshot.localReport.checkedCriteria) {
            "Snapshot local report must include the SOURCE_INTEGRITY criterion."
        }
        require(ConsistencyCriterionV1.BASIC_READABILITY in snapshot.localReport.checkedCriteria) {
            "Snapshot local report must include the BASIC_READABILITY criterion."
        }
        require(snapshot.minimumBodyCodePoints in 1..1_000_000) {
            "Snapshot minimumBodyCodePoints must be in 1..1,000,000."
        }
        require(snapshot.totalRevisionAttemptsUsed >= 0) {
            "Snapshot totalRevisionAttemptsUsed must be non-negative."
        }
        require(snapshot.revisionStageMaximumAttempts in 1..16) {
            "Snapshot revisionStageMaximumAttempts must be in 1..16."
        }
    }

    private fun encode(snapshot: ChapterFinalConsistencyMappingSnapshotV1): String =
        JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(if (snapshot.narrativeExpectation == null) 1 else 2),
                "schemaId" to JsonPrimitive(
                    if (snapshot.narrativeExpectation == null) SCHEMA_ID else POST_ANALYSIS_SCHEMA_ID,
                ),
                "consistencyRequestSourceBindingHash" to JsonPrimitive(snapshot.consistencyRequestSourceBindingHash),
                "minimumBodyCodePoints" to JsonPrimitive(snapshot.minimumBodyCodePoints),
                "totalRevisionAttemptsUsed" to JsonPrimitive(snapshot.totalRevisionAttemptsUsed),
                "revisionStageMaximumAttempts" to JsonPrimitive(snapshot.revisionStageMaximumAttempts),
                "localReport" to encodeLocalReport(snapshot.localReport),
                "expectation" to encodeExpectation(snapshot.expectation),
                "sceneContract" to encodeSceneContract(snapshot.sceneContract),
            ).also { values ->
                snapshot.narrativeExpectation?.let { values["narrativeExpectation"] = encodeNarrativeExpectation(it) }
            },
        ).toString()

    private fun encodeNarrativeExpectation(
        expectation: ChapterPostAnalysisNarrativeExpectationV1,
    ): JsonObject = JsonObject(linkedMapOf(
        "activeNamespaces" to JsonArray(
            expectation.activeNamespaces.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) },
        ),
        "priorObligations" to JsonArray(expectation.priorObligations.sortedBy { it.obligationId }.map { item ->
            JsonObject(linkedMapOf(
                "obligationId" to JsonPrimitive(item.obligationId),
                "description" to JsonPrimitive(item.description),
                "dueChapterIndex" to (item.dueChapterIndex?.let(::JsonPrimitive) ?: JsonNull),
            ))
        }),
        "currentStateValues" to JsonArray(expectation.currentStateValues.entries
            .sortedBy { it.key.reference() }.map { (key, value) ->
                JsonObject(linkedMapOf(
                    "namespace" to JsonPrimitive(key.namespace.name),
                    "entityId" to JsonPrimitive(key.entityId),
                    "attribute" to JsonPrimitive(key.attribute),
                    "relatedEntityId" to (key.relatedEntityId?.let(::JsonPrimitive) ?: JsonNull),
                    "valueJson" to JsonPrimitive(value),
                ))
            }),
    ))

    private fun decodeNarrativeExpectation(obj: JsonObject): ChapterPostAnalysisNarrativeExpectationV1 {
        requireExactKeys(obj, NARRATIVE_KEYS, "narrativeExpectation")
        val namespaces = requireArray(obj.getValue("activeNamespaces"), "narrativeExpectation.activeNamespaces")
            .mapIndexed { index, value -> requireEnum<StoryStateNamespaceV1>(value, "activeNamespaces[$index]") }
            .toSet()
        val obligations = requireArray(obj.getValue("priorObligations"), "narrativeExpectation.priorObligations")
            .mapIndexed { index, value ->
                val item = requireObject(value, "priorObligations[$index]")
                requireExactKeys(item, OBLIGATION_KEYS, "priorObligations[$index]")
                NarrativeObligationV1(
                    obligationId = requireString(item.getValue("obligationId"), "obligationId"),
                    description = requireString(item.getValue("description"), "description"),
                    dueChapterIndex = requireNullableInt(item.getValue("dueChapterIndex"), "dueChapterIndex"),
                )
            }
        val states = requireArray(obj.getValue("currentStateValues"), "narrativeExpectation.currentStateValues")
            .mapIndexed { index, value ->
                val item = requireObject(value, "currentStateValues[$index]")
                requireExactKeys(item, STATE_VALUE_KEYS, "currentStateValues[$index]")
                StoryStateKeyV1(
                    namespace = requireEnum(item.getValue("namespace"), "namespace"),
                    entityId = requireString(item.getValue("entityId"), "entityId"),
                    attribute = requireString(item.getValue("attribute"), "attribute"),
                    relatedEntityId = requireNullableString(item.getValue("relatedEntityId"), "relatedEntityId"),
                ) to requireString(item.getValue("valueJson"), "valueJson")
            }.toMap()
        return ChapterPostAnalysisNarrativeExpectationV1(namespaces, obligations, states)
    }

    private fun encodeLocalReport(report: ChapterLocalConsistencyReport): JsonObject =
        JsonObject(
            linkedMapOf(
                "checkerVersion" to JsonPrimitive(report.checkerVersion),
                "contentHash" to JsonPrimitive(report.contentHash),
                "bodyCodePointCount" to JsonPrimitive(report.bodyCodePointCount),
                "bodyByteCount" to JsonPrimitive(report.bodyByteCount),
                "checkedCriteria" to JsonArray(
                    report.checkedCriteria.sortedBy { it.ordinal }.map { JsonPrimitive(it.name) },
                ),
                "issues" to JsonArray(report.issues.map(::encodeIssue)),
            ),
        )

    private fun encodeIssue(issue: DeterministicConsistencyIssue): JsonObject =
        JsonObject(
            linkedMapOf(
                "issueId" to JsonPrimitive(issue.issueId),
                "code" to JsonPrimitive(issue.code.name),
                "severity" to JsonPrimitive(issue.severity.name),
                "criterion" to JsonPrimitive(issue.criterion.name),
                "startCodePointInclusive" to JsonPrimitive(issue.evidenceRange.startCodePointInclusive),
                "endCodePointExclusive" to JsonPrimitive(issue.evidenceRange.endCodePointExclusive),
                "repairAction" to JsonPrimitive(issue.repairAction.name),
            ),
        )

    private fun encodeExpectation(expectation: ChapterConsistencyExpectation): JsonObject =
        JsonObject(
            linkedMapOf(
                "sourceChapterVersionId" to JsonPrimitive(expectation.sourceChapterVersionId),
                "sourceChapterContentHash" to JsonPrimitive(expectation.sourceChapterContentHash),
                "chapterId" to JsonPrimitive(expectation.chapterId),
                "chapterIndex" to JsonPrimitive(expectation.chapterIndex),
                "checkSourceSnapshotHash" to JsonPrimitive(expectation.checkSourceSnapshotHash),
                "sceneContractHash" to JsonPrimitive(expectation.sceneContractHash),
                "bodyCodePointCount" to JsonPrimitive(expectation.bodyCodePointCount),
                "expectedCriteria" to JsonArray(expectation.expectedCriteria.map { JsonPrimitive(it.name) }),
                "knownEntityIds" to JsonArray(expectation.knownEntityIds.sorted().map { JsonPrimitive(it) }),
                "knownForeshadowItemIds" to JsonArray(
                    expectation.knownForeshadowItemIds.sorted().map { JsonPrimitive(it) },
                ),
                "requiredProcessNodeIds" to JsonArray(
                    expectation.requiredProcessNodeIds.sorted().map { JsonPrimitive(it) },
                ),
            ),
        )

    private fun encodeSceneContract(contract: ChapterSceneConsistencyContractV1): JsonObject =
        JsonObject(
            linkedMapOf(
                "mode" to JsonPrimitive(contract.mode.name),
                "intimacyDetailLevel" to (contract.intimacyDetailLevel?.let { JsonPrimitive(it) } ?: JsonNull),
                "fadePolicy" to (contract.fadePolicy?.let { JsonPrimitive(it.name) } ?: JsonNull),
                "requiredKeyProcessCoveragePercent" to (
                    contract.requiredKeyProcessCoveragePercent?.let { JsonPrimitive(it) } ?: JsonNull
                    ),
                "fadeSubstitutionAllowed" to JsonPrimitive(contract.fadeSubstitutionAllowed),
                "requiresStateContinuity" to JsonPrimitive(contract.requiresStateContinuity),
                "requiresRelevantAftermath" to JsonPrimitive(contract.requiresRelevantAftermath),
                "requiredProcessNodeIds" to JsonArray(contract.requiredProcessNodeIds.map { JsonPrimitive(it) }),
                "expectedCriteria" to JsonArray(contract.expectedCriteria.map { JsonPrimitive(it.name) }),
                "contractHash" to JsonPrimitive(contract.contractHash),
            ),
        )

    private fun decodeLocalReport(obj: JsonObject): ChapterLocalConsistencyReport {
        requireExactKeys(obj, LOCAL_REPORT_KEYS, "localReport")
        val checkerVersion = requireString(obj.getValue("checkerVersion"), "localReport.checkerVersion")
        require(checkerVersion == ChapterLocalConsistencyCheckerV1.CHECKER_VERSION) {
            "Snapshot localReport.checkerVersion must equal the current checker version."
        }
        val contentHash = requireString(obj.getValue("contentHash"), "localReport.contentHash")
        require(HASH.matches(contentHash)) {
            "Snapshot localReport.contentHash must be a lowercase SHA-256."
        }
        val bodyCodePointCount = requireInt(obj.getValue("bodyCodePointCount"), "localReport.bodyCodePointCount")
        require(bodyCodePointCount in 1..MAX_BODY_CODE_POINTS) {
            "Snapshot localReport.bodyCodePointCount is out of range."
        }
        val bodyByteCount = requireInt(obj.getValue("bodyByteCount"), "localReport.bodyByteCount")
        require(bodyByteCount in 1..MAX_BODY_BYTES) {
            "Snapshot localReport.bodyByteCount is out of range."
        }
        val checkedCriteria = decodeCriteria(
            obj.getValue("checkedCriteria"),
            "localReport.checkedCriteria",
            requireNonEmpty = true,
        ).toCollection(linkedSetOf())
        val issues = requireArray(obj.getValue("issues"), "localReport.issues")
            .mapIndexed { index, item ->
                decodeIssue(
                    requireObject(item, "localReport.issues[$index]"),
                    "localReport.issues[$index]",
                )
            }
        require(issues.size <= ChapterLocalConsistencyCheckerV1.MAX_ISSUES) {
            "Snapshot localReport.issues must not exceed ${ChapterLocalConsistencyCheckerV1.MAX_ISSUES} entries."
        }
        require(issues.all { it.evidenceRange.endCodePointExclusive <= maxOf(1, bodyCodePointCount) }) {
            "Snapshot localReport.issues contains a range beyond the reported body."
        }
        return ChapterLocalConsistencyReport(
            checkerVersion = checkerVersion,
            contentHash = contentHash,
            bodyCodePointCount = bodyCodePointCount,
            bodyByteCount = bodyByteCount,
            checkedCriteria = checkedCriteria,
            issues = issues,
        )
    }

    private fun decodeIssue(obj: JsonObject, path: String): DeterministicConsistencyIssue {
        requireExactKeys(obj, ISSUE_KEYS, path)
        val issueId = requireString(obj.getValue("issueId"), "$path.issueId")
        require(IDENTIFIER.matches(issueId)) {
            "Snapshot $path.issueId must be a valid identifier."
        }
        return DeterministicConsistencyIssue(
            issueId = issueId,
            code = requireEnum(obj.getValue("code"), "$path.code"),
            severity = requireEnum(obj.getValue("severity"), "$path.severity"),
            criterion = requireEnum(obj.getValue("criterion"), "$path.criterion"),
            evidenceRange = ConsistencyEvidenceRange(
                startCodePointInclusive = requireInt(
                    obj.getValue("startCodePointInclusive"),
                    "$path.startCodePointInclusive",
                ),
                endCodePointExclusive = requireInt(
                    obj.getValue("endCodePointExclusive"),
                    "$path.endCodePointExclusive",
                ),
            ),
            repairAction = requireEnum(obj.getValue("repairAction"), "$path.repairAction"),
        )
    }

    private fun decodeExpectation(obj: JsonObject): ChapterConsistencyExpectation {
        requireExactKeys(obj, EXPECTATION_KEYS, "expectation")
        return ChapterConsistencyExpectation(
            sourceChapterVersionId = requireString(
                obj.getValue("sourceChapterVersionId"),
                "expectation.sourceChapterVersionId",
            ),
            sourceChapterContentHash = requireString(
                obj.getValue("sourceChapterContentHash"),
                "expectation.sourceChapterContentHash",
            ),
            chapterId = requireString(obj.getValue("chapterId"), "expectation.chapterId"),
            chapterIndex = requireInt(obj.getValue("chapterIndex"), "expectation.chapterIndex"),
            checkSourceSnapshotHash = requireString(
                obj.getValue("checkSourceSnapshotHash"),
                "expectation.checkSourceSnapshotHash",
            ),
            sceneContractHash = requireString(
                obj.getValue("sceneContractHash"),
                "expectation.sceneContractHash",
            ),
            bodyCodePointCount = requireInt(
                obj.getValue("bodyCodePointCount"),
                "expectation.bodyCodePointCount",
            ),
            expectedCriteria = decodeCriteria(
                obj.getValue("expectedCriteria"),
                "expectation.expectedCriteria",
                requireNonEmpty = true,
            ),
            knownEntityIds = decodeSortedIdentifierSet(
                obj.getValue("knownEntityIds"),
                "expectation.knownEntityIds",
            ),
            knownForeshadowItemIds = decodeSortedIdentifierSet(
                obj.getValue("knownForeshadowItemIds"),
                "expectation.knownForeshadowItemIds",
            ),
            requiredProcessNodeIds = decodeSortedIdentifierSet(
                obj.getValue("requiredProcessNodeIds"),
                "expectation.requiredProcessNodeIds",
            ),
        )
    }

    private fun decodeSceneContract(obj: JsonObject): ChapterSceneConsistencyContractV1 {
        requireExactKeys(obj, SCENE_CONTRACT_KEYS, "sceneContract")
        return ChapterSceneConsistencyContractV1(
            mode = requireEnum(obj.getValue("mode"), "sceneContract.mode"),
            intimacyDetailLevel = requireNullableInt(
                obj.getValue("intimacyDetailLevel"),
                "sceneContract.intimacyDetailLevel",
            ),
            fadePolicy = requireNullableEnum(obj.getValue("fadePolicy"), "sceneContract.fadePolicy"),
            requiredKeyProcessCoveragePercent = requireNullableInt(
                obj.getValue("requiredKeyProcessCoveragePercent"),
                "sceneContract.requiredKeyProcessCoveragePercent",
            ),
            fadeSubstitutionAllowed = requireBoolean(
                obj.getValue("fadeSubstitutionAllowed"),
                "sceneContract.fadeSubstitutionAllowed",
            ),
            requiresStateContinuity = requireBoolean(
                obj.getValue("requiresStateContinuity"),
                "sceneContract.requiresStateContinuity",
            ),
            requiresRelevantAftermath = requireBoolean(
                obj.getValue("requiresRelevantAftermath"),
                "sceneContract.requiresRelevantAftermath",
            ),
            requiredProcessNodeIds = requireStringList(
                obj.getValue("requiredProcessNodeIds"),
                "sceneContract.requiredProcessNodeIds",
            ),
            expectedCriteria = decodeCriteria(
                obj.getValue("expectedCriteria"),
                "sceneContract.expectedCriteria",
                requireNonEmpty = true,
            ),
            contractHash = requireString(obj.getValue("contractHash"), "sceneContract.contractHash"),
        )
    }

    private fun decodeCriteria(
        element: JsonElement,
        key: String,
        requireNonEmpty: Boolean,
    ): List<ConsistencyCriterionV1> {
        val values = requireArray(element, key).mapIndexed { index, item ->
            requireEnum<ConsistencyCriterionV1>(item, "$key[$index]")
        }
        require(values.distinct().size == values.size) {
            "Snapshot field '$key' must not contain duplicates."
        }
        require(values == values.sortedBy { it.ordinal }) {
            "Snapshot field '$key' must be strictly increasing by enum ordinal."
        }
        if (requireNonEmpty) {
            require(values.isNotEmpty()) {
                "Snapshot field '$key' must not be empty."
            }
        }
        return values
    }

    private fun decodeSortedIdentifierSet(element: JsonElement, key: String): LinkedHashSet<String> {
        val values = requireStringList(element, key)
        require(values.distinct().size == values.size) {
            "Snapshot field '$key' must not contain duplicates."
        }
        require(values == values.sorted()) {
            "Snapshot field '$key' must be sorted in dictionary order."
        }
        require(values.all(IDENTIFIER::matches)) {
            "Snapshot field '$key' contains an invalid identifier."
        }
        return values.toCollection(linkedSetOf())
    }

    private fun requireExactKeys(obj: JsonObject, expected: Set<String>, path: String) {
        require(obj.keys == expected) {
            "Snapshot $path must contain exactly the schema keys."
        }
    }

    private fun requireObject(element: JsonElement, key: String): JsonObject =
        element as? JsonObject
            ?: throw IllegalArgumentException("Snapshot field '$key' must be an object.")

    private fun requireArray(element: JsonElement, key: String): JsonArray =
        element as? JsonArray
            ?: throw IllegalArgumentException("Snapshot field '$key' must be an array.")

    private fun requireString(element: JsonElement, key: String): String {
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("Snapshot field '$key' must be a string.")
        require(primitive.isString) {
            "Snapshot field '$key' must be a string."
        }
        return primitive.content
    }

    private fun requireInt(element: JsonElement, key: String): Int {
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("Snapshot field '$key' must be an integer.")
        require(!primitive.isString) {
            "Snapshot field '$key' must be an integer, not a string."
        }
        return primitive.intOrNull
            ?: throw IllegalArgumentException("Snapshot field '$key' must be an integer.")
    }

    private fun requireNullableInt(element: JsonElement, key: String): Int? {
        if (element is JsonNull) return null
        return requireInt(element, key)
    }

    private fun requireNullableString(element: JsonElement, key: String): String? {
        if (element is JsonNull) return null
        return requireString(element, key)
    }

    private fun requireBoolean(element: JsonElement, key: String): Boolean {
        val primitive = element as? JsonPrimitive
            ?: throw IllegalArgumentException("Snapshot field '$key' must be a boolean.")
        require(!primitive.isString) {
            "Snapshot field '$key' must be a boolean, not a string."
        }
        return primitive.booleanOrNull
            ?: throw IllegalArgumentException("Snapshot field '$key' must be a boolean.")
    }

    private inline fun <reified T : Enum<T>> requireEnum(element: JsonElement, key: String): T {
        val name = requireString(element, key)
        return enumValues<T>().firstOrNull { it.name == name }
            ?: throw IllegalArgumentException(
                "Snapshot field '$key' has an unknown ${T::class.simpleName} value.",
            )
    }

    private inline fun <reified T : Enum<T>> requireNullableEnum(element: JsonElement, key: String): T? {
        if (element is JsonNull) return null
        return requireEnum(element, key)
    }

    private fun requireStringList(element: JsonElement, key: String): List<String> =
        requireArray(element, key).mapIndexed { index, item ->
            requireString(item, "$key[$index]")
        }
}

private fun StoryStateKeyV1.reference(): String =
    listOfNotNull(namespace.name, entityId, relatedEntityId, attribute).joinToString(":")

private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")

private fun utf8ByteCount(value: String): Int {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return try {
        bytes.size
    } finally {
        bytes.fill(0)
    }
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
