package app.zhijuan.core.database.search

import app.zhijuan.core.database.memory.CanonFactEntity
import app.zhijuan.core.database.memory.ChapterSummaryEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.StoryEntity
import app.zhijuan.core.database.memory.TimelineEventEntity
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.StoryEntityType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemorySearchDocumentFactoryTest {
    @Test
    fun `source type names are a stable six-value contract`() {
        assertEquals(
            listOf(
                "STORY_ENTITY",
                "CHAPTER_SUMMARY",
                "ENTITY_EVENT",
                "CANON_FACT",
                "TIMELINE_EVENT",
                "FORESHADOW",
            ),
            enumValues<MemorySearchSourceTypeV1>().map { it.name },
        )
    }

    @Test
    fun `all six valid source types map required metadata`() {
        val story = requireNotNull(MemorySearchDocumentFactoryV1.from(story()))
        val summary = requireNotNull(MemorySearchDocumentFactoryV1.from(summary()))
        val event = requireNotNull(MemorySearchDocumentFactoryV1.from(event(), chapterIndex = 3))
        val fact = requireNotNull(MemorySearchDocumentFactoryV1.from(fact(), chapterIndex = 3))
        val timeline = requireNotNull(MemorySearchDocumentFactoryV1.from(timeline(), chapterIndex = 3))
        val foreshadow = requireNotNull(MemorySearchDocumentFactoryV1.from(foreshadow(), chapterIndex = 2))

        assertEquals(listOf(100, 85, 50, 100, 70, 90), listOf(
            story.importance,
            summary.importance,
            event.importance,
            fact.importance,
            timeline.importance,
            foreshadow.importance,
        ))
        assertEquals(listOf(null, 3, 3, 3, 3, 2), listOf(
            story.chapterIndex,
            summary.chapterIndex,
            event.chapterIndex,
            fact.chapterIndex,
            timeline.chapterIndex,
            foreshadow.chapterIndex,
        ))
        assertEquals(listOf(null, null, 42L, 10L, 12L, null), listOf(
            story.storyOrder,
            summary.storyOrder,
            event.storyOrder,
            fact.storyOrder,
            timeline.storyOrder,
            foreshadow.storyOrder,
        ))
        assertEquals(
            MemorySearchSourceTypeV1.entries.map { it.name },
            listOf(story, summary, event, fact, timeline, foreshadow).map { it.sourceType },
        )
        listOf(story, summary, event, fact, timeline, foreshadow).forEach { document ->
            assertEquals(0L, document.rowId)
            assertEquals(81, document.documentId.length)
            assertTrue(document.documentId.startsWith("memory_search_v1:"))
            assertTrue(document.sourceContentHash.matches(Regex("[0-9a-f]{64}")))
            assertTrue(document.searchTerms.all { it.code < 128 })
        }
    }

    @Test
    fun `chinese values become matching ascii tokens without source prose`() {
        val document = requireNotNull(MemorySearchDocumentFactoryV1.from(story()))
        val storedTokens = document.searchTerms.split(' ').toSet()

        assertFalse(document.searchTerms.contains("玄铁剑"))
        requireNotNull(SearchIndexText.matchExpression("玄铁剑"))
            .split(' ')
            .forEach { assertTrue(it in storedTokens) }
    }

    @Test
    fun `json object keys are excluded and key order is canonical`() {
        val first = requireNotNull(
            MemorySearchDocumentFactoryV1.from(summary(summaryJson = """{"人物":"张三","性格":"沉稳"}""")),
        )
        val reordered = requireNotNull(
            MemorySearchDocumentFactoryV1.from(summary(summaryJson = """{"性格":"沉稳","人物":"张三"}""")),
        )

        assertEquals(first.searchTerms, reordered.searchTerms)
        assertEquals(first.sourceContentHash, reordered.sourceContentHash)
        assertTrue("g5f20x4e09" in first.searchTerms)
        assertTrue("g6c89x7a33" in first.searchTerms)
        assertFalse("g4ebax7269" in first.searchTerms)
        assertFalse("g6027x683c" in first.searchTerms)
    }

    @Test
    fun `json array order remains content-significant`() {
        val first = requireNotNull(MemorySearchDocumentFactoryV1.from(summary(summaryJson = """["张三","李四"]""")))
        val reversed = requireNotNull(MemorySearchDocumentFactoryV1.from(summary(summaryJson = """["李四","张三"]""")))
        assertNotEquals(first.sourceContentHash, reversed.sourceContentHash)
    }

    @Test
    fun `non-recallable source states return null`() {
        assertNull(MemorySearchDocumentFactoryV1.from(story(archivedAt = 9)))
        assertNull(MemorySearchDocumentFactoryV1.from(summary(status = DerivedDataStatus.STALE)))
        assertNull(MemorySearchDocumentFactoryV1.from(summary(status = DerivedDataStatus.FAILED)))
        assertNull(MemorySearchDocumentFactoryV1.from(event(status = DerivedDataStatus.STALE), 3))
        assertNull(MemorySearchDocumentFactoryV1.from(fact(status = DerivedDataStatus.FAILED), 3))
        assertNull(MemorySearchDocumentFactoryV1.from(timeline(status = DerivedDataStatus.STALE), 3))
        assertNull(MemorySearchDocumentFactoryV1.from(foreshadow(memoryStatus = DerivedDataStatus.STALE), 2))
        assertNull(MemorySearchDocumentFactoryV1.from(foreshadow(status = ForeshadowStatus.RESOLVED), 2))
        assertNull(MemorySearchDocumentFactoryV1.from(foreshadow(status = ForeshadowStatus.ABANDONED), 2))
    }

    @Test
    fun `empty numeric-only and punctuation-only sources do not create fts rows`() {
        assertNull(MemorySearchDocumentFactoryV1.from(summary(summaryJson = "{}")))
        assertNull(MemorySearchDocumentFactoryV1.from(summary(summaryJson = """{"count":3}""")))
        assertNull(MemorySearchDocumentFactoryV1.from(story(name = "，。", aliasesJson = "[]", definitionJson = "{}")))
        assertNull(MemorySearchDocumentFactoryV1.from(foreshadow(description = "……"), 2))
    }

    @Test
    fun `opaque ids change identity but never enter search terms or content hash`() {
        val base = requireNotNull(MemorySearchDocumentFactoryV1.from(story()))
        val otherSource = requireNotNull(MemorySearchDocumentFactoryV1.from(story(entityId = "entity-secret-2")))
        val otherBook = requireNotNull(MemorySearchDocumentFactoryV1.from(story(bookId = "book-secret-2")))

        assertNotEquals(base.documentId, otherSource.documentId)
        assertNotEquals(base.documentId, otherBook.documentId)
        assertEquals(base.sourceContentHash, otherSource.sourceContentHash)
        assertEquals(base.sourceContentHash, otherBook.sourceContentHash)
        assertEquals(base.searchTerms, otherSource.searchTerms)
        assertEquals(base.searchTerms, otherBook.searchTerms)
        assertFalse("wentity" in base.searchTerms)
        assertFalse("wbook" in base.searchTerms)
    }

    @Test
    fun `changing readable content or retrieval metadata rotates source hash`() {
        val base = requireNotNull(MemorySearchDocumentFactoryV1.from(story()))
        val renamed = requireNotNull(MemorySearchDocumentFactoryV1.from(story(name = "玄铁重剑")))
        val summaryAtThree = requireNotNull(MemorySearchDocumentFactoryV1.from(summary(chapterIndex = 3)))
        val summaryAtFour = requireNotNull(MemorySearchDocumentFactoryV1.from(summary(chapterIndex = 4)))

        assertNotEquals(base.sourceContentHash, renamed.sourceContentHash)
        assertNotEquals(base.searchTerms, renamed.searchTerms)
        assertNotEquals(summaryAtThree.sourceContentHash, summaryAtFour.sourceContentHash)
    }

    @Test
    fun `canon importance mapping is fixed`() {
        val expected = mapOf(
            CanonLevel.HARD_CANON to 100,
            CanonLevel.STORY_CANON to 80,
            CanonLevel.PLAN_ONLY to 60,
            CanonLevel.INFERRED to 40,
        )
        expected.forEach { (level, importance) ->
            assertEquals(importance, requireNotNull(MemorySearchDocumentFactoryV1.from(fact(level = level), 3)).importance)
        }
    }

    @Test
    fun `invalid json and numeric bounds fail without echoing inputs`() {
        assertPrivateFailure("SENSITIVE_JSON_MARKER") {
            MemorySearchDocumentFactoryV1.from(summary(summaryJson = "SENSITIVE_JSON_MARKER"))
        }
        assertPrivateFailure("101") { MemorySearchDocumentFactoryV1.from(summary(importance = 101)) }
        assertPrivateFailure("-1") { MemorySearchDocumentFactoryV1.from(foreshadow(importance = -1), 2) }
        assertPrivateFailure("1500000") {
            MemorySearchDocumentFactoryV1.from(event(confidenceMicros = 1_500_000), 3)
        }
        assertPrivateFailure("book-secret") { MemorySearchDocumentFactoryV1.from(story(bookId = " ")) }
        assertPrivateFailure("chapter-secret") { MemorySearchDocumentFactoryV1.from(event(), chapterIndex = 0) }
    }

    @Test
    fun `size leaf-count and nesting limits fail closed`() {
        val oversizedMarker = "PRIVATE_OVERSIZED_MARKER"
        assertPrivateFailure(oversizedMarker) {
            MemorySearchDocumentFactoryV1.from(
                summary(summaryJson = "\"$oversizedMarker${"x".repeat(65_537)}\""),
            )
        }
        val manyLeaves = (1..513).joinToString(prefix = "[", postfix = "]") { "\"value-$it\"" }
        assertPrivateFailure("value-513") { MemorySearchDocumentFactoryV1.from(summary(summaryJson = manyLeaves)) }
        val deeplyNested = "[".repeat(66) + "\"deep-private-value\"" + "]".repeat(66)
        assertPrivateFailure("deep-private-value") {
            MemorySearchDocumentFactoryV1.from(summary(summaryJson = deeplyNested))
        }
        val longName = "私".repeat(16 * 1024 + 1)
        assertPrivateFailure("私") { MemorySearchDocumentFactoryV1.from(story(name = longName)) }
    }

    @Test
    fun `bounded large json leaf remains indexable and content sensitive`() {
        val longDetail = "optional-history-" + "x".repeat(40_000)
        val first = requireNotNull(
            MemorySearchDocumentFactoryV1.from(summary(summaryJson = """{"detail":"$longDetail"}""")),
        )
        val changed = requireNotNull(
            MemorySearchDocumentFactoryV1.from(summary(summaryJson = """{"detail":"${longDetail}y"}""")),
        )

        requireNotNull(SearchIndexText.matchExpression("optional history")).split(' ').forEach { token ->
            assertTrue(token in first.searchTerms.split(' '))
        }
        assertNotEquals(first.sourceContentHash, changed.sourceContentHash)
    }

    @Test
    fun `foreshadow visible entity ids are never indexed`() {
        val first = requireNotNull(MemorySearchDocumentFactoryV1.from(foreshadow(), 2))
        val changed = requireNotNull(
            MemorySearchDocumentFactoryV1.from(foreshadow(visibleIds = """["secret-entity-99"]"""), 2),
        )
        assertEquals(first.searchTerms, changed.searchTerms)
        assertEquals(first.sourceContentHash, changed.sourceContentHash)
        assertFalse("wsecret" in first.searchTerms)
    }

    private fun assertPrivateFailure(
        privateValue: String,
        block: () -> Unit,
    ) {
        val error = assertThrows(IllegalArgumentException::class.java, block)
        assertFalse(error.message.orEmpty().contains(privateValue))
    }

    private fun story(
        entityId: String = "entity-secret-1",
        bookId: String = "book-secret-1",
        name: String = "玄铁剑",
        aliasesJson: String = """["玄铁","铁剑"]""",
        definitionJson: String = """{"类型":"兵器","材质":"玄铁"}""",
        archivedAt: Long? = null,
    ) = StoryEntity(
        entityId = entityId,
        bookId = bookId,
        entityType = StoryEntityType.ITEM,
        canonicalName = name,
        aliasesJson = aliasesJson,
        stableDefinitionJson = definitionJson,
        adultStatus = AdultStatus.NOT_APPLICABLE,
        ageYears = null,
        sourceBibleRevisionId = "bible-1",
        createdAt = 1,
        updatedAt = 10,
        archivedAt = archivedAt,
    )

    private fun summary(
        summaryJson: String = """{"事件":"主角获得玄铁剑","地点":"藏剑阁"}""",
        importance: Int = 85,
        status: DerivedDataStatus = DerivedDataStatus.VALID,
        chapterIndex: Int = 3,
    ) = ChapterSummaryEntity(
        chapterSummaryId = "summary-1",
        bookId = "book-secret-1",
        chapterVersionId = "chapter-version-1",
        chapterIndex = chapterIndex,
        schemaVersion = 1,
        summaryJson = summaryJson,
        importance = importance,
        status = status,
        modelSnapshotJson = null,
        createdAt = 11,
        updatedAt = 12,
    )

    private fun event(
        status: DerivedDataStatus = DerivedDataStatus.VALID,
        confidenceMicros: Int = 500_000,
    ) = EntityEventEntity(
        entityEventId = "event-1",
        bookId = "book-secret-1",
        entityId = "entity-secret-1",
        sourceChapterVersionId = "chapter-version-1",
        storyOrder = 42,
        attributeKey = "性格",
        oldValueJson = """["沉稳"]""",
        newValueJson = """["沉稳","果决"]""",
        storyTimeExpression = "第三章",
        confidenceMicros = confidenceMicros,
        canonLevel = CanonLevel.STORY_CANON,
        evidenceJson = """{"来源":"第三章"}""",
        status = status,
        createdAt = 13,
    )

    private fun fact(
        level: CanonLevel = CanonLevel.HARD_CANON,
        status: DerivedDataStatus = DerivedDataStatus.VALID,
    ) = CanonFactEntity(
        canonFactId = "fact-1",
        bookId = "book-secret-1",
        entityId = "entity-secret-1",
        factText = "玄铁剑重铸于藏剑阁",
        factPayloadJson = """{"细节":"剑身刻有铭文"}""",
        canonLevel = level,
        scopeJson = """{"章节":"第三章"}""",
        sourceChapterVersionId = "chapter-version-1",
        sourceBibleRevisionId = null,
        validFromStoryOrder = 10,
        validToStoryOrder = null,
        conflictGroupId = null,
        status = status,
        createdAt = 14,
    )

    private fun timeline(status: DerivedDataStatus = DerivedDataStatus.VALID) = TimelineEventEntity(
        timelineEventId = "timeline-1",
        bookId = "book-secret-1",
        name = "藏剑阁取剑",
        participantsJson = """["主角","铸剑师"]""",
        locationEntityId = null,
        storyTimeExpression = "第三章",
        storyOrder = 12,
        constraintsJson = """{"条件":"完成拜师"}""",
        sourceChapterVersionId = "chapter-version-1",
        status = status,
        createdAt = 15,
    )

    private fun foreshadow(
        status: ForeshadowStatus = ForeshadowStatus.PLANTED,
        memoryStatus: DerivedDataStatus = DerivedDataStatus.VALID,
        description: String = "剑鞘暗藏机关，将在决战时启用",
        importance: Int = 90,
        visibleIds: String = """["secret-entity-9"]""",
    ) = ForeshadowItemEntity(
        foreshadowItemId = "foreshadow-1",
        bookId = "book-secret-1",
        description = description,
        foreshadowStatus = status,
        memoryStatus = memoryStatus,
        targetStartChapterIndex = 8,
        targetEndChapterIndex = 10,
        sourceChapterVersionId = "chapter-version-1",
        plantedChapterVersionId = null,
        resolvedChapterVersionId = null,
        visibleEntityIdsJson = visibleIds,
        importance = importance,
        source = MemorySource.CHAPTER_EXTRACTION,
        createdAt = 16,
        updatedAt = 17,
    )
}
