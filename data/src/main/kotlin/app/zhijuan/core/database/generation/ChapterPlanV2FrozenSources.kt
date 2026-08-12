package app.zhijuan.core.database.generation

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Immutable request-preexisting manifests for one normal chapter-plan v2 Stage. The three JSON
 * documents remain schema-owned by the generation feature; this data-layer boundary freezes their
 * canonical bytes and cross-checks the authority hashes needed again at Provider-open and commit.
 */
class ChapterPlanV2FrozenSources private constructor(
    val expectationJson: String,
    val expectationHash: String,
    val activationManifestJson: String,
    val activationManifestHash: String,
    val activationHash: String,
    val policyManifestJson: String,
    val policyManifestHash: String,
    val policyCompilationHash: String,
    val contextEvidenceHash: String,
) {
    override fun toString(): String =
        "ChapterPlanV2FrozenSources(manifests=redacted, hashes=redacted)"

    companion object {
        fun freeze(
            expectationJson: String,
            activationManifestJson: String,
            activationHash: String,
            policyManifestJson: String,
            policyCompilationHash: String,
            contextEvidenceHash: String,
        ): ChapterPlanV2FrozenSources {
            require(listOf(activationHash, policyCompilationHash, contextEvidenceHash).all(HASH::matches)) {
                "Chapter-plan v2 authority hash is invalid."
            }
            val expectation = canonicalObject(expectationJson, "expectation")
            val activation = canonicalObject(activationManifestJson, "activation manifest")
            val policy = canonicalObject(policyManifestJson, "policy manifest")
            require(expectation.string("activationHash") == activationHash) {
                "Chapter-plan v2 expectation activation hash is inconsistent."
            }
            require(expectation.string("policyCompilationHash") == policyCompilationHash) {
                "Chapter-plan v2 expectation policy hash is inconsistent."
            }
            require(expectation.string("contextEvidenceHash") == contextEvidenceHash) {
                "Chapter-plan v2 expectation context evidence hash is inconsistent."
            }
            require(activation.string("activationHash") == activationHash) {
                "Chapter-plan v2 activation manifest hash binding is inconsistent."
            }
            require(policy.string("policyCompilationHash") == policyCompilationHash) {
                "Chapter-plan v2 policy manifest hash binding is inconsistent."
            }
            val canonicalExpectation = expectation.toString()
            val canonicalActivation = activation.toString()
            val canonicalPolicy = policy.toString()
            val totalBytes = canonicalExpectation.toByteArray().size +
                canonicalActivation.toByteArray().size + canonicalPolicy.toByteArray().size
            require(totalBytes in 6..MAXIMUM_MANIFEST_BYTES) {
                "Chapter-plan v2 frozen manifests exceed the Stage input budget."
            }
            return ChapterPlanV2FrozenSources(
                expectationJson = canonicalExpectation,
                expectationHash = sha256(canonicalExpectation),
                activationManifestJson = canonicalActivation,
                activationManifestHash = sha256(canonicalActivation),
                activationHash = activationHash,
                policyManifestJson = canonicalPolicy,
                policyManifestHash = sha256(canonicalPolicy),
                policyCompilationHash = policyCompilationHash,
                contextEvidenceHash = contextEvidenceHash,
            )
        }

        internal fun fromStageRoot(root: JsonObject): ChapterPlanV2FrozenSources {
            val frozen = freeze(
                expectationJson = root.objectValue("expectation").toString(),
                activationManifestJson = root.objectValue("activationManifest").toString(),
                activationHash = root.string("activationHash"),
                policyManifestJson = root.objectValue("policyManifest").toString(),
                policyCompilationHash = root.string("policyCompilationHash"),
                contextEvidenceHash = root.string("contextEvidenceHash"),
            )
            require(root.string("expectationHash") == frozen.expectationHash) {
                "Chapter-plan v2 expectation manifest changed after freezing."
            }
            require(root.string("activationManifestHash") == frozen.activationManifestHash) {
                "Chapter-plan v2 activation manifest changed after freezing."
            }
            require(root.string("policyManifestHash") == frozen.policyManifestHash) {
                "Chapter-plan v2 policy manifest changed after freezing."
            }
            return frozen
        }

        private fun canonicalObject(value: String, label: String): JsonObject {
            require(value.toByteArray().size in 2..MAXIMUM_MANIFEST_BYTES) {
                "Chapter-plan v2 $label size is invalid."
            }
            val parsed = runCatching { STRICT_JSON.parseToJsonElement(value) as JsonObject }
                .getOrElse { throw IllegalArgumentException("Chapter-plan v2 $label is not a strict JSON object.") }
            return canonicalize(parsed) as JsonObject
        }

        private fun canonicalize(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(
                element.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) },
            )
            is JsonArray -> JsonArray(element.map(::canonicalize))
            else -> element
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

        private val HASH = Regex("[0-9a-f]{64}")
        private val STRICT_JSON = Json { isLenient = false }
        private const val MAXIMUM_MANIFEST_BYTES = 48 * 1_024
    }
}

internal fun ChapterPlanV2FrozenSources.stageFields(): Map<String, JsonElement> = linkedMapOf(
    "expectation" to Json.parseToJsonElement(expectationJson),
    "expectationHash" to JsonPrimitive(expectationHash),
    "activationManifest" to Json.parseToJsonElement(activationManifestJson),
    "activationManifestHash" to JsonPrimitive(activationManifestHash),
    "activationHash" to JsonPrimitive(activationHash),
    "policyManifest" to Json.parseToJsonElement(policyManifestJson),
    "policyManifestHash" to JsonPrimitive(policyManifestHash),
    "policyCompilationHash" to JsonPrimitive(policyCompilationHash),
    "contextEvidenceHash" to JsonPrimitive(contextEvidenceHash),
)

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
        ?: throw IllegalArgumentException("Chapter-plan v2 string field is missing or invalid: $key")

private fun JsonObject.objectValue(key: String): JsonObject = this[key] as? JsonObject
    ?: throw IllegalArgumentException("Chapter-plan v2 object field is missing or invalid: $key")
