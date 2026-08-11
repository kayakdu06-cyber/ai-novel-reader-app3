package app.zhijuan.core.task

import app.zhijuan.core.model.ConsistencyCriterionV1
import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.ConsistencyRepairActionV1
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.StoryEntityType
import java.security.MessageDigest

data class ConsistencyEvidenceRange(
    val startCodePointInclusive: Int,
    val endCodePointExclusive: Int,
) {
    init {
        require(startCodePointInclusive >= 0)
        require(endCodePointExclusive > startCodePointInclusive)
    }
}

data class DeterministicConsistencyIssue(
    val issueId: String,
    val code: ConsistencyIssueCode,
    val severity: ConsistencyIssueSeverity,
    val criterion: ConsistencyCriterionV1,
    val evidenceRange: ConsistencyEvidenceRange,
    val repairAction: ConsistencyRepairActionV1,
)

data class DeterministicEntityFactV1(
    val entityId: String,
    val entityType: StoryEntityType,
    val adultStatus: AdultStatus,
    val ageYears: Int?,
) {
    init {
        require(IDENTIFIER.matches(entityId))
    }
}

data class DeterministicEntityReferenceV1(
    val entityId: String,
    val adultRelevant: Boolean,
    val evidenceRange: ConsistencyEvidenceRange,
) {
    init {
        require(IDENTIFIER.matches(entityId))
    }
}

data class DeterministicCharacterReturnV1(
    val entityId: String,
    val unavailableAtChapterStart: Boolean,
    val returnExplained: Boolean,
    val evidenceRange: ConsistencyEvidenceRange,
) {
    init {
        require(IDENTIFIER.matches(entityId))
    }
}

data class DeterministicLocationConstraintV1(
    val entityId: String,
    val fromLocationEntityId: String,
    val toLocationEntityId: String,
    val travelConstraintSatisfied: Boolean,
    val evidenceRange: ConsistencyEvidenceRange,
) {
    init {
        require(listOf(entityId, fromLocationEntityId, toLocationEntityId).all(IDENTIFIER::matches))
    }
}

data class DeterministicItemOwnershipConstraintV1(
    val itemEntityId: String,
    val priorOwnerEntityId: String?,
    val currentOwnerEntityId: String,
    val ownershipChangeExplained: Boolean,
    val evidenceRange: ConsistencyEvidenceRange,
) {
    init {
        require(IDENTIFIER.matches(itemEntityId) && IDENTIFIER.matches(currentOwnerEntityId))
        require(priorOwnerEntityId == null || IDENTIFIER.matches(priorOwnerEntityId))
    }
}

data class DeterministicTimelineConstraintV1(
    val eventId: String,
    val orderSatisfied: Boolean,
    val evidenceRange: ConsistencyEvidenceRange,
) {
    init {
        require(IDENTIFIER.matches(eventId))
    }
}

data class DeterministicRequiredEventV1(
    val requiredEventId: String,
    val covered: Boolean,
    val evidenceRange: ConsistencyEvidenceRange,
) {
    init {
        require(IDENTIFIER.matches(requiredEventId))
    }
}

data class ChapterDeterministicConsistencyFactsV1(
    val currentChapterIndex: Int,
    val expectedChapterIndex: Int,
    val entities: List<DeterministicEntityFactV1>,
    val references: List<DeterministicEntityReferenceV1>,
    val characterReturns: List<DeterministicCharacterReturnV1>,
    val locationConstraints: List<DeterministicLocationConstraintV1>,
    val itemOwnershipConstraints: List<DeterministicItemOwnershipConstraintV1>,
    val timelineConstraints: List<DeterministicTimelineConstraintV1>,
    val requiredEvents: List<DeterministicRequiredEventV1>,
) {
    init {
        require(currentChapterIndex in 1..10_000 && expectedChapterIndex in 1..10_000)
        require(entities.size <= 256 && entities.map { it.entityId }.distinct().size == entities.size)
        require(references.size <= 512)
        require(characterReturns.size <= 256)
        require(locationConstraints.size <= 256)
        require(itemOwnershipConstraints.size <= 256)
        require(timelineConstraints.size <= 256)
        require(requiredEvents.size <= 256 && requiredEvents.map { it.requiredEventId }.distinct().size == requiredEvents.size)
    }

    override fun toString(): String =
        "ChapterDeterministicConsistencyFactsV1(chapterIndex=$currentChapterIndex, " +
            "entityCount=${entities.size}, referenceCount=${references.size}, content=redacted)"
}

data class ChapterLocalConsistencyInput(
    val chapterContent: String,
    val expectedContentHash: String,
    val minimumBodyCodePoints: Int,
    val maximumBodyBytes: Int = 4 * 1_024 * 1_024,
    val allowChapterHeading: Boolean = false,
    val duplicateParagraphMinimumCodePoints: Int = 64,
    val deterministicFacts: ChapterDeterministicConsistencyFactsV1? = null,
) {
    init {
        require(CONTENT_HASH.matches(expectedContentHash))
        require(minimumBodyCodePoints in 1..1_000_000)
        require(maximumBodyBytes in 1_024..4 * 1_024 * 1_024)
        require(duplicateParagraphMinimumCodePoints in 16..4_096)
        val bodyCodePoints = chapterContent.codePointCount(0, chapterContent.length)
        deterministicFacts?.allRanges()?.forEach { range ->
            require(range.endCodePointExclusive <= maxOf(1, bodyCodePoints)) {
                "Deterministic evidence range exceeds the candidate chapter."
            }
        }
    }

    override fun toString(): String =
        "ChapterLocalConsistencyInput(minimumBodyCodePoints=$minimumBodyCodePoints, " +
            "maximumBodyBytes=$maximumBodyBytes, content=redacted)"
}

data class ChapterLocalConsistencyReport(
    val checkerVersion: String,
    val contentHash: String,
    val bodyCodePointCount: Int,
    val bodyByteCount: Int,
    val checkedCriteria: Set<ConsistencyCriterionV1>,
    val issues: List<DeterministicConsistencyIssue>,
) {
    val blockerCount: Int = issues.count { it.severity == ConsistencyIssueSeverity.BLOCKER }
    val majorCount: Int = issues.count { it.severity == ConsistencyIssueSeverity.MAJOR }
    val minorCount: Int = issues.count { it.severity == ConsistencyIssueSeverity.MINOR }

    override fun toString(): String =
        "ChapterLocalConsistencyReport(codePoints=$bodyCodePointCount, bytes=$bodyByteCount, " +
            "issueCount=${issues.size}, content=redacted)"
}

object ChapterLocalConsistencyCheckerV1 {
    const val CHECKER_VERSION = "zhijuan.local-consistency.v1"
    const val MAX_ISSUES = 64

    private val BASIC_CRITERIA = setOf(
        ConsistencyCriterionV1.SOURCE_INTEGRITY,
        ConsistencyCriterionV1.BASIC_READABILITY,
    )

    private val STRUCTURED_CRITERIA = setOf(
        ConsistencyCriterionV1.ADULT_AND_IDENTITY_FACTS,
        ConsistencyCriterionV1.ENTITY_REFERENCES,
        ConsistencyCriterionV1.TIMELINE_ORDER,
        ConsistencyCriterionV1.LOCATION_AND_SPATIAL_CONTINUITY,
        ConsistencyCriterionV1.ITEM_OWNERSHIP,
        ConsistencyCriterionV1.CHARACTER_AVAILABILITY,
        ConsistencyCriterionV1.REQUIRED_EVENT_COVERAGE,
    )

    fun check(input: ChapterLocalConsistencyInput): ChapterLocalConsistencyReport {
        val body = input.chapterContent
        val codePointCount = body.codePointCount(0, body.length)
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val byteCount = bodyBytes.size
        val contentHash = try {
            sha256(bodyBytes)
        } finally {
            bodyBytes.fill(0)
        }
        val issues = mutableListOf<DeterministicConsistencyIssue>()
        fun add(
            code: ConsistencyIssueCode,
            severity: ConsistencyIssueSeverity,
            range: ConsistencyEvidenceRange,
            repair: ConsistencyRepairActionV1,
        ) {
            if (issues.size >= MAX_ISSUES) return
            issues += DeterministicConsistencyIssue(
                issueId = "local.${issues.size + 1}.${code.name.lowercase()}",
                code = code,
                severity = severity,
                criterion = localCriterion(code),
                evidenceRange = range,
                repairAction = repair,
            )
        }
        val wholeRange = ConsistencyEvidenceRange(0, maxOf(1, codePointCount))
        if (contentHash != input.expectedContentHash) {
            add(
                ConsistencyIssueCode.SOURCE_CONTENT_MISMATCH,
                ConsistencyIssueSeverity.BLOCKER,
                wholeRange,
                ConsistencyRepairActionV1.REVIEW_MANUALLY,
            )
        }
        if (body.isBlank()) {
            add(
                ConsistencyIssueCode.BODY_EMPTY,
                ConsistencyIssueSeverity.BLOCKER,
                wholeRange,
                ConsistencyRepairActionV1.REWRITE_RANGE,
            )
        } else if (codePointCount < input.minimumBodyCodePoints) {
            add(
                ConsistencyIssueCode.BODY_BELOW_PLAN_MINIMUM,
                ConsistencyIssueSeverity.MAJOR,
                wholeRange,
                ConsistencyRepairActionV1.REWRITE_RANGE,
            )
        }
        if (byteCount > input.maximumBodyBytes) {
            add(
                ConsistencyIssueCode.BODY_SIZE_LIMIT_EXCEEDED,
                ConsistencyIssueSeverity.BLOCKER,
                wholeRange,
                ConsistencyRepairActionV1.REVIEW_MANUALLY,
            )
        }
        val trimmed = body.trim()
        if (trimmed.startsWith("```") || trimmed.endsWith("```")) {
            add(
                ConsistencyIssueCode.CODE_FENCE_WRAPPER,
                ConsistencyIssueSeverity.BLOCKER,
                wholeRange,
                ConsistencyRepairActionV1.REWRITE_RANGE,
            )
        }
        if (!input.allowChapterHeading && body.lineSequence().any { CHAPTER_HEADING.matches(it) }) {
            add(
                ConsistencyIssueCode.UNEXPECTED_CHAPTER_HEADING,
                ConsistencyIssueSeverity.MAJOR,
                wholeRange,
                ConsistencyRepairActionV1.REWRITE_RANGE,
            )
        }
        duplicateParagraphRanges(body, input.duplicateParagraphMinimumCodePoints).forEach { range ->
            add(
                ConsistencyIssueCode.EXACT_DUPLICATE_PARAGRAPH,
                ConsistencyIssueSeverity.MAJOR,
                range,
                ConsistencyRepairActionV1.REMOVE_DUPLICATION,
            )
        }
        input.deterministicFacts?.let { facts ->
            checkStructuredFacts(facts, ::add)
        }
        return ChapterLocalConsistencyReport(
            checkerVersion = CHECKER_VERSION,
            contentHash = contentHash,
            bodyCodePointCount = codePointCount,
            bodyByteCount = byteCount,
            checkedCriteria = BASIC_CRITERIA + if (input.deterministicFacts == null) emptySet() else STRUCTURED_CRITERIA,
            issues = issues,
        )
    }

    private fun checkStructuredFacts(
        facts: ChapterDeterministicConsistencyFactsV1,
        add: (
            ConsistencyIssueCode,
            ConsistencyIssueSeverity,
            ConsistencyEvidenceRange,
            ConsistencyRepairActionV1,
        ) -> Unit,
    ) {
        val entities = facts.entities.associateBy { it.entityId }
        if (facts.currentChapterIndex != facts.expectedChapterIndex) {
            add(
                ConsistencyIssueCode.TIMELINE_ORDER_CONFLICT,
                ConsistencyIssueSeverity.BLOCKER,
                ConsistencyEvidenceRange(0, 1),
                ConsistencyRepairActionV1.RESTORE_FACT,
            )
        }
        facts.references.sortedWith(compareBy({ it.evidenceRange.startCodePointInclusive }, { it.entityId })).forEach { reference ->
            val entity = entities[reference.entityId]
            if (entity == null) {
                add(
                    ConsistencyIssueCode.UNKNOWN_ENTITY_REFERENCE,
                    ConsistencyIssueSeverity.BLOCKER,
                    reference.evidenceRange,
                    ConsistencyRepairActionV1.RESTORE_FACT,
                )
            } else if (reference.adultRelevant && (
                    entity.entityType != StoryEntityType.CHARACTER ||
                        entity.adultStatus != AdultStatus.CONFIRMED_ADULT ||
                        entity.ageYears == null || entity.ageYears < 18
                    )
            ) {
                add(
                    ConsistencyIssueCode.ADULT_FACT_CONFLICT,
                    ConsistencyIssueSeverity.BLOCKER,
                    reference.evidenceRange,
                    ConsistencyRepairActionV1.RESTORE_FACT,
                )
            }
        }
        facts.characterReturns.sortedBy { it.evidenceRange.startCodePointInclusive }.forEach { value ->
            if (value.unavailableAtChapterStart && !value.returnExplained) {
                add(
                    ConsistencyIssueCode.DEAD_OR_EXITED_CHARACTER_RETURN,
                    ConsistencyIssueSeverity.BLOCKER,
                    value.evidenceRange,
                    ConsistencyRepairActionV1.RESTORE_CONTINUITY,
                )
            }
        }
        facts.locationConstraints.sortedBy { it.evidenceRange.startCodePointInclusive }.forEach { value ->
            if (!value.travelConstraintSatisfied) {
                add(
                    ConsistencyIssueCode.LOCATION_TRAVEL_CONFLICT,
                    ConsistencyIssueSeverity.BLOCKER,
                    value.evidenceRange,
                    ConsistencyRepairActionV1.RESTORE_CONTINUITY,
                )
            }
        }
        val firstCurrentOwner = mutableMapOf<String, String>()
        facts.itemOwnershipConstraints.sortedWith(compareBy({ it.evidenceRange.startCodePointInclusive }, { it.itemEntityId }))
            .forEach { value ->
                val existingOwner = firstCurrentOwner.putIfAbsent(value.itemEntityId, value.currentOwnerEntityId)
                val simultaneousConflict = existingOwner != null && existingOwner != value.currentOwnerEntityId
                val unexplainedTransfer = value.priorOwnerEntityId != null &&
                    value.priorOwnerEntityId != value.currentOwnerEntityId && !value.ownershipChangeExplained
                if (simultaneousConflict || unexplainedTransfer) {
                    add(
                        ConsistencyIssueCode.ITEM_OWNERSHIP_CONFLICT,
                        ConsistencyIssueSeverity.BLOCKER,
                        value.evidenceRange,
                        ConsistencyRepairActionV1.RESTORE_FACT,
                    )
                }
            }
        facts.timelineConstraints.sortedBy { it.evidenceRange.startCodePointInclusive }.forEach { value ->
            if (!value.orderSatisfied) {
                add(
                    ConsistencyIssueCode.TIMELINE_ORDER_CONFLICT,
                    ConsistencyIssueSeverity.BLOCKER,
                    value.evidenceRange,
                    ConsistencyRepairActionV1.RESTORE_CONTINUITY,
                )
            }
        }
        facts.requiredEvents.sortedBy { it.requiredEventId }.forEach { value ->
            if (!value.covered) {
                add(
                    ConsistencyIssueCode.REQUIRED_EVENT_MISSING,
                    ConsistencyIssueSeverity.BLOCKER,
                    value.evidenceRange,
                    ConsistencyRepairActionV1.REWRITE_RANGE,
                )
            }
        }
    }

    private fun duplicateParagraphRanges(body: String, minimumCodePoints: Int): List<ConsistencyEvidenceRange> {
        val seen = mutableSetOf<String>()
        val ranges = mutableListOf<ConsistencyEvidenceRange>()
        var paragraphStartChar = 0
        PARAGRAPH_SEPARATOR.findAll(body).forEach { separator ->
            addDuplicateIfNeeded(body, paragraphStartChar, separator.range.first, minimumCodePoints, seen, ranges)
            paragraphStartChar = separator.range.last + 1
        }
        addDuplicateIfNeeded(body, paragraphStartChar, body.length, minimumCodePoints, seen, ranges)
        return ranges.take(MAX_ISSUES)
    }

    private fun addDuplicateIfNeeded(
        body: String,
        startChar: Int,
        endChar: Int,
        minimumCodePoints: Int,
        seen: MutableSet<String>,
        ranges: MutableList<ConsistencyEvidenceRange>,
    ) {
        if (startChar >= endChar) return
        val raw = body.substring(startChar, endChar)
        val leading = raw.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return
        val trailing = raw.indexOfLast { !it.isWhitespace() }
        val normalized = raw.substring(leading, trailing + 1).replace(INLINE_WHITESPACE, " ")
        if (normalized.codePointCount(0, normalized.length) < minimumCodePoints) return
        if (!seen.add(normalized)) {
            val absoluteStart = startChar + leading
            val absoluteEnd = startChar + trailing + 1
            ranges += ConsistencyEvidenceRange(
                startCodePointInclusive = body.codePointCount(0, absoluteStart),
                endCodePointExclusive = body.codePointCount(0, absoluteEnd),
            )
        }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private val CHAPTER_HEADING = Regex("^\\s{0,3}#{1,6}\\s+\\S.*$")
    private val PARAGRAPH_SEPARATOR = Regex("(?:\\r?\\n)[ \\t]*(?:\\r?\\n)+")
    private val INLINE_WHITESPACE = Regex("[ \\t\\r\\n]+")
}

private fun localCriterion(code: ConsistencyIssueCode): ConsistencyCriterionV1 = when (code) {
    ConsistencyIssueCode.SOURCE_CONTENT_MISMATCH -> ConsistencyCriterionV1.SOURCE_INTEGRITY
    ConsistencyIssueCode.ADULT_FACT_CONFLICT -> ConsistencyCriterionV1.ADULT_AND_IDENTITY_FACTS
    ConsistencyIssueCode.UNKNOWN_ENTITY_REFERENCE -> ConsistencyCriterionV1.ENTITY_REFERENCES
    ConsistencyIssueCode.TIMELINE_ORDER_CONFLICT -> ConsistencyCriterionV1.TIMELINE_ORDER
    ConsistencyIssueCode.LOCATION_TRAVEL_CONFLICT -> ConsistencyCriterionV1.LOCATION_AND_SPATIAL_CONTINUITY
    ConsistencyIssueCode.ITEM_OWNERSHIP_CONFLICT -> ConsistencyCriterionV1.ITEM_OWNERSHIP
    ConsistencyIssueCode.DEAD_OR_EXITED_CHARACTER_RETURN -> ConsistencyCriterionV1.CHARACTER_AVAILABILITY
    ConsistencyIssueCode.REQUIRED_EVENT_MISSING -> ConsistencyCriterionV1.REQUIRED_EVENT_COVERAGE
    else -> ConsistencyCriterionV1.BASIC_READABILITY
}

private fun ChapterDeterministicConsistencyFactsV1.allRanges(): List<ConsistencyEvidenceRange> = buildList {
    addAll(references.map { it.evidenceRange })
    addAll(characterReturns.map { it.evidenceRange })
    addAll(locationConstraints.map { it.evidenceRange })
    addAll(itemOwnershipConstraints.map { it.evidenceRange })
    addAll(timelineConstraints.map { it.evidenceRange })
    addAll(requiredEvents.map { it.evidenceRange })
}

private val CONTENT_HASH = Regex("[0-9a-f]{64}")
private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
