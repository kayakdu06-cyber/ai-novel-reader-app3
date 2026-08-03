package app.zhijuan.reader.creation

import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookLengthPolicy
import app.zhijuan.core.model.BookPresentationPreset
import app.zhijuan.core.model.ContentPresentationMappingV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CreationStandardizerTest {
    @Test
    fun preservesRawInputNormalizesTextAndHashesContentDeterministically() {
        val rawIdea = "  雨夜\t重逢后，两个人被困在旧旅馆。  "
        val first = prepare(draft(storyIdea = rawIdea), snapshotId = "snapshot-a", bookId = "book-a", now = 1)
        val second = prepare(draft(storyIdea = rawIdea), snapshotId = "snapshot-b", bookId = "book-b", now = 99)

        assertEquals(rawIdea, parse(first.rawInputJson)["storyIdea"]!!.jsonPrimitive.content)
        assertEquals(
            "雨夜 重逢后，两个人被困在旧旅馆。",
            parse(first.normalizedInputJson)["storyIdea"]!!.jsonPrimitive.content,
        )
        assertEquals("雨夜 重逢后，两个人被困在旧旅馆", first.title)
        assertEquals(64, first.contentHash.length)
        assertEquals(first.contentHash, second.contentHash)
    }

    @Test
    fun explicitGenreWinsAndRecordsUserProvenance() {
        val prepared = prepare(draft(genreId = "mystery"))
        val genre = parse(prepared.genrePayloadJson)
        val provenance = parse(prepared.inferenceProvenanceJson)

        assertEquals("mystery", genre["id"]!!.jsonPrimitive.content)
        assertEquals("USER", genre["source"]!!.jsonPrimitive.content)
        assertEquals(
            "USER",
            provenance["genre"]!!.jsonObject["source"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun missingGenreUsesKeywordInferenceThenStableDefault() {
        val inferred = prepare(draft(storyIdea = "宗门小弟子带着残卷踏上修仙之路"))
        val fallback = prepare(draft(storyIdea = "一群普通人决定重新开始"))

        assertEquals("xianxia", parse(inferred.genrePayloadJson)["id"]!!.jsonPrimitive.content)
        assertEquals(
            "KEYWORD_INFERENCE",
            parse(inferred.genrePayloadJson)["source"]!!.jsonPrimitive.content,
        )
        assertEquals("urban", parse(fallback.genrePayloadJson)["id"]!!.jsonPrimitive.content)
        assertEquals(
            "SYSTEM_DEFAULT",
            parse(fallback.genrePayloadJson)["source"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun detailedPresentationKeepsGenreViolenceBaselineAndStartsWithAdultGateBlocked() {
        val prepared = prepare(
            draft(
                genreId = "romance",
                presentation = BookPresentationPreset.DETAILED,
            ),
        )
        val presentation = parse(prepared.presentationProfileJson)
        val profile = presentation["resolvedProfile"]!!.jsonObject
        val execution = presentation["relevantSceneExecution"]!!.jsonObject

        assertEquals(4, profile["narrativeDetailLevel"]!!.jsonPrimitive.int)
        assertEquals(4, profile["intimacyDetailLevel"]!!.jsonPrimitive.int)
        assertEquals(0, profile["conflictDetailLevel"]!!.jsonPrimitive.int)
        assertEquals(0, profile["graphicInjuryLevel"]!!.jsonPrimitive.int)
        assertEquals("UNKNOWN", presentation["relevantCharacterAdultGate"]!!.jsonPrimitive.content)
        assertEquals("BLOCKED", execution["status"]!!.jsonPrimitive.content)
        assertEquals("ADULT_STATUS_UNKNOWN", execution["reason"]!!.jsonPrimitive.content)
        assertFalse(execution["strictBodyAndSensoryContinuity"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun freezesMinimumTargetAndLengthPolicyVersion() {
        val short = prepare(
            draft(
                lengthMode = BookLengthMode.SHORT,
                minimum = 80,
                target = 80,
            ),
        )
        val medium = prepare(draft())
        val long = prepare(
            draft(
                lengthMode = BookLengthMode.LONG,
                minimum = 301,
                target = 888,
            ),
        )

        assertEquals(listOf(80, 300, 301), listOf(short, medium, long).map { it.minimumChapterCount })
        assertEquals(listOf(80, 300, 888), listOf(short, medium, long).map { it.targetChapterCount })
        assertTrue(listOf(short, medium, long).all { it.lengthPolicySchemaVersion == 1 })
    }

    @Test
    fun invalidLengthAndUnknownSchemasFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            prepare(draft(lengthMode = BookLengthMode.MEDIUM, minimum = 300, target = 299))
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepare(draft(optionCatalogSchemaVersion = 99))
        }
        assertThrows(IllegalArgumentException::class.java) {
            prepare(draft(genreId = "not-a-genre"))
        }
    }

    @Test
    fun modelPreferenceStoresOnlyConnectionAndModelReferences() {
        val prepared = prepare(draft())
        val model = parse(prepared.modelPreferenceJson)

        assertEquals(setOf("connectionId", "modelId", "source"), model.keys)
        assertEquals("connection-1", model["connectionId"]!!.jsonPrimitive.content)
        assertEquals("model-1", model["modelId"]!!.jsonPrimitive.content)
        assertFalse(prepared.modelPreferenceJson.contains("baseUrl", ignoreCase = true))
        assertFalse(prepared.modelPreferenceJson.contains("secret", ignoreCase = true))
    }

    private fun prepare(
        draft: MinimalBookDraft,
        snapshotId: String = "snapshot-1",
        bookId: String = "book-1",
        now: Long = 1,
    ) = CreationStandardizerV1.prepare(
        draft = draft,
        connection = CreationConnectionSelection("connection-1", "model-1"),
        snapshotId = snapshotId,
        bookId = bookId,
        createdAt = now,
    )

    private fun draft(
        storyIdea: String = "雨夜重逢后，两个人被困在海边旧旅馆。",
        genreId: String? = null,
        lengthMode: BookLengthMode = BookLengthMode.MEDIUM,
        minimum: Int = BookLengthPolicy.minimumChapterCount(lengthMode),
        target: Int = when (lengthMode) {
            BookLengthMode.SHORT -> 80
            BookLengthMode.MEDIUM -> 300
            BookLengthMode.LONG -> 500
        },
        presentation: BookPresentationPreset = BookPresentationPreset.BALANCED,
        optionCatalogSchemaVersion: Int = 1,
    ) = MinimalBookDraft(
        storyIdea = storyIdea,
        genreId = genreId,
        lengthMode = lengthMode,
        minimumChapterCount = minimum,
        targetChapterCount = target,
        lengthPolicySchemaVersion = 1,
        presentationDirective = ContentPresentationMappingV1.directiveFor(presentation),
        optionCatalogSchemaVersion = optionCatalogSchemaVersion,
    )

    private fun parse(value: String) = Json.parseToJsonElement(value).jsonObject
}
