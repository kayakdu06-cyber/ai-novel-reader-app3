package app.zhijuan.feature.generation

import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.ProviderJsonSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterDraftOutputContractTest {
    @Test
    fun providerSchemaAllowsOnlyOneRequiredBodyString() {
        val schema = ChapterDraftOutputContractV1.providerSchema.withValue {
            Json.parseToJsonElement(it) as JsonObject
        }

        assertEquals(false, (schema["additionalProperties"] as JsonPrimitive).content.toBoolean())
        assertEquals(
            listOf("body"),
            (schema["required"] as JsonArray).map { (it as JsonPrimitive).content },
        )
        val properties = schema["properties"] as JsonObject
        assertEquals(setOf("body"), properties.keys)
        assertEquals(
            "string",
            ((properties["body"] as JsonObject)["type"] as JsonPrimitive).content,
        )
    }

    @Test
    fun initialContractRequiresBareJsonAndContinuousBody() {
        val part = ChapterDraftOutputContractV1.initialStageContractPart()

        assertEquals(PromptLayer.STAGE_CONTRACT, part.layer)
        part.content.withValue { value ->
            assertTrue(value.contains("只能有 body 字段"))
            assertTrue(value.contains("不得用省略说明"))
            assertFalse(value.contains("```"))
        }
        assertFalse(part.toString().contains("完整候选正文"))
    }

    @Test
    fun requestMustCarryTheExactVersionedProviderSchema() {
        assertTrue(ChapterDraftOutputContractV1.matches(ChapterDraftOutputContractV1.providerSchema))
        assertFalse(ChapterDraftOutputContractV1.matches(null))
        assertFalse(
            ChapterDraftOutputContractV1.matches(
                ProviderJsonSchema.from("{\"type\":\"object\"}"),
            ),
        )
    }
}
