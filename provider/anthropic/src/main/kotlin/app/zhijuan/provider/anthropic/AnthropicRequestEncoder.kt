package app.zhijuan.provider.anthropic

import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderJsonSchema
import app.zhijuan.provider.common.ProviderRequestField
import app.zhijuan.provider.transport.SensitiveHttpBody
import app.zhijuan.provider.transport.SensitiveJsonBodyBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class AnthropicUnsupportedFieldsException(
    val fields: Set<ProviderRequestField>,
) : IllegalArgumentException("Anthropic request contains unsupported fields.")

internal object AnthropicRequestEncoder {
    private val strictJson = Json { isLenient = false }

    fun encode(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
        capabilities: ProviderCapabilitySnapshot,
    ): SensitiveHttpBody {
        val unsupported = request.unsupportedFields(profile, capabilities)
        if (unsupported.isNotEmpty()) throw AnthropicUnsupportedFieldsException(unsupported)
        val maximum = requireNotNull(request.parameters.maxOutputTokens) {
            "Anthropic Messages requires max_tokens."
        }
        request.parameters.temperature?.let { require(it <= 1.0) }
        request.structuredOutputSchema?.let(::validateSchema)

        val output = SensitiveJsonBodyBuilder()
        return try {
            output.ascii("{\"model\":")
            request.modelId.withValue(output::quoted)
            output.ascii(",\"max_tokens\":")
            output.ascii(maximum.toString())

            request.prompt.withParts { parts ->
                val userParts = parts.filter { it.layer == PromptLayer.USER_REQUEST }
                val fallbackToStage = userParts.isEmpty()
                val systemParts = parts.filter {
                    it.layer != PromptLayer.USER_REQUEST &&
                        !(fallbackToStage && it.layer == PromptLayer.STAGE_CONTRACT)
                }
                if (systemParts.isNotEmpty()) {
                    output.ascii(",\"system\":")
                    writeParts(output, systemParts)
                }
                output.ascii(",\"messages\":[{\"role\":\"user\",\"content\":")
                writeParts(
                    output,
                    if (fallbackToStage) parts.filter { it.layer == PromptLayer.STAGE_CONTRACT } else userParts,
                )
                output.ascii("}]")
            }

            output.ascii(",\"stream\":")
            output.ascii(if (request.stream) "true" else "false")
            request.parameters.temperature?.let {
                output.ascii(",\"temperature\":")
                output.ascii(it.toString())
            }
            request.parameters.topP?.let {
                output.ascii(",\"top_p\":")
                output.ascii(it.toString())
            }
            if (request.parameters.reasoningEffort != null || request.structuredOutputSchema != null) {
                output.ascii(",\"output_config\":{")
                var needsComma = false
                request.parameters.reasoningEffort?.let {
                    output.ascii("\"effort\":")
                    output.quoted(it.name.lowercase())
                    needsComma = true
                }
                request.structuredOutputSchema?.let { schema ->
                    if (needsComma) output.ascii(",")
                    output.ascii("\"format\":{\"type\":\"json_schema\",\"schema\":")
                    schema.withValue(output::rawJson)
                    output.ascii("}")
                }
                output.ascii("}")
            }
            output.ascii("}")
            output.toSensitiveBody()
        } finally {
            output.close()
        }
    }

    private fun writeParts(output: SensitiveJsonBodyBuilder, parts: List<PromptPart>) {
        require(parts.isNotEmpty())
        output.beginString()
        parts.forEachIndexed { index, part ->
            if (index > 0) output.stringFragment("\n\n")
            output.stringFragment("[")
            output.stringFragment(part.layer.name)
            output.stringFragment("]\n")
            part.content.withValue(output::stringFragment)
        }
        output.endString()
    }

    private fun validateSchema(schema: ProviderJsonSchema) {
        schema.withValue {
            require(strictJson.parseToJsonElement(it) is JsonObject) {
                "Structured output schema must be a JSON object."
            }
        }
    }
}
