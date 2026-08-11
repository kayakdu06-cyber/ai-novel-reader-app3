package app.zhijuan.core.database.template

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.TemplateOriginType
import app.zhijuan.core.model.TemplateTagDimension
import app.zhijuan.core.model.TemplateTagSource
import app.zhijuan.core.model.TemplateUseMode

@Entity(
    tableName = "template",
    foreignKeys = [
        ForeignKey(
            entity = TemplateRevisionEntity::class,
            parentColumns = ["template_id", "template_revision_id"],
            childColumns = ["template_id", "current_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["template_id", "current_revision_id"]),
        Index(value = ["system_preset_key"], unique = true),
        Index(value = ["origin_type"]),
        Index(value = ["is_favorite", "is_pinned"]),
        Index(value = ["archived_at"]),
        Index(value = ["updated_at"]),
    ],
)
data class TemplateEntity(
    @PrimaryKey @ColumnInfo(name = "template_id") val templateId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val description: String,
    @ColumnInfo(name = "origin_type") val originType: TemplateOriginType,
    @ColumnInfo(name = "system_preset_key") val systemPresetKey: String?,
    @ColumnInfo(name = "current_revision_id") val currentRevisionId: String? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_pinned") val isPinned: Boolean = false,
    @ColumnInfo(name = "archived_at") val archivedAt: Long? = null,
    @ColumnInfo(name = "last_used_at") val lastUsedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "template_revision",
    foreignKeys = [
        ForeignKey(
            entity = TemplateEntity::class,
            parentColumns = ["template_id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TemplateRevisionEntity::class,
            parentColumns = ["template_revision_id"],
            childColumns = ["parent_template_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TemplateRevisionEntity::class,
            parentColumns = ["template_revision_id"],
            childColumns = ["origin_root_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["template_id", "template_revision_id"], unique = true),
        Index(value = ["template_id", "revision_no"], unique = true),
        Index(value = ["parent_template_revision_id"]),
        Index(value = ["origin_root_revision_id"]),
        Index(value = ["source_book_id"]),
        Index(value = ["derivation_key"], unique = true),
        Index(value = ["content_hash"]),
    ],
)
data class TemplateRevisionEntity(
    @PrimaryKey @ColumnInfo(name = "template_revision_id") val templateRevisionId: String,
    @ColumnInfo(name = "template_id") val templateId: String,
    @ColumnInfo(name = "revision_no") val revisionNo: Int,
    @ColumnInfo(name = "parent_template_revision_id") val parentTemplateRevisionId: String?,
    @ColumnInfo(name = "source_book_id") val sourceBookId: String?,
    @ColumnInfo(name = "source_book_title_snapshot") val sourceBookTitleSnapshot: String?,
    @ColumnInfo(name = "origin_root_revision_id") val originRootRevisionId: String,
    @ColumnInfo(name = "origin_chain_json") val originChainJson: String,
    @ColumnInfo(name = "derivation_key") val derivationKey: String?,
    @ColumnInfo(name = "story_seed_json") val storySeedJson: String,
    @ColumnInfo(name = "genre_json") val genreJson: String,
    @ColumnInfo(name = "stable_characters_json") val stableCharactersJson: String,
    @ColumnInfo(name = "world_rules_json") val worldRulesJson: String,
    @ColumnInfo(name = "writing_style_json") val writingStyleJson: String,
    @ColumnInfo(name = "structure_json") val structureJson: String,
    @ColumnInfo(name = "presentation_json") val presentationJson: String,
    @ColumnInfo(name = "content_rules_json") val contentRulesJson: String,
    @ColumnInfo(name = "generation_strategy_json") val generationStrategyJson: String,
    @ColumnInfo(name = "model_role_preferences_json") val modelRolePreferencesJson: String,
    @ColumnInfo(name = "extension_json") val extensionJson: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "template_schema_version") val templateSchemaVersion: Int,
    @ColumnInfo(name = "prompt_bundle_version") val promptBundleVersion: String,
    @ColumnInfo(name = "content_control_schema_version") val contentControlSchemaVersion: Int,
    @ColumnInfo(name = "created_by_app_version") val createdByAppVersion: String,
    @ColumnInfo(name = "extraction_model_snapshot_json") val extractionModelSnapshotJson: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "template_use_snapshot",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["book_id"],
            childColumns = ["book_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TemplateRevisionEntity::class,
            parentColumns = ["template_id", "template_revision_id"],
            childColumns = ["template_id", "template_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["book_id"], unique = true),
        Index(value = ["template_id", "template_revision_id"]),
        Index(value = ["created_at"]),
    ],
)
data class TemplateUseSnapshotEntity(
    @PrimaryKey @ColumnInfo(name = "template_use_snapshot_id") val templateUseSnapshotId: String,
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "template_id") val templateId: String,
    @ColumnInfo(name = "template_revision_id") val templateRevisionId: String,
    @ColumnInfo(name = "use_mode") val useMode: TemplateUseMode,
    @ColumnInfo(name = "user_overrides_json") val userOverridesJson: String,
    @ColumnInfo(name = "source_provenance_json") val sourceProvenanceJson: String,
    @ColumnInfo(name = "story_seed_json") val storySeedJson: String,
    @ColumnInfo(name = "genre_json") val genreJson: String,
    @ColumnInfo(name = "stable_characters_json") val stableCharactersJson: String,
    @ColumnInfo(name = "world_rules_json") val worldRulesJson: String,
    @ColumnInfo(name = "writing_style_json") val writingStyleJson: String,
    @ColumnInfo(name = "structure_json") val structureJson: String,
    @ColumnInfo(name = "presentation_json") val presentationJson: String,
    @ColumnInfo(name = "content_rules_json") val contentRulesJson: String,
    @ColumnInfo(name = "generation_strategy_json") val generationStrategyJson: String,
    @ColumnInfo(name = "model_role_preferences_json") val modelRolePreferencesJson: String,
    @ColumnInfo(name = "extension_json") val extensionJson: String,
    @ColumnInfo(name = "capability_resolution_json") val capabilityResolutionJson: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "template_schema_version") val templateSchemaVersion: Int,
    @ColumnInfo(name = "prompt_bundle_version") val promptBundleVersion: String,
    @ColumnInfo(name = "content_control_schema_version") val contentControlSchemaVersion: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(
    tableName = "template_tag",
    foreignKeys = [
        ForeignKey(
            entity = TemplateEntity::class,
            parentColumns = ["template_id"],
            childColumns = ["template_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = TemplateRevisionEntity::class,
            parentColumns = ["template_id", "template_revision_id"],
            childColumns = ["template_id", "derived_from_revision_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["template_id", "dimension", "normalized_value"], unique = true),
        Index(value = ["template_id", "derived_from_revision_id"]),
        Index(value = ["dimension", "normalized_value"]),
        Index(value = ["is_primary", "is_confirmed", "confidence_micros"]),
    ],
)
data class TemplateTagEntity(
    @PrimaryKey @ColumnInfo(name = "template_tag_id") val templateTagId: String,
    @ColumnInfo(name = "template_id") val templateId: String,
    @ColumnInfo(name = "derived_from_revision_id") val derivedFromRevisionId: String?,
    val dimension: TemplateTagDimension,
    @ColumnInfo(name = "normalized_value") val normalizedValue: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val source: TemplateTagSource,
    @ColumnInfo(name = "confidence_micros") val confidenceMicros: Int,
    @ColumnInfo(name = "is_confirmed") val isConfirmed: Boolean,
    @ColumnInfo(name = "is_primary") val isPrimary: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
