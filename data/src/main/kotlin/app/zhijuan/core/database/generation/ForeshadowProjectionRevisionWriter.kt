package app.zhijuan.core.database.generation

import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.database.memory.ForeshadowProjectionRevisionEntity
import app.zhijuan.core.database.memory.ForeshadowTransitionEntity
import app.zhijuan.core.database.memory.MemoryDao
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.MemorySource
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class EncodedForeshadowProjectionSnapshotV1(
    val json: String,
    val hash: String,
)

/** Strict, canonical codec for the complete mutable foreshadow projection after one transition. */
internal object ForeshadowProjectionSnapshotCodecV1 {
    const val SCHEMA_VERSION = 1
    const val MAX_SNAPSHOT_BYTES = 64 * 1_024

    fun encode(item: ForeshadowItemEntity): EncodedForeshadowProjectionSnapshotV1 {
        validate(item)
        val json = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(SCHEMA_VERSION),
                "itemId" to JsonPrimitive(item.foreshadowItemId),
                "bookId" to JsonPrimitive(item.bookId),
                "description" to JsonPrimitive(item.description),
                "foreshadowStatus" to JsonPrimitive(item.foreshadowStatus.name),
                "memoryStatus" to JsonPrimitive(item.memoryStatus.name),
                "targetStart" to item.targetStartChapterIndex.jsonValue(),
                "targetEnd" to item.targetEndChapterIndex.jsonValue(),
                "sourceVersion" to item.sourceChapterVersionId.jsonValue(),
                "plantedVersion" to item.plantedChapterVersionId.jsonValue(),
                "resolvedVersion" to item.resolvedChapterVersionId.jsonValue(),
                "visibleEntityIdsJson" to JsonPrimitive(item.visibleEntityIdsJson),
                "importance" to JsonPrimitive(item.importance),
                "source" to JsonPrimitive(item.source.name),
                "createdAt" to JsonPrimitive(item.createdAt),
                "updatedAt" to JsonPrimitive(item.updatedAt),
            ),
        ).toString()
        require(utf8Size(json) <= MAX_SNAPSHOT_BYTES) { "Foreshadow projection snapshot exceeds its storage limit." }
        return EncodedForeshadowProjectionSnapshotV1(json = json, hash = sha256(json))
    }

    fun decodeAndVerify(json: String, expectedHash: String): ForeshadowItemEntity {
        require(utf8Size(json) <= MAX_SNAPSHOT_BYTES && HASH.matches(expectedHash)) {
            "Foreshadow projection snapshot envelope is invalid."
        }
        require(sha256(json) == expectedHash) { "Foreshadow projection snapshot hash does not match." }
        val objectValue = runCatching { STRICT_JSON.parseToJsonElement(json) as? JsonObject }
            .getOrNull()
            ?: throw IllegalArgumentException("Foreshadow projection snapshot is not a JSON object.")
        require(objectValue.keys == KEYS) { "Foreshadow projection snapshot fields are invalid." }
        require(objectValue.requiredInt("schemaVersion") == SCHEMA_VERSION) {
            "Foreshadow projection snapshot schema is unsupported."
        }
        val item = ForeshadowItemEntity(
            foreshadowItemId = objectValue.requiredString("itemId"),
            bookId = objectValue.requiredString("bookId"),
            description = objectValue.requiredString("description"),
            foreshadowStatus = objectValue.requiredEnum("foreshadowStatus"),
            memoryStatus = objectValue.requiredEnum("memoryStatus"),
            targetStartChapterIndex = objectValue.nullableInt("targetStart"),
            targetEndChapterIndex = objectValue.nullableInt("targetEnd"),
            sourceChapterVersionId = objectValue.nullableString("sourceVersion"),
            plantedChapterVersionId = objectValue.nullableString("plantedVersion"),
            resolvedChapterVersionId = objectValue.nullableString("resolvedVersion"),
            visibleEntityIdsJson = objectValue.requiredString("visibleEntityIdsJson"),
            importance = objectValue.requiredInt("importance"),
            source = objectValue.requiredEnum("source"),
            createdAt = objectValue.requiredLong("createdAt"),
            updatedAt = objectValue.requiredLong("updatedAt"),
        )
        validate(item)
        require(encode(item).json == json) { "Foreshadow projection snapshot is not canonical." }
        return item
    }

    private fun validate(item: ForeshadowItemEntity) {
        require(IDENTIFIER.matches(item.foreshadowItemId) && IDENTIFIER.matches(item.bookId)) {
            "Foreshadow projection identifiers are invalid."
        }
        require(item.description.isNotBlank() && utf8Size(item.description) <= MAX_DESCRIPTION_BYTES) {
            "Foreshadow projection description is invalid."
        }
        require(item.memoryStatus == DerivedDataStatus.VALID && item.importance in 0..100) {
            "Foreshadow projection state is invalid."
        }
        require(item.targetStartChapterIndex == null || item.targetStartChapterIndex > 0)
        require(item.targetEndChapterIndex == null || item.targetEndChapterIndex > 0)
        require(
            item.targetStartChapterIndex == null || item.targetEndChapterIndex == null ||
                item.targetStartChapterIndex <= item.targetEndChapterIndex,
        ) { "Foreshadow projection target range is invalid." }
        listOf(
            item.sourceChapterVersionId,
            item.plantedChapterVersionId,
            item.resolvedChapterVersionId,
        ).filterNotNull().forEach { require(IDENTIFIER.matches(it)) { "Foreshadow projection version reference is invalid." } }
        require(item.createdAt >= 0L && item.updatedAt >= item.createdAt) {
            "Foreshadow projection timestamps are invalid."
        }
        validateVisibleEntityIds(item.visibleEntityIdsJson)
    }

    private fun validateVisibleEntityIds(json: String) {
        require(utf8Size(json) <= MAX_VISIBLE_IDS_BYTES) { "Foreshadow visible-entity list exceeds its limit." }
        val values = runCatching { STRICT_JSON.parseToJsonElement(json) as? JsonArray }
            .getOrNull()
            ?: throw IllegalArgumentException("Foreshadow visible-entity list is invalid.")
        require(values.size <= MAX_VISIBLE_ENTITY_IDS) { "Foreshadow visible-entity list exceeds its item limit." }
        val ids = values.map { element ->
            val primitive = element as? JsonPrimitive
                ?: throw IllegalArgumentException("Foreshadow visible-entity entry is invalid.")
            require(primitive.isString)
            primitive.contentOrNull?.takeIf(IDENTIFIER::matches)
                ?: throw IllegalArgumentException("Foreshadow visible-entity identifier is invalid.")
        }
        require(ids == ids.distinct().sorted()) { "Foreshadow visible-entity identifiers are not canonical." }
        require(JsonArray(ids.map(::JsonPrimitive)).toString() == json) {
            "Foreshadow visible-entity list is not canonical."
        }
    }

    private fun Int?.jsonValue(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
    private fun String?.jsonValue(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private fun JsonObject.requiredString(key: String): String {
        val value = getValue(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("Foreshadow projection snapshot string field is invalid.")
        require(value.isString)
        return value.contentOrNull
            ?: throw IllegalArgumentException("Foreshadow projection snapshot string field is invalid.")
    }

    private fun JsonObject.nullableString(key: String): String? {
        val value = getValue(key)
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw IllegalArgumentException("Foreshadow projection snapshot optional string is invalid.")
    }

    private fun JsonObject.requiredInt(key: String): Int {
        val value = getValue(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("Foreshadow projection snapshot integer field is invalid.")
        require(!value.isString)
        return value.content.toIntOrNull()
            ?: throw IllegalArgumentException("Foreshadow projection snapshot integer field is invalid.")
    }

    private fun JsonObject.nullableInt(key: String): Int? {
        val value = getValue(key)
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("Foreshadow projection snapshot optional integer is invalid.")
        require(!primitive.isString)
        return primitive.content.toIntOrNull()
            ?: throw IllegalArgumentException("Foreshadow projection snapshot optional integer is invalid.")
    }

    private fun JsonObject.requiredLong(key: String): Long {
        val value = getValue(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("Foreshadow projection snapshot long field is invalid.")
        require(!value.isString)
        return value.content.toLongOrNull()
            ?: throw IllegalArgumentException("Foreshadow projection snapshot long field is invalid.")
    }

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(key: String): T {
        val value = requiredString(key)
        return enumValues<T>().singleOrNull { it.name == value }
            ?: throw IllegalArgumentException("Foreshadow projection snapshot enum field is invalid.")
    }

    private fun utf8Size(value: String): Int = value.toByteArray(Charsets.UTF_8).let { bytes ->
        try {
            bytes.size
        } finally {
            bytes.fill(0)
        }
    }

    private fun sha256(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
        } finally {
            bytes.fill(0)
        }
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private val STRICT_JSON = Json { isLenient = false }
    private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
    private val HASH = Regex("[0-9a-f]{64}")
    private const val MAX_DESCRIPTION_BYTES = 16 * 1_024
    private const val MAX_VISIBLE_IDS_BYTES = 8 * 1_024
    private const val MAX_VISIBLE_ENTITY_IDS = 32
    private val KEYS = linkedSetOf(
        "schemaVersion",
        "itemId",
        "bookId",
        "description",
        "foreshadowStatus",
        "memoryStatus",
        "targetStart",
        "targetEnd",
        "sourceVersion",
        "plantedVersion",
        "resolvedVersion",
        "visibleEntityIdsJson",
        "importance",
        "source",
        "createdAt",
        "updatedAt",
    )
}

/** Writes and verifies revision rows from the actual post-CAS database projection. */
internal class ForeshadowProjectionRevisionWriterV1(
    private val memory: MemoryDao,
) {
    suspend fun persistAfterStates(
        bookId: String,
        chapterIndex: Int,
        sourceChapterVersionId: String,
        generationStageId: String,
        transitions: List<ForeshadowTransitionEntity>,
    ): List<ForeshadowProjectionRevisionEntity> {
        validateEnvelope(bookId, chapterIndex, sourceChapterVersionId, generationStageId, transitions)
        val revisions = transitions.sortedWith(TRANSITION_ORDER).map { transition ->
            val item = requireNotNull(memory.findForeshadow(transition.foreshadowItemId)) {
                "Foreshadow projection disappeared before revision capture."
            }
            requireAfterStateMatches(item, transition)
            val snapshot = ForeshadowProjectionSnapshotCodecV1.encode(item)
            ForeshadowProjectionRevisionEntity(
                revisionId = revisionId(transition.transitionId),
                bookId = bookId,
                foreshadowItemId = transition.foreshadowItemId,
                sourceChapterVersionId = sourceChapterVersionId,
                generationStageId = generationStageId,
                transitionId = transition.transitionId,
                chapterIndex = chapterIndex,
                storyOrder = transition.storyOrder,
                snapshotSchemaVersion = ForeshadowProjectionSnapshotCodecV1.SCHEMA_VERSION,
                snapshotJson = snapshot.json,
                snapshotHash = snapshot.hash,
                status = DerivedDataStatus.VALID,
                createdAt = transition.createdAt,
            )
        }
        if (revisions.isNotEmpty()) memory.insertForeshadowProjectionRevisions(revisions)
        return revisions
    }

    suspend fun requireStoredAfterStates(
        bookId: String,
        chapterIndex: Int,
        sourceChapterVersionId: String,
        generationStageId: String,
        transitions: List<ForeshadowTransitionEntity>,
    ): List<ForeshadowItemEntity> {
        validateEnvelope(bookId, chapterIndex, sourceChapterVersionId, generationStageId, transitions)
        val expected = transitions.sortedWith(TRANSITION_ORDER)
        val revisions = memory.foreshadowProjectionRevisionsForStage(generationStageId)
        require(revisions.size == expected.size) {
            "Completed foreshadow transitions do not have a complete revision ledger."
        }
        return revisions.zip(expected).map { (revision, transition) ->
            require(
                revision.bookId == bookId && revision.sourceChapterVersionId == sourceChapterVersionId &&
                    revision.generationStageId == generationStageId && revision.chapterIndex == chapterIndex,
            ) { "Foreshadow projection revision provenance changed after commit." }
            decodeAndVerifyStored(revision, transition)
        }
    }

    internal fun decodeAndVerifyStored(
        revision: ForeshadowProjectionRevisionEntity,
        transition: ForeshadowTransitionEntity,
    ): ForeshadowItemEntity {
        require(
            revision.revisionId == revisionId(transition.transitionId) &&
                revision.bookId == transition.bookId &&
                revision.foreshadowItemId == transition.foreshadowItemId &&
                revision.sourceChapterVersionId == transition.sourceChapterVersionId &&
                revision.generationStageId == transition.generationStageId &&
                revision.transitionId == transition.transitionId &&
                revision.storyOrder == transition.storyOrder &&
                revision.snapshotSchemaVersion == ForeshadowProjectionSnapshotCodecV1.SCHEMA_VERSION &&
                revision.status == DerivedDataStatus.VALID && transition.status == DerivedDataStatus.VALID &&
                revision.createdAt == transition.createdAt,
        ) { "Foreshadow projection revision provenance changed after commit." }
        val item = ForeshadowProjectionSnapshotCodecV1.decodeAndVerify(
            revision.snapshotJson,
            revision.snapshotHash,
        )
        requireAfterStateMatches(item, transition)
        return item
    }

    private fun validateEnvelope(
        bookId: String,
        chapterIndex: Int,
        sourceChapterVersionId: String,
        generationStageId: String,
        transitions: List<ForeshadowTransitionEntity>,
    ) {
        require(
            IDENTIFIER.matches(bookId) && IDENTIFIER.matches(sourceChapterVersionId) &&
                IDENTIFIER.matches(generationStageId) && chapterIndex > 0 && transitions.size <= MAX_TRANSITIONS,
        ) { "Foreshadow revision envelope is invalid." }
        require(transitions.map { it.transitionId }.distinct().size == transitions.size)
        require(transitions.map { it.foreshadowItemId }.distinct().size == transitions.size)
        transitions.forEach { transition ->
            require(
                IDENTIFIER.matches(transition.transitionId) && IDENTIFIER.matches(transition.foreshadowItemId) &&
                    transition.bookId == bookId && transition.sourceChapterVersionId == sourceChapterVersionId &&
                    transition.generationStageId == generationStageId && transition.storyOrder > 0L &&
                    transition.status == DerivedDataStatus.VALID && transition.createdAt >= 0L,
            ) { "Foreshadow transition provenance is invalid for revision capture." }
        }
    }

    private fun requireAfterStateMatches(
        item: ForeshadowItemEntity,
        transition: ForeshadowTransitionEntity,
    ) {
        require(
            item.foreshadowItemId == transition.foreshadowItemId && item.bookId == transition.bookId &&
                item.memoryStatus == DerivedDataStatus.VALID && item.foreshadowStatus == transition.toStatus &&
                item.sourceChapterVersionId == transition.sourceChapterVersionId &&
                item.updatedAt == transition.createdAt && item.createdAt <= item.updatedAt,
        ) { "Foreshadow projection does not match its committed transition." }
        when (transition.operation) {
            "PLANT" -> require(
                transition.fromStatus == null && transition.toStatus == ForeshadowStatus.PLANTED &&
                    item.plantedChapterVersionId == transition.sourceChapterVersionId &&
                    item.resolvedChapterVersionId == null,
            )
            "DEVELOP" -> require(
                transition.fromStatus in setOf(ForeshadowStatus.PLANTED, ForeshadowStatus.DEVELOPING) &&
                    transition.toStatus == ForeshadowStatus.DEVELOPING && item.resolvedChapterVersionId == null,
            )
            "RESOLVE" -> require(
                transition.fromStatus in setOf(ForeshadowStatus.PLANTED, ForeshadowStatus.DEVELOPING) &&
                    transition.toStatus == ForeshadowStatus.RESOLVED &&
                    item.resolvedChapterVersionId == transition.sourceChapterVersionId,
            )
            "ABANDON" -> require(
                transition.fromStatus in setOf(
                    ForeshadowStatus.PLANNED,
                    ForeshadowStatus.PLANTED,
                    ForeshadowStatus.DEVELOPING,
                ) && transition.toStatus == ForeshadowStatus.ABANDONED && item.resolvedChapterVersionId == null,
            )
            else -> throw IllegalArgumentException("Foreshadow transition operation is unsupported.")
        }
    }

    private fun revisionId(transitionId: String): String = "fsr-" + sha256("v1\n$transitionId")

    private fun sha256(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        } finally {
            bytes.fill(0)
        }
    }

    private companion object {
        const val MAX_TRANSITIONS = 64
        val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
        val TRANSITION_ORDER = compareBy<ForeshadowTransitionEntity>({ it.storyOrder }, { it.transitionId })
    }
}
