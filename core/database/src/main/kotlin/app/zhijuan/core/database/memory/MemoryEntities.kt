package app.zhijuan.core.database.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.zhijuan.core.database.generation.GenerationStageEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.ChapterEntity
import app.zhijuan.core.database.library.ChapterVersionEntity
import app.zhijuan.core.model.AdultStatus
import app.zhijuan.core.model.CanonLevel
import app.zhijuan.core.model.DerivedDataStatus
import app.zhijuan.core.model.ForeshadowStatus
import app.zhijuan.core.model.MemorySource
import app.zhijuan.core.model.StoryEntityType

@Entity(
    tableName = "chapter_summary",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ChapterVersionEntity::class,
            parentColumns = ["chapter_version_id"],
            childColumns = ["chapter_version_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["chapter_version_id"], unique = true),
        Index(value = ["book_id", "chapter_index"]),
        Index(value = ["status"]),
    ],
)
data class ChapterSummaryEntity(
    @PrimaryKey @ColumnInfo(name = "chapter_summary_id") val chapterSummaryId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_version_id") val chapterVersionId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "schema_version") val schemaVersion: Int,
    @ColumnInfo(name = "summary_json") val summaryJson: String,
    val importance: Int,
    val status: DerivedDataStatus,
    @ColumnInfo(name = "model_snapshot_json") val modelSnapshotJson: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "story_entity",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(
            entity = StoryBibleRevisionEntity::class,
            parentColumns = ["book_id", "bible_revision_id"],
            childColumns = ["book_id", "source_bible_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["book_id", "entity_id"], unique = true),
        Index(value = ["book_id", "entity_type", "canonical_name"], unique = true),
        Index(value = ["book_id", "source_bible_revision_id"]),
    ],
)
data class StoryEntity(
    @PrimaryKey @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "entity_type") val entityType: StoryEntityType,
    @ColumnInfo(name = "canonical_name") val canonicalName: String,
    @ColumnInfo(name = "aliases_json") val aliasesJson: String,
    @ColumnInfo(name = "stable_definition_json") val stableDefinitionJson: String,
    @ColumnInfo(name = "adult_status") val adultStatus: AdultStatus,
    @ColumnInfo(name = "age_years") val ageYears: Int?,
    @ColumnInfo(name = "source_bible_revision_id") val sourceBibleRevisionId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
)

@Entity(
    tableName = "entity_event",
    foreignKeys = [
        ForeignKey(
            entity = StoryEntity::class,
            parentColumns = ["book_id", "entity_id"],
            childColumns = ["book_id", "entity_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["source_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "entity_id", "story_order"]),
        Index(value = ["source_chapter_version_id"]),
        Index(value = ["status"]),
    ],
)
data class EntityEventEntity(
    @PrimaryKey @ColumnInfo(name = "entity_event_id") val entityEventId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "entity_id") val entityId: String,
    @ColumnInfo(name = "source_chapter_version_id") val sourceChapterVersionId: String,
    @ColumnInfo(name = "story_order") val storyOrder: Long,
    @ColumnInfo(name = "attribute_key") val attributeKey: String,
    @ColumnInfo(name = "old_value_json") val oldValueJson: String?,
    @ColumnInfo(name = "new_value_json") val newValueJson: String,
    @ColumnInfo(name = "story_time_expression") val storyTimeExpression: String?,
    @ColumnInfo(name = "confidence_micros") val confidenceMicros: Int,
    @ColumnInfo(name = "canon_level") val canonLevel: CanonLevel,
    @ColumnInfo(name = "evidence_json") val evidenceJson: String,
    val status: DerivedDataStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "canon_fact",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = StoryEntity::class, parentColumns = ["book_id", "entity_id"], childColumns = ["book_id", "entity_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["source_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = StoryBibleRevisionEntity::class, parentColumns = ["bible_revision_id"], childColumns = ["source_bible_revision_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "entity_id", "status"]),
        Index(value = ["source_chapter_version_id"]),
        Index(value = ["source_bible_revision_id"]),
        Index(value = ["conflict_group_id"]),
    ],
)
data class CanonFactEntity(
    @PrimaryKey @ColumnInfo(name = "canon_fact_id") val canonFactId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "entity_id") val entityId: String?,
    @ColumnInfo(name = "fact_text") val factText: String,
    @ColumnInfo(name = "fact_payload_json") val factPayloadJson: String,
    @ColumnInfo(name = "canon_level") val canonLevel: CanonLevel,
    @ColumnInfo(name = "scope_json") val scopeJson: String,
    @ColumnInfo(name = "source_chapter_version_id") val sourceChapterVersionId: String?,
    @ColumnInfo(name = "source_bible_revision_id") val sourceBibleRevisionId: String?,
    @ColumnInfo(name = "valid_from_story_order") val validFromStoryOrder: Long?,
    @ColumnInfo(name = "valid_to_story_order") val validToStoryOrder: Long?,
    @ColumnInfo(name = "conflict_group_id") val conflictGroupId: String?,
    val status: DerivedDataStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "timeline_event",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = StoryEntity::class, parentColumns = ["book_id", "entity_id"], childColumns = ["book_id", "location_entity_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["source_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "story_order"]),
        Index(value = ["book_id", "location_entity_id"]),
        Index(value = ["source_chapter_version_id"]),
        Index(value = ["status"]),
    ],
)
data class TimelineEventEntity(
    @PrimaryKey @ColumnInfo(name = "timeline_event_id") val timelineEventId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val name: String,
    @ColumnInfo(name = "participants_json") val participantsJson: String,
    @ColumnInfo(name = "location_entity_id") val locationEntityId: String?,
    @ColumnInfo(name = "story_time_expression") val storyTimeExpression: String,
    @ColumnInfo(name = "story_order") val storyOrder: Long,
    @ColumnInfo(name = "constraints_json") val constraintsJson: String,
    @ColumnInfo(name = "source_chapter_version_id") val sourceChapterVersionId: String,
    val status: DerivedDataStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "foreshadow_item",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["source_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["planted_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["resolved_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "foreshadow_item_id"], unique = true),
        Index(value = ["book_id", "foreshadow_status"]),
        Index(value = ["source_chapter_version_id"]),
        Index(value = ["planted_chapter_version_id"]),
        Index(value = ["resolved_chapter_version_id"]),
        Index(value = ["memory_status"]),
    ],
)
data class ForeshadowItemEntity(
    @PrimaryKey @ColumnInfo(name = "foreshadow_item_id") val foreshadowItemId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    val description: String,
    @ColumnInfo(name = "foreshadow_status") val foreshadowStatus: ForeshadowStatus,
    @ColumnInfo(name = "memory_status") val memoryStatus: DerivedDataStatus,
    @ColumnInfo(name = "target_start_chapter_index") val targetStartChapterIndex: Int?,
    @ColumnInfo(name = "target_end_chapter_index") val targetEndChapterIndex: Int?,
    @ColumnInfo(name = "source_chapter_version_id") val sourceChapterVersionId: String?,
    @ColumnInfo(name = "planted_chapter_version_id") val plantedChapterVersionId: String?,
    @ColumnInfo(name = "resolved_chapter_version_id") val resolvedChapterVersionId: String?,
    @ColumnInfo(name = "visible_entity_ids_json") val visibleEntityIdsJson: String,
    val importance: Int,
    val source: MemorySource,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "chapter_tracking_projection",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = GenerationStageEntity::class, parentColumns = ["stage_id"], childColumns = ["generation_stage_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["chapter_version_id"], unique = true),
        Index(value = ["generation_stage_id"], unique = true),
        Index(value = ["book_id", "chapter_index"]),
        Index(value = ["status"]),
    ],
)
data class ChapterTrackingProjectionEntity(
    @PrimaryKey @ColumnInfo(name = "projection_id") val projectionId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "chapter_version_id") val chapterVersionId: String,
    @ColumnInfo(name = "chapter_index") val chapterIndex: Int,
    @ColumnInfo(name = "generation_stage_id") val generationStageId: String,
    @ColumnInfo(name = "source_chapter_content_hash") val sourceChapterContentHash: String,
    @ColumnInfo(name = "source_memory_snapshot_hash") val sourceMemorySnapshotHash: String,
    @ColumnInfo(name = "prior_foreshadow_snapshot_hash") val priorForeshadowSnapshotHash: String,
    @ColumnInfo(name = "output_content_hash") val outputContentHash: String,
    @ColumnInfo(name = "payload_hash") val payloadHash: String,
    val status: DerivedDataStatus,
    @ColumnInfo(name = "model_snapshot_json") val modelSnapshotJson: String,
    @ColumnInfo(name = "timeline_event_count") val timelineEventCount: Int,
    @ColumnInfo(name = "foreshadow_transition_count") val foreshadowTransitionCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "foreshadow_transition",
    foreignKeys = [
        ForeignKey(entity = BookEntity::class, parentColumns = ["book_id"], childColumns = ["book_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(
            entity = ForeshadowItemEntity::class,
            parentColumns = ["book_id", "foreshadow_item_id"],
            childColumns = ["book_id", "foreshadow_item_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(entity = ChapterVersionEntity::class, parentColumns = ["chapter_version_id"], childColumns = ["source_chapter_version_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = GenerationStageEntity::class, parentColumns = ["stage_id"], childColumns = ["generation_stage_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index(value = ["book_id", "foreshadow_item_id"]),
        Index(value = ["source_chapter_version_id"]),
        Index(value = ["generation_stage_id"]),
        Index(value = ["book_id", "story_order"]),
        Index(value = ["foreshadow_item_id", "source_chapter_version_id"], unique = true),
        Index(value = ["status"]),
    ],
)
data class ForeshadowTransitionEntity(
    @PrimaryKey @ColumnInfo(name = "transition_id") val transitionId: String,
    @ColumnInfo(name = "foreshadow_item_id") val foreshadowItemId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "source_chapter_version_id") val sourceChapterVersionId: String,
    @ColumnInfo(name = "generation_stage_id") val generationStageId: String,
    @ColumnInfo(name = "story_order") val storyOrder: Long,
    val operation: String,
    @ColumnInfo(name = "from_status") val fromStatus: ForeshadowStatus?,
    @ColumnInfo(name = "to_status") val toStatus: ForeshadowStatus,
    @ColumnInfo(name = "evidence_json") val evidenceJson: String,
    val status: DerivedDataStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
