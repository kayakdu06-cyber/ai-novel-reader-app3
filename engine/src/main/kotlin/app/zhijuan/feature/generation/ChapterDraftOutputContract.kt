package app.zhijuan.feature.generation

import app.zhijuan.core.database.generation.PreparedChapterDraftContinuation
import app.zhijuan.core.task.ChapterDraftContinuationPolicyV1
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderPrompt
import app.zhijuan.provider.common.ProviderJsonSchema
import app.zhijuan.provider.common.SensitiveProviderText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Provider-facing envelope for streamed chapter prose. The deliberately tiny object lets the
 * incremental decoder persist readable body text without ever committing partial JSON syntax.
 */
object ChapterDraftOutputContractV1 {
    const val SCHEMA_ID = ChapterDraftContinuationPolicyV1.OUTPUT_CONTRACT_ID

    val providerSchema: ProviderJsonSchema = ProviderJsonSchema.from(
        """
        {
          "type": "object",
          "additionalProperties": false,
          "required": ["body"],
          "properties": {
            "body": {
              "type": "string",
              "minLength": 1,
              "maxLength": 4194304
            }
          }
        }
        """.trimIndent(),
    )

    fun initialStageContractPart(): PromptPart = PromptPart(
        PromptLayer.STAGE_CONTRACT,
        SensitiveProviderText.from(
            """
            只输出一个符合 $SCHEMA_ID 的 JSON object；对象必须且只能有 body 字段，body 为本章完整候选正文。
            不要输出 Markdown、代码围栏、解释、标题前缀、状态摘要、第二个候选或对象之外的任何字符。
            body 必须从本章第一字开始连续写到本次可完成的位置；不得用省略说明代替场景过程。
            """.trimIndent(),
        ),
    )

    fun continuationParts(prepared: PreparedChapterDraftContinuation): List<PromptPart> {
        val contract = PromptPart(
            PromptLayer.STAGE_CONTRACT,
            SensitiveProviderText.from(
                """
                只输出一个符合 $SCHEMA_ID 的 JSON object；对象必须且只能有 body 字段。
                body 的开头必须逐码点精确复制 requiredBodyPrefix，随后只写尚未生成的新正文；不得改写、缩写、重复或解释已保存正文。
                不要输出 Markdown、代码围栏、解释、标题前缀、状态摘要、第二个候选或对象之外的任何字符。
                """.trimIndent(),
            ),
        )
        val request = prepared.withTail { tail ->
            prepared.withAnchor { anchor ->
                JsonObject(
                    mapOf(
                        "continuationIndex" to JsonPrimitive(prepared.continuationIndex),
                        "savedTail" to JsonPrimitive(tail),
                        "requiredBodyPrefix" to JsonPrimitive(anchor),
                    ),
                ).toString()
            }
        }
        return listOf(
            contract,
            PromptPart(PromptLayer.USER_REQUEST, SensitiveProviderText.from(request)),
        )
    }

    internal fun matches(schema: ProviderJsonSchema?): Boolean {
        if (schema == null) return false
        val expected = providerSchema.withValue { it }
        return schema.withValue { it == expected }
    }

    internal fun requireContinuationBinding(
        prompt: ProviderPrompt,
        prepared: PreparedChapterDraftContinuation,
    ) {
        val matched = prompt.withParts { parts ->
            parts.asSequence()
                .filter { it.layer == PromptLayer.USER_REQUEST }
                .mapNotNull { part ->
                    part.content.withValue { value ->
                        runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
                    }
                }
                .any { payload ->
                    if (payload.keys != CONTINUATION_KEYS) return@any false
                    val index = (payload["continuationIndex"] as? JsonPrimitive)?.intOrNull
                    val savedTail = (payload["savedTail"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                    val requiredPrefix = (payload["requiredBodyPrefix"] as? JsonPrimitive)
                        ?.takeIf(JsonPrimitive::isString)
                        ?.content
                    prepared.withTail { expectedTail ->
                        prepared.withAnchor { expectedAnchor ->
                            index == prepared.continuationIndex &&
                                savedTail == expectedTail && requiredPrefix == expectedAnchor
                        }
                    }
                }
        }
        require(matched) { "Continuation prompt does not bind the saved tail and exact prefix." }
    }

    private val CONTINUATION_KEYS = setOf("continuationIndex", "savedTail", "requiredBodyPrefix")
}
