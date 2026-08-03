package app.zhijuan.provider.gemini

import app.zhijuan.provider.common.GenerationRequest
import app.zhijuan.provider.common.PromptLayer
import app.zhijuan.provider.common.PromptPart
import app.zhijuan.provider.common.ProviderCapabilitySnapshot
import app.zhijuan.provider.common.ProviderConnectionProfile
import app.zhijuan.provider.common.ProviderJsonSchema
import app.zhijuan.provider.common.ProviderModelId
import app.zhijuan.provider.common.ProviderRequestField
import app.zhijuan.provider.transport.SensitiveHttpBody
import app.zhijuan.provider.transport.SensitiveJsonBodyBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class GeminiUnsupportedFieldsException(
    val fields: Set<ProviderRequestField>,
) : IllegalArgumentException("Gemini request contains unsupported fields.")

internal object GeminiRequestEncoder {
    private val strictJson = Json { isLenient = false }

    fun encode(
        profile: ProviderConnectionProfile,
        request: GenerationRequest,
        capabilities: ProviderCapabilitySnapshot,
    ): SensitiveHttpBody {
        val unsupported = request.unsupportedFields(profile, capabilities)
        if (unsupported.isNotEmpty()) throw GeminiUnsupportedFieldsException(unsupported)
        request.parameters.temperature?.let { require(it <= 1.0) }
        request.parameters.seed?.let { require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }
        request.structuredOutputSchema?.let(::validateSchema)

        val output = SensitiveJsonBodyBuilder()
        return try {
            request.prompt.withParts { parts ->
                val userParts = parts.filter { it.layer == PromptLayer.USER_REQUEST }
                val fallbackToStage = userParts.isEmpty()
                val systemParts = parts.filter {
                    it.layer != PromptLayer.USER_REQUEST &&
                        !(fallbackToStage && it.layer == PromptLayer.STAGE_CONTRACT)
                }
                output.ascii("{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":")
                writeParts(
                    output,
                    if (fallbackToStage) parts.filter { it.layer == PromptLayer.STAGE_CONTRACT } else userParts,
                )
                output.ascii("}]}]")
                if (systemParts.isNotEmpty()) {
                    output.ascii(",\"systemInstruction\":{\"parts\":[{\"text\":")
                    writeParts(output, systemParts)
                    output.ascii("}]}")
                }
            }
            output.ascii(",\"store\":false")

            val hasGenerationConfig = request.parameters.temperature != null ||
                request.parameters.topP != null ||
                request.parameters.maxOutputTokens != null ||
                request.parameters.seed != null ||
                request.parameters.reasoningEffort != null ||
                request.structuredOutputSchema != null
            if (hasGenerationConfig) {
                output.ascii(",\"generationConfig\":{")
                var needsComma = false
                fun separator() {
                    if (needsComma) output.ascii(",")
                    needsComma = true
                }
                request.parameters.maxOutputTokens?.let {
                    separator()
                    output.ascii("\"maxOutputTokens\":")
                    output.ascii(it.toString())
                }
                request.parameters.temperature?.let {
                    separator()
                    output.ascii("\"temperature\":")
                    output.ascii(it.toString())
                }
                request.parameters.topP?.let {
                    separator()
                    output.ascii("\"topP\":")
                    output.ascii(it.toString())
                }
                request.parameters.seed?.let {
                    separator()
                    output.ascii("\"seed\":")
                    output.ascii(it.toString())
                }
                request.parameters.reasoningEffort?.let {
                    separator()
                    output.ascii("\"thinkingConfig\":{\"includeThoughts\":false,\"thinkingLevel\":")
                    output.quoted(it.name)
                    output.ascii("}")
                }
                request.structuredOutputSchema?.let { schema ->
                    separator()
                    output.ascii("\"responseFormat\":{\"text\":{\"mimeType\":\"application/json\",\"schema\":")
                    schema.withValue(output::rawJson)
                    output.ascii("}}")
                }
                output.ascii("}")
            }
            output.ascii("}")
            output.toSensitiveBody()
        } finally {
            output.close()
        }
    }

    fun generationPath(modelId: ProviderModelId, stream: Boolean): List<String> =
        modelId.withValue { raw ->
            val model = raw.removePrefix("models/")
            require(model.isNotBlank() && '/' !in model && '\\' !in model)
            require(!model.contains(':') && model.none(Char::isISOControl))
            listOf("models", model + if (stream) ":streamGenerateContent" else ":generateContent")
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
