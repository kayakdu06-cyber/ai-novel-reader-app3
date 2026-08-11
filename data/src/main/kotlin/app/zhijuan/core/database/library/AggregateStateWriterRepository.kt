package app.zhijuan.core.database.library

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.database.generation.ForeshadowProjectionSnapshotCodecV1
import app.zhijuan.core.database.memory.AggregateStateProjectionEntity
import app.zhijuan.core.database.memory.EntityEventEntity
import app.zhijuan.core.database.memory.ForeshadowItemEntity
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class AggregateStateWriteCommand(
    val plan: ChapterEditRebuildPlan,
    val chapterIndex: Int,
    val generatedAt: Long,
) {
    init {
        require(chapterIndex > 0 && generatedAt >= 0L) {
            "Aggregate-state write command is invalid."
        }
    }

    override fun toString(): String =
        "AggregateStateWriteCommand(chapterIndex=$chapterIndex, generatedAt=$generatedAt, " +
            "identifiers=redacted, hashes=redacted)"
}

data class AggregateStateWriteResult(
    val aggregateStateId: String,
    val bookId: String,
    val throughChapterIndex: Int,
    val sourceThroughChapterVersionId: String,
    val schemaVersion: Int,
    val contentHash: String,
    val status: DerivedDataStatus,
    val replayed: Boolean,
) {
    override fun toString(): String =
        "AggregateStateWriteResult(throughChapterIndex=$throughChapterIndex, " +
            "schemaVersion=$schemaVersion, status=$status, replayed=$replayed, " +
            "identifiers=redacted, hashes=redacted)"
}

internal data class AggregateEntityStateV1(
    val entityEventId: String,
    val entityId: String,
    val attributeKey: String,
    val sourceChapterVersionId: String,
    val storyOrder: Long,
    val storyTimeExpression: String?,
    val canonLevel: CanonLevel,
    val newValue: JsonElement,
)

internal data class AggregateStateSnapshotV1(
    val bookId: String,
    val throughChapterIndex: Int,
    val sourceChapterVersionId: String,
    val sourceChapterContentHash: String,
    val sourceTrackingProjectionId: String,
    val sourceTrackingStageId: String,
    val sourceMemorySnapshotHash: String,
    val priorForeshadowSnapshotHash: String,
    val sourceTrackingOutputHash: String,
    val sourceTrackingPayloadHash: String,
    val entityStates: List<AggregateEntityStateV1>,
    val activeForeshadows: List<ForeshadowItemEntity>,
)

internal data class EncodedAggregateStateSnapshotV1(
    val json: String,
    val hash: String,
)

/** Canonical, bounded CURRENT_STATE payload. It never contains chapter body or Provider metadata. */
internal object AggregateStateSnapshotCodecV1 {
    const val SCHEMA_VERSION = 1
    const val SCHEMA_ID = "zhijuan.aggregate-state.v1"
    const val MAX_ENTITY_STATES = 256
    const val MAX_ACTIVE_FORESHADOWS = 128
    const val MAX_SNAPSHOT_BYTES = 128 * 1_024

    private val ROOT_KEYS = setOf(
        "schema",
        "schemaVersion",
        "bookId",
        "throughChapterIndex",
        "sourceChapterVersionId",
        "sourceChapterContentHash",
        "sourceTrackingProjectionId",
        "sourceTrackingStageId",
        "sourceMemorySnapshotHash",
        "priorForeshadowSnapshotHash",
        "sourceTrackingOutputHash",
        "sourceTrackingPayloadHash",
        "entityStates",
        "activeForeshadows",
    )
    private val ENTITY_KEYS = setOf(
        "entityEventId",
        "entityId",
        "attributeKey",
        "sourceChapterVersionId",
        "storyOrder",
        "storyTimeExpression",
        "canonLevel",
        "newValue",
    )

    fun encode(snapshot: AggregateStateSnapshotV1): EncodedAggregateStateSnapshotV1 {
        validate(snapshot)
        val json = JsonObject(
            linkedMapOf(
                "schema" to JsonPrimitive(SCHEMA_ID),
                "schemaVersion" to JsonPrimitive(SCHEMA_VERSION),
                "bookId" to JsonPrimitive(snapshot.bookId),
                "throughChapterIndex" to JsonPrimitive(snapshot.throughChapterIndex),
                "sourceChapterVersionId" to JsonPrimitive(snapshot.sourceChapterVersionId),
                "sourceChapterContentHash" to JsonPrimitive(snapshot.sourceChapterContentHash),
                "sourceTrackingProjectionId" to JsonPrimitive(snapshot.sourceTrackingProjectionId),
                "sourceTrackingStageId" to JsonPrimitive(snapshot.sourceTrackingStageId),
                "sourceMemorySnapshotHash" to JsonPrimitive(snapshot.sourceMemorySnapshotHash),
                "priorForeshadowSnapshotHash" to JsonPrimitive(snapshot.priorForeshadowSnapshotHash),
                "sourceTrackingOutputHash" to JsonPrimitive(snapshot.sourceTrackingOutputHash),
                "sourceTrackingPayloadHash" to JsonPrimitive(snapshot.sourceTrackingPayloadHash),
                "entityStates" to JsonArray(snapshot.entityStates.map(::encodeEntityState)),
                "activeForeshadows" to JsonArray(snapshot.activeForeshadows.map { item ->
                    val encoded = ForeshadowProjectionSnapshotCodecV1.encode(item)
                    STRICT_JSON.parseToJsonElement(encoded.json)
                }),
            ),
        ).toString()
        require(utf8Size(json) <= MAX_SNAPSHOT_BYTES) {
            "Aggregate-state snapshot exceeds its storage limit."
        }
        return EncodedAggregateStateSnapshotV1(json = json, hash = sha256(json))
    }

    fun decodeAndVerify(json: String, expectedHash: String): AggregateStateSnapshotV1 {
        require(utf8Size(json) <= MAX_SNAPSHOT_BYTES && HASH.matches(expectedHash)) {
            "Aggregate-state snapshot envelope is invalid."
        }
        require(sha256(json) == expectedHash) { "Aggregate-state snapshot hash does not match." }
        val root = runCatching { STRICT_JSON.parseToJsonElement(json) as? JsonObject }
            .getOrNull()
            ?: throw IllegalArgumentException("Aggregate-state snapshot is not a JSON object.")
        require(root.keys == ROOT_KEYS) { "Aggregate-state snapshot fields are invalid." }
        require(root.requiredString("schema") == SCHEMA_ID && root.requiredInt("schemaVersion") == SCHEMA_VERSION) {
            "Aggregate-state snapshot schema is unsupported."
        }
        val snapshot = AggregateStateSnapshotV1(
            bookId = root.requiredString("bookId"),
            throughChapterIndex = root.requiredInt("throughChapterIndex"),
            sourceChapterVersionId = root.requiredString("sourceChapterVersionId"),
            sourceChapterContentHash = root.requiredString("sourceChapterContentHash"),
            sourceTrackingProjectionId = root.requiredString("sourceTrackingProjectionId"),
            sourceTrackingStageId = root.requiredString("sourceTrackingStageId"),
            sourceMemorySnapshotHash = root.requiredString("sourceMemorySnapshotHash"),
            priorForeshadowSnapshotHash = root.requiredString("priorForeshadowSnapshotHash"),
            sourceTrackingOutputHash = root.requiredString("sourceTrackingOutputHash"),
            sourceTrackingPayloadHash = root.requiredString("sourceTrackingPayloadHash"),
            entityStates = root.requiredArray("entityStates").map(::decodeEntityState),
            activeForeshadows = root.requiredArray("activeForeshadows").map { element ->
                val objectValue = element as? JsonObject
                    ?: throw IllegalArgumentException("Aggregate-state foreshadow entry is invalid.")
                val nestedJson = objectValue.toString()
                ForeshadowProjectionSnapshotCodecV1.decodeAndVerify(nestedJson, sha256(nestedJson))
            },
        )
        validate(snapshot)
        require(encode(snapshot).json == json) { "Aggregate-state snapshot is not canonical." }
        return snapshot
    }

    fun canonicalJsonElement(rawJson: String): JsonElement {
        require(utf8Size(rawJson) <= MAX_NESTED_JSON_BYTES) { "Aggregate-state value exceeds its limit." }
        return runCatching { canonicalize(STRICT_JSON.parseToJsonElement(rawJson)) }
            .getOrElse { throw IllegalArgumentException("Aggregate-state value is invalid JSON.") }
    }

    private fun encodeEntityState(state: AggregateEntityStateV1): JsonObject = JsonObject(
        linkedMapOf(
            "entityEventId" to JsonPrimitive(state.entityEventId),
            "entityId" to JsonPrimitive(state.entityId),
            "attributeKey" to JsonPrimitive(state.attributeKey),
            "sourceChapterVersionId" to JsonPrimitive(state.sourceChapterVersionId),
            "storyOrder" to JsonPrimitive(state.storyOrder),
            "storyTimeExpression" to state.storyTimeExpression.jsonValue(),
            "canonLevel" to JsonPrimitive(state.canonLevel.name),
            "newValue" to canonicalize(state.newValue),
        ),
    )

    private fun decodeEntityState(element: JsonElement): AggregateEntityStateV1 {
        val value = element as? JsonObject
            ?: throw IllegalArgumentException("Aggregate-state entity entry is invalid.")
        require(value.keys == ENTITY_KEYS) { "Aggregate-state entity fields are invalid." }
        return AggregateEntityStateV1(
            entityEventId = value.requiredString("entityEventId"),
            entityId = value.requiredString("entityId"),
            attributeKey = value.requiredString("attributeKey"),
            sourceChapterVersionId = value.requiredString("sourceChapterVersionId"),
            storyOrder = value.requiredLong("storyOrder"),
            storyTimeExpression = value.nullableString("storyTimeExpression"),
            canonLevel = value.requiredEnum("canonLevel"),
            newValue = canonicalize(value.getValue("newValue")),
        )
    }

    private fun validate(snapshot: AggregateStateSnapshotV1) {
        require(
            IDENTIFIER.matches(snapshot.bookId) &&
                IDENTIFIER.matches(snapshot.sourceChapterVersionId) &&
                IDENTIFIER.matches(snapshot.sourceTrackingProjectionId) &&
                IDENTIFIER.matches(snapshot.sourceTrackingStageId) &&
                listOf(
                    snapshot.sourceChapterContentHash,
                    snapshot.sourceMemorySnapshotHash,
                    snapshot.priorForeshadowSnapshotHash,
                    snapshot.sourceTrackingOutputHash,
                    snapshot.sourceTrackingPayloadHash,
                ).all(HASH::matches) &&
                snapshot.throughChapterIndex > 0,
        ) { "Aggregate-state snapshot provenance is invalid." }
        require(snapshot.entityStates.size <= MAX_ENTITY_STATES) {
            "Aggregate-state entity-state limit is exceeded."
        }
        require(snapshot.activeForeshadows.size <= MAX_ACTIVE_FORESHADOWS) {
            "Aggregate-state foreshadow limit is exceeded."
        }
        require(snapshot.entityStates == snapshot.entityStates.sortedWith(ENTITY_STATE_ORDER)) {
            "Aggregate-state entity states are not canonical."
        }
        require(
            snapshot.entityStates.map { it.entityId to it.attributeKey }.distinct().size ==
                snapshot.entityStates.size,
        ) { "Aggregate-state entity-state keys are duplicated." }
        snapshot.entityStates.forEach { state ->
            require(
                IDENTIFIER.matches(state.entityEventId) && IDENTIFIER.matches(state.entityId) &&
                    IDENTIFIER.matches(state.sourceChapterVersionId) &&
                    state.attributeKey.isNotBlank() && utf8Size(state.attributeKey) <= MAX_ATTRIBUTE_KEY_BYTES &&
                    state.storyOrder > 0,
            ) { "Aggregate-state entity-state provenance is invalid." }
            state.storyTimeExpression?.let { expression ->
                require(utf8Size(expression) <= MAX_STORY_TIME_BYTES) {
                    "Aggregate-state story-time expression exceeds its limit."
                }
            }
            require(utf8Size(canonicalize(state.newValue).toString()) <= MAX_NESTED_JSON_BYTES) {
                "Aggregate-state value exceeds its limit."
            }
        }
        require(
            snapshot.activeForeshadows == snapshot.activeForeshadows.sortedBy(ForeshadowItemEntity::foreshadowItemId) &&
                snapshot.activeForeshadows.map(ForeshadowItemEntity::foreshadowItemId).distinct().size ==
                snapshot.activeForeshadows.size,
        ) { "Aggregate-state foreshadows are not canonical." }
        snapshot.activeForeshadows.forEach { item ->
            require(item.bookId == snapshot.bookId) { "Aggregate-state foreshadow belongs to another book." }
            ForeshadowProjectionSnapshotCodecV1.encode(item)
        }
    }

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            linkedMapOf<String, JsonElement>().apply {
                element.entries.sortedBy(Map.Entry<String, JsonElement>::key).forEach { (key, value) ->
                    put(key, canonicalize(value))
                }
            },
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }

    private fun JsonObject.requiredArray(key: String): JsonArray = getValue(key) as? JsonArray
        ?: throw IllegalArgumentException("Aggregate-state array field is invalid.")

    private fun JsonObject.requiredString(key: String): String {
        val value = getValue(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("Aggregate-state string field is invalid.")
        require(value.isString)
        return value.contentOrNull
            ?: throw IllegalArgumentException("Aggregate-state string field is invalid.")
    }

    private fun JsonObject.nullableString(key: String): String? {
        val value = getValue(key)
        if (value is JsonNull) return null
        return (value as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull
            ?: throw IllegalArgumentException("Aggregate-state optional string field is invalid.")
    }

    private fun JsonObject.requiredInt(key: String): Int {
        val value = getValue(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("Aggregate-state integer field is invalid.")
        require(!value.isString)
        return value.content.toIntOrNull()
            ?: throw IllegalArgumentException("Aggregate-state integer field is invalid.")
    }

    private fun JsonObject.requiredLong(key: String): Long {
        val value = getValue(key) as? JsonPrimitive
            ?: throw IllegalArgumentException("Aggregate-state long field is invalid.")
        require(!value.isString)
        return value.content.toLongOrNull()
            ?: throw IllegalArgumentException("Aggregate-state long field is invalid.")
    }

    private inline fun <reified T : Enum<T>> JsonObject.requiredEnum(key: String): T {
        val name = requiredString(key)
        return enumValues<T>().singleOrNull { it.name == name }
            ?: throw IllegalArgumentException("Aggregate-state enum field is invalid.")
    }

    private fun String?.jsonValue(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private const val MAX_ATTRIBUTE_KEY_BYTES = 256
    private const val MAX_STORY_TIME_BYTES = 1_024
    private const val MAX_NESTED_JSON_BYTES = 16 * 1_024
    private val ENTITY_STATE_ORDER = compareBy<AggregateEntityStateV1>(
        AggregateEntityStateV1::entityId,
        AggregateEntityStateV1::attributeKey,
        AggregateEntityStateV1::entityEventId,
    )
}

/**
 * Writes one deterministic aggregate-state generation for an affected chapter.
 *
 * The previous aggregate is only an execution-order fence. Every payload is recomputed from
 * current-version-bound authoritative rows, so a corrupt or legacy aggregate cannot infect later state.
 */
class AggregateStateWriterRepository(private val database: ZhijuanDatabase) {
    suspend fun write(command: AggregateStateWriteCommand): AggregateStateWriteResult {
        validateCommand(command)
        return try {
            database.withTransaction { writeInTransaction(command, allowFreshWrite = true) }
        } catch (conflict: SQLiteConstraintException) {
            database.withTransaction { writeInTransaction(command, allowFreshWrite = false) }
        }
    }

    private suspend fun writeInTransaction(
        command: AggregateStateWriteCommand,
        allowFreshWrite: Boolean,
    ): AggregateStateWriteResult {
        val plan = command.plan
        val currentSnapshots = requireFrozenSourcesCurrent(plan)
        val through = currentSnapshots.singleOrNull { it.chapterIndex == command.chapterIndex }
            ?: throw IllegalArgumentException("Aggregate-state target is outside the frozen range.")
        val throughVersion = requireNotNull(database.libraryDao().findChapterVersion(through.chapterVersionId)) {
            "Aggregate-state source version is missing."
        }
        require(command.generatedAt >= throughVersion.createdAt) {
            "Aggregate-state generation time cannot precede its source version."
        }

        val memory = database.memoryDao()
        val tracking = requireNotNull(memory.findTrackingProjectionForVersion(through.chapterVersionId)) {
            "Aggregate-state source tracking projection is missing."
        }
        require(
            tracking.bookId == plan.request.bookId &&
                tracking.chapterVersionId == through.chapterVersionId &&
                tracking.chapterIndex == command.chapterIndex &&
                tracking.sourceChapterContentHash == through.contentHash &&
                tracking.status == DerivedDataStatus.VALID,
        ) { "Aggregate-state source tracking projection is invalid." }
        val entityEvents = memory.latestValidEntityStatesThrough(
            bookId = plan.request.bookId,
            targetChapterIndex = Math.addExact(command.chapterIndex, 1),
            limit = AggregateStateSnapshotCodecV1.MAX_ENTITY_STATES + 1,
        )
        require(entityEvents.size <= AggregateStateSnapshotCodecV1.MAX_ENTITY_STATES) {
            "Aggregate-state entity-state limit is exceeded."
        }
        require(
            memory.invalidActiveForeshadowProjectionCountThrough(
                bookId = plan.request.bookId,
                targetChapterIndex = Math.addExact(command.chapterIndex, 1),
            ) == 0,
        ) { "Aggregate-state foreshadow projection is outside the chapter boundary." }
        val foreshadows = memory.activeForeshadowsThroughProjection(
            bookId = plan.request.bookId,
            targetChapterIndex = Math.addExact(command.chapterIndex, 1),
            limit = AggregateStateSnapshotCodecV1.MAX_ACTIVE_FORESHADOWS + 1,
        )
        require(foreshadows.size <= AggregateStateSnapshotCodecV1.MAX_ACTIVE_FORESHADOWS) {
            "Aggregate-state foreshadow limit is exceeded."
        }
        val latestSourceTime = buildList {
            add(throughVersion.createdAt)
            add(tracking.updatedAt)
            entityEvents.mapTo(this, EntityEventEntity::createdAt)
            foreshadows.mapTo(this, ForeshadowItemEntity::updatedAt)
        }.maxOrNull() ?: throughVersion.createdAt
        require(command.generatedAt >= latestSourceTime) {
            "Aggregate-state generation time cannot precede its authoritative sources."
        }

        val snapshot = AggregateStateSnapshotV1(
            bookId = plan.request.bookId,
            throughChapterIndex = command.chapterIndex,
            sourceChapterVersionId = through.chapterVersionId,
            sourceChapterContentHash = through.contentHash,
            sourceTrackingProjectionId = tracking.projectionId,
            sourceTrackingStageId = tracking.generationStageId,
            sourceMemorySnapshotHash = tracking.sourceMemorySnapshotHash,
            priorForeshadowSnapshotHash = tracking.priorForeshadowSnapshotHash,
            sourceTrackingOutputHash = tracking.outputContentHash,
            sourceTrackingPayloadHash = tracking.payloadHash,
            entityStates = entityEvents.map { event ->
                AggregateEntityStateV1(
                    entityEventId = event.entityEventId,
                    entityId = event.entityId,
                    attributeKey = event.attributeKey,
                    sourceChapterVersionId = event.sourceChapterVersionId,
                    storyOrder = event.storyOrder,
                    storyTimeExpression = event.storyTimeExpression,
                    canonLevel = event.canonLevel,
                    newValue = AggregateStateSnapshotCodecV1.canonicalJsonElement(event.newValueJson),
                )
            }.sortedWith(compareBy(AggregateEntityStateV1::entityId, AggregateEntityStateV1::attributeKey, AggregateEntityStateV1::entityEventId)),
            activeForeshadows = foreshadows.sortedBy(ForeshadowItemEntity::foreshadowItemId),
        )
        val encoded = AggregateStateSnapshotCodecV1.encode(snapshot)
        val aggregateId = deterministicAggregateId(
            planHash = plan.planHash,
            chapterIndex = command.chapterIndex,
            sourceVersionId = through.chapterVersionId,
            contentHash = encoded.hash,
        )
        memory.findAggregateState(aggregateId)?.let { existing ->
            requireExactReplay(existing, command, snapshot, encoded)
            return existing.toResult(replayed = true)
        }
        require(allowFreshWrite) { "Aggregate-state write lost a concurrent slot race." }

        ChapterEditRebuildPlanRepository(database).requireCurrentMatchesInTransaction(plan)
        val targetStep = plan.steps.singleOrNull {
            it.type == ChapterEditRebuildStepType.REBUILD_AGGREGATE_STATE &&
                it.chapterIndex == command.chapterIndex
        } ?: throw IllegalArgumentException("Aggregate-state plan step is missing.")
        require(targetStep.state == ChapterEditRebuildStepState.READY) {
            "Aggregate-state plan step is not ready."
        }
        val existingValid = memory.aggregateStateHistoryForChapter(
            plan.request.bookId,
            command.chapterIndex,
        ).filter { it.status == DerivedDataStatus.VALID }
        require(existingValid.size <= 1) { "Aggregate-state slot has multiple valid heads." }
        existingValid.singleOrNull()?.let { head ->
            require(command.generatedAt >= head.updatedAt) {
                "Aggregate-state generation time cannot precede the current slot head."
            }
        }
        val staleCount = memory.staleAggregateStateSlot(
            bookId = plan.request.bookId,
            throughChapterIndex = command.chapterIndex,
            updatedAt = command.generatedAt,
        )
        require(staleCount == existingValid.size) { "Aggregate-state slot changed concurrently." }
        val entity = AggregateStateProjectionEntity(
            aggregateStateId = aggregateId,
            bookId = snapshot.bookId,
            throughChapterIndex = snapshot.throughChapterIndex,
            sourceThroughChapterVersionId = snapshot.sourceChapterVersionId,
            schemaVersion = AggregateStateSnapshotCodecV1.SCHEMA_VERSION,
            stateJson = encoded.json,
            contentHash = encoded.hash,
            status = DerivedDataStatus.VALID,
            createdAt = command.generatedAt,
            updatedAt = command.generatedAt,
        )
        memory.insertAggregateState(entity)
        require(memory.findAggregateState(aggregateId) == entity) {
            "Aggregate-state generation did not persist exactly."
        }
        return entity.toResult(replayed = false)
    }

    private suspend fun requireFrozenSourcesCurrent(
        plan: ChapterEditRebuildPlan,
    ): List<CurrentChapterVersionSnapshot> {
        require(
            plan.planSchemaVersion == PLAN_SCHEMA_VERSION &&
                plan.policyVersion == PLAN_POLICY_VERSION &&
                plan.futureChapterPolicy == FutureChapterPolicy.KEEP_EXISTING &&
                plan.laterBodiesRetained,
        ) { "Aggregate-state rebuild plan policy is unsupported." }
        val current = database.libraryDao().currentChapterVersionSnapshotsForBook(plan.request.bookId)
            .filter { it.chapterIndex >= plan.editedChapterIndex }
        require(current.size == plan.frozenChapters.size) {
            "Aggregate-state frozen chapter range changed."
        }
        current.zip(plan.frozenChapters).forEach { (actual, frozen) ->
            require(
                actual.chapterIndex == frozen.chapterIndex &&
                    sha256(actual.chapterId) == frozen.chapterIdHash &&
                    sha256(actual.chapterVersionId) == frozen.currentVersionIdHash &&
                    actual.contentHash == frozen.contentHash &&
                    actual.chapterStatus == frozen.status &&
                    actual.consistencyStatus == frozen.consistencyStatus,
            ) { "Aggregate-state frozen chapter source changed." }
        }
        return current
    }

    private fun requireExactReplay(
        existing: AggregateStateProjectionEntity,
        command: AggregateStateWriteCommand,
        snapshot: AggregateStateSnapshotV1,
        encoded: EncodedAggregateStateSnapshotV1,
    ) {
        val decoded = AggregateStateSnapshotCodecV1.decodeAndVerify(existing.stateJson, existing.contentHash)
        require(
            existing.bookId == snapshot.bookId &&
                existing.throughChapterIndex == snapshot.throughChapterIndex &&
                existing.sourceThroughChapterVersionId == snapshot.sourceChapterVersionId &&
                existing.schemaVersion == AggregateStateSnapshotCodecV1.SCHEMA_VERSION &&
                existing.stateJson == encoded.json &&
                existing.contentHash == encoded.hash &&
                existing.status == DerivedDataStatus.VALID &&
                existing.createdAt == command.generatedAt &&
                existing.updatedAt == command.generatedAt &&
                decoded == snapshot,
        ) { "Stored aggregate-state generation conflicts with replay evidence." }
    }

    private fun validateCommand(command: AggregateStateWriteCommand) {
        require(command.chapterIndex in command.plan.editedChapterIndex..command.plan.highestCommittedChapterIndex) {
            "Aggregate-state target is outside the rebuild range."
        }
    }

    private fun deterministicAggregateId(
        planHash: String,
        chapterIndex: Int,
        sourceVersionId: String,
        contentHash: String,
    ): String = "aggregate.${stableHash(planHash, chapterIndex, sourceVersionId, contentHash).take(48)}"

    private fun AggregateStateProjectionEntity.toResult(replayed: Boolean) = AggregateStateWriteResult(
        aggregateStateId = aggregateStateId,
        bookId = bookId,
        throughChapterIndex = throughChapterIndex,
        sourceThroughChapterVersionId = sourceThroughChapterVersionId,
        schemaVersion = schemaVersion,
        contentHash = contentHash,
        status = status,
        replayed = replayed,
    )

    private companion object {
        const val PLAN_SCHEMA_VERSION = 2
        const val PLAN_POLICY_VERSION = "zhijuan.chapter-edit-rebuild-plan.v2"
    }
}

private fun stableHash(vararg values: Any?): String {
    val digest = MessageDigest.getInstance("SHA-256")
    values.forEach { value ->
        val bytes = (value?.toString() ?: "<null>").toByteArray(Charsets.UTF_8)
        try {
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        } finally {
            bytes.fill(0)
        }
    }
    return digest.digest().toHex()
}

private fun sha256(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return try {
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
    } finally {
        bytes.fill(0)
    }
}

private fun utf8Size(value: String): Int {
    val bytes = value.toByteArray(Charsets.UTF_8)
    return try {
        bytes.size
    } finally {
        bytes.fill(0)
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private val STRICT_JSON = Json {
    isLenient = false
    ignoreUnknownKeys = false
    explicitNulls = true
}
private val IDENTIFIER = Regex("[A-Za-z0-9._:-]{1,128}")
private val HASH = Regex("[0-9a-f]{64}")
