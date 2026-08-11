package app.zhijuan.reader.template

import app.zhijuan.core.database.template.StoredTemplateSource
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.TemplateOriginType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TemplateRestartDraftFactoryTest {
    private val factory = TemplateRestartDraftFactory()

    @Test
    fun `restarts through creation draft and keeps source plus categories`() {
        val result = factory.create(source())

        assertEquals("修仙世界中的旧约重启", result.draft.storyIdea)
        assertEquals("xianxia", result.draft.genreId)
        assertEquals(BookLengthMode.MEDIUM, result.draft.lengthMode)
        assertEquals(300, result.draft.targetChapterCount)
        assertEquals(BookPresentationPreset.DETAILED, result.draft.presentationPreset)
        assertEquals("book-old", result.provenance.sourceBookId)
        assertEquals(listOf("修仙", "情感"), result.provenance.categories)
    }

    @Test
    fun `rejects a shortened template instead of silently changing the policy`() {
        val invalid = source(structureJson =
            """{"lengthMode":"MEDIUM","minimumChapterCount":20,"targetChapterCount":20,"lengthPolicySchemaVersion":1}""",
        )

        assertThrows(IllegalArgumentException::class.java) { factory.create(invalid) }
    }

    private fun source(
        structureJson: String =
            """{"lengthMode":"MEDIUM","minimumChapterCount":300,"targetChapterCount":300,"lengthPolicySchemaVersion":1}""",
    ) = StoredTemplateSource(
        templateId = "template-1",
        revisionId = "revision-3",
        revisionNo = 3,
        displayName = "上一版修仙设定",
        description = "从旧书提取",
        originType = TemplateOriginType.BOOK_DERIVED,
        sourceBookId = "book-old",
        sourceBookTitle = "旧卷",
        categoryTags = listOf("修仙", "情感"),
        storySeedJson = """{"storyIdea":"修仙世界中的旧约重启"}""",
        genreJson = """{"id":"xianxia"}""",
        stableCharactersJson = """{"summary":"男主与盟友的稳定关系"}""",
        worldRulesJson = """{"summary":"境界与宗门规则"}""",
        writingStyleJson = """{"summary":"第三人称连续叙事"}""",
        structureJson = structureJson,
        presentationJson = """{"directive":{"preset":"DETAILED"}}""",
        contentRulesJson = """{"requiredElements":"保留旧约","excludedElements":"无"}""",
        generationStrategyJson = "{}",
        modelRolePreferencesJson = "{}",
        extensionJson = "{}",
        contentHash = "sha256",
    )
}
