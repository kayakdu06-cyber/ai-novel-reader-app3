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
        installGenerationTimingGuards(db)
        installBudgetGuards(db)
    }

    /**
     * Schema v17 budget policy + reservation guards. Every trigger is created
     * with `IF NOT EXISTS` and a stable name so re-installation after a
     * migration or onOpen is harmless, and the whole family is skipped when
     * the tables do not exist yet (older migrations).
     */
    private fun installBudgetGuards(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "budget_policy_revision") ||
            !tableExists(db, "budget_policy_head") ||
            !tableExists(db, "request_budget_reservation")
        ) {
            return
        }
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS prevent_budget_policy_revision_update
            BEFORE UPDATE ON budget_policy_revision
            BEGIN
                SELECT RAISE(ABORT, 'budget policy revision is immutable');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS prevent_budget_policy_revision_delete
            BEFORE DELETE ON budget_policy_revision
            BEGIN
                SELECT RAISE(ABORT, 'budget policy revision is immutable');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_budget_policy_revision_insert
            BEFORE INSERT ON budget_policy_revision
            WHEN length(trim(NEW.budget_policy_id)) = 0
              OR length(trim(NEW.scope_key)) = 0
              OR NEW.scope NOT IN ('BOOK', 'DAILY')
              OR NEW.revision_no <= 0
              OR NEW.max_tokens <= 0
              OR ((NEW.max_cost_micros IS NULL) != (NEW.currency IS NULL))
              OR (NEW.max_cost_micros IS NOT NULL AND NEW.max_cost_micros <= 0)
              OR (NEW.currency IS NOT NULL AND (
                    length(NEW.currency) != 3
                    OR NEW.currency != upper(NEW.currency)
                    OR NEW.currency GLOB '*[^A-Z]*'
              ))
              OR NEW.policy_version != 'zhijuan.budget-policy.v1'
              OR NEW.created_at < 0
              OR (NEW.scope = 'BOOK' AND (
                    NEW.book_id IS NULL
                    OR NEW.scope_key != NEW.book_id
                    OR NEW.daily_zone_id IS NOT NULL
              ))
              OR (NEW.scope = 'DAILY' AND (
                    NEW.scope_key != 'GLOBAL'
                    OR NEW.book_id IS NOT NULL
                    OR NEW.daily_zone_id IS NULL
                    OR length(trim(NEW.daily_zone_id)) = 0
                    OR length(NEW.daily_zone_id) > 64
              ))
              OR (NEW.revision_no = 1) != (NEW.parent_budget_policy_id IS NULL)
              OR (NEW.parent_budget_policy_id IS NOT NULL AND NOT EXISTS (
                    SELECT 1 FROM budget_policy_revision p
                    WHERE p.budget_policy_id = NEW.parent_budget_policy_id
                      AND p.scope = NEW.scope
                      AND p.scope_key = NEW.scope_key
                      AND p.revision_no = NEW.revision_no - 1
                      AND p.book_id IS NEW.book_id
                      AND p.daily_zone_id IS NEW.daily_zone_id
              ))
            BEGIN
                SELECT RAISE(ABORT, 'invalid budget policy revision');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS prevent_budget_policy_head_delete
            BEFORE DELETE ON budget_policy_head
            BEGIN
                SELECT RAISE(ABORT, 'budget policy head is immutable');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_budget_policy_head_insert
            BEFORE INSERT ON budget_policy_head
            WHEN NEW.scope NOT IN ('BOOK', 'DAILY')
              OR length(trim(NEW.scope_key)) = 0
              OR NEW.updated_at < 0
              OR NOT EXISTS (
                    SELECT 1 FROM budget_policy_revision r
                    WHERE r.budget_policy_id = NEW.current_budget_policy_id
                      AND r.scope = NEW.scope
                      AND r.scope_key = NEW.scope_key
                      AND r.revision_no = 1
                      AND r.parent_budget_policy_id IS NULL
              )
            BEGIN
                SELECT RAISE(ABORT, 'invalid budget policy head');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_budget_policy_head_update
            BEFORE UPDATE ON budget_policy_head
            WHEN NEW.scope IS NOT OLD.scope
              OR NEW.scope_key IS NOT OLD.scope_key
              OR NEW.updated_at < 0
              OR NEW.updated_at < OLD.updated_at
              OR NOT EXISTS (
                    SELECT 1 FROM budget_policy_revision r
                    WHERE r.budget_policy_id = NEW.current_budget_policy_id
                      AND r.scope = NEW.scope
                      AND r.scope_key = NEW.scope_key
                      AND r.parent_budget_policy_id = OLD.current_budget_policy_id
              )
            BEGIN
                SELECT RAISE(ABORT, 'budget policy head can only advance to a direct child revision');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS prevent_request_budget_reservation_delete
            BEFORE DELETE ON request_budget_reservation
            BEGIN
                SELECT RAISE(ABORT, 'budget reservation is immutable');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_request_budget_reservation_insert
            BEFORE INSERT ON request_budget_reservation
            WHEN length(trim(NEW.budget_reservation_id)) = 0
              OR length(trim(NEW.attempt_id)) = 0
              OR length(trim(NEW.job_id)) = 0
              OR length(trim(NEW.stage_id)) = 0
              OR length(trim(NEW.book_id)) = 0
              OR length(trim(NEW.connection_id)) = 0
              OR length(trim(NEW.normalized_destination)) = 0
              OR length(trim(NEW.protocol_id)) = 0
              OR NEW.status != 'RESERVED'
              OR NEW.request_max_tokens <= 0
              OR NEW.estimated_tokens <= 0
              OR NEW.accounted_tokens < 0
              OR NEW.disclosure_version < 0
              OR ((NEW.request_max_cost_micros IS NULL) != (NEW.request_currency IS NULL))
              OR (NEW.request_max_cost_micros IS NOT NULL AND NEW.request_max_cost_micros <= 0)
              OR (NEW.request_currency IS NOT NULL AND (
                    length(NEW.request_currency) != 3
                    OR NEW.request_currency != upper(NEW.request_currency)
                    OR NEW.request_currency GLOB '*[^A-Z]*'
              ))
              OR ((NEW.estimated_cost_micros IS NULL) != (NEW.estimated_currency IS NULL))
              OR (NEW.estimated_cost_micros IS NOT NULL AND NEW.estimated_cost_micros < 0)
              OR (NEW.estimated_currency IS NOT NULL AND (
                    length(NEW.estimated_currency) != 3
                    OR NEW.estimated_currency != upper(NEW.estimated_currency)
                    OR NEW.estimated_currency GLOB '*[^A-Z]*'
              ))
              OR ((NEW.accounted_cost_micros IS NULL) != (NEW.accounted_currency IS NULL))
              OR (NEW.accounted_cost_micros IS NOT NULL AND NEW.accounted_cost_micros < 0)
              OR (NEW.accounted_currency IS NOT NULL AND (
                    length(NEW.accounted_currency) != 3
                    OR NEW.accounted_currency != upper(NEW.accounted_currency)
                    OR NEW.accounted_currency GLOB '*[^A-Z]*'
              ))
              OR length(NEW.disclosure_binding_hash) != 64
              OR NEW.disclosure_binding_hash GLOB '*[^0-9a-f]*'
              OR NEW.created_at < 0
              OR NEW.updated_at < 0
              OR NEW.updated_at < NEW.created_at
              OR NEW.disclosure_accepted_at < 0
              OR NEW.disclosure_accepted_at > NEW.created_at
              OR NEW.accounted_tokens != NEW.estimated_tokens
              OR NEW.accounted_cost_micros IS NOT NEW.estimated_cost_micros
              OR NEW.accounted_currency IS NOT NEW.estimated_currency
              OR NEW.settled_at IS NOT NULL
              OR NEW.released_at IS NOT NULL
              OR (NEW.settled_at IS NOT NULL AND (
                    NEW.settled_at < 0 OR NEW.settled_at < NEW.created_at
              ))
              OR (NEW.released_at IS NOT NULL AND (
                    NEW.released_at < 0 OR NEW.released_at < NEW.created_at
              ))
              OR substr(NEW.daily_period_key, 1, 10) NOT GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]'
              OR NOT EXISTS (
                    SELECT 1 FROM budget_policy_revision p
                    WHERE p.budget_policy_id = NEW.book_policy_id
                      AND p.scope = 'BOOK'
                      AND p.book_id = NEW.book_id
              )
              OR NOT EXISTS (
                    SELECT 1
                    FROM generation_stage s
                    JOIN generation_job j ON j.job_id = s.job_id
                    WHERE s.job_id = NEW.job_id
                      AND s.stage_id = NEW.stage_id
                      AND j.book_id = NEW.book_id
              )
              OR NOT EXISTS (
                    SELECT 1 FROM budget_policy_revision p
                    WHERE p.budget_policy_id = NEW.daily_policy_id
                      AND p.scope = 'DAILY'
                      AND p.daily_zone_id IS NOT NULL
                      AND NEW.daily_period_key =
                          substr(NEW.daily_period_key, 1, 10) || '|' || p.daily_zone_id
              )
            BEGIN
                SELECT RAISE(ABORT, 'invalid budget reservation fields');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_request_budget_reservation_update
            BEFORE UPDATE ON request_budget_reservation
            WHEN NEW.budget_reservation_id IS NOT OLD.budget_reservation_id
              OR NEW.attempt_id IS NOT OLD.attempt_id
              OR NEW.job_id IS NOT OLD.job_id
              OR NEW.stage_id IS NOT OLD.stage_id
              OR NEW.book_id IS NOT OLD.book_id
              OR NEW.request_max_tokens IS NOT OLD.request_max_tokens
              OR NEW.request_max_cost_micros IS NOT OLD.request_max_cost_micros
              OR NEW.request_currency IS NOT OLD.request_currency
              OR NEW.estimated_tokens IS NOT OLD.estimated_tokens
              OR NEW.estimated_cost_micros IS NOT OLD.estimated_cost_micros
              OR NEW.estimated_currency IS NOT OLD.estimated_currency
              OR NEW.estimate_source_version IS NOT OLD.estimate_source_version
              OR NEW.book_policy_id IS NOT OLD.book_policy_id
              OR NEW.daily_policy_id IS NOT OLD.daily_policy_id
              OR NEW.daily_period_key IS NOT OLD.daily_period_key
              OR NEW.connection_id IS NOT OLD.connection_id
              OR NEW.normalized_destination IS NOT OLD.normalized_destination
              OR NEW.protocol_id IS NOT OLD.protocol_id
              OR NEW.disclosure_version IS NOT OLD.disclosure_version
              OR NEW.disclosure_binding_hash IS NOT OLD.disclosure_binding_hash
              OR NEW.disclosure_accepted_at IS NOT OLD.disclosure_accepted_at
              OR NEW.created_at IS NOT OLD.created_at
              OR NEW.updated_at < OLD.updated_at
              OR NEW.status NOT IN ('RESERVED', 'SETTLED', 'RELEASED')
              OR NEW.accounted_tokens < 0
              OR ((NEW.accounted_cost_micros IS NULL) != (NEW.accounted_currency IS NULL))
              OR (NEW.accounted_cost_micros IS NOT NULL AND NEW.accounted_cost_micros < 0)
              OR (NEW.accounted_currency IS NOT NULL AND (
                    length(NEW.accounted_currency) != 3
                    OR NEW.accounted_currency != upper(NEW.accounted_currency)
                    OR NEW.accounted_currency GLOB '*[^A-Z]*'
              ))
              OR NOT (
                    (OLD.status = 'RESERVED' AND NEW.status IN ('RESERVED', 'SETTLED', 'RELEASED'))
                    OR (OLD.status = 'SETTLED' AND NEW.status = 'SETTLED')
                    OR (OLD.status = 'RELEASED' AND NEW.status IN ('RELEASED', 'SETTLED'))
              )
              OR (NEW.status = 'RESERVED' AND (
                    NEW.accounted_tokens != NEW.estimated_tokens
                    OR NEW.accounted_cost_micros IS NOT NEW.estimated_cost_micros
                    OR NEW.accounted_currency IS NOT NEW.estimated_currency
                    OR NEW.settled_at IS NOT NULL
                    OR NEW.released_at IS NOT NULL
              ))
              OR (NEW.status = 'SETTLED' AND (
                    NEW.settled_at IS NULL
                    OR NEW.released_at IS NOT OLD.released_at
              ))
              OR (NEW.status = 'RELEASED' AND (
                    NEW.released_at IS NULL
                    OR NEW.accounted_tokens != 0
                    OR NEW.accounted_cost_micros IS NOT NULL
              ))
              OR (NEW.settled_at IS NOT NULL AND (
                    NEW.settled_at < 0 OR NEW.settled_at < NEW.created_at
              ))
              OR (NEW.released_at IS NOT NULL AND (
                    NEW.released_at < 0 OR NEW.released_at < NEW.created_at
              ))
              OR (OLD.settled_at IS NOT NULL AND NEW.settled_at IS NOT OLD.settled_at)
              OR (OLD.released_at IS NOT NULL AND NEW.released_at IS NOT OLD.released_at)
            BEGIN
                SELECT RAISE(ABORT, 'invalid budget reservation update');
            END
            """.trimIndent(),
        )
        if (columnExists(db, "request_attempt", "budget_enforcement_version") &&
            columnExists(db, "request_attempt", "budget_reservation_id")
        ) {
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_request_attempt_budget_insert
                BEFORE INSERT ON request_attempt
                WHEN NEW.budget_enforcement_version NOT IN (0, 1)
                  OR (NEW.budget_enforcement_version = 0 AND NEW.budget_reservation_id IS NOT NULL)
                  OR (NEW.budget_enforcement_version = 1 AND (
                        NEW.budget_reservation_id IS NULL
                        OR NOT EXISTS (
                            SELECT 1 FROM request_budget_reservation r
                            WHERE r.attempt_id = NEW.attempt_id
                              AND r.job_id = NEW.job_id
                              AND r.stage_id = NEW.stage_id
                        )
                  ))
                BEGIN
                    SELECT RAISE(ABORT, 'invalid request attempt budget enforcement');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS validate_request_attempt_budget_update
                BEFORE UPDATE ON request_attempt
                WHEN NEW.budget_enforcement_version IS NOT OLD.budget_enforcement_version
                  OR NEW.budget_reservation_id IS NOT OLD.budget_reservation_id
                  OR NEW.budget_enforcement_version NOT IN (0, 1)
                  OR (NEW.budget_enforcement_version = 0 AND NEW.budget_reservation_id IS NOT NULL)
                  OR (NEW.budget_enforcement_version = 1 AND (
                        NEW.budget_reservation_id IS NULL
                        OR NOT EXISTS (
                            SELECT 1 FROM request_budget_reservation r
                            WHERE r.attempt_id = NEW.attempt_id
                              AND r.job_id = NEW.job_id
                              AND r.stage_id = NEW.stage_id
                        )
                  ))
                BEGIN
                    SELECT RAISE(ABORT, 'request attempt budget identity is immutable');
                END
                """.trimIndent(),
            )
        }
    }

    private fun installGenerationTimingGuards(db: SupportSQLiteDatabase) {
        if (!tableExists(db, "generation_timing_event")) return

        installImmutableTrigger(db, "generation_timing_event")
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS prevent_generation_timing_event_delete
            BEFORE DELETE ON generation_timing_event
            BEGIN
                SELECT RAISE(ABORT, 'generation timing evidence is immutable');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_generation_timing_event_insert
            BEFORE INSERT ON generation_timing_event
            WHEN length(NEW.event_id) != 64
              OR NEW.event_id GLOB '*[^0-9a-f]*'
              OR length(NEW.boot_fingerprint) != 24
              OR NEW.boot_fingerprint GLOB '*[^0-9a-f]*'
              OR length(NEW.run_fingerprint) != 24
              OR NEW.run_fingerprint GLOB '*[^0-9a-f]*'
              OR length(NEW.book_fingerprint) != 24
              OR NEW.book_fingerprint GLOB '*[^0-9a-f]*'
              OR (NEW.job_fingerprint IS NOT NULL AND (
                    length(NEW.job_fingerprint) != 24
                 OR NEW.job_fingerprint GLOB '*[^0-9a-f]*'))
              OR (NEW.stage_fingerprint IS NOT NULL AND (
                    length(NEW.stage_fingerprint) != 24
                 OR NEW.stage_fingerprint GLOB '*[^0-9a-f]*'))
              OR (NEW.attempt_fingerprint IS NOT NULL AND (
                    length(NEW.attempt_fingerprint) != 24
                 OR NEW.attempt_fingerprint GLOB '*[^0-9a-f]*'))
              OR (NEW.connection_fingerprint IS NOT NULL AND (
                    length(NEW.connection_fingerprint) != 24
                 OR NEW.connection_fingerprint GLOB '*[^0-9a-f]*'))
              OR (NEW.model_fingerprint IS NOT NULL AND (
                    length(NEW.model_fingerprint) != 24
                 OR NEW.model_fingerprint GLOB '*[^0-9a-f]*'))
              OR NEW.occurred_epoch_millis < 0
              OR NEW.occurred_elapsed_realtime_millis < 0
              OR (NEW.stage_fingerprint IS NOT NULL AND NEW.job_fingerprint IS NULL)
              OR (NEW.attempt_fingerprint IS NOT NULL AND NEW.stage_fingerprint IS NULL)
              OR ((NEW.attempt_no IS NULL) != (NEW.attempt_fingerprint IS NULL))
              OR (NEW.attempt_no IS NOT NULL AND (NEW.attempt_no < 1 OR NEW.attempt_no > 1000))
              OR (NEW.character_count IS NOT NULL AND NEW.character_count < 0)
              OR (NEW.input_token_count IS NOT NULL AND NEW.input_token_count < 0)
              OR (NEW.output_token_count IS NOT NULL AND NEW.output_token_count < 0)
              OR (NEW.total_token_count IS NOT NULL AND NEW.total_token_count < 0)
              OR NEW.phase NOT IN (
                    'CHAPTER', 'CONTEXT', 'BODY', 'MEMORY', 'TRACKING',
                    'CONSISTENCY', 'REVISION', 'COMMIT'
              )
              OR NEW.milestone NOT IN (
                    'CHAPTER_REQUESTED', 'STAGE_QUEUED', 'STAGE_STARTED', 'LOCAL_CONTEXT_READY',
                    'PROVIDER_OPENED', 'FIRST_BYTE', 'FIRST_FULL_PARAGRAPH', 'BODY_STREAM_ENDED',
                    'MEMORY_STARTED', 'MEMORY_ENDED', 'TRACKING_STARTED', 'TRACKING_ENDED',
                    'CONSISTENCY_STARTED', 'CONSISTENCY_ENDED', 'REVISION_STARTED', 'REVISION_ENDED',
                    'COMMIT_STARTED', 'FORMAL_COMMIT', 'NEXT_CHAPTER_STARTED'
              )
              OR (NEW.milestone IN ('CHAPTER_REQUESTED', 'NEXT_CHAPTER_STARTED')
                    AND NEW.phase != 'CHAPTER')
              OR (NEW.milestone = 'LOCAL_CONTEXT_READY' AND NEW.phase != 'CONTEXT')
              OR (NEW.milestone = 'FIRST_FULL_PARAGRAPH' AND NEW.phase != 'BODY')
              OR (NEW.milestone IN ('MEMORY_STARTED', 'MEMORY_ENDED')
                    AND NEW.phase != 'MEMORY')
              OR (NEW.milestone IN ('TRACKING_STARTED', 'TRACKING_ENDED')
                    AND NEW.phase != 'TRACKING')
              OR (NEW.milestone IN ('CONSISTENCY_STARTED', 'CONSISTENCY_ENDED')
                    AND NEW.phase != 'CONSISTENCY')
              OR (NEW.milestone IN ('REVISION_STARTED', 'REVISION_ENDED')
                    AND NEW.phase != 'REVISION')
              OR (NEW.milestone IN ('COMMIT_STARTED', 'FORMAL_COMMIT')
                    AND NEW.phase != 'COMMIT')
              OR (NEW.outcome IS NOT NULL AND NEW.outcome NOT IN (
                    'SUCCEEDED', 'FAILED_CLOSED', 'CANCELLED', 'NEEDS_ACTION', 'TRUNCATED', 'UNKNOWN'
              ))
              OR (NEW.milestone IN (
                    'BODY_STREAM_ENDED', 'MEMORY_ENDED', 'TRACKING_ENDED',
                    'CONSISTENCY_ENDED', 'REVISION_ENDED', 'FORMAL_COMMIT'
                 ) AND NEW.outcome IS NULL)
              OR (NEW.milestone NOT IN (
                    'BODY_STREAM_ENDED', 'MEMORY_ENDED', 'TRACKING_ENDED',
                    'CONSISTENCY_ENDED', 'REVISION_ENDED', 'FORMAL_COMMIT'
                 ) AND NEW.outcome IS NOT NULL)
              OR (NEW.milestone IN (
                    'PROVIDER_OPENED', 'FIRST_BYTE', 'FIRST_FULL_PARAGRAPH', 'BODY_STREAM_ENDED'
                 ) AND NEW.attempt_fingerprint IS NULL)
              OR EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.boot_fingerprint = NEW.boot_fingerprint
                      AND prior.occurred_elapsed_realtime_millis > NEW.occurred_elapsed_realtime_millis
              )
              OR (NEW.milestone != 'NEXT_CHAPTER_STARTED' AND EXISTS (
                    SELECT 1 FROM generation_timing_event terminal
                    WHERE terminal.run_fingerprint = NEW.run_fingerprint
                      AND terminal.milestone = 'FORMAL_COMMIT'
                      AND terminal.outcome = 'SUCCEEDED'
              ))
            BEGIN
                SELECT RAISE(ABORT, 'invalid generation timing evidence');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS validate_generation_timing_predecessor_insert
            BEFORE INSERT ON generation_timing_event
            WHEN (NEW.milestone = 'STAGE_STARTED' AND NOT EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.stage_fingerprint = NEW.stage_fingerprint
                      AND prior.phase = NEW.phase
                      AND prior.milestone = 'STAGE_QUEUED'
                 ))
              OR (NEW.milestone = 'LOCAL_CONTEXT_READY' AND NOT EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.stage_fingerprint = NEW.stage_fingerprint
                      AND prior.phase = NEW.phase
                      AND prior.milestone = 'STAGE_STARTED'
                 ))
              OR (NEW.milestone IN ('FIRST_BYTE', 'FIRST_FULL_PARAGRAPH', 'BODY_STREAM_ENDED') AND NOT EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.attempt_fingerprint = NEW.attempt_fingerprint
                      AND prior.phase = NEW.phase
                      AND prior.milestone = 'PROVIDER_OPENED'
                 ))
              OR (NEW.milestone = 'FIRST_FULL_PARAGRAPH' AND NOT EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.attempt_fingerprint = NEW.attempt_fingerprint
                      AND prior.phase = NEW.phase
                      AND prior.milestone = 'FIRST_BYTE'
                 ))
              OR (NEW.milestone = 'MEMORY_ENDED' AND NOT EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.phase = NEW.phase
                      AND prior.milestone = 'MEMORY_STARTED'
                 ))
              OR (NEW.milestone = 'TRACKING_ENDED' AND NOT EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.phase = NEW.phase
                      AND prior.milestone = 'TRACKING_STARTED'
                 ))
              OR (NEW.milestone = 'CONSISTENCY_ENDED' AND NOT EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.phase = NEW.phase
                      AND prior.milestone = 'CONSISTENCY_STARTED'
                 ))
              OR (NEW.milestone = 'REVISION_ENDED' AND NOT EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.phase = NEW.phase
                      AND prior.milestone = 'REVISION_STARTED'
                 ))
              OR (NEW.milestone = 'FORMAL_COMMIT' AND NOT EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.phase = NEW.phase
                      AND prior.milestone = 'COMMIT_STARTED'
                 ))
              OR (NEW.milestone = 'NEXT_CHAPTER_STARTED' AND NOT EXISTS (
                    SELECT 1 FROM generation_timing_event prior
                    WHERE prior.run_fingerprint = NEW.run_fingerprint
                      AND prior.phase = 'COMMIT'
                      AND prior.milestone = 'FORMAL_COMMIT'
                      AND prior.outcome = 'SUCCEEDED'
                 ))
            BEGIN
                SELECT RAISE(ABORT, 'generation timing predecessor is missing');
            END
            """.trimIndent(),
        )
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
            if (tableExists(db, "foreshadow_projection_revision")) {
                installChapterVersionGuard(
                    db,
                    "foreshadow_projection_revision",
                    "source_chapter_version_id",
                    "OR NEW.chapter_index <= 0 OR NEW.chapter_index != (SELECT chapter.chapter_index FROM chapter_version INNER JOIN chapter ON chapter.chapter_id = chapter_version.chapter_id WHERE chapter_version.chapter_version_id = NEW.source_chapter_version_id) OR NEW.book_id != (SELECT generation_job.book_id FROM generation_stage INNER JOIN generation_job ON generation_job.job_id = generation_stage.job_id WHERE generation_stage.stage_id = NEW.generation_stage_id) OR NEW.book_id != (SELECT book_id FROM foreshadow_item WHERE foreshadow_item_id = NEW.foreshadow_item_id) OR NEW.book_id != (SELECT book_id FROM foreshadow_transition WHERE transition_id = NEW.transition_id) OR NEW.foreshadow_item_id != (SELECT foreshadow_item_id FROM foreshadow_transition WHERE transition_id = NEW.transition_id) OR NEW.source_chapter_version_id != (SELECT source_chapter_version_id FROM foreshadow_transition WHERE transition_id = NEW.transition_id) OR NEW.generation_stage_id != (SELECT generation_stage_id FROM foreshadow_transition WHERE transition_id = NEW.transition_id) OR NEW.story_order != (SELECT story_order FROM foreshadow_transition WHERE transition_id = NEW.transition_id) OR NEW.created_at != (SELECT created_at FROM foreshadow_transition WHERE transition_id = NEW.transition_id) OR NEW.story_order <= 0 OR NEW.snapshot_schema_version != 1 OR length(NEW.snapshot_json) NOT BETWEEN 2 AND 65536 OR length(NEW.snapshot_hash) != 64 OR NEW.snapshot_hash GLOB '*[^0-9a-f]*'",
                )
            }
            if (tableExists(db, "foreshadow_projection_rewind")) {
                installImmutableTrigger(db, "foreshadow_projection_rewind")
                installDerivedHistoryDeleteGuard(db, "foreshadow_projection_rewind")
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS validate_foreshadow_projection_rewind_insert
                    BEFORE INSERT ON foreshadow_projection_rewind
                    WHEN NOT EXISTS (
                        SELECT 1
                        FROM chapter
                        INNER JOIN chapter_version AS edited_version
                          ON edited_version.chapter_id = chapter.chapter_id
                        INNER JOIN chapter_version AS replaced_version
                          ON replaced_version.chapter_version_id = NEW.replaced_chapter_version_id
                        WHERE chapter.book_id = NEW.book_id
                          AND chapter.chapter_id = NEW.edited_chapter_id
                          AND chapter.current_version_id = NEW.edited_chapter_version_id
                          AND chapter.chapter_index = NEW.first_affected_chapter_index
                          AND edited_version.chapter_version_id = NEW.edited_chapter_version_id
                          AND edited_version.chapter_id = chapter.chapter_id
                          AND edited_version.source = 'USER_EDIT'
                          AND edited_version.parent_version_id = NEW.replaced_chapter_version_id
                          AND replaced_version.chapter_id = chapter.chapter_id
                    )
                      OR NEW.first_affected_chapter_index <= 0
                      OR NEW.last_affected_chapter_index < NEW.first_affected_chapter_index
                      OR length(NEW.plan_hash) != 64 OR NEW.plan_hash GLOB '*[^0-9a-f]*'
                      OR length(NEW.before_projection_set_hash) != 64 OR NEW.before_projection_set_hash GLOB '*[^0-9a-f]*'
                      OR length(NEW.trusted_baseline_set_hash) != 64 OR NEW.trusted_baseline_set_hash GLOB '*[^0-9a-f]*'
                      OR length(NEW.after_projection_set_hash) != 64 OR NEW.after_projection_set_hash GLOB '*[^0-9a-f]*'
                      OR NEW.affected_item_count < 0
                      OR NEW.baseline_item_count < 0
                      OR NEW.absent_item_count < 0
                      OR NEW.affected_item_count != NEW.baseline_item_count + NEW.absent_item_count
                      OR NEW.stale_revision_count < 0
                      OR NEW.stale_transition_count < 0
                      OR NEW.policy_version != 'zhijuan.foreshadow-projection-rewind.v1'
                      OR NEW.created_at < 0
                      OR NEW.created_at < (
                          SELECT created_at FROM chapter_version
                          WHERE chapter_version_id = NEW.edited_chapter_version_id
                      )
                    BEGIN
                        SELECT RAISE(ABORT, 'invalid foreshadow projection rewind provenance');
                    END
                    """.trimIndent(),
                )
            }
            if (tableExists(db, "chapter_edit_rebuild_execution")) {
                installImmutableTrigger(db, "chapter_edit_rebuild_execution")
                installDerivedHistoryDeleteGuard(db, "chapter_edit_rebuild_execution")
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS validate_chapter_edit_rebuild_execution_insert
                    BEFORE INSERT ON chapter_edit_rebuild_execution
                    WHEN NOT EXISTS (
                        SELECT 1
                        FROM chapter
                        INNER JOIN chapter_version AS edited_version
                          ON edited_version.chapter_version_id = NEW.edited_chapter_version_id
                        INNER JOIN chapter_version AS replaced_version
                          ON replaced_version.chapter_version_id = NEW.replaced_chapter_version_id
                        INNER JOIN foreshadow_projection_rewind AS rewind
                          ON rewind.rewind_id = NEW.rewind_id
                        WHERE chapter.book_id = NEW.book_id
                          AND chapter.chapter_id = NEW.edited_chapter_id
                          AND chapter.current_version_id = NEW.edited_chapter_version_id
                          AND chapter.chapter_index = NEW.first_affected_chapter_index
                          AND edited_version.chapter_id = chapter.chapter_id
                          AND edited_version.source = 'USER_EDIT'
                          AND edited_version.parent_version_id = NEW.replaced_chapter_version_id
                          AND replaced_version.chapter_id = chapter.chapter_id
                          AND rewind.book_id = NEW.book_id
                          AND rewind.edited_chapter_id = NEW.edited_chapter_id
                          AND rewind.edited_chapter_version_id = NEW.edited_chapter_version_id
                          AND rewind.replaced_chapter_version_id = NEW.replaced_chapter_version_id
                          AND rewind.first_affected_chapter_index = NEW.first_affected_chapter_index
                          AND rewind.last_affected_chapter_index = NEW.last_affected_chapter_index
                          AND rewind.plan_hash = NEW.initial_plan_hash
                          AND rewind.created_at = NEW.prepared_at
                    )
                      OR length(NEW.execution_id) NOT BETWEEN 1 AND 128
                      OR NEW.first_affected_chapter_index <= 0
                      OR NEW.last_affected_chapter_index < NEW.first_affected_chapter_index
                      OR NEW.future_chapter_policy != 'KEEP_EXISTING'
                      OR NEW.plan_schema_version != 2
                      OR length(NEW.initial_plan_hash) != 64 OR NEW.initial_plan_hash GLOB '*[^0-9a-f]*'
                      OR length(NEW.stable_fence_hash) != 64 OR NEW.stable_fence_hash GLOB '*[^0-9a-f]*'
                      OR NEW.policy_version != 'zhijuan.chapter-edit-rebuild-execution.v1'
                      OR NEW.status != 'PREPARED'
                      OR NEW.prepared_at < 0
                    BEGIN
                        SELECT RAISE(ABORT, 'invalid chapter edit rebuild execution provenance');
                    END
                    """.trimIndent(),
                )
            }
            if (tableExists(db, "chapter_edit_rebuild_step")) {
                installImmutableTrigger(db, "chapter_edit_rebuild_step")
                installDerivedHistoryDeleteGuard(db, "chapter_edit_rebuild_step")
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS validate_chapter_edit_rebuild_step_insert
                    BEFORE INSERT ON chapter_edit_rebuild_step
                    WHEN NOT EXISTS (
                        SELECT 1
                        FROM chapter_edit_rebuild_execution AS execution
                        INNER JOIN chapter
                          ON chapter.book_id = NEW.book_id
                         AND chapter.chapter_id = NEW.chapter_id
                        INNER JOIN chapter_version
                          ON chapter_version.chapter_version_id = NEW.source_chapter_version_id
                        WHERE execution.execution_id = NEW.execution_id
                          AND execution.book_id = NEW.book_id
                          AND NEW.chapter_index BETWEEN execution.first_affected_chapter_index
                                                    AND execution.last_affected_chapter_index
                          AND NEW.created_at = execution.prepared_at
                          AND chapter.chapter_index = NEW.chapter_index
                          AND chapter.current_version_id = NEW.source_chapter_version_id
                          AND chapter_version.chapter_id = NEW.chapter_id
                          AND chapter_version.content_hash = NEW.source_content_hash
                    )
                      OR NEW.step_ordinal <= 0
                      OR NEW.step_type NOT IN ('EDITED_MEMORY', 'TRACKING', 'AGGREGATE')
                      OR NEW.prepared_state NOT IN ('PENDING', 'SATISFIED')
                      OR NEW.needs_provider NOT IN (0, 1)
                      OR length(NEW.source_content_hash) != 64 OR NEW.source_content_hash GLOB '*[^0-9a-f]*'
                      OR ((NEW.baseline_summary_id IS NULL) != (NEW.baseline_summary_fingerprint IS NULL))
                      OR ((NEW.baseline_tracking_projection_id IS NULL) != (NEW.baseline_tracking_fingerprint IS NULL))
                      OR ((NEW.baseline_aggregate_state_id IS NULL) != (NEW.baseline_aggregate_fingerprint IS NULL))
                      OR (NEW.baseline_summary_fingerprint IS NOT NULL AND (length(NEW.baseline_summary_fingerprint) != 64 OR NEW.baseline_summary_fingerprint GLOB '*[^0-9a-f]*'))
                      OR (NEW.baseline_tracking_fingerprint IS NOT NULL AND (length(NEW.baseline_tracking_fingerprint) != 64 OR NEW.baseline_tracking_fingerprint GLOB '*[^0-9a-f]*'))
                      OR (NEW.baseline_aggregate_fingerprint IS NOT NULL AND (length(NEW.baseline_aggregate_fingerprint) != 64 OR NEW.baseline_aggregate_fingerprint GLOB '*[^0-9a-f]*'))
                      OR (NEW.baseline_summary_id IS NOT NULL AND NOT EXISTS (
                          SELECT 1 FROM chapter_summary
                          WHERE chapter_summary_id = NEW.baseline_summary_id
                            AND book_id = NEW.book_id
                            AND chapter_version_id = NEW.source_chapter_version_id
                            AND chapter_index = NEW.chapter_index
                            AND status = 'VALID'
                      ))
                      OR (NEW.baseline_tracking_projection_id IS NOT NULL AND NOT EXISTS (
                          SELECT 1 FROM chapter_tracking_projection
                          WHERE projection_id = NEW.baseline_tracking_projection_id
                            AND book_id = NEW.book_id
                            AND chapter_version_id = NEW.source_chapter_version_id
                            AND chapter_index = NEW.chapter_index
                            AND status = 'VALID'
                      ))
                      OR (NEW.baseline_aggregate_state_id IS NOT NULL AND NOT EXISTS (
                          SELECT 1 FROM aggregate_state_projection
                          WHERE aggregate_state_id = NEW.baseline_aggregate_state_id
                            AND book_id = NEW.book_id
                            AND source_through_chapter_version_id = NEW.source_chapter_version_id
                            AND through_chapter_index = NEW.chapter_index
                            AND status = 'VALID'
                      ))
                      OR (NEW.step_type = 'EDITED_MEMORY' AND (
                          NEW.needs_provider != 1
                          OR NEW.chapter_index != (SELECT first_affected_chapter_index FROM chapter_edit_rebuild_execution WHERE execution_id = NEW.execution_id)
                          OR NEW.baseline_tracking_projection_id IS NOT NULL
                          OR NEW.baseline_aggregate_state_id IS NOT NULL
                          OR (NEW.prepared_state = 'PENDING' AND NEW.baseline_summary_id IS NOT NULL)
                          OR (NEW.prepared_state = 'SATISFIED' AND NEW.baseline_summary_id IS NULL)
                      ))
                      OR (NEW.step_type = 'TRACKING' AND (
                          NEW.needs_provider != 1
                          OR NEW.baseline_summary_id IS NOT NULL
                          OR NEW.baseline_aggregate_state_id IS NOT NULL
                          OR (NEW.prepared_state = 'SATISFIED' AND NEW.baseline_tracking_projection_id IS NULL)
                      ))
                      OR (NEW.step_type = 'AGGREGATE' AND (
                          NEW.needs_provider != 0
                          OR NEW.baseline_summary_id IS NOT NULL
                          OR NEW.baseline_tracking_projection_id IS NOT NULL
                          OR (NEW.prepared_state = 'SATISFIED' AND NEW.baseline_aggregate_state_id IS NULL)
                      ))
                    BEGIN
                        SELECT RAISE(ABORT, 'invalid chapter edit rebuild step provenance');
                    END
                    """.trimIndent(),
                )
            }
            if (tableExists(db, "chapter_edit_rebuild_tracking_retirement")) {
                installImmutableTrigger(db, "chapter_edit_rebuild_tracking_retirement")
                installDerivedHistoryDeleteGuard(db, "chapter_edit_rebuild_tracking_retirement")
                db.execSQL(
                    """
                    CREATE TRIGGER IF NOT EXISTS validate_chapter_edit_rebuild_tracking_retirement_insert
                    BEFORE INSERT ON chapter_edit_rebuild_tracking_retirement
                    WHEN NOT EXISTS (
                        SELECT 1
                        FROM chapter_edit_rebuild_execution AS execution
                        INNER JOIN chapter_edit_rebuild_step AS step
                          ON step.execution_id = NEW.execution_id
                         AND step.step_ordinal = NEW.step_ordinal
                        INNER JOIN chapter
                          ON chapter.book_id = NEW.book_id
                         AND chapter.chapter_id = NEW.chapter_id
                        INNER JOIN chapter_version
                          ON chapter_version.chapter_version_id = NEW.source_chapter_version_id
                        INNER JOIN chapter_tracking_projection AS baseline
                          ON baseline.projection_id = NEW.baseline_tracking_projection_id
                        INNER JOIN generation_job AS replacement_job
                          ON replacement_job.job_id = NEW.replacement_job_id
                        INNER JOIN generation_stage AS replacement_stage
                          ON replacement_stage.stage_id = NEW.replacement_stage_id
                        WHERE execution.execution_id = NEW.execution_id
                          AND execution.book_id = NEW.book_id
                          AND execution.status = 'PREPARED'
                          AND execution.future_chapter_policy = 'KEEP_EXISTING'
                          AND NEW.chapter_index > execution.first_affected_chapter_index
                          AND NEW.chapter_index <= execution.last_affected_chapter_index
                          AND step.book_id = NEW.book_id
                          AND step.chapter_id = NEW.chapter_id
                          AND step.chapter_index = NEW.chapter_index
                          AND step.source_chapter_version_id = NEW.source_chapter_version_id
                          AND step.step_type = 'TRACKING'
                          AND step.needs_provider = 1
                          AND step.prepared_state = 'PENDING'
                          AND step.baseline_tracking_projection_id = NEW.baseline_tracking_projection_id
                          AND step.baseline_tracking_fingerprint = NEW.baseline_tracking_fingerprint
                          AND chapter.chapter_index = NEW.chapter_index
                          AND chapter.current_version_id = NEW.source_chapter_version_id
                          AND chapter_version.chapter_id = NEW.chapter_id
                          AND baseline.book_id = NEW.book_id
                          AND baseline.chapter_version_id = NEW.source_chapter_version_id
                          AND baseline.chapter_index = NEW.chapter_index
                          AND baseline.status = 'STALE'
                          AND baseline.updated_at = NEW.retired_at
                          AND baseline.timeline_event_count = NEW.baseline_timeline_event_count
                          AND replacement_job.book_id = NEW.book_id
                          AND replacement_job.current_stage_id = NEW.replacement_stage_id
                          AND replacement_job.status = 'CREATED'
                          AND replacement_job.created_at = NEW.retired_at
                          AND replacement_stage.job_id = NEW.replacement_job_id
                          AND replacement_stage.target_type = 'CHAPTER'
                          AND replacement_stage.target_id = NEW.chapter_id
                          AND replacement_stage.status = 'PENDING'
                          AND replacement_stage.created_at = NEW.retired_at
                    )
                      OR NEW.step_ordinal <= 0
                      OR NEW.chapter_index <= 0
                      OR length(NEW.baseline_tracking_fingerprint) != 64 OR NEW.baseline_tracking_fingerprint GLOB '*[^0-9a-f]*'
                      OR length(NEW.retired_tracking_fingerprint) != 64 OR NEW.retired_tracking_fingerprint GLOB '*[^0-9a-f]*'
                      OR NEW.baseline_timeline_event_count NOT BETWEEN 0 AND 64
                      OR length(NEW.baseline_timeline_event_ids_json) NOT BETWEEN 2 AND 16384
                      OR length(NEW.baseline_timeline_fingerprint) != 64 OR NEW.baseline_timeline_fingerprint GLOB '*[^0-9a-f]*'
                      OR NEW.policy_version != 'zhijuan.chapter-edit-rebuild-tracking-retirement.v1'
                      OR NEW.retired_at < 0
                    BEGIN
                        SELECT RAISE(ABORT, 'invalid chapter edit rebuild tracking retirement provenance');
                    END
                    """.trimIndent(),
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
            installDerivedHistorySlotGuards(db)
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

    private fun installDerivedHistorySlotGuards(db: SupportSQLiteDatabase) {
        installSingleValidHistorySlotGuard(
            db = db,
            table = "chapter_summary",
            primaryKey = "chapter_summary_id",
            sameSlot = "existing.chapter_version_id = NEW.chapter_version_id",
            immutableChange = """
                NEW.chapter_summary_id IS NOT OLD.chapter_summary_id
                  OR NEW.book_id IS NOT OLD.book_id
                  OR NEW.chapter_version_id IS NOT OLD.chapter_version_id
                  OR NEW.chapter_index IS NOT OLD.chapter_index
                  OR NEW.schema_version IS NOT OLD.schema_version
                  OR NEW.summary_json IS NOT OLD.summary_json
                  OR NEW.importance IS NOT OLD.importance
                  OR NEW.model_snapshot_json IS NOT OLD.model_snapshot_json
                  OR NEW.created_at IS NOT OLD.created_at
                OR NEW.updated_at < OLD.updated_at
            """.trimIndent(),
        )
        installAppendOnlyDerivedHistoryGuard(
            db = db,
            table = "entity_event",
            immutableChange = """
                NEW.entity_event_id IS NOT OLD.entity_event_id
                  OR NEW.book_id IS NOT OLD.book_id
                  OR NEW.entity_id IS NOT OLD.entity_id
                  OR NEW.source_chapter_version_id IS NOT OLD.source_chapter_version_id
                  OR NEW.story_order IS NOT OLD.story_order
                  OR NEW.attribute_key IS NOT OLD.attribute_key
                  OR NEW.old_value_json IS NOT OLD.old_value_json
                  OR NEW.new_value_json IS NOT OLD.new_value_json
                  OR NEW.story_time_expression IS NOT OLD.story_time_expression
                  OR NEW.confidence_micros IS NOT OLD.confidence_micros
                  OR NEW.canon_level IS NOT OLD.canon_level
                  OR NEW.evidence_json IS NOT OLD.evidence_json
                  OR NEW.created_at IS NOT OLD.created_at
            """.trimIndent(),
        )
        installAppendOnlyDerivedHistoryGuard(
            db = db,
            table = "canon_fact",
            immutableChange = """
                NEW.canon_fact_id IS NOT OLD.canon_fact_id
                  OR NEW.book_id IS NOT OLD.book_id
                  OR NEW.entity_id IS NOT OLD.entity_id
                  OR NEW.fact_text IS NOT OLD.fact_text
                  OR NEW.fact_payload_json IS NOT OLD.fact_payload_json
                  OR NEW.canon_level IS NOT OLD.canon_level
                  OR NEW.scope_json IS NOT OLD.scope_json
                  OR NEW.source_chapter_version_id IS NOT OLD.source_chapter_version_id
                  OR NEW.source_bible_revision_id IS NOT OLD.source_bible_revision_id
                  OR NEW.valid_from_story_order IS NOT OLD.valid_from_story_order
                  OR NEW.valid_to_story_order IS NOT OLD.valid_to_story_order
                  OR NEW.conflict_group_id IS NOT OLD.conflict_group_id
                  OR NEW.created_at IS NOT OLD.created_at
            """.trimIndent(),
        )
        installAppendOnlyDerivedHistoryGuard(
            db = db,
            table = "timeline_event",
            immutableChange = """
                NEW.timeline_event_id IS NOT OLD.timeline_event_id
                  OR NEW.book_id IS NOT OLD.book_id
                  OR NEW.name IS NOT OLD.name
                  OR NEW.participants_json IS NOT OLD.participants_json
                  OR NEW.location_entity_id IS NOT OLD.location_entity_id
                  OR NEW.story_time_expression IS NOT OLD.story_time_expression
                  OR NEW.story_order IS NOT OLD.story_order
                  OR NEW.constraints_json IS NOT OLD.constraints_json
                  OR NEW.source_chapter_version_id IS NOT OLD.source_chapter_version_id
                  OR NEW.created_at IS NOT OLD.created_at
            """.trimIndent(),
        )
        if (tableExists(db, "chapter_tracking_projection")) {
            installSingleValidHistorySlotGuard(
                db = db,
                table = "chapter_tracking_projection",
                primaryKey = "projection_id",
                sameSlot = "existing.chapter_version_id = NEW.chapter_version_id",
                immutableChange = """
                    NEW.projection_id IS NOT OLD.projection_id
                      OR NEW.book_id IS NOT OLD.book_id
                      OR NEW.chapter_version_id IS NOT OLD.chapter_version_id
                      OR NEW.chapter_index IS NOT OLD.chapter_index
                      OR NEW.generation_stage_id IS NOT OLD.generation_stage_id
                      OR NEW.source_chapter_content_hash IS NOT OLD.source_chapter_content_hash
                      OR NEW.source_memory_snapshot_hash IS NOT OLD.source_memory_snapshot_hash
                      OR NEW.prior_foreshadow_snapshot_hash IS NOT OLD.prior_foreshadow_snapshot_hash
                      OR NEW.output_content_hash IS NOT OLD.output_content_hash
                      OR NEW.payload_hash IS NOT OLD.payload_hash
                      OR NEW.model_snapshot_json IS NOT OLD.model_snapshot_json
                      OR NEW.timeline_event_count IS NOT OLD.timeline_event_count
                      OR NEW.foreshadow_transition_count IS NOT OLD.foreshadow_transition_count
                      OR NEW.created_at IS NOT OLD.created_at
                      OR NEW.updated_at < OLD.updated_at
                """.trimIndent(),
            )
        }
        if (tableExists(db, "aggregate_state_projection")) {
            installSingleValidHistorySlotGuard(
                db = db,
                table = "aggregate_state_projection",
                primaryKey = "aggregate_state_id",
                sameSlot = "existing.book_id = NEW.book_id AND existing.through_chapter_index = NEW.through_chapter_index",
                immutableChange = """
                    NEW.aggregate_state_id IS NOT OLD.aggregate_state_id
                      OR NEW.book_id IS NOT OLD.book_id
                      OR NEW.through_chapter_index IS NOT OLD.through_chapter_index
                      OR NEW.source_through_chapter_version_id IS NOT OLD.source_through_chapter_version_id
                      OR NEW.schema_version IS NOT OLD.schema_version
                      OR NEW.state_json IS NOT OLD.state_json
                      OR NEW.content_hash IS NOT OLD.content_hash
                      OR NEW.created_at IS NOT OLD.created_at
                      OR NEW.updated_at < OLD.updated_at
                """.trimIndent(),
            )
        }
        if (tableExists(db, "foreshadow_transition")) {
            installSingleValidHistorySlotGuard(
                db = db,
                table = "foreshadow_transition",
                primaryKey = "transition_id",
                sameSlot = "existing.foreshadow_item_id = NEW.foreshadow_item_id AND existing.source_chapter_version_id = NEW.source_chapter_version_id",
                immutableChange = """
                    NEW.transition_id IS NOT OLD.transition_id
                      OR NEW.foreshadow_item_id IS NOT OLD.foreshadow_item_id
                      OR NEW.book_id IS NOT OLD.book_id
                      OR NEW.source_chapter_version_id IS NOT OLD.source_chapter_version_id
                      OR NEW.generation_stage_id IS NOT OLD.generation_stage_id
                      OR NEW.story_order IS NOT OLD.story_order
                      OR NEW.operation IS NOT OLD.operation
                      OR NEW.from_status IS NOT OLD.from_status
                      OR NEW.to_status IS NOT OLD.to_status
                      OR NEW.evidence_json IS NOT OLD.evidence_json
                      OR NEW.created_at IS NOT OLD.created_at
                """.trimIndent(),
            )
        }
        if (tableExists(db, "foreshadow_projection_revision")) {
            installAppendOnlyDerivedHistoryGuard(
                db = db,
                table = "foreshadow_projection_revision",
                immutableChange = """
                    NEW.revision_id IS NOT OLD.revision_id
                      OR NEW.book_id IS NOT OLD.book_id
                      OR NEW.foreshadow_item_id IS NOT OLD.foreshadow_item_id
                      OR NEW.source_chapter_version_id IS NOT OLD.source_chapter_version_id
                      OR NEW.generation_stage_id IS NOT OLD.generation_stage_id
                      OR NEW.transition_id IS NOT OLD.transition_id
                      OR NEW.chapter_index IS NOT OLD.chapter_index
                      OR NEW.story_order IS NOT OLD.story_order
                      OR NEW.snapshot_schema_version IS NOT OLD.snapshot_schema_version
                      OR NEW.snapshot_json IS NOT OLD.snapshot_json
                      OR NEW.snapshot_hash IS NOT OLD.snapshot_hash
                      OR NEW.created_at IS NOT OLD.created_at
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS require_revision_stale_before_transition
                BEFORE UPDATE OF status ON foreshadow_transition
                WHEN OLD.status = 'VALID'
                  AND NEW.status = 'STALE'
                  AND EXISTS (
                      SELECT 1 FROM foreshadow_projection_revision
                      WHERE transition_id = OLD.transition_id AND status = 'VALID'
                  )
                BEGIN
                    SELECT RAISE(ABORT, 'foreshadow revisions must be stale before their transition');
                END
                """.trimIndent(),
            )
        }
    }

    private fun installSingleValidHistorySlotGuard(
        db: SupportSQLiteDatabase,
        table: String,
        primaryKey: String,
        sameSlot: String,
        immutableChange: String,
    ) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS enforce_${table}_single_valid_insert
            BEFORE INSERT ON $table
            WHEN NEW.status = 'VALID'
              AND EXISTS (
                  SELECT 1 FROM $table AS existing
                  WHERE $sameSlot AND existing.status = 'VALID'
              )
            BEGIN
                SELECT RAISE(ABORT, '$table already has a valid history head');
            END
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS protect_${table}_history_update
            BEFORE UPDATE ON $table
            WHEN $immutableChange
              OR NOT (
                  NEW.status IS OLD.status
                  OR (OLD.status = 'VALID' AND NEW.status = 'STALE')
              )
              OR (
                  NEW.status = 'VALID'
                  AND EXISTS (
                      SELECT 1 FROM $table AS existing
                      WHERE $sameSlot
                        AND existing.status = 'VALID'
                        AND existing.$primaryKey != OLD.$primaryKey
                  )
              )
            BEGIN
                SELECT RAISE(ABORT, 'invalid $table history update');
            END
            """.trimIndent(),
        )
        installDerivedHistoryDeleteGuard(db, table)
    }

    private fun installAppendOnlyDerivedHistoryGuard(
        db: SupportSQLiteDatabase,
        table: String,
        immutableChange: String,
    ) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS protect_${table}_history_update
            BEFORE UPDATE ON $table
            WHEN $immutableChange
              OR NOT (
                  NEW.status IS OLD.status
                  OR (OLD.status = 'VALID' AND NEW.status = 'STALE')
              )
            BEGIN
                SELECT RAISE(ABORT, 'invalid $table history update');
            END
            """.trimIndent(),
        )
        installDerivedHistoryDeleteGuard(db, table)
    }

    private fun installDerivedHistoryDeleteGuard(
        db: SupportSQLiteDatabase,
        table: String,
    ) {
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS protect_${table}_history_delete
            BEFORE DELETE ON $table
            BEGIN
                SELECT RAISE(ABORT, '$table history cannot be deleted');
            END
            """.trimIndent(),
        )
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
