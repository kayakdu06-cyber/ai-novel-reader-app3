package app.zhijuan.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ZhijuanMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `generation_job` (
                    `job_id` TEXT NOT NULL,
                    `book_id` TEXT NOT NULL,
                    `job_type` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `user_intent_json` TEXT NOT NULL,
                    `budget_snapshot_json` TEXT NOT NULL,
                    `prompt_bundle_version` TEXT NOT NULL,
                    `current_stage_id` TEXT,
                    `pause_or_stop_reason` TEXT,
                    `lease_owner_id` TEXT,
                    `lease_acquired_at` INTEGER,
                    `lease_heartbeat_at` INTEGER,
                    `started_at` INTEGER,
                    `finished_at` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`job_id`),
                    FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`job_id`, `current_stage_id`)
                        REFERENCES `generation_stage`(`job_id`, `stage_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `generation_stage` (
                    `stage_id` TEXT NOT NULL,
                    `job_id` TEXT NOT NULL,
                    `phase` TEXT NOT NULL,
                    `target_type` TEXT NOT NULL,
                    `target_id` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `input_version_hash` TEXT NOT NULL,
                    `idempotency_key` TEXT NOT NULL,
                    `attempt_count` INTEGER NOT NULL,
                    `max_attempts` INTEGER NOT NULL,
                    `input_sources_json` TEXT NOT NULL,
                    `output_reference_json` TEXT,
                    `standard_error_code` TEXT,
                    `next_retry_at` INTEGER,
                    `lease_owner_id` TEXT,
                    `lease_acquired_at` INTEGER,
                    `lease_heartbeat_at` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`stage_id`),
                    FOREIGN KEY(`job_id`) REFERENCES `generation_job`(`job_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `request_attempt` (
                    `attempt_id` TEXT NOT NULL,
                    `job_id` TEXT NOT NULL,
                    `stage_id` TEXT NOT NULL,
                    `attempt_no` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `request_intent_at` INTEGER NOT NULL,
                    `sent_at` INTEGER,
                    `finished_at` INTEGER,
                    `provider_request_id` TEXT,
                    `connection_snapshot_json` TEXT NOT NULL,
                    `model_snapshot_json` TEXT NOT NULL,
                    `protocol_snapshot_json` TEXT NOT NULL,
                    `standard_error_code` TEXT,
                    `http_status` INTEGER,
                    `input_hash` TEXT NOT NULL,
                    `output_hash` TEXT,
                    `stream_draft_ref` TEXT,
                    `retry_parent_attempt_id` TEXT,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`attempt_id`),
                    FOREIGN KEY(`job_id`, `stage_id`)
                        REFERENCES `generation_stage`(`job_id`, `stage_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`stage_id`, `retry_parent_attempt_id`)
                        REFERENCES `request_attempt`(`stage_id`, `attempt_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `usage_ledger` (
                    `usage_ledger_id` TEXT NOT NULL,
                    `attempt_id` TEXT NOT NULL,
                    `book_id` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `input_tokens` INTEGER,
                    `output_tokens` INTEGER,
                    `cached_tokens` INTEGER,
                    `reasoning_tokens` INTEGER,
                    `total_tokens` INTEGER,
                    `currency` TEXT,
                    `estimated_cost_micros` INTEGER,
                    `price_catalog_version` TEXT,
                    `daily_period_key` TEXT NOT NULL,
                    `finalized_at` INTEGER,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`usage_ledger_id`),
                    FOREIGN KEY(`attempt_id`) REFERENCES `request_attempt`(`attempt_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`)
                        ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )

            listOf(
                "CREATE INDEX IF NOT EXISTS `index_generation_job_book_id_created_at` ON `generation_job` (`book_id`, `created_at`)",
                "CREATE INDEX IF NOT EXISTS `index_generation_job_job_id_current_stage_id` ON `generation_job` (`job_id`, `current_stage_id`)",
                "CREATE INDEX IF NOT EXISTS `index_generation_job_status_updated_at` ON `generation_job` (`status`, `updated_at`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_generation_stage_job_id_stage_id` ON `generation_stage` (`job_id`, `stage_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_generation_stage_idempotency_key` ON `generation_stage` (`idempotency_key`)",
                "CREATE INDEX IF NOT EXISTS `index_generation_stage_job_id_phase_target_id` ON `generation_stage` (`job_id`, `phase`, `target_id`)",
                "CREATE INDEX IF NOT EXISTS `index_generation_stage_status_updated_at` ON `generation_stage` (`status`, `updated_at`)",
                "CREATE INDEX IF NOT EXISTS `index_generation_stage_next_retry_at` ON `generation_stage` (`next_retry_at`)",
                "CREATE INDEX IF NOT EXISTS `index_request_attempt_job_id_stage_id` ON `request_attempt` (`job_id`, `stage_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_request_attempt_stage_id_attempt_id` ON `request_attempt` (`stage_id`, `attempt_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_request_attempt_stage_id_attempt_no` ON `request_attempt` (`stage_id`, `attempt_no`)",
                "CREATE INDEX IF NOT EXISTS `index_request_attempt_stage_id_retry_parent_attempt_id` ON `request_attempt` (`stage_id`, `retry_parent_attempt_id`)",
                "CREATE INDEX IF NOT EXISTS `index_request_attempt_job_id_created_at` ON `request_attempt` (`job_id`, `created_at`)",
                "CREATE INDEX IF NOT EXISTS `index_request_attempt_provider_request_id` ON `request_attempt` (`provider_request_id`)",
                "CREATE INDEX IF NOT EXISTS `index_request_attempt_status_updated_at` ON `request_attempt` (`status`, `updated_at`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_usage_ledger_attempt_id` ON `usage_ledger` (`attempt_id`)",
                "CREATE INDEX IF NOT EXISTS `index_usage_ledger_book_id_created_at` ON `usage_ledger` (`book_id`, `created_at`)",
                "CREATE INDEX IF NOT EXISTS `index_usage_ledger_daily_period_key_created_at` ON `usage_ledger` (`daily_period_key`, `created_at`)",
                "CREATE INDEX IF NOT EXISTS `index_usage_ledger_status_updated_at` ON `usage_ledger` (`status`, `updated_at`)",
            ).forEach(db::execSQL)

            LibraryDatabaseGuards.install(db)
        }
    }

    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Composite chapter references in the v3 tables require this parent key
            // to be unique before those tables become usable.
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_chapter_book_id_chapter_id` ON `chapter` (`book_id`, `chapter_id`)",
            )
            listOf(
                """
                CREATE TABLE IF NOT EXISTS `story_bible_revision` (`bible_revision_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `revision_no` INTEGER NOT NULL, `parent_revision_id` TEXT, `source` TEXT NOT NULL, `schema_version` INTEGER NOT NULL, `content_control_schema_version` INTEGER NOT NULL, `payload_json` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `generation_stage_id` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`bible_revision_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`book_id`, `parent_revision_id`) REFERENCES `story_bible_revision`(`book_id`, `bible_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`generation_stage_id`) REFERENCES `generation_stage`(`stage_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `outline_revision` (`outline_revision_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `revision_no` INTEGER NOT NULL, `parent_revision_id` TEXT, `source` TEXT NOT NULL, `schema_version` INTEGER NOT NULL, `summary_json` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `generation_stage_id` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`outline_revision_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`book_id`, `parent_revision_id`) REFERENCES `outline_revision`(`book_id`, `outline_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`generation_stage_id`) REFERENCES `generation_stage`(`stage_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `outline_node` (`outline_node_id` TEXT NOT NULL, `outline_revision_id` TEXT NOT NULL, `parent_node_id` TEXT, `node_type` TEXT NOT NULL, `order_key` INTEGER NOT NULL, `planned_chapter_index` INTEGER, `title` TEXT NOT NULL, `plan_json` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`outline_node_id`), FOREIGN KEY(`outline_revision_id`) REFERENCES `outline_revision`(`outline_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`outline_revision_id`, `parent_node_id`) REFERENCES `outline_node`(`outline_revision_id`, `outline_node_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `book_memory_head` (`book_id` TEXT NOT NULL, `current_bible_revision_id` TEXT, `current_outline_revision_id` TEXT, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`book_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`book_id`, `current_bible_revision_id`) REFERENCES `story_bible_revision`(`book_id`, `bible_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`book_id`, `current_outline_revision_id`) REFERENCES `outline_revision`(`book_id`, `outline_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `chapter_summary` (`chapter_summary_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `chapter_version_id` TEXT NOT NULL, `chapter_index` INTEGER NOT NULL, `schema_version` INTEGER NOT NULL, `summary_json` TEXT NOT NULL, `importance` INTEGER NOT NULL, `status` TEXT NOT NULL, `model_snapshot_json` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`chapter_summary_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `story_entity` (`entity_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `entity_type` TEXT NOT NULL, `canonical_name` TEXT NOT NULL, `aliases_json` TEXT NOT NULL, `stable_definition_json` TEXT NOT NULL, `adult_status` TEXT NOT NULL, `age_years` INTEGER, `source_bible_revision_id` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `archived_at` INTEGER, PRIMARY KEY(`entity_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`book_id`, `source_bible_revision_id`) REFERENCES `story_bible_revision`(`book_id`, `bible_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `entity_event` (`entity_event_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `entity_id` TEXT NOT NULL, `source_chapter_version_id` TEXT NOT NULL, `story_order` INTEGER NOT NULL, `attribute_key` TEXT NOT NULL, `old_value_json` TEXT, `new_value_json` TEXT NOT NULL, `story_time_expression` TEXT, `confidence_micros` INTEGER NOT NULL, `canon_level` TEXT NOT NULL, `evidence_json` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`entity_event_id`), FOREIGN KEY(`book_id`, `entity_id`) REFERENCES `story_entity`(`book_id`, `entity_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`source_chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `canon_fact` (`canon_fact_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `entity_id` TEXT, `fact_text` TEXT NOT NULL, `fact_payload_json` TEXT NOT NULL, `canon_level` TEXT NOT NULL, `scope_json` TEXT NOT NULL, `source_chapter_version_id` TEXT, `source_bible_revision_id` TEXT, `valid_from_story_order` INTEGER, `valid_to_story_order` INTEGER, `conflict_group_id` TEXT, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`canon_fact_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`book_id`, `entity_id`) REFERENCES `story_entity`(`book_id`, `entity_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`source_chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`source_bible_revision_id`) REFERENCES `story_bible_revision`(`bible_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `timeline_event` (`timeline_event_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `name` TEXT NOT NULL, `participants_json` TEXT NOT NULL, `location_entity_id` TEXT, `story_time_expression` TEXT NOT NULL, `story_order` INTEGER NOT NULL, `constraints_json` TEXT NOT NULL, `source_chapter_version_id` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`timeline_event_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`book_id`, `location_entity_id`) REFERENCES `story_entity`(`book_id`, `entity_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`source_chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `foreshadow_item` (`foreshadow_item_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `description` TEXT NOT NULL, `foreshadow_status` TEXT NOT NULL, `memory_status` TEXT NOT NULL, `target_start_chapter_index` INTEGER, `target_end_chapter_index` INTEGER, `source_chapter_version_id` TEXT, `planted_chapter_version_id` TEXT, `resolved_chapter_version_id` TEXT, `visible_entity_ids_json` TEXT NOT NULL, `importance` INTEGER NOT NULL, `source` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`foreshadow_item_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`source_chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`planted_chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`resolved_chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `context_snapshot` (`context_snapshot_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `target_chapter_id` TEXT NOT NULL, `target_chapter_index` INTEGER NOT NULL, `generation_stage_id` TEXT NOT NULL, `source_manifest_json` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`context_snapshot_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`book_id`, `target_chapter_id`) REFERENCES `chapter`(`book_id`, `chapter_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`generation_stage_id`) REFERENCES `generation_stage`(`stage_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `consistency_report` (`consistency_report_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `target_chapter_version_id` TEXT NOT NULL, `target_chapter_index` INTEGER NOT NULL, `generation_stage_id` TEXT, `checker_version` TEXT NOT NULL, `issues_json` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`consistency_report_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`target_chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`generation_stage_id`) REFERENCES `generation_stage`(`stage_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `aggregate_state_projection` (`aggregate_state_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `through_chapter_index` INTEGER NOT NULL, `source_through_chapter_version_id` TEXT NOT NULL, `schema_version` INTEGER NOT NULL, `state_json` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `status` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`aggregate_state_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`source_through_chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
            ).forEach(db::execSQL)

            listOf(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_story_bible_revision_book_id_bible_revision_id` ON `story_bible_revision` (`book_id`, `bible_revision_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_story_bible_revision_book_id_revision_no` ON `story_bible_revision` (`book_id`, `revision_no`)",
                "CREATE INDEX IF NOT EXISTS `index_story_bible_revision_book_id_parent_revision_id` ON `story_bible_revision` (`book_id`, `parent_revision_id`)",
                "CREATE INDEX IF NOT EXISTS `index_story_bible_revision_generation_stage_id` ON `story_bible_revision` (`generation_stage_id`)",
                "CREATE INDEX IF NOT EXISTS `index_story_bible_revision_content_hash` ON `story_bible_revision` (`content_hash`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_outline_revision_book_id_outline_revision_id` ON `outline_revision` (`book_id`, `outline_revision_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_outline_revision_book_id_revision_no` ON `outline_revision` (`book_id`, `revision_no`)",
                "CREATE INDEX IF NOT EXISTS `index_outline_revision_book_id_parent_revision_id` ON `outline_revision` (`book_id`, `parent_revision_id`)",
                "CREATE INDEX IF NOT EXISTS `index_outline_revision_generation_stage_id` ON `outline_revision` (`generation_stage_id`)",
                "CREATE INDEX IF NOT EXISTS `index_outline_revision_content_hash` ON `outline_revision` (`content_hash`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_outline_node_outline_revision_id_outline_node_id` ON `outline_node` (`outline_revision_id`, `outline_node_id`)",
                "CREATE INDEX IF NOT EXISTS `index_outline_node_outline_revision_id_parent_node_id` ON `outline_node` (`outline_revision_id`, `parent_node_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_outline_node_outline_revision_id_order_key` ON `outline_node` (`outline_revision_id`, `order_key`)",
                "CREATE INDEX IF NOT EXISTS `index_outline_node_outline_revision_id_planned_chapter_index` ON `outline_node` (`outline_revision_id`, `planned_chapter_index`)",
                "CREATE INDEX IF NOT EXISTS `index_book_memory_head_book_id_current_bible_revision_id` ON `book_memory_head` (`book_id`, `current_bible_revision_id`)",
                "CREATE INDEX IF NOT EXISTS `index_book_memory_head_book_id_current_outline_revision_id` ON `book_memory_head` (`book_id`, `current_outline_revision_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_chapter_summary_chapter_version_id` ON `chapter_summary` (`chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_chapter_summary_book_id_chapter_index` ON `chapter_summary` (`book_id`, `chapter_index`)",
                "CREATE INDEX IF NOT EXISTS `index_chapter_summary_status` ON `chapter_summary` (`status`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_story_entity_book_id_entity_id` ON `story_entity` (`book_id`, `entity_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_story_entity_book_id_entity_type_canonical_name` ON `story_entity` (`book_id`, `entity_type`, `canonical_name`)",
                "CREATE INDEX IF NOT EXISTS `index_story_entity_book_id_source_bible_revision_id` ON `story_entity` (`book_id`, `source_bible_revision_id`)",
                "CREATE INDEX IF NOT EXISTS `index_entity_event_book_id_entity_id_story_order` ON `entity_event` (`book_id`, `entity_id`, `story_order`)",
                "CREATE INDEX IF NOT EXISTS `index_entity_event_source_chapter_version_id` ON `entity_event` (`source_chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_entity_event_status` ON `entity_event` (`status`)",
                "CREATE INDEX IF NOT EXISTS `index_canon_fact_book_id_entity_id_status` ON `canon_fact` (`book_id`, `entity_id`, `status`)",
                "CREATE INDEX IF NOT EXISTS `index_canon_fact_source_chapter_version_id` ON `canon_fact` (`source_chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_canon_fact_source_bible_revision_id` ON `canon_fact` (`source_bible_revision_id`)",
                "CREATE INDEX IF NOT EXISTS `index_canon_fact_conflict_group_id` ON `canon_fact` (`conflict_group_id`)",
                "CREATE INDEX IF NOT EXISTS `index_timeline_event_book_id_story_order` ON `timeline_event` (`book_id`, `story_order`)",
                "CREATE INDEX IF NOT EXISTS `index_timeline_event_book_id_location_entity_id` ON `timeline_event` (`book_id`, `location_entity_id`)",
                "CREATE INDEX IF NOT EXISTS `index_timeline_event_source_chapter_version_id` ON `timeline_event` (`source_chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_timeline_event_status` ON `timeline_event` (`status`)",
                "CREATE INDEX IF NOT EXISTS `index_foreshadow_item_book_id_foreshadow_status` ON `foreshadow_item` (`book_id`, `foreshadow_status`)",
                "CREATE INDEX IF NOT EXISTS `index_foreshadow_item_source_chapter_version_id` ON `foreshadow_item` (`source_chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_foreshadow_item_planted_chapter_version_id` ON `foreshadow_item` (`planted_chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_foreshadow_item_resolved_chapter_version_id` ON `foreshadow_item` (`resolved_chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_foreshadow_item_memory_status` ON `foreshadow_item` (`memory_status`)",
                "CREATE INDEX IF NOT EXISTS `index_context_snapshot_book_id_target_chapter_id` ON `context_snapshot` (`book_id`, `target_chapter_id`)",
                "CREATE INDEX IF NOT EXISTS `index_context_snapshot_book_id_target_chapter_index` ON `context_snapshot` (`book_id`, `target_chapter_index`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_context_snapshot_generation_stage_id` ON `context_snapshot` (`generation_stage_id`)",
                "CREATE INDEX IF NOT EXISTS `index_context_snapshot_status` ON `context_snapshot` (`status`)",
                "CREATE INDEX IF NOT EXISTS `index_consistency_report_target_chapter_version_id` ON `consistency_report` (`target_chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_consistency_report_book_id_target_chapter_index` ON `consistency_report` (`book_id`, `target_chapter_index`)",
                "CREATE INDEX IF NOT EXISTS `index_consistency_report_generation_stage_id` ON `consistency_report` (`generation_stage_id`)",
                "CREATE INDEX IF NOT EXISTS `index_consistency_report_status` ON `consistency_report` (`status`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_aggregate_state_projection_book_id_through_chapter_index` ON `aggregate_state_projection` (`book_id`, `through_chapter_index`)",
                "CREATE INDEX IF NOT EXISTS `index_aggregate_state_projection_source_through_chapter_version_id` ON `aggregate_state_projection` (`source_through_chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_aggregate_state_projection_status` ON `aggregate_state_projection` (`status`)",
            ).forEach(db::execSQL)

            LibraryDatabaseGuards.install(db)
        }
    }

    val MIGRATION_3_4: Migration = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            listOf(
                """
                CREATE TABLE IF NOT EXISTS `template` (`template_id` TEXT NOT NULL, `display_name` TEXT NOT NULL, `description` TEXT NOT NULL, `origin_type` TEXT NOT NULL, `system_preset_key` TEXT, `current_revision_id` TEXT, `is_favorite` INTEGER NOT NULL, `is_pinned` INTEGER NOT NULL, `archived_at` INTEGER, `last_used_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`template_id`), FOREIGN KEY(`template_id`, `current_revision_id`) REFERENCES `template_revision`(`template_id`, `template_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `template_revision` (`template_revision_id` TEXT NOT NULL, `template_id` TEXT NOT NULL, `revision_no` INTEGER NOT NULL, `parent_template_revision_id` TEXT, `source_book_id` TEXT, `source_book_title_snapshot` TEXT, `origin_root_revision_id` TEXT NOT NULL, `origin_chain_json` TEXT NOT NULL, `derivation_key` TEXT, `story_seed_json` TEXT NOT NULL, `genre_json` TEXT NOT NULL, `stable_characters_json` TEXT NOT NULL, `world_rules_json` TEXT NOT NULL, `writing_style_json` TEXT NOT NULL, `structure_json` TEXT NOT NULL, `presentation_json` TEXT NOT NULL, `content_rules_json` TEXT NOT NULL, `generation_strategy_json` TEXT NOT NULL, `model_role_preferences_json` TEXT NOT NULL, `extension_json` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `template_schema_version` INTEGER NOT NULL, `prompt_bundle_version` TEXT NOT NULL, `content_control_schema_version` INTEGER NOT NULL, `created_by_app_version` TEXT NOT NULL, `extraction_model_snapshot_json` TEXT, `created_at` INTEGER NOT NULL, PRIMARY KEY(`template_revision_id`), FOREIGN KEY(`template_id`) REFERENCES `template`(`template_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`parent_template_revision_id`) REFERENCES `template_revision`(`template_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`origin_root_revision_id`) REFERENCES `template_revision`(`template_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `template_use_snapshot` (`template_use_snapshot_id` TEXT NOT NULL, `book_id` TEXT NOT NULL, `template_id` TEXT NOT NULL, `template_revision_id` TEXT NOT NULL, `use_mode` TEXT NOT NULL, `user_overrides_json` TEXT NOT NULL, `source_provenance_json` TEXT NOT NULL, `story_seed_json` TEXT NOT NULL, `genre_json` TEXT NOT NULL, `stable_characters_json` TEXT NOT NULL, `world_rules_json` TEXT NOT NULL, `writing_style_json` TEXT NOT NULL, `structure_json` TEXT NOT NULL, `presentation_json` TEXT NOT NULL, `content_rules_json` TEXT NOT NULL, `generation_strategy_json` TEXT NOT NULL, `model_role_preferences_json` TEXT NOT NULL, `extension_json` TEXT NOT NULL, `capability_resolution_json` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `template_schema_version` INTEGER NOT NULL, `prompt_bundle_version` TEXT NOT NULL, `content_control_schema_version` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`template_use_snapshot_id`), FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`template_id`, `template_revision_id`) REFERENCES `template_revision`(`template_id`, `template_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
                """
                CREATE TABLE IF NOT EXISTS `template_tag` (`template_tag_id` TEXT NOT NULL, `template_id` TEXT NOT NULL, `derived_from_revision_id` TEXT, `dimension` TEXT NOT NULL, `normalized_value` TEXT NOT NULL, `display_name` TEXT NOT NULL, `source` TEXT NOT NULL, `confidence_micros` INTEGER NOT NULL, `is_confirmed` INTEGER NOT NULL, `is_primary` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`template_tag_id`), FOREIGN KEY(`template_id`) REFERENCES `template`(`template_id`) ON UPDATE NO ACTION ON DELETE RESTRICT, FOREIGN KEY(`template_id`, `derived_from_revision_id`) REFERENCES `template_revision`(`template_id`, `template_revision_id`) ON UPDATE NO ACTION ON DELETE RESTRICT)
                """.trimIndent(),
            ).forEach(db::execSQL)

            listOf(
                "CREATE INDEX IF NOT EXISTS `index_template_template_id_current_revision_id` ON `template` (`template_id`, `current_revision_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_template_system_preset_key` ON `template` (`system_preset_key`)",
                "CREATE INDEX IF NOT EXISTS `index_template_origin_type` ON `template` (`origin_type`)",
                "CREATE INDEX IF NOT EXISTS `index_template_is_favorite_is_pinned` ON `template` (`is_favorite`, `is_pinned`)",
                "CREATE INDEX IF NOT EXISTS `index_template_archived_at` ON `template` (`archived_at`)",
                "CREATE INDEX IF NOT EXISTS `index_template_updated_at` ON `template` (`updated_at`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_template_revision_template_id_template_revision_id` ON `template_revision` (`template_id`, `template_revision_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_template_revision_template_id_revision_no` ON `template_revision` (`template_id`, `revision_no`)",
                "CREATE INDEX IF NOT EXISTS `index_template_revision_parent_template_revision_id` ON `template_revision` (`parent_template_revision_id`)",
                "CREATE INDEX IF NOT EXISTS `index_template_revision_origin_root_revision_id` ON `template_revision` (`origin_root_revision_id`)",
                "CREATE INDEX IF NOT EXISTS `index_template_revision_source_book_id` ON `template_revision` (`source_book_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_template_revision_derivation_key` ON `template_revision` (`derivation_key`)",
                "CREATE INDEX IF NOT EXISTS `index_template_revision_content_hash` ON `template_revision` (`content_hash`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_template_use_snapshot_book_id` ON `template_use_snapshot` (`book_id`)",
                "CREATE INDEX IF NOT EXISTS `index_template_use_snapshot_template_id_template_revision_id` ON `template_use_snapshot` (`template_id`, `template_revision_id`)",
                "CREATE INDEX IF NOT EXISTS `index_template_use_snapshot_created_at` ON `template_use_snapshot` (`created_at`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_template_tag_template_id_dimension_normalized_value` ON `template_tag` (`template_id`, `dimension`, `normalized_value`)",
                "CREATE INDEX IF NOT EXISTS `index_template_tag_template_id_derived_from_revision_id` ON `template_tag` (`template_id`, `derived_from_revision_id`)",
                "CREATE INDEX IF NOT EXISTS `index_template_tag_dimension_normalized_value` ON `template_tag` (`dimension`, `normalized_value`)",
                "CREATE INDEX IF NOT EXISTS `index_template_tag_is_primary_is_confirmed_confidence_micros` ON `template_tag` (`is_primary`, `is_confirmed`, `confidence_micros`)",
            ).forEach(db::execSQL)

            LibraryDatabaseGuards.install(db)
        }
    }

    val MIGRATION_4_5: Migration = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `provider_capability` (
                    `connection_id` TEXT NOT NULL,
                    `endpoint_fingerprint` TEXT NOT NULL,
                    `protocol_id` TEXT NOT NULL,
                    `model_id` TEXT NOT NULL,
                    `capability_source` TEXT NOT NULL,
                    `streaming_support` TEXT NOT NULL,
                    `stream_format` TEXT NOT NULL,
                    `structured_output_support` TEXT NOT NULL,
                    `stream_usage_support` TEXT NOT NULL,
                    `system_instruction_support` TEXT NOT NULL,
                    `temperature_support` TEXT NOT NULL,
                    `top_p_support` TEXT NOT NULL,
                    `max_output_tokens_parameter_support` TEXT NOT NULL,
                    `seed_support` TEXT NOT NULL,
                    `reasoning_effort_support` TEXT NOT NULL,
                    `idempotency_key_support` TEXT NOT NULL,
                    `context_limit` INTEGER,
                    `max_output_tokens` INTEGER,
                    `tokenizer_family` TEXT NOT NULL,
                    `verified_at` INTEGER NOT NULL,
                    `expires_at` INTEGER,
                    `adapter_version` TEXT NOT NULL,
                    `risk_acknowledged_at` INTEGER,
                    PRIMARY KEY(
                        `connection_id`,
                        `endpoint_fingerprint`,
                        `protocol_id`,
                        `model_id`,
                        `capability_source`
                    )
                )
                """.trimIndent(),
            )
            listOf(
                "CREATE INDEX IF NOT EXISTS `index_provider_capability_connection_id_protocol_id_model_id` ON `provider_capability` (`connection_id`, `protocol_id`, `model_id`)",
                "CREATE INDEX IF NOT EXISTS `index_provider_capability_expires_at` ON `provider_capability` (`expires_at`)",
                "CREATE INDEX IF NOT EXISTS `index_provider_capability_adapter_version` ON `provider_capability` (`adapter_version`)",
            ).forEach(db::execSQL)
            LibraryDatabaseGuards.install(db)
        }
    }

    val MIGRATION_5_6: Migration = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `connection_profile` (
                    `connection_id` TEXT NOT NULL,
                    `display_name` TEXT NOT NULL,
                    `service_id` TEXT NOT NULL,
                    `protocol_id` TEXT NOT NULL,
                    `base_url` TEXT NOT NULL,
                    `normalized_destination` TEXT NOT NULL,
                    `secret_ref_id` TEXT NOT NULL,
                    `secret_last_four` TEXT NOT NULL,
                    `selected_model_id` TEXT NOT NULL,
                    `available_models_json` TEXT NOT NULL,
                    `model_verification` TEXT NOT NULL,
                    `basic_verified_at` INTEGER NOT NULL,
                    `full_verified_at` INTEGER,
                    `data_disclosure_version` INTEGER,
                    `data_disclosure_accepted_at` INTEGER,
                    `data_disclosure_binding_hash` TEXT,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`connection_id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `current_connection_selection` (
                    `singleton_id` INTEGER NOT NULL,
                    `connection_id` TEXT NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`singleton_id`),
                    FOREIGN KEY(`connection_id`) REFERENCES `connection_profile`(`connection_id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            listOf(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_connection_profile_secret_ref_id` ON `connection_profile` (`secret_ref_id`)",
                "CREATE INDEX IF NOT EXISTS `index_connection_profile_service_id` ON `connection_profile` (`service_id`)",
                "CREATE INDEX IF NOT EXISTS `index_connection_profile_updated_at` ON `connection_profile` (`updated_at`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_current_connection_selection_connection_id` ON `current_connection_selection` (`connection_id`)",
            ).forEach(db::execSQL)
            LibraryDatabaseGuards.install(db)
        }
    }

    val MIGRATION_6_7: Migration = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `book` ADD COLUMN `minimum_chapters` INTEGER NOT NULL DEFAULT 1",
            )
            db.execSQL(
                "ALTER TABLE `book` ADD COLUMN `length_policy_schema_version` INTEGER NOT NULL DEFAULT 0",
            )
            db.execSQL(
                """
                UPDATE `book`
                SET `minimum_chapters` = CASE `length_mode`
                    WHEN 'SHORT' THEN CASE
                        WHEN `target_chapters` IS NOT NULL AND `target_chapters` < 80
                            THEN `target_chapters`
                        ELSE 80
                    END
                    WHEN 'MEDIUM' THEN CASE
                        WHEN `target_chapters` IS NOT NULL AND `target_chapters` < 300
                            THEN `target_chapters`
                        ELSE 300
                    END
                    WHEN 'LONG' THEN CASE
                        WHEN `target_chapters` IS NOT NULL AND `target_chapters` < 301
                            THEN `target_chapters`
                        ELSE 301
                    END
                    ELSE 1
                END
                """.trimIndent(),
            )
            LibraryDatabaseGuards.install(db)
        }
    }

    val MIGRATION_7_8: Migration = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_foreshadow_item_book_id_foreshadow_item_id` ON `foreshadow_item` (`book_id`, `foreshadow_item_id`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `chapter_tracking_projection` (
                    `projection_id` TEXT NOT NULL,
                    `book_id` TEXT NOT NULL,
                    `chapter_version_id` TEXT NOT NULL,
                    `chapter_index` INTEGER NOT NULL,
                    `generation_stage_id` TEXT NOT NULL,
                    `source_chapter_content_hash` TEXT NOT NULL,
                    `source_memory_snapshot_hash` TEXT NOT NULL,
                    `prior_foreshadow_snapshot_hash` TEXT NOT NULL,
                    `output_content_hash` TEXT NOT NULL,
                    `payload_hash` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `model_snapshot_json` TEXT NOT NULL,
                    `timeline_event_count` INTEGER NOT NULL,
                    `foreshadow_transition_count` INTEGER NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`projection_id`),
                    FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`generation_stage_id`) REFERENCES `generation_stage`(`stage_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `foreshadow_transition` (
                    `transition_id` TEXT NOT NULL,
                    `foreshadow_item_id` TEXT NOT NULL,
                    `book_id` TEXT NOT NULL,
                    `source_chapter_version_id` TEXT NOT NULL,
                    `generation_stage_id` TEXT NOT NULL,
                    `story_order` INTEGER NOT NULL,
                    `operation` TEXT NOT NULL,
                    `from_status` TEXT,
                    `to_status` TEXT NOT NULL,
                    `evidence_json` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    PRIMARY KEY(`transition_id`),
                    FOREIGN KEY(`book_id`) REFERENCES `book`(`book_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`book_id`, `foreshadow_item_id`) REFERENCES `foreshadow_item`(`book_id`, `foreshadow_item_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`source_chapter_version_id`) REFERENCES `chapter_version`(`chapter_version_id`) ON UPDATE NO ACTION ON DELETE RESTRICT,
                    FOREIGN KEY(`generation_stage_id`) REFERENCES `generation_stage`(`stage_id`) ON UPDATE NO ACTION ON DELETE RESTRICT
                )
                """.trimIndent(),
            )
            listOf(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_chapter_tracking_projection_chapter_version_id` ON `chapter_tracking_projection` (`chapter_version_id`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_chapter_tracking_projection_generation_stage_id` ON `chapter_tracking_projection` (`generation_stage_id`)",
                "CREATE INDEX IF NOT EXISTS `index_chapter_tracking_projection_book_id_chapter_index` ON `chapter_tracking_projection` (`book_id`, `chapter_index`)",
                "CREATE INDEX IF NOT EXISTS `index_chapter_tracking_projection_status` ON `chapter_tracking_projection` (`status`)",
                "CREATE INDEX IF NOT EXISTS `index_foreshadow_transition_book_id_foreshadow_item_id` ON `foreshadow_transition` (`book_id`, `foreshadow_item_id`)",
                "CREATE INDEX IF NOT EXISTS `index_foreshadow_transition_source_chapter_version_id` ON `foreshadow_transition` (`source_chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_foreshadow_transition_generation_stage_id` ON `foreshadow_transition` (`generation_stage_id`)",
                "CREATE INDEX IF NOT EXISTS `index_foreshadow_transition_book_id_story_order` ON `foreshadow_transition` (`book_id`, `story_order`)",
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_foreshadow_transition_foreshadow_item_id_source_chapter_version_id` ON `foreshadow_transition` (`foreshadow_item_id`, `source_chapter_version_id`)",
                "CREATE INDEX IF NOT EXISTS `index_foreshadow_transition_status` ON `foreshadow_transition` (`status`)",
            ).forEach(db::execSQL)
            LibraryDatabaseGuards.install(db)
        }
    }

    /**
     * The single production migration registry. Keep every supported adjacent
     * migration here so application startup and migration tests cannot drift.
     */
    val ALL: Array<Migration>
        get() = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
        )

    fun pathFrom(
        startVersion: Int,
        targetVersion: Int = ZHIJUAN_DATABASE_SCHEMA_VERSION,
    ): Array<Migration> {
        require(startVersion in 1..targetVersion) {
            "Unsupported database migration start version: $startVersion"
        }
        val byStartVersion = ALL.associateBy(Migration::startVersion)
        val path = mutableListOf<Migration>()
        var currentVersion = startVersion
        while (currentVersion < targetVersion) {
            val migration = checkNotNull(byStartVersion[currentVersion]) {
                "Missing database migration from version $currentVersion to $targetVersion."
            }
            check(migration.endVersion > currentVersion && migration.endVersion <= targetVersion) {
                "Invalid database migration ${migration.startVersion}->${migration.endVersion}."
            }
            path += migration
            currentVersion = migration.endVersion
        }
        return path.toTypedArray()
    }
}
