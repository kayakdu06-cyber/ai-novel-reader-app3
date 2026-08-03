package app.zhijuan.core.database

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

object LibraryDatabaseGuards {
    val callback: RoomDatabase.Callback = object : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            install(db)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            install(db)
        }
    }

    internal fun install(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS prevent_book_creation_snapshot_update
            BEFORE UPDATE ON book_creation_snapshot
            BEGIN
                SELECT RAISE(ABORT, 'book_creation_snapshot is immutable');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS prevent_chapter_version_update
            BEFORE UPDATE ON chapter_version
            BEGIN
                SELECT RAISE(ABORT, 'chapter_version is immutable');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_book_creation_snapshot_insert
            BEFORE INSERT ON book_creation_snapshot
            WHEN length(trim(NEW.snapshot_id)) = 0
              OR length(trim(NEW.raw_input_json)) = 0
              OR length(trim(NEW.normalized_input_json)) = 0
              OR length(trim(NEW.inference_provenance_json)) = 0
              OR length(trim(NEW.genre_payload_json)) = 0
              OR length(trim(NEW.presentation_profile_json)) = 0
              OR length(trim(NEW.model_preference_json)) = 0
              OR NEW.schema_version <= 0
              OR length(trim(NEW.prompt_bundle_version)) = 0
              OR NEW.content_control_schema_version <= 0
              OR length(trim(NEW.content_hash)) = 0
              OR NEW.created_at < 0
            BEGIN
                SELECT RAISE(ABORT, 'invalid book creation snapshot fields');
            END
            """.trimIndent(),
        )
        db.execSQL("DROP TRIGGER IF EXISTS validate_book_insert")
        db.execSQL("DROP TRIGGER IF EXISTS validate_book_update")
        val lengthPolicyCondition = if (columnExists(db, "book", "minimum_chapters")) {
            """
              OR NEW.minimum_chapters <= 0
              OR NEW.length_policy_schema_version NOT IN (0, 1)
              OR (NEW.length_policy_schema_version = 1 AND (
                    NEW.target_chapters IS NULL
                 OR NEW.target_chapters > 10000
                 OR (NEW.length_mode = 'SHORT' AND (NEW.minimum_chapters != 80 OR NEW.target_chapters < 80))
                 OR (NEW.length_mode = 'MEDIUM' AND (NEW.minimum_chapters != 300 OR NEW.target_chapters < 300))
                 OR (NEW.length_mode = 'LONG' AND (NEW.minimum_chapters != 301 OR NEW.target_chapters < 301))
                 OR NEW.length_mode NOT IN ('SHORT', 'MEDIUM', 'LONG')
              ))
            """.trimIndent()
        } else {
            ""
        }
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_book_insert
            BEFORE INSERT ON book
            WHEN length(trim(NEW.book_id)) = 0
              OR length(trim(NEW.title)) = 0
              OR NEW.completed_chapter_count < 0
              OR (NEW.target_characters IS NOT NULL AND NEW.target_characters <= 0)
              OR (NEW.target_chapters IS NOT NULL AND NEW.target_chapters <= 0)
              $lengthPolicyCondition
            BEGIN
                SELECT RAISE(ABORT, 'invalid book fields');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_book_update
            BEFORE UPDATE ON book
            WHEN length(trim(NEW.book_id)) = 0
              OR length(trim(NEW.title)) = 0
              OR NEW.completed_chapter_count < 0
              OR (NEW.target_characters IS NOT NULL AND NEW.target_characters <= 0)
              OR (NEW.target_chapters IS NOT NULL AND NEW.target_chapters <= 0)
              $lengthPolicyCondition
            BEGIN
                SELECT RAISE(ABORT, 'invalid book fields');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_chapter_insert
            BEFORE INSERT ON chapter
            WHEN length(trim(NEW.chapter_id)) = 0 OR NEW.chapter_index <= 0
            BEGIN
                SELECT RAISE(ABORT, 'invalid chapter fields');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_chapter_version_insert
            BEFORE INSERT ON chapter_version
            WHEN length(trim(NEW.chapter_version_id)) = 0
              OR NEW.version_no <= 0
              OR length(NEW.content) = 0
              OR NEW.character_count != length(NEW.content)
              OR length(trim(NEW.content_hash)) = 0
            BEGIN
                SELECT RAISE(ABORT, 'invalid chapter version fields');
            END
            """.trimIndent(),
        )
        if (tableExists(db, "usage_ledger")) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS prevent_final_usage_update
                BEFORE UPDATE ON usage_ledger
                WHEN OLD.status = 'FINAL'
                  AND NOT (
                      NEW.status = 'FINAL'
                      AND NEW.source = 'PROVIDER_REPORTED'
                      AND OLD.source IN ('UNKNOWN', 'ESTIMATED')
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'final usage ledger is immutable');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS prevent_usage_identity_update
                BEFORE UPDATE ON usage_ledger
                WHEN NEW.usage_ledger_id != OLD.usage_ledger_id
                  OR NEW.attempt_id != OLD.attempt_id
                  OR NEW.book_id != OLD.book_id
                  OR NEW.daily_period_key != OLD.daily_period_key
                  OR NEW.created_at != OLD.created_at
                BEGIN
                    SELECT RAISE(ABORT, 'usage ledger identity is immutable');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_usage_ledger_insert
                BEFORE INSERT ON usage_ledger
                WHEN length(trim(NEW.usage_ledger_id)) = 0
                  OR length(trim(NEW.attempt_id)) = 0
                  OR length(trim(NEW.daily_period_key)) = 0
                  OR NEW.book_id != (
                      SELECT generation_job.book_id
                      FROM request_attempt
                      INNER JOIN generation_job ON generation_job.job_id = request_attempt.job_id
                      WHERE request_attempt.attempt_id = NEW.attempt_id
                  )
                  OR COALESCE(NEW.input_tokens, 0) < 0
                  OR COALESCE(NEW.output_tokens, 0) < 0
                  OR COALESCE(NEW.cached_tokens, 0) < 0
                  OR COALESCE(NEW.reasoning_tokens, 0) < 0
                  OR COALESCE(NEW.total_tokens, 0) < 0
                  OR COALESCE(NEW.estimated_cost_micros, 0) < 0
                  OR ((NEW.currency IS NULL) != (NEW.estimated_cost_micros IS NULL))
                  OR (NEW.currency IS NOT NULL AND (length(NEW.currency) != 3 OR NEW.currency != upper(NEW.currency)))
                  OR (NEW.source = 'UNKNOWN' AND (
                      NEW.input_tokens IS NOT NULL OR NEW.output_tokens IS NOT NULL
                      OR NEW.cached_tokens IS NOT NULL OR NEW.reasoning_tokens IS NOT NULL
                      OR NEW.total_tokens IS NOT NULL OR NEW.estimated_cost_micros IS NOT NULL
                  ))
                  OR (NEW.source != 'UNKNOWN' AND NEW.total_tokens IS NULL)
                  OR ((NEW.status = 'FINAL') != (NEW.finalized_at IS NOT NULL))
                BEGIN
                    SELECT RAISE(ABORT, 'invalid usage ledger fields');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_usage_ledger_update
                BEFORE UPDATE ON usage_ledger
                WHEN NEW.book_id != (
                      SELECT generation_job.book_id
                      FROM request_attempt
                      INNER JOIN generation_job ON generation_job.job_id = request_attempt.job_id
                      WHERE request_attempt.attempt_id = NEW.attempt_id
                  )
                  OR COALESCE(NEW.input_tokens, 0) < 0
                  OR COALESCE(NEW.output_tokens, 0) < 0
                  OR COALESCE(NEW.cached_tokens, 0) < 0
                  OR COALESCE(NEW.reasoning_tokens, 0) < 0
                  OR COALESCE(NEW.total_tokens, 0) < 0
                  OR COALESCE(NEW.estimated_cost_micros, 0) < 0
                  OR ((NEW.currency IS NULL) != (NEW.estimated_cost_micros IS NULL))
                  OR (NEW.currency IS NOT NULL AND (length(NEW.currency) != 3 OR NEW.currency != upper(NEW.currency)))
                  OR (NEW.source = 'UNKNOWN' AND (
                      NEW.input_tokens IS NOT NULL OR NEW.output_tokens IS NOT NULL
                      OR NEW.cached_tokens IS NOT NULL OR NEW.reasoning_tokens IS NOT NULL
                      OR NEW.total_tokens IS NOT NULL OR NEW.estimated_cost_micros IS NOT NULL
                  ))
                  OR (NEW.source != 'UNKNOWN' AND NEW.total_tokens IS NULL)
                  OR ((NEW.status = 'FINAL') != (NEW.finalized_at IS NOT NULL))
                BEGIN
                    SELECT RAISE(ABORT, 'invalid usage ledger fields');
                END
                """.trimIndent(),
            )
        }
        if (tableExists(db, "generation_stage")) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_generation_stage_insert
                BEFORE INSERT ON generation_stage
                WHEN length(trim(NEW.stage_id)) = 0
                  OR length(trim(NEW.target_id)) = 0
                  OR length(trim(NEW.input_version_hash)) = 0
                  OR length(trim(NEW.idempotency_key)) = 0
                  OR NEW.attempt_count < 0
                  OR NEW.max_attempts <= 0
                  OR NEW.attempt_count > NEW.max_attempts
                BEGIN
                    SELECT RAISE(ABORT, 'invalid generation stage fields');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_generation_stage_update
                BEFORE UPDATE ON generation_stage
                WHEN NEW.attempt_count < 0
                  OR NEW.max_attempts <= 0
                  OR NEW.attempt_count > NEW.max_attempts
                BEGIN
                    SELECT RAISE(ABORT, 'invalid generation stage fields');
                END
                """.trimIndent(),
            )
        }
        if (tableExists(db, "request_attempt")) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_request_attempt_insert
                BEFORE INSERT ON request_attempt
                WHEN length(trim(NEW.attempt_id)) = 0
                  OR NEW.attempt_no <= 0
                  OR length(trim(NEW.input_hash)) = 0
                  OR (NEW.http_status IS NOT NULL AND (NEW.http_status < 100 OR NEW.http_status > 599))
                BEGIN
                    SELECT RAISE(ABORT, 'invalid request attempt fields');
                END
                """.trimIndent(),
            )
        }
        installMemoryGuards(db)
        installTemplateGuards(db)
    }

    private fun installMemoryGuards(db: SupportSQLiteDatabase) {
        if (tableExists(db, "story_bible_revision")) {
            installImmutableTrigger(db, "story_bible_revision")
            installImmutableTrigger(db, "outline_revision")
            installImmutableTrigger(db, "outline_node")
            listOf("story_bible_revision", "outline_revision").forEach { table ->
                val idColumn = if (table == "story_bible_revision") "bible_revision_id" else "outline_revision_id"
                val tableSpecificCondition =
                    if (table == "story_bible_revision") "OR NEW.content_control_schema_version <= 0" else ""
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS validate_${table}_insert
                    BEFORE INSERT ON $table
                    WHEN length(trim(NEW.$idColumn)) = 0
                      OR NEW.revision_no <= 0
                      OR NEW.schema_version <= 0
                      OR length(trim(NEW.content_hash)) = 0
                      $tableSpecificCondition
                      OR (NEW.generation_stage_id IS NOT NULL AND NEW.book_id != (
                          SELECT generation_job.book_id
                          FROM generation_stage
                          INNER JOIN generation_job ON generation_job.job_id = generation_stage.job_id
                          WHERE generation_stage.stage_id = NEW.generation_stage_id
                      ))
                    BEGIN
                        SELECT RAISE(ABORT, 'invalid $table fields');
                    END
                    """.trimIndent(),
                )
            }
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_outline_node_insert
                BEFORE INSERT ON outline_node
                WHEN length(trim(NEW.outline_node_id)) = 0
                  OR length(trim(NEW.title)) = 0
                  OR length(trim(NEW.content_hash)) = 0
                  OR NEW.order_key < 0
                  OR (NEW.planned_chapter_index IS NOT NULL AND NEW.planned_chapter_index <= 0)
                BEGIN
                    SELECT RAISE(ABORT, 'invalid outline node fields');
                END
                """.trimIndent(),
            )
        }

        if (tableExists(db, "story_entity")) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_story_entity_insert
                BEFORE INSERT ON story_entity
                WHEN length(trim(NEW.entity_id)) = 0
                  OR length(trim(NEW.canonical_name)) = 0
                  OR NOT COALESCE((
                      (NEW.entity_type = 'CHARACTER' AND NEW.adult_status = 'CONFIRMED_ADULT' AND NEW.age_years >= 18)
                      OR (NEW.entity_type = 'CHARACTER' AND NEW.adult_status = 'NOT_ADULT' AND NEW.age_years BETWEEN 0 AND 17)
                      OR (NEW.entity_type = 'CHARACTER' AND NEW.adult_status = 'UNKNOWN' AND NEW.age_years IS NULL)
                      OR (NEW.entity_type != 'CHARACTER' AND NEW.adult_status = 'NOT_APPLICABLE' AND NEW.age_years IS NULL)
                  ), 0)
                BEGIN
                    SELECT RAISE(ABORT, 'invalid story entity age classification');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_story_entity_update
                BEFORE UPDATE ON story_entity
                WHEN length(trim(NEW.canonical_name)) = 0
                  OR NOT COALESCE((
                      (NEW.entity_type = 'CHARACTER' AND NEW.adult_status = 'CONFIRMED_ADULT' AND NEW.age_years >= 18)
                      OR (NEW.entity_type = 'CHARACTER' AND NEW.adult_status = 'NOT_ADULT' AND NEW.age_years BETWEEN 0 AND 17)
                      OR (NEW.entity_type = 'CHARACTER' AND NEW.adult_status = 'UNKNOWN' AND NEW.age_years IS NULL)
                      OR (NEW.entity_type != 'CHARACTER' AND NEW.adult_status = 'NOT_APPLICABLE' AND NEW.age_years IS NULL)
                  ), 0)
                BEGIN
                    SELECT RAISE(ABORT, 'invalid story entity age classification');
                END
                """.trimIndent(),
            )
        }

        if (tableExists(db, "chapter_summary")) {
            installChapterVersionGuard(
                db = db,
                table = "chapter_summary",
                versionColumn = "chapter_version_id",
                extraCondition = "OR NEW.chapter_index != (SELECT chapter.chapter_index FROM chapter_version INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id WHERE chapter_version.chapter_version_id = NEW.chapter_version_id) OR NEW.importance NOT BETWEEN 0 AND 100 OR NEW.schema_version <= 0",
            )
            installChapterVersionGuard(db, "entity_event", "source_chapter_version_id", "OR NEW.confidence_micros NOT BETWEEN 0 AND 1000000")
            installChapterVersionGuard(
                db,
                "canon_fact",
                "source_chapter_version_id",
                "OR (NEW.source_bible_revision_id IS NOT NULL AND NEW.book_id != (SELECT book_id FROM story_bible_revision WHERE bible_revision_id = NEW.source_bible_revision_id)) OR (NEW.valid_from_story_order IS NOT NULL AND NEW.valid_to_story_order IS NOT NULL AND NEW.valid_from_story_order > NEW.valid_to_story_order)",
                versionNullable = true,
            )
            installChapterVersionGuard(db, "timeline_event", "source_chapter_version_id")
            listOf("source_chapter_version_id", "planted_chapter_version_id", "resolved_chapter_version_id").forEach { column ->
                installChapterVersionGuard(
                    db,
                    "foreshadow_item",
                    column,
                    if (column == "source_chapter_version_id") {
                        "OR NEW.importance NOT BETWEEN 0 AND 100 OR (NEW.target_start_chapter_index IS NOT NULL AND NEW.target_start_chapter_index <= 0) OR (NEW.target_end_chapter_index IS NOT NULL AND NEW.target_end_chapter_index < NEW.target_start_chapter_index)"
                    } else {
                        ""
                    },
                    versionNullable = true,
                    triggerSuffix = column,
                )
            }
            if (tableExists(db, "chapter_tracking_projection")) {
                installChapterVersionGuard(
                    db,
                    "chapter_tracking_projection",
                    "chapter_version_id",
                    "OR NEW.chapter_index != (SELECT chapter.chapter_index FROM chapter_version INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id WHERE chapter_version.chapter_version_id = NEW.chapter_version_id) OR NEW.book_id != (SELECT generation_job.book_id FROM generation_stage INNER JOIN generation_job ON generation_job.job_id = generation_stage.job_id WHERE generation_stage.stage_id = NEW.generation_stage_id) OR NEW.timeline_event_count NOT BETWEEN 0 AND 64 OR NEW.foreshadow_transition_count NOT BETWEEN 0 AND 64",
                )
            }
            if (tableExists(db, "foreshadow_transition")) {
                installChapterVersionGuard(
                    db,
                    "foreshadow_transition",
                    "source_chapter_version_id",
                    "OR NEW.book_id != (SELECT generation_job.book_id FROM generation_stage INNER JOIN generation_job ON generation_job.job_id = generation_stage.job_id WHERE generation_stage.stage_id = NEW.generation_stage_id) OR NEW.story_order <= 0 OR NEW.operation NOT IN ('PLANT', 'DEVELOP', 'RESOLVE', 'ABANDON') OR NOT ((NEW.operation = 'PLANT' AND NEW.from_status IS NULL AND NEW.to_status = 'PLANTED') OR (NEW.operation = 'DEVELOP' AND NEW.from_status IN ('PLANTED', 'DEVELOPING') AND NEW.to_status = 'DEVELOPING') OR (NEW.operation = 'RESOLVE' AND NEW.from_status IN ('PLANTED', 'DEVELOPING') AND NEW.to_status = 'RESOLVED') OR (NEW.operation = 'ABANDON' AND NEW.from_status IN ('PLANNED', 'PLANTED', 'DEVELOPING') AND NEW.to_status = 'ABANDONED'))",
                )
            }
            installChapterVersionGuard(
                db,
                "consistency_report",
                "target_chapter_version_id",
                "OR NEW.target_chapter_index != (SELECT chapter.chapter_index FROM chapter_version INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id WHERE chapter_version.chapter_version_id = NEW.target_chapter_version_id) OR (NEW.generation_stage_id IS NOT NULL AND NEW.book_id != (SELECT generation_job.book_id FROM generation_stage INNER JOIN generation_job ON generation_job.job_id = generation_stage.job_id WHERE generation_stage.stage_id = NEW.generation_stage_id))",
            )
            installChapterVersionGuard(
                db,
                "aggregate_state_projection",
                "source_through_chapter_version_id",
                "OR NEW.through_chapter_index != (SELECT chapter.chapter_index FROM chapter_version INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id WHERE chapter_version.chapter_version_id = NEW.source_through_chapter_version_id) OR NEW.schema_version <= 0",
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_context_snapshot_insert
                BEFORE INSERT ON context_snapshot
                WHEN NEW.target_chapter_index != (
                    SELECT chapter_index FROM chapter
                    WHERE book_id = NEW.book_id AND chapter_id = NEW.target_chapter_id
                )
                  OR NEW.book_id != (
                    SELECT generation_job.book_id
                    FROM generation_stage
                    INNER JOIN generation_job ON generation_job.job_id = generation_stage.job_id
                    WHERE generation_stage.stage_id = NEW.generation_stage_id
                )
                  OR length(trim(NEW.content_hash)) = 0
                BEGIN
                    SELECT RAISE(ABORT, 'invalid context snapshot provenance');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_context_snapshot_update
                BEFORE UPDATE ON context_snapshot
                WHEN NEW.target_chapter_index != (
                    SELECT chapter_index FROM chapter
                    WHERE book_id = NEW.book_id AND chapter_id = NEW.target_chapter_id
                )
                  OR NEW.book_id != (
                    SELECT generation_job.book_id
                    FROM generation_stage
                    INNER JOIN generation_job ON generation_job.job_id = generation_stage.job_id
                    WHERE generation_stage.stage_id = NEW.generation_stage_id
                )
                  OR length(trim(NEW.content_hash)) = 0
                BEGIN
                    SELECT RAISE(ABORT, 'invalid context snapshot provenance');
                END
                """.trimIndent(),
            )
        }
    }

    private fun installImmutableTrigger(db: SupportSQLiteDatabase, table: String) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS prevent_${table}_update
            BEFORE UPDATE ON $table
            BEGIN
                SELECT RAISE(ABORT, '$table is immutable');
            END
            """.trimIndent(),
        )
    }

    private fun installTemplateGuards(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "template_revision")) return

        installImmutableTrigger(db, "template_revision")
        installImmutableTrigger(db, "template_use_snapshot")
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_template_insert
            BEFORE INSERT ON template
            WHEN length(trim(NEW.template_id)) = 0
              OR length(trim(NEW.display_name)) = 0
              OR NEW.current_revision_id IS NOT NULL
              OR NEW.is_favorite NOT IN (0, 1)
              OR NEW.is_pinned NOT IN (0, 1)
              OR ((NEW.origin_type = 'SYSTEM_PRESET') != (NEW.system_preset_key IS NOT NULL))
              OR (NEW.system_preset_key IS NOT NULL AND length(trim(NEW.system_preset_key)) = 0)
            BEGIN
                SELECT RAISE(ABORT, 'invalid template fields');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS protect_template_identity_update
            BEFORE UPDATE ON template
            WHEN NEW.template_id IS NOT OLD.template_id
              OR NEW.origin_type IS NOT OLD.origin_type
              OR NEW.system_preset_key IS NOT OLD.system_preset_key
              OR NEW.created_at IS NOT OLD.created_at
              OR length(trim(NEW.display_name)) = 0
              OR NEW.is_favorite NOT IN (0, 1)
              OR NEW.is_pinned NOT IN (0, 1)
              OR (
                  OLD.current_revision_id IS NOT NULL
                  AND NEW.current_revision_id IS NOT OLD.current_revision_id
                  AND (SELECT revision_no FROM template_revision WHERE template_revision_id = NEW.current_revision_id)
                      <= (SELECT revision_no FROM template_revision WHERE template_revision_id = OLD.current_revision_id)
              )
            BEGIN
                SELECT RAISE(ABORT, 'template source identity is immutable');
            END
            """.trimIndent(),
        )

        val revisionPayload = """
            NEW.story_seed_json || NEW.genre_json || NEW.stable_characters_json ||
            NEW.world_rules_json || NEW.writing_style_json || NEW.structure_json ||
            NEW.presentation_json || NEW.content_rules_json || NEW.generation_strategy_json ||
            NEW.model_role_preferences_json || NEW.extension_json || NEW.origin_chain_json ||
            COALESCE(NEW.extraction_model_snapshot_json, '')
        """.trimIndent()
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_template_revision_insert
            BEFORE INSERT ON template_revision
            WHEN length(trim(NEW.template_revision_id)) = 0
              OR NEW.revision_no <= 0
              OR NEW.parent_template_revision_id = NEW.template_revision_id
              OR length(trim(NEW.origin_root_revision_id)) = 0
              OR length(trim(NEW.content_hash)) = 0
              OR NEW.template_schema_version <= 0
              OR NEW.content_control_schema_version <= 0
              OR length(trim(NEW.prompt_bundle_version)) = 0
              OR length(trim(NEW.created_by_app_version)) = 0
              OR (
                  NEW.revision_no = 1
                  AND (SELECT origin_type FROM template WHERE template_id = NEW.template_id) = 'TEMPLATE_FORK'
                  AND (
                      NEW.parent_template_revision_id IS NULL
                      OR NEW.origin_root_revision_id != (
                          SELECT origin_root_revision_id FROM template_revision
                          WHERE template_revision_id = NEW.parent_template_revision_id
                      )
                  )
              )
              OR (
                  NEW.revision_no = 1
                  AND (SELECT origin_type FROM template WHERE template_id = NEW.template_id) != 'TEMPLATE_FORK'
                  AND (NEW.parent_template_revision_id IS NOT NULL OR NEW.origin_root_revision_id != NEW.template_revision_id)
              )
              OR (
                  NEW.revision_no > 1
                  AND (
                      NEW.parent_template_revision_id IS NULL
                      OR NEW.template_id != (
                          SELECT template_id FROM template_revision
                          WHERE template_revision_id = NEW.parent_template_revision_id
                      )
                      OR NEW.revision_no != 1 + (
                          SELECT revision_no FROM template_revision
                          WHERE template_revision_id = NEW.parent_template_revision_id
                      )
                      OR NEW.origin_root_revision_id != (
                          SELECT origin_root_revision_id FROM template_revision
                          WHERE template_revision_id = NEW.parent_template_revision_id
                      )
                      OR NEW.derivation_key IS NOT NULL
                  )
              )
              OR (
                  (SELECT origin_type FROM template WHERE template_id = NEW.template_id) = 'BOOK_DERIVED'
                  AND (
                      NEW.source_book_title_snapshot IS NULL
                      OR length(trim(NEW.source_book_title_snapshot)) = 0
                      OR (NEW.revision_no = 1 AND (
                          NEW.source_book_id IS NULL
                          OR NEW.derivation_key IS NULL
                          OR length(trim(NEW.derivation_key)) = 0
                          OR NOT EXISTS (SELECT 1 FROM book WHERE book_id = NEW.source_book_id)
                          OR NEW.source_book_title_snapshot != (SELECT title FROM book WHERE book_id = NEW.source_book_id)
                      ))
                      OR (NEW.revision_no > 1 AND NEW.derivation_key IS NOT NULL)
                  )
              )
              OR (
                  (SELECT origin_type FROM template WHERE template_id = NEW.template_id) != 'BOOK_DERIVED'
                  AND (NEW.source_book_id IS NOT NULL OR NEW.source_book_title_snapshot IS NOT NULL OR NEW.derivation_key IS NOT NULL)
              )
              OR length($revisionPayload) > 1000000
              OR instr(lower($revisionPayload), '"api_key"') > 0
              OR instr(lower($revisionPayload), '"authorization"') > 0
              OR instr(lower($revisionPayload), '"access_token"') > 0
              OR instr(lower($revisionPayload), '"chapter_content"') > 0
              OR instr(lower($revisionPayload), '"stream_draft"') > 0
              OR instr(lower($revisionPayload), '"request_attempt"') > 0
              OR instr(lower($revisionPayload), '"usage_ledger"') > 0
              OR instr(lower($revisionPayload), 'bearer ') > 0
            BEGIN
                SELECT RAISE(ABORT, 'invalid or unsafe template revision');
            END
            """.trimIndent(),
        )

        val usePayload = """
            NEW.user_overrides_json || NEW.source_provenance_json || NEW.story_seed_json ||
            NEW.genre_json || NEW.stable_characters_json || NEW.world_rules_json ||
            NEW.writing_style_json || NEW.structure_json || NEW.presentation_json ||
            NEW.content_rules_json || NEW.generation_strategy_json ||
            NEW.model_role_preferences_json || NEW.extension_json || NEW.capability_resolution_json
        """.trimIndent()
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_template_use_snapshot_insert
            BEFORE INSERT ON template_use_snapshot
            WHEN length(trim(NEW.template_use_snapshot_id)) = 0
              OR length(trim(NEW.content_hash)) = 0
              OR NEW.template_schema_version <= 0
              OR NEW.content_control_schema_version <= 0
              OR length(trim(NEW.prompt_bundle_version)) = 0
              OR NEW.content_hash != (
                  SELECT book_creation_snapshot.content_hash
                  FROM book
                  INNER JOIN book_creation_snapshot
                      ON book_creation_snapshot.snapshot_id = book.creation_snapshot_id
                  WHERE book.book_id = NEW.book_id
              )
              OR length($usePayload) > 1000000
              OR instr(lower($usePayload), '"api_key"') > 0
              OR instr(lower($usePayload), '"authorization"') > 0
              OR instr(lower($usePayload), '"access_token"') > 0
              OR instr(lower($usePayload), '"chapter_content"') > 0
              OR instr(lower($usePayload), '"stream_draft"') > 0
              OR instr(lower($usePayload), '"request_attempt"') > 0
              OR instr(lower($usePayload), '"usage_ledger"') > 0
              OR instr(lower($usePayload), 'bearer ') > 0
            BEGIN
                SELECT RAISE(ABORT, 'invalid or unsafe template use snapshot');
            END
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_template_tag_insert
            BEFORE INSERT ON template_tag
            WHEN length(trim(NEW.template_tag_id)) = 0
              OR length(trim(NEW.normalized_value)) = 0
              OR length(trim(NEW.display_name)) = 0
              OR NEW.confidence_micros NOT BETWEEN 0 AND 1000000
              OR NEW.is_confirmed NOT IN (0, 1)
              OR NEW.is_primary NOT IN (0, 1)
              OR (NEW.source != 'USER' AND NEW.derived_from_revision_id IS NULL)
              OR (NEW.source = 'USER' AND NEW.is_confirmed != 1)
              OR (NEW.is_primary = 1 AND NEW.is_confirmed != 1 AND NEW.confidence_micros < 800000)
              OR (NEW.is_primary = 1 AND EXISTS (
                  SELECT 1 FROM template_tag
                  WHERE template_id = NEW.template_id
                    AND dimension = NEW.dimension
                    AND is_primary = 1
              ))
            BEGIN
                SELECT RAISE(ABORT, 'invalid template tag');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS protect_template_tag_identity_update
            BEFORE UPDATE ON template_tag
            WHEN NEW.template_tag_id IS NOT OLD.template_tag_id
              OR NEW.template_id IS NOT OLD.template_id
              OR NEW.derived_from_revision_id IS NOT OLD.derived_from_revision_id
              OR NEW.dimension IS NOT OLD.dimension
              OR NEW.normalized_value IS NOT OLD.normalized_value
              OR NEW.source IS NOT OLD.source
              OR NEW.created_at IS NOT OLD.created_at
              OR NEW.confidence_micros NOT BETWEEN 0 AND 1000000
              OR NEW.is_confirmed NOT IN (0, 1)
              OR NEW.is_primary NOT IN (0, 1)
              OR (NEW.source = 'USER' AND NEW.is_confirmed != 1)
              OR (NEW.is_primary = 1 AND NEW.is_confirmed != 1 AND NEW.confidence_micros < 800000)
              OR (NEW.is_primary = 1 AND EXISTS (
                  SELECT 1 FROM template_tag
                  WHERE template_id = NEW.template_id
                    AND dimension = NEW.dimension
                    AND is_primary = 1
                    AND template_tag_id != NEW.template_tag_id
              ))
            BEGIN
                SELECT RAISE(ABORT, 'template tag provenance is immutable');
            END
            """.trimIndent(),
        )
    }

    private fun installChapterVersionGuard(
        db: SupportSQLiteDatabase,
        table: String,
        versionColumn: String,
        extraCondition: String = "",
        versionNullable: Boolean = false,
        triggerSuffix: String = "provenance",
    ) {
        val versionCheck =
            if (versionNullable) {
                "(NEW.$versionColumn IS NOT NULL AND NEW.book_id != (SELECT chapter.book_id FROM chapter_version INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id WHERE chapter_version.chapter_version_id = NEW.$versionColumn))"
            } else {
                "NEW.book_id != (SELECT chapter.book_id FROM chapter_version INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id WHERE chapter_version.chapter_version_id = NEW.$versionColumn)"
            }
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_${table}_${triggerSuffix}_insert
            BEFORE INSERT ON $table
            WHEN $versionCheck $extraCondition
            BEGIN
                SELECT RAISE(ABORT, 'invalid $table provenance');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_${table}_${triggerSuffix}_update
            BEFORE UPDATE ON $table
            WHEN $versionCheck $extraCondition
            BEGIN
                SELECT RAISE(ABORT, 'invalid $table provenance');
            END
            """.trimIndent(),
        )
    }

    private fun tableExists(db: SupportSQLiteDatabase, tableName: String): Boolean =
        db.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(tableName),
        ).use { cursor -> cursor.moveToFirst() }

    private fun columnExists(
        db: SupportSQLiteDatabase,
        tableName: String,
        columnName: String,
    ): Boolean = db.query("PRAGMA table_info(`$tableName`)").use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == columnName) return@use true
        }
        false
    }
}
