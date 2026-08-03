package app.zhijuan.core.task

import app.zhijuan.core.model.ConsistencyIssueCode
import app.zhijuan.core.model.ConsistencyIssueSeverity
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.StoryEntityType
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterLocalConsistencyCheckerTest {
    @Test
    fun cleanBodyPassesDeterministicChecks() {
        val body = "甲".repeat(80) + "\n\n" + "乙".repeat(80)
        val report = ChapterLocalConsistencyCheckerV1.check(input(body, minimum = 100))

        assertTrue(report.issues.isEmpty())
        assertEquals(0, report.blockerCount)
        assertEquals(0, report.majorCount)
        assertTrue(report.toString().contains("content=redacted"))
        assertFalse(report.toString().contains("甲"))
    }

    @Test
    fun sourceMismatchIsBlocker() {
        val body = "正文".repeat(80)
        val report = ChapterLocalConsistencyCheckerV1.check(
            input(body, minimum = 10).copy(expectedContentHash = "0".repeat(64)),
        )

        assertEquals(ConsistencyIssueCode.SOURCE_CONTENT_MISMATCH, report.issues.single().code)
        assertEquals(ConsistencyIssueSeverity.BLOCKER, report.issues.single().severity)
    }

    @Test
    fun blankBodyDoesNotAlsoReportMinimumLength() {
        val report = ChapterLocalConsistencyCheckerV1.check(input(" \n ", minimum = 100))

        assertTrue(report.issues.any { it.code == ConsistencyIssueCode.BODY_EMPTY })
        assertFalse(report.issues.any { it.code == ConsistencyIssueCode.BODY_BELOW_PLAN_MINIMUM })
    }

    @Test
    fun shortBodyHeadingAndFenceAreSeparateBoundedFindings() {
        val body = "```\n# 第一章\n很短\n```"
        val report = ChapterLocalConsistencyCheckerV1.check(input(body, minimum = 100))

        assertEquals(
            setOf(
                ConsistencyIssueCode.BODY_BELOW_PLAN_MINIMUM,
                ConsistencyIssueCode.CODE_FENCE_WRAPPER,
                ConsistencyIssueCode.UNEXPECTED_CHAPTER_HEADING,
            ),
            report.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun exactLongDuplicateParagraphReportsOnlyLaterOccurrence() {
        val paragraph = "这是用于确定性重复检测的长段落。".repeat(8)
        val body = "$paragraph\n\n中间段落不重复。\n\n$paragraph"
        val report = ChapterLocalConsistencyCheckerV1.check(input(body, minimum = 10))
        val duplicate = report.issues.single { it.code == ConsistencyIssueCode.EXACT_DUPLICATE_PARAGRAPH }

        assertTrue(duplicate.evidenceRange.startCodePointInclusive > paragraph.length)
        assertEquals(ConsistencyIssueSeverity.MAJOR, duplicate.severity)
    }

    @Test
    fun similarOrShortParagraphsAreNotGuessedAsDuplicates() {
        val body = "短句。\n\n短句。\n\n" + "甲".repeat(70) + "\n\n" + "甲".repeat(69) + "乙"
        val report = ChapterLocalConsistencyCheckerV1.check(input(body, minimum = 10))

        assertFalse(report.issues.any { it.code == ConsistencyIssueCode.EXACT_DUPLICATE_PARAGRAPH })
    }

    @Test
    fun structuredFactsDetectTheFrozenLocalConflictSetWithoutSemanticGuessing() {
        val body = "用于结构化冲突检查的普通正文。".repeat(30)
        val range = ConsistencyEvidenceRange(2, 8)
        val facts = ChapterDeterministicConsistencyFactsV1(
            currentChapterIndex = 4,
            expectedChapterIndex = 3,
            entities = listOf(
                DeterministicEntityFactV1(
                    "char.adult",
                    StoryEntityType.CHARACTER,
                    AdultStatus.CONFIRMED_ADULT,
                    24,
                ),
                DeterministicEntityFactV1(
                    "char.unknown",
                    StoryEntityType.CHARACTER,
                    AdultStatus.UNKNOWN,
                    null,
                ),
            ),
            references = listOf(
                DeterministicEntityReferenceV1("char.missing", false, range),
                DeterministicEntityReferenceV1("char.unknown", true, range),
            ),
            characterReturns = listOf(
                DeterministicCharacterReturnV1("char.adult", true, false, range),
            ),
            locationConstraints = listOf(
                DeterministicLocationConstraintV1("char.adult", "place.a", "place.b", false, range),
            ),
            itemOwnershipConstraints = listOf(
                DeterministicItemOwnershipConstraintV1("item.key", "char.adult", "char.unknown", false, range),
            ),
            timelineConstraints = listOf(
                DeterministicTimelineConstraintV1("event.2", false, range),
            ),
            requiredEvents = listOf(
                DeterministicRequiredEventV1("required.1", false, range),
            ),
        )
        val report = ChapterLocalConsistencyCheckerV1.check(
            input(body, minimum = 10).copy(deterministicFacts = facts),
        )

        assertEquals(
            setOf(
                ConsistencyIssueCode.TIMELINE_ORDER_CONFLICT,
                ConsistencyIssueCode.UNKNOWN_ENTITY_REFERENCE,
                ConsistencyIssueCode.ADULT_FACT_CONFLICT,
                ConsistencyIssueCode.DEAD_OR_EXITED_CHARACTER_RETURN,
                ConsistencyIssueCode.LOCATION_TRAVEL_CONFLICT,
                ConsistencyIssueCode.ITEM_OWNERSHIP_CONFLICT,
                ConsistencyIssueCode.REQUIRED_EVENT_MISSING,
            ),
            report.issues.map { it.code }.toSet(),
        )
        assertTrue(report.issues.all { it.severity == ConsistencyIssueSeverity.BLOCKER })
        assertTrue(report.checkedCriteria.size > 2)
    }

    @Test
    fun explainedTransitionsAndConfirmedAdultsPassStructuredChecks() {
        val body = "用于结构化正常路径检查的普通正文。".repeat(30)
        val range = ConsistencyEvidenceRange(1, 6)
        val facts = ChapterDeterministicConsistencyFactsV1(
            currentChapterIndex = 3,
            expectedChapterIndex = 3,
            entities = listOf(
                DeterministicEntityFactV1(
                    "char.adult",
                    StoryEntityType.CHARACTER,
                    AdultStatus.CONFIRMED_ADULT,
                    24,
                ),
            ),
            references = listOf(DeterministicEntityReferenceV1("char.adult", true, range)),
            characterReturns = listOf(DeterministicCharacterReturnV1("char.adult", true, true, range)),
            locationConstraints = listOf(
                DeterministicLocationConstraintV1("char.adult", "place.a", "place.b", true, range),
            ),
            itemOwnershipConstraints = listOf(
                DeterministicItemOwnershipConstraintV1("item.key", "char.adult", "char.adult", false, range),
            ),
            timelineConstraints = listOf(DeterministicTimelineConstraintV1("event.2", true, range)),
            requiredEvents = listOf(DeterministicRequiredEventV1("required.1", true, range)),
        )
        val report = ChapterLocalConsistencyCheckerV1.check(
            input(body, minimum = 10).copy(deterministicFacts = facts),
        )

        assertTrue(report.issues.isEmpty())
    }

    private fun input(body: String, minimum: Int) = ChapterLocalConsistencyInput(
        chapterContent = body,
        expectedContentHash = sha256(body),
        minimumBodyCodePoints = minimum,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
