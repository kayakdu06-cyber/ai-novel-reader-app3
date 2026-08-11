package app.zhijuan.core.database

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.zhijuan.core.security.AndroidKeystoreAesGcm
import app.zhijuan.core.security.DatabasePassphraseStore
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class ZhijuanMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databasesToDelete = mutableSetOf<String>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ZhijuanDatabase::class.java,
    )

    @After
    fun cleanUp() {
        databasesToDelete.forEach(context::deleteDatabase)
        databasesToDelete.clear()
        deletePassphraseEnvelope()
    }

    @Test
    fun migrationRegistryHasAContiguousPathFromEverySupportedVersion() {
        for (startVersion in 1 until ZHIJUAN_DATABASE_SCHEMA_VERSION) {
            var expectedStart = startVersion
            ZhijuanMigrations.pathFrom(startVersion).forEach { migration ->
                assertEquals(expectedStart, migration.startVersion)
                assertTrue(migration.endVersion > migration.startVersion)
                expectedStart = migration.endVersion
            }
            assertEquals(ZHIJUAN_DATABASE_SCHEMA_VERSION, expectedStart)
        }
        assertTrue(ZhijuanMigrations.pathFrom(ZHIJUAN_DATABASE_SCHEMA_VERSION).isEmpty())
    }

    @Test
    fun everySupportedPlaintextSchemaMigratesToLatestWithoutLosingOwnedData() {
        for (startVersion in 1 until ZHIJUAN_DATABASE_SCHEMA_VERSION) {
            val databaseName = uniqueDatabaseName("plaintext-v$startVersion")
            helper.createDatabase(databaseName, startVersion).use { database ->
                seedCoreNovel(database)
                if (startVersion >= 7) {
                    database.execSQL(
                        "UPDATE book SET minimum_chapters = 200, length_policy_schema_version = 0 WHERE book_id = '$BOOK_ID'",
                    )
                }
                if (startVersion >= 2) seedGenerationAudit(database)
                if (startVersion >= 3) seedNarrativeMemory(database)
            }

            helper.runMigrationsAndValidate(
                databaseName,
                ZHIJUAN_DATABASE_SCHEMA_VERSION,
                true,
                *ZhijuanMigrations.pathFrom(startVersion),
            ).use { database ->
                assertCoreNovelPreserved(database)
                if (startVersion >= 2) assertGenerationAuditPreserved(database)
                if (startVersion >= 3) assertNarrativeMemoryPreserved(database)
                assertLatestSchemaAvailable(database)
                assertDatabaseIntegrity(database)
            }
        }
    }

    @Test
    fun migrationEightToNineSynchronizesProductionMemorySearchFts() {
        val databaseName = uniqueDatabaseName("memory-search-v8")
        helper.createDatabase(databaseName, 8).use { database ->
            seedCoreNovel(database)
            database.execSQL(
                "UPDATE book SET minimum_chapters = 200, length_policy_schema_version = 0 WHERE book_id = '$BOOK_ID'",
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            ZhijuanMigrations.MIGRATION_8_9,
        ).use { database ->
            assertProductionMemorySearchSchemaAvailable(database)
            database.execSQL(
                """
                INSERT INTO memory_search_document (
                    document_id, book_id, source_type, source_id, chapter_index,
                    story_order, importance, source_content_hash, search_terms, updated_at
                ) VALUES (
                    'summary:1', '$BOOK_ID', 'CHAPTER_SUMMARY', 'summary-1', 1,
                    10, 60, 'summary-hash', 'whero wold', 10
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO memory_search_document (
                    document_id, book_id, source_type, source_id, chapter_index,
                    story_order, importance, source_content_hash, search_terms, updated_at
                ) VALUES (
                    'fact:global', '$BOOK_ID', 'CANON_FACT', 'fact-global', NULL,
                    NULL, 100, 'fact-hash', 'whero wglobal', 11
                )
                """.trimIndent(),
            )

            assertEquals(2, rowCount(database, "memory_search_document_fts"))
            assertEquals(2, productionSearchCount(database, BOOK_ID, "whero", targetChapterIndex = 2))
            assertEquals(1, productionSearchCount(database, BOOK_ID, "whero", targetChapterIndex = 1))
            assertEquals(0, productionSearchCount(database, "another-book", "whero", targetChapterIndex = 2))
            assertEquals(1, productionSearchCount(database, BOOK_ID, "wold", targetChapterIndex = 2))

            database.execSQL(
                "UPDATE memory_search_document SET search_terms = 'whero wnew' WHERE document_id = 'summary:1'",
            )
            assertEquals(0, productionSearchCount(database, BOOK_ID, "wold", targetChapterIndex = 2))
            assertEquals(1, productionSearchCount(database, BOOK_ID, "wnew", targetChapterIndex = 2))

            database.execSQL("DELETE FROM memory_search_document WHERE document_id = 'summary:1'")
            assertEquals(0, productionSearchCount(database, BOOK_ID, "wnew", targetChapterIndex = 2))
            assertEquals(1, rowCount(database, "memory_search_document_fts"))
            assertDatabaseIntegrity(database)
        }
    }

    @Test
    fun migrationNineToTenAddsEmptyBackfillMarkerWithoutChangingSearchDocuments() {
        val databaseName = uniqueDatabaseName("memory-search-backfill-v9")
        helper.createDatabase(databaseName, 9).use { database ->
            seedCoreNovel(database)
            database.execSQL(
                "UPDATE book SET minimum_chapters = 200, length_policy_schema_version = 0 WHERE book_id = '$BOOK_ID'",
            )
            database.execSQL(
                """
                INSERT INTO memory_search_document (
                    document_id, book_id, source_type, source_id, chapter_index,
                    story_order, importance, source_content_hash, search_terms, updated_at
                ) VALUES (
                    'pre-backfill', '$BOOK_ID', 'CANON_FACT', 'fact-before-v10', NULL,
                    NULL, 100, 'pre-backfill-hash', 'wbefore', 10
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            10,
            true,
            ZhijuanMigrations.MIGRATION_9_10,
        ).use { database ->
            assertLatestSchemaAvailable(database)
            assertEquals(1, rowCount(database, "memory_search_document"))
            assertEquals(1, rowCount(database, "memory_search_document_fts"))
            assertEquals(0, rowCount(database, "memory_search_backfill_state"))
            assertEquals(1, productionSearchCount(database, BOOK_ID, "wbefore", targetChapterIndex = 1))
            assertDatabaseIntegrity(database)
        }
    }

    @Test
    fun migrationTenToElevenKeepsDerivedRowsAndInstallsSingleValidHistoryGuards() {
        val databaseName = uniqueDatabaseName("derived-history-v10")
        helper.createDatabase(databaseName, 10).use { database ->
            seedCoreNovel(database)
            database.execSQL(
                "UPDATE book SET minimum_chapters = 200, length_policy_schema_version = 0 WHERE book_id = '$BOOK_ID'",
            )
            seedGenerationAudit(database)
            database.execSQL(
                """
                INSERT INTO chapter_summary (
                    chapter_summary_id, book_id, chapter_version_id, chapter_index,
                    schema_version, summary_json, importance, status,
                    model_snapshot_json, created_at, updated_at
                ) VALUES (
                    'summary-v10', '$BOOK_ID', '$CHAPTER_VERSION_ID', 1,
                    1, '{"generation":1}', 80, 'VALID', NULL, 10, 10
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO chapter_tracking_projection (
                    projection_id, book_id, chapter_version_id, chapter_index,
                    generation_stage_id, source_chapter_content_hash,
                    source_memory_snapshot_hash, prior_foreshadow_snapshot_hash,
                    output_content_hash, payload_hash, status, model_snapshot_json,
                    timeline_event_count, foreshadow_transition_count, created_at, updated_at
                ) VALUES (
                    'projection-v10', '$BOOK_ID', '$CHAPTER_VERSION_ID', 1,
                    '$STAGE_ID', 'chapter-hash', 'memory-hash', 'foreshadow-hash',
                    'output-hash', 'payload-hash', 'VALID', '{}', 0, 1, 10, 10
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO aggregate_state_projection (
                    aggregate_state_id, book_id, through_chapter_index,
                    source_through_chapter_version_id, schema_version, state_json,
                    content_hash, status, created_at, updated_at
                ) VALUES (
                    'aggregate-v10', '$BOOK_ID', 1, '$CHAPTER_VERSION_ID',
                    1, '{"generation":1}', 'aggregate-hash', 'VALID', 10, 10
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO foreshadow_item (
                    foreshadow_item_id, book_id, description, foreshadow_status,
                    memory_status, target_start_chapter_index, target_end_chapter_index,
                    source_chapter_version_id, planted_chapter_version_id,
                    resolved_chapter_version_id, visible_entity_ids_json, importance,
                    source, created_at, updated_at
                ) VALUES (
                    'foreshadow-v10', '$BOOK_ID', 'migration clue', 'PLANTED',
                    'VALID', 2, 3, '$CHAPTER_VERSION_ID', '$CHAPTER_VERSION_ID',
                    NULL, '[]', 80, 'CHAPTER_EXTRACTION', 10, 10
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO foreshadow_transition (
                    transition_id, foreshadow_item_id, book_id,
                    source_chapter_version_id, generation_stage_id, story_order,
                    operation, from_status, to_status, evidence_json, status, created_at
                ) VALUES (
                    'transition-v10', 'foreshadow-v10', '$BOOK_ID',
                    '$CHAPTER_VERSION_ID', '$STAGE_ID', 1,
                    'PLANT', NULL, 'PLANTED', '{}', 'VALID', 10
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            11,
            true,
            ZhijuanMigrations.MIGRATION_10_11,
        ).use { database ->
            listOf(
                "chapter_summary",
                "chapter_tracking_projection",
                "aggregate_state_projection",
                "foreshadow_transition",
            ).forEach { table ->
                assertEquals("Migration lost $table history.", 1, rowCount(database, table))
                assertSqliteObjectExists(database, "trigger", "enforce_${table}_single_valid_insert")
                assertSqliteObjectExists(database, "trigger", "protect_${table}_history_update")
                assertSqliteObjectExists(database, "trigger", "protect_${table}_history_delete")
            }
            listOf("entity_event", "canon_fact", "timeline_event").forEach { table ->
                assertSqliteObjectExists(database, "trigger", "protect_${table}_history_update")
                assertSqliteObjectExists(database, "trigger", "protect_${table}_history_delete")
            }
            mapOf(
                "chapter_summary" to "index_chapter_summary_chapter_version_id",
                "chapter_tracking_projection" to "index_chapter_tracking_projection_chapter_version_id",
                "aggregate_state_projection" to "index_aggregate_state_projection_book_id_through_chapter_index",
                "foreshadow_transition" to
                    "index_foreshadow_transition_foreshadow_item_id_source_chapter_version_id",
            ).forEach { (table, index) ->
                assertFalse("$index must allow stale history.", indexIsUnique(database, table, index))
            }

            database.execSQL("UPDATE chapter_summary SET status = 'STALE', updated_at = 20 WHERE chapter_summary_id = 'summary-v10'")
            database.execSQL("UPDATE chapter_tracking_projection SET status = 'STALE', updated_at = 20 WHERE projection_id = 'projection-v10'")
            database.execSQL("UPDATE aggregate_state_projection SET status = 'STALE', updated_at = 20 WHERE aggregate_state_id = 'aggregate-v10'")
            database.execSQL("UPDATE foreshadow_transition SET status = 'STALE' WHERE transition_id = 'transition-v10'")
            database.execSQL(
                """
                INSERT INTO generation_job (
                    job_id, book_id, job_type, status, user_intent_json,
                    budget_snapshot_json, prompt_bundle_version, current_stage_id,
                    pause_or_stop_reason, lease_owner_id, lease_acquired_at,
                    lease_heartbeat_at, started_at, finished_at, created_at, updated_at
                ) VALUES (
                    'history-job-v11', '$BOOK_ID', 'REBUILD_MEMORY', 'CREATED', '{}',
                    '{}', 'prompt-1', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 30, 30
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO generation_stage (
                    stage_id, job_id, phase, target_type, target_id, status,
                    input_version_hash, idempotency_key, attempt_count, max_attempts,
                    input_sources_json, output_reference_json, standard_error_code,
                    next_retry_at, lease_owner_id, lease_acquired_at,
                    lease_heartbeat_at, created_at, updated_at
                ) VALUES (
                    'history-stage-v11', 'history-job-v11', 'EXTRACT_MEMORY', 'CHAPTER',
                    '$CHAPTER_ID', 'PENDING', 'history-input', 'history-idempotency',
                    0, 2, '[]', NULL, NULL, NULL, NULL, NULL, NULL, 30, 30
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO chapter_summary (
                    chapter_summary_id, book_id, chapter_version_id, chapter_index,
                    schema_version, summary_json, importance, status,
                    model_snapshot_json, created_at, updated_at
                ) VALUES (
                    'summary-v11', '$BOOK_ID', '$CHAPTER_VERSION_ID', 1,
                    1, '{"generation":2}', 80, 'VALID', NULL, 30, 30
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO chapter_tracking_projection (
                    projection_id, book_id, chapter_version_id, chapter_index,
                    generation_stage_id, source_chapter_content_hash,
                    source_memory_snapshot_hash, prior_foreshadow_snapshot_hash,
                    output_content_hash, payload_hash, status, model_snapshot_json,
                    timeline_event_count, foreshadow_transition_count, created_at, updated_at
                ) VALUES (
                    'projection-v11', '$BOOK_ID', '$CHAPTER_VERSION_ID', 1,
                    'history-stage-v11', 'chapter-hash', 'memory-hash-2', 'foreshadow-hash-2',
                    'output-hash-2', 'payload-hash-2', 'VALID', '{}', 0, 1, 30, 30
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO aggregate_state_projection (
                    aggregate_state_id, book_id, through_chapter_index,
                    source_through_chapter_version_id, schema_version, state_json,
                    content_hash, status, created_at, updated_at
                ) VALUES (
                    'aggregate-v11', '$BOOK_ID', 1, '$CHAPTER_VERSION_ID',
                    1, '{"generation":2}', 'aggregate-hash-2', 'VALID', 30, 30
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO foreshadow_transition (
                    transition_id, foreshadow_item_id, book_id,
                    source_chapter_version_id, generation_stage_id, story_order,
                    operation, from_status, to_status, evidence_json, status, created_at
                ) VALUES (
                    'transition-v11', 'foreshadow-v10', '$BOOK_ID',
                    '$CHAPTER_VERSION_ID', 'history-stage-v11', 1,
                    'PLANT', NULL, 'PLANTED', '{"generation":2}', 'VALID', 30
                )
                """.trimIndent(),
            )
            listOf(
                "chapter_summary",
                "chapter_tracking_projection",
                "aggregate_state_projection",
                "foreshadow_transition",
            ).forEach { table ->
                assertEquals("Expected two retained generations in $table.", 2, rowCount(database, table))
                assertEquals(1, singleInt(database, "SELECT COUNT(*) FROM $table WHERE status = 'VALID'"))
                assertEquals(1, singleInt(database, "SELECT COUNT(*) FROM $table WHERE status = 'STALE'"))
            }
            assertNotNull(
                runCatching {
                    database.execSQL(
                        """
                        INSERT INTO chapter_summary (
                            chapter_summary_id, book_id, chapter_version_id, chapter_index,
                            schema_version, summary_json, importance, status,
                            model_snapshot_json, created_at, updated_at
                        ) VALUES (
                            'summary-v11-conflict', '$BOOK_ID', '$CHAPTER_VERSION_ID', 1,
                            1, '{}', 80, 'VALID', NULL, 40, 40
                        )
                        """.trimIndent(),
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        "UPDATE chapter_summary SET status = 'VALID', updated_at = 40 " +
                            "WHERE chapter_summary_id = 'summary-v10'",
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        "UPDATE chapter_summary SET summary_json = '{}' WHERE chapter_summary_id = 'summary-v10'",
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL("DELETE FROM chapter_summary WHERE chapter_summary_id = 'summary-v10'")
                }.exceptionOrNull(),
            )
            assertDatabaseIntegrity(database)
        }
    }

    @Test
    fun migrationElevenToTwelveAddsEmptyGuardedForeshadowRevisionLedger() {
        val databaseName = uniqueDatabaseName("foreshadow-revisions-v11")
        helper.createDatabase(databaseName, 11).use { database ->
            seedCoreNovel(database)
            seedGenerationAudit(database)
            database.execSQL(
                """
                INSERT INTO foreshadow_item (
                    foreshadow_item_id, book_id, description, foreshadow_status,
                    memory_status, target_start_chapter_index, target_end_chapter_index,
                    source_chapter_version_id, planted_chapter_version_id,
                    resolved_chapter_version_id, visible_entity_ids_json, importance,
                    source, created_at, updated_at
                ) VALUES (
                    'revision-foreshadow-v11', '$BOOK_ID', 'legacy clue', 'PLANTED',
                    'VALID', 2, 4, '$CHAPTER_VERSION_ID', '$CHAPTER_VERSION_ID',
                    NULL, '[]', 80, 'CHAPTER_EXTRACTION', 10, 10
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO foreshadow_transition (
                    transition_id, foreshadow_item_id, book_id,
                    source_chapter_version_id, generation_stage_id, story_order,
                    operation, from_status, to_status, evidence_json, status, created_at
                ) VALUES (
                    'revision-transition-v11', 'revision-foreshadow-v11', '$BOOK_ID',
                    '$CHAPTER_VERSION_ID', '$STAGE_ID', 1,
                    'PLANT', NULL, 'PLANTED', '{}', 'VALID', 10
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            12,
            true,
            ZhijuanMigrations.MIGRATION_11_12,
        ).use { database ->
            assertEquals(1, rowCount(database, "foreshadow_transition"))
            assertEquals(0, rowCount(database, "foreshadow_projection_revision"))
            assertSqliteObjectExists(database, "trigger", "validate_foreshadow_projection_revision_provenance_insert")
            assertSqliteObjectExists(database, "trigger", "validate_foreshadow_projection_revision_provenance_update")
            assertSqliteObjectExists(database, "trigger", "protect_foreshadow_projection_revision_history_update")
            assertSqliteObjectExists(database, "trigger", "protect_foreshadow_projection_revision_history_delete")
            assertSqliteObjectExists(database, "trigger", "require_revision_stale_before_transition")
            assertTrue(
                indexIsUnique(
                    database,
                    "foreshadow_projection_revision",
                    "index_foreshadow_projection_revision_transition_id",
                ),
            )
            database.execSQL(
                """
                INSERT INTO foreshadow_projection_revision (
                    revision_id, book_id, foreshadow_item_id,
                    source_chapter_version_id, generation_stage_id, transition_id,
                    chapter_index, story_order, snapshot_schema_version,
                    snapshot_json, snapshot_hash, status, created_at
                ) VALUES (
                    'revision-row-v12', '$BOOK_ID', 'revision-foreshadow-v11',
                    '$CHAPTER_VERSION_ID', '$STAGE_ID', 'revision-transition-v11',
                    1, 1, 1, '{}', '${"a".repeat(64)}', 'VALID', 10
                )
                """.trimIndent(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        "UPDATE foreshadow_transition SET status = 'STALE' " +
                            "WHERE transition_id = 'revision-transition-v11'",
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        "UPDATE foreshadow_projection_revision SET snapshot_json = '{\"tampered\":true}' " +
                            "WHERE revision_id = 'revision-row-v12'",
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        "DELETE FROM foreshadow_projection_revision WHERE revision_id = 'revision-row-v12'",
                    )
                }.exceptionOrNull(),
            )
            database.execSQL(
                "UPDATE foreshadow_projection_revision SET status = 'STALE' WHERE revision_id = 'revision-row-v12'",
            )
            database.execSQL(
                "UPDATE foreshadow_transition SET status = 'STALE' WHERE transition_id = 'revision-transition-v11'",
            )
            assertEquals(
                "STALE",
                singleText(
                    database,
                    "SELECT status FROM foreshadow_projection_revision WHERE revision_id = 'revision-row-v12'",
                ),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        "UPDATE foreshadow_projection_revision SET status = 'VALID' " +
                            "WHERE revision_id = 'revision-row-v12'",
                    )
                }.exceptionOrNull(),
            )
            assertDatabaseIntegrity(database)
        }
    }

    @Test
    fun migrationTwelveToThirteenAddsEmptyImmutableForeshadowRewindAudit() {
        val databaseName = uniqueDatabaseName("foreshadow-rewind-v12")
        helper.createDatabase(databaseName, 12).use { database ->
            seedCoreNovel(database)
            database.execSQL(
                """
                INSERT INTO chapter_version (
                    chapter_version_id, chapter_id, version_no, content, character_count,
                    content_hash, source, parent_version_id, generation_stage_id,
                    model_snapshot_json, created_at
                ) VALUES (
                    'chapter-version-edited-v13', '$CHAPTER_ID', 2, 'edited', 6,
                    '${"e".repeat(64)}', 'USER_EDIT', '$CHAPTER_VERSION_ID', NULL, NULL, 4
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                UPDATE chapter
                SET current_version_id = 'chapter-version-edited-v13',
                    status = 'EDITED', consistency_status = 'UNKNOWN', updated_at = 4
                WHERE chapter_id = '$CHAPTER_ID'
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            13,
            true,
            ZhijuanMigrations.MIGRATION_12_13,
        ).use { database ->
            assertEquals(0, rowCount(database, "foreshadow_projection_rewind"))
            assertSqliteObjectExists(database, "trigger", "validate_foreshadow_projection_rewind_insert")
            assertSqliteObjectExists(database, "trigger", "prevent_foreshadow_projection_rewind_update")
            assertSqliteObjectExists(database, "trigger", "protect_foreshadow_projection_rewind_history_delete")
            assertTrue(
                indexIsUnique(
                    database,
                    "foreshadow_projection_rewind",
                    "index_foreshadow_projection_rewind_plan_hash",
                ),
            )
            assertSqliteObjectExists(
                database,
                "index",
                "index_foreshadow_projection_revision_book_id_foreshadow_item_id_chapter_index_story_order_status",
            )
            val insert =
                """
                INSERT INTO foreshadow_projection_rewind (
                    rewind_id, book_id, edited_chapter_id, edited_chapter_version_id,
                    replaced_chapter_version_id, first_affected_chapter_index,
                    last_affected_chapter_index, plan_hash, before_projection_set_hash,
                    trusted_baseline_set_hash, after_projection_set_hash,
                    affected_item_count, baseline_item_count, absent_item_count,
                    stale_revision_count, stale_transition_count, policy_version, created_at
                ) VALUES (
                    'rewind-v13', '$BOOK_ID', '$CHAPTER_ID', 'chapter-version-edited-v13',
                    '$CHAPTER_VERSION_ID', 1, 1, '${"a".repeat(64)}', '${"b".repeat(64)}',
                    '${"c".repeat(64)}', '${"d".repeat(64)}',
                    0, 0, 0, 0, 0, 'zhijuan.foreshadow-projection-rewind.v1', 5
                )
                """.trimIndent()
            database.execSQL(insert)
            assertEquals(1, rowCount(database, "foreshadow_projection_rewind"))
            assertNotNull(
                runCatching {
                    database.execSQL(
                        insert
                            .replace("'rewind-v13'", "'rewind-v13-old-time'")
                            .replace("'${"a".repeat(64)}'", "'${"f".repeat(64)}'")
                            .replace(
                                "'zhijuan.foreshadow-projection-rewind.v1', 5",
                                "'zhijuan.foreshadow-projection-rewind.v1', 3",
                            ),
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        insert.replace("'rewind-v13'", "'rewind-v13-conflict'"),
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        "UPDATE foreshadow_projection_rewind SET created_at = 6 WHERE rewind_id = 'rewind-v13'",
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL("DELETE FROM foreshadow_projection_rewind WHERE rewind_id = 'rewind-v13'")
                }.exceptionOrNull(),
            )
            assertDatabaseIntegrity(database)
        }
    }

    @Test
    fun migrationThirteenToFourteenAddsImmutablePreparedRebuildLedger() {
        val databaseName = uniqueDatabaseName("rebuild-ledger-v13")
        helper.createDatabase(databaseName, 13).use { database ->
            seedCoreNovel(database)
            database.execSQL(
                """
                INSERT INTO chapter_version (
                    chapter_version_id, chapter_id, version_no, content, character_count,
                    content_hash, source, parent_version_id, generation_stage_id,
                    model_snapshot_json, created_at
                ) VALUES (
                    'chapter-version-edited-v14', '$CHAPTER_ID', 2, 'edited', 6,
                    '${"e".repeat(64)}', 'USER_EDIT', '$CHAPTER_VERSION_ID', NULL, NULL, 4
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                UPDATE chapter
                SET current_version_id = 'chapter-version-edited-v14',
                    status = 'EDITED', consistency_status = 'UNKNOWN', updated_at = 4
                WHERE chapter_id = '$CHAPTER_ID'
                """.trimIndent(),
            )
            database.execSQL(
                """
                INSERT INTO foreshadow_projection_rewind (
                    rewind_id, book_id, edited_chapter_id, edited_chapter_version_id,
                    replaced_chapter_version_id, first_affected_chapter_index,
                    last_affected_chapter_index, plan_hash, before_projection_set_hash,
                    trusted_baseline_set_hash, after_projection_set_hash,
                    affected_item_count, baseline_item_count, absent_item_count,
                    stale_revision_count, stale_transition_count, policy_version, created_at
                ) VALUES (
                    'rewind-v14', '$BOOK_ID', '$CHAPTER_ID', 'chapter-version-edited-v14',
                    '$CHAPTER_VERSION_ID', 1, 1, '${"a".repeat(64)}', '${"b".repeat(64)}',
                    '${"c".repeat(64)}', '${"d".repeat(64)}',
                    0, 0, 0, 0, 0, 'zhijuan.foreshadow-projection-rewind.v1', 5
                )
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(
            databaseName,
            14,
            true,
            ZhijuanMigrations.MIGRATION_13_14,
        ).use { database ->
            assertEquals(0, rowCount(database, "chapter_edit_rebuild_execution"))
            assertEquals(0, rowCount(database, "chapter_edit_rebuild_step"))
            listOf(
                "validate_chapter_edit_rebuild_execution_insert",
                "prevent_chapter_edit_rebuild_execution_update",
                "protect_chapter_edit_rebuild_execution_history_delete",
                "validate_chapter_edit_rebuild_step_insert",
                "prevent_chapter_edit_rebuild_step_update",
                "protect_chapter_edit_rebuild_step_history_delete",
            ).forEach { trigger -> assertSqliteObjectExists(database, "trigger", trigger) }
            assertTrue(
                indexIsUnique(
                    database,
                    "chapter_edit_rebuild_execution",
                    "index_chapter_edit_rebuild_execution_stable_fence_hash",
                ),
            )
            assertTrue(
                indexIsUnique(
                    database,
                    "chapter_edit_rebuild_step",
                    "index_chapter_edit_rebuild_step_execution_id_step_type_chapter_index",
                ),
            )
            val executionInsert =
                """
                INSERT INTO chapter_edit_rebuild_execution (
                    execution_id, book_id, edited_chapter_id, edited_chapter_version_id,
                    replaced_chapter_version_id, rewind_id, first_affected_chapter_index,
                    last_affected_chapter_index, future_chapter_policy, plan_schema_version,
                    initial_plan_hash, stable_fence_hash, policy_version, status, prepared_at
                ) VALUES (
                    'execution-v14', '$BOOK_ID', '$CHAPTER_ID', 'chapter-version-edited-v14',
                    '$CHAPTER_VERSION_ID', 'rewind-v14', 1, 1, 'KEEP_EXISTING', 2,
                    '${"a".repeat(64)}', '${"f".repeat(64)}',
                    'zhijuan.chapter-edit-rebuild-execution.v1', 'PREPARED', 5
                )
                """.trimIndent()
            database.execSQL(executionInsert)
            database.execSQL(
                """
                INSERT INTO chapter_edit_rebuild_step (
                    execution_id, step_ordinal, book_id, chapter_id, chapter_index,
                    source_chapter_version_id, source_content_hash, step_type,
                    needs_provider, prepared_state, baseline_summary_id,
                    baseline_summary_fingerprint, baseline_tracking_projection_id,
                    baseline_tracking_fingerprint, baseline_aggregate_state_id,
                    baseline_aggregate_fingerprint, created_at
                ) VALUES (
                    'execution-v14', 1, '$BOOK_ID', '$CHAPTER_ID', 1,
                    'chapter-version-edited-v14', '${"e".repeat(64)}', 'EDITED_MEMORY',
                    1, 'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, 5
                )
                """.trimIndent(),
            )
            assertEquals(1, rowCount(database, "chapter_edit_rebuild_execution"))
            assertEquals(1, rowCount(database, "chapter_edit_rebuild_step"))
            assertNotNull(
                runCatching {
                    database.execSQL(
                        executionInsert
                            .replace("'execution-v14'", "'execution-v14-conflict'")
                            .replace("'${"f".repeat(64)}'", "'${"1".repeat(64)}'"),
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        "UPDATE chapter_edit_rebuild_execution SET prepared_at = 6 " +
                            "WHERE execution_id = 'execution-v14'",
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        "UPDATE chapter_edit_rebuild_step SET prepared_state = 'SATISFIED' " +
                            "WHERE execution_id = 'execution-v14' AND step_ordinal = 1",
                    )
                }.exceptionOrNull(),
            )
            assertNotNull(
                runCatching {
                    database.execSQL(
                        "DELETE FROM chapter_edit_rebuild_step " +
                            "WHERE execution_id = 'execution-v14' AND step_ordinal = 1",
                    )
                }.exceptionOrNull(),
            )
            assertDatabaseIntegrity(database)
        }
    }

    @Test
    fun migrationFourteenToFifteenAddsImmutableRetainedTrackingRetirementEvidence() {
        val databaseName = uniqueDatabaseName("tracking-retirement-v14")
        helper.createDatabase(databaseName, 14).close()

        helper.runMigrationsAndValidate(
            databaseName,
            15,
            true,
            ZhijuanMigrations.MIGRATION_14_15,
        ).use { database ->
            assertEquals(0, rowCount(database, "chapter_edit_rebuild_tracking_retirement"))
            listOf(
                "validate_chapter_edit_rebuild_tracking_retirement_insert",
                "prevent_chapter_edit_rebuild_tracking_retirement_update",
                "protect_chapter_edit_rebuild_tracking_retirement_history_delete",
            ).forEach { trigger -> assertSqliteObjectExists(database, "trigger", trigger) }
            listOf(
                "index_chapter_edit_rebuild_tracking_retirement_baseline_tracking_projection_id",
                "index_chapter_edit_rebuild_tracking_retirement_replacement_job_id",
                "index_chapter_edit_rebuild_tracking_retirement_replacement_stage_id",
                "index_chapter_edit_rebuild_tracking_retirement_execution_id_chapter_index",
            ).forEach { index ->
                assertTrue(indexIsUnique(database, "chapter_edit_rebuild_tracking_retirement", index))
            }
            assertDatabaseIntegrity(database)
        }
    }

    @Test
    fun migrationFifteenToSixteenAddsImmutableGenerationTimingEvidence() {
        val databaseName = uniqueDatabaseName("generation-timing-v15")
        helper.createDatabase(databaseName, 15).close()

        helper.runMigrationsAndValidate(
            databaseName,
            16,
            true,
            ZhijuanMigrations.MIGRATION_15_16,
        ).use { database ->
            assertEquals(0, rowCount(database, "generation_timing_event"))
            listOf(
                "validate_generation_timing_event_insert",
                "validate_generation_timing_predecessor_insert",
                "prevent_generation_timing_event_update",
                "prevent_generation_timing_event_delete",
            ).forEach { trigger -> assertSqliteObjectExists(database, "trigger", trigger) }
            listOf(
                "index_generation_timing_event_run_fingerprint_occurred_elapsed_realtime_millis",
                "index_generation_timing_event_stage_fingerprint_phase_milestone",
                "index_generation_timing_event_attempt_fingerprint_phase_milestone",
                "index_generation_timing_event_boot_fingerprint_occurred_elapsed_realtime_millis",
            ).forEach { index -> assertSqliteObjectExists(database, "index", index) }
            assertDatabaseIntegrity(database)
        }
    }

    @Test
    fun migrationSixteenToSeventeenAddsBudgetTablesAndLegacyAttemptFields() {
        val databaseName = uniqueDatabaseName("budget-v16")
        helper.createDatabase(databaseName, 16).use { database ->
            seedCoreNovel(database)
            seedGenerationAudit(database)
        }

        helper.runMigrationsAndValidate(
            databaseName,
            17,
            true,
            ZhijuanMigrations.MIGRATION_16_17,
        ).use { database ->
            assertCoreNovelPreserved(database)
            assertGenerationAuditPreserved(database)
            assertEquals(
                0,
                singleInt(
                    database,
                    "SELECT budget_enforcement_version FROM request_attempt WHERE attempt_id = '$ATTEMPT_ID'",
                ),
            )
            database.query(
                "SELECT budget_reservation_id FROM request_attempt WHERE attempt_id = '$ATTEMPT_ID'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("Legacy attempt must not carry a reservation.", cursor.isNull(0))
            }
            listOf("budget_policy_revision", "budget_policy_head", "request_budget_reservation").forEach {
                assertEquals("Unexpected row count for $it", 0, rowCount(database, it))
            }
            listOf(
                "prevent_budget_policy_revision_update",
                "prevent_budget_policy_revision_delete",
                "validate_budget_policy_revision_insert",
                "prevent_budget_policy_head_delete",
                "validate_budget_policy_head_insert",
                "validate_budget_policy_head_update",
                "prevent_request_budget_reservation_delete",
                "validate_request_budget_reservation_insert",
                "validate_request_budget_reservation_update",
                "validate_request_attempt_budget_insert",
                "validate_request_attempt_budget_update",
            ).forEach { trigger ->
                assertSqliteObjectExists(database, "trigger", trigger)
            }
            listOf(
                "index_budget_policy_revision_scope_scope_key_revision_no",
                "index_budget_policy_revision_parent_budget_policy_id",
                "index_budget_policy_head_current_budget_policy_id",
                "index_request_budget_reservation_attempt_id",
                "index_request_attempt_budget_reservation_id",
            ).forEach { index ->
                assertSqliteObjectExists(database, "index", index)
                val table = when {
                    index.startsWith("index_budget_policy_revision_") -> "budget_policy_revision"
                    index.startsWith("index_budget_policy_head_") -> "budget_policy_head"
                    index.startsWith("index_request_budget_reservation_") -> "request_budget_reservation"
                    else -> "request_attempt"
                }
                assertTrue(indexIsUnique(database, table, index))
            }
            assertDatabaseIntegrity(database)
            assertEquals(17, database.version)
        }
    }

    @Test
    fun productionSqlCipherFactoryMigratesVersionOneToLatestAndKeepsPlaintextEncrypted() {
        val databaseName = uniqueDatabaseName("encrypted-v1")
        val keyAlias = "app.zhijuan.reader.test.migration." + System.nanoTime()
        val cipher = AndroidKeystoreAesGcm(keyAlias)
        val passphraseStore = DatabasePassphraseStore(context, cipher)
        deletePassphraseEnvelope()

        try {
            createEncryptedDatabase(
                databaseName = databaseName,
                callbackVersion = 1,
                schemaAssetVersion = 1,
                passphraseStore = passphraseStore,
            )

            EncryptedZhijuanDatabaseFactory(context, passphraseStore).open(databaseName).use { handle ->
                val database = handle.database.openHelper.writableDatabase
                assertCoreNovelPreserved(database)
                assertLatestSchemaAvailable(database)
                assertDatabaseIntegrity(database)
                assertEquals(ZHIJUAN_DATABASE_SCHEMA_VERSION, database.version)
            }

            assertEncryptedDatabaseDoesNotExposeCanary(databaseName)
        } finally {
            context.deleteDatabase(databaseName)
            cipher.deleteKey()
            deletePassphraseEnvelope()
        }
    }

    @Test
    fun missingDowngradeMigrationFailsClosedWithoutClearingTheExistingNovel() {
        val databaseName = uniqueDatabaseName("future-v5")
        val keyAlias = "app.zhijuan.reader.test.missing-migration." + System.nanoTime()
        val cipher = AndroidKeystoreAesGcm(keyAlias)
        val passphraseStore = DatabasePassphraseStore(context, cipher)
        deletePassphraseEnvelope()

        try {
            createEncryptedDatabase(
                databaseName = databaseName,
                callbackVersion = FUTURE_SCHEMA_VERSION,
                schemaAssetVersion = 1,
                passphraseStore = passphraseStore,
            )

            val failure = runCatching {
                EncryptedZhijuanDatabaseFactory(context, passphraseStore).open(databaseName).use { }
            }.exceptionOrNull()
            assertNotNull("Opening an unsupported future schema must fail.", failure)

            openExistingEncryptedDatabase(
                databaseName = databaseName,
                version = FUTURE_SCHEMA_VERSION,
                passphraseStore = passphraseStore,
            ).use { openHelper ->
                val database = openHelper.readableDatabase
                assertEquals(FUTURE_SCHEMA_VERSION, database.version)
                assertCoreNovelPreserved(database)
                assertDatabaseIntegrity(database)
            }
            assertEncryptedDatabaseDoesNotExposeCanary(databaseName)
        } finally {
            context.deleteDatabase(databaseName)
            cipher.deleteKey()
            deletePassphraseEnvelope()
        }
    }

    private fun seedCoreNovel(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO book_creation_snapshot (
                snapshot_id, raw_input_json, normalized_input_json,
                inference_provenance_json, genre_payload_json,
                presentation_profile_json, model_preference_json,
                schema_version, prompt_bundle_version,
                content_control_schema_version, content_hash, created_at
            ) VALUES (
                '$SNAPSHOT_ID', '{"idea":"migration"}', '{}', '{}', '{}', '{}', '{}',
                1, 'prompt-1', 1, '$SNAPSHOT_HASH', 1
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO book (
                book_id, creation_snapshot_id, title, title_source, status,
                length_mode, target_characters, target_chapters,
                branched_from_book_id, branched_from_chapter_version_id,
                completed_chapter_count, generation_status_summary,
                archived_at, deleted_at, created_at, updated_at
            ) VALUES (
                '$BOOK_ID', '$SNAPSHOT_ID', '$BOOK_TITLE', 'USER', 'DRAFT',
                'LONG', 500000, 200, NULL, NULL, 1, 'ready', NULL, NULL, 1, 2
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO chapter (
                chapter_id, book_id, chapter_index, planned_title, display_title,
                status, current_version_id, consistency_status, created_at, updated_at
            ) VALUES (
                '$CHAPTER_ID', '$BOOK_ID', 1, 'Chapter 1', 'Chapter 1',
                'READY', NULL, 'PASS', 1, 2
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO chapter_version (
                chapter_version_id, chapter_id, version_no, content, character_count,
                content_hash, source, parent_version_id, generation_stage_id,
                model_snapshot_json, created_at
            ) VALUES (
                '$CHAPTER_VERSION_ID', '$CHAPTER_ID', 1, '$NOVEL_CANARY', 34,
                '$CHAPTER_HASH', 'USER_EDIT', NULL, NULL, NULL, 2
            )
            """.trimIndent(),
        )
        database.execSQL(
            "UPDATE chapter SET current_version_id = '$CHAPTER_VERSION_ID' WHERE chapter_id = '$CHAPTER_ID'",
        )
    }

    private fun seedGenerationAudit(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO generation_job (
                job_id, book_id, job_type, status, user_intent_json,
                budget_snapshot_json, prompt_bundle_version, current_stage_id,
                pause_or_stop_reason, lease_owner_id, lease_acquired_at,
                lease_heartbeat_at, started_at, finished_at, created_at, updated_at
            ) VALUES (
                '$JOB_ID', '$BOOK_ID', 'NEW_BOOK', 'COMPLETED', '{}',
                '{}', 'prompt-1', NULL, NULL, NULL, NULL, NULL, 2, 3, 2, 3
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO generation_stage (
                stage_id, job_id, phase, target_type, target_id, status,
                input_version_hash, idempotency_key, attempt_count, max_attempts,
                input_sources_json, output_reference_json, standard_error_code,
                next_retry_at, lease_owner_id, lease_acquired_at,
                lease_heartbeat_at, created_at, updated_at
            ) VALUES (
                '$STAGE_ID', '$JOB_ID', 'CHAPTER', 'CHAPTER', '$CHAPTER_ID', 'COMPLETED',
                'input-hash', 'idempotency-key', 1, 3, '{}', '{}', NULL,
                NULL, NULL, NULL, NULL, 2, 3
            )
            """.trimIndent(),
        )
        database.execSQL(
            "UPDATE generation_job SET current_stage_id = '$STAGE_ID' WHERE job_id = '$JOB_ID'",
        )
        database.execSQL(
            """
            INSERT INTO request_attempt (
                attempt_id, job_id, stage_id, attempt_no, status, request_intent_at,
                sent_at, finished_at, provider_request_id, connection_snapshot_json,
                model_snapshot_json, protocol_snapshot_json, standard_error_code,
                http_status, input_hash, output_hash, stream_draft_ref,
                retry_parent_attempt_id, created_at, updated_at
            ) VALUES (
                '$ATTEMPT_ID', '$JOB_ID', '$STAGE_ID', 1, 'SUCCEEDED', 2,
                2, 3, 'provider-request', '{}', '{}', '{}', NULL,
                200, 'request-input-hash', '$OUTPUT_HASH', NULL, NULL, 2, 3
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO usage_ledger (
                usage_ledger_id, attempt_id, book_id, source, status,
                input_tokens, output_tokens, cached_tokens, reasoning_tokens,
                total_tokens, currency, estimated_cost_micros, price_catalog_version,
                daily_period_key, finalized_at, created_at, updated_at
            ) VALUES (
                '$USAGE_ID', '$ATTEMPT_ID', '$BOOK_ID', 'PROVIDER_REPORTED', 'FINAL',
                10, 20, 0, 0, 30, 'CNY', 1200, 'fixture',
                '2026-08-01', 3, 2, 3
            )
            """.trimIndent(),
        )
    }

    private fun seedNarrativeMemory(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            INSERT INTO story_bible_revision (
                bible_revision_id, book_id, revision_no, parent_revision_id,
                source, schema_version, content_control_schema_version,
                payload_json, content_hash, generation_stage_id, created_at
            ) VALUES (
                '$BIBLE_ID', '$BOOK_ID', 1, NULL, 'USER_EDIT', 1, 1,
                '{"canon":"kept"}', '$BIBLE_HASH', NULL, 4
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO outline_revision (
                outline_revision_id, book_id, revision_no, parent_revision_id,
                source, schema_version, summary_json, content_hash,
                generation_stage_id, created_at
            ) VALUES (
                '$OUTLINE_ID', '$BOOK_ID', 1, NULL, 'USER_EDIT', 1,
                '{"summary":"kept"}', '$OUTLINE_HASH', NULL, 4
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO outline_node (
                outline_node_id, outline_revision_id, parent_node_id, node_type,
                order_key, planned_chapter_index, title, plan_json, content_hash, created_at
            ) VALUES (
                '$OUTLINE_NODE_ID', '$OUTLINE_ID', NULL, 'CHAPTER',
                1, 1, 'Chapter 1', '{"plan":"kept"}', '$OUTLINE_NODE_HASH', 4
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO book_memory_head (
                book_id, current_bible_revision_id, current_outline_revision_id, updated_at
            ) VALUES ('$BOOK_ID', '$BIBLE_ID', '$OUTLINE_ID', 4)
            """.trimIndent(),
        )
        database.execSQL(
            """
            INSERT INTO chapter_summary (
                chapter_summary_id, book_id, chapter_version_id, chapter_index,
                schema_version, summary_json, importance, status,
                model_snapshot_json, created_at, updated_at
            ) VALUES (
                '$SUMMARY_ID', '$BOOK_ID', '$CHAPTER_VERSION_ID', 1,
                1, '{"summary":"kept"}', 80, 'READY', NULL, 4, 4
            )
            """.trimIndent(),
        )
    }

    private fun assertCoreNovelPreserved(database: SupportSQLiteDatabase) {
        assertEquals(1, rowCount(database, "book_creation_snapshot"))
        assertEquals(1, rowCount(database, "book"))
        assertEquals(1, rowCount(database, "chapter"))
        assertEquals(1, rowCount(database, "chapter_version"))
        assertEquals(BOOK_TITLE, singleText(database, "SELECT title FROM book WHERE book_id = '$BOOK_ID'"))
        assertEquals(
            SNAPSHOT_HASH,
            singleText(database, "SELECT content_hash FROM book_creation_snapshot WHERE snapshot_id = '$SNAPSHOT_ID'"),
        )
        assertEquals(
            CHAPTER_VERSION_ID,
            singleText(database, "SELECT current_version_id FROM chapter WHERE chapter_id = '$CHAPTER_ID'"),
        )
        assertEquals(
            NOVEL_CANARY,
            singleText(database, "SELECT content FROM chapter_version WHERE chapter_version_id = '$CHAPTER_VERSION_ID'"),
        )
        assertEquals(
            CHAPTER_HASH,
            singleText(database, "SELECT content_hash FROM chapter_version WHERE chapter_version_id = '$CHAPTER_VERSION_ID'"),
        )
    }

    private fun assertGenerationAuditPreserved(database: SupportSQLiteDatabase) {
        listOf("generation_job", "generation_stage", "request_attempt", "usage_ledger").forEach {
            assertEquals("Unexpected row count for $it", 1, rowCount(database, it))
        }
        assertEquals(
            STAGE_ID,
            singleText(database, "SELECT current_stage_id FROM generation_job WHERE job_id = '$JOB_ID'"),
        )
        assertEquals(
            OUTPUT_HASH,
            singleText(database, "SELECT output_hash FROM request_attempt WHERE attempt_id = '$ATTEMPT_ID'"),
        )
        assertEquals(30, singleInt(database, "SELECT total_tokens FROM usage_ledger WHERE usage_ledger_id = '$USAGE_ID'"))
    }

    private fun assertNarrativeMemoryPreserved(database: SupportSQLiteDatabase) {
        listOf(
            "story_bible_revision",
            "outline_revision",
            "outline_node",
            "book_memory_head",
            "chapter_summary",
        ).forEach {
            assertEquals("Unexpected row count for $it", 1, rowCount(database, it))
        }
        assertEquals(
            BIBLE_HASH,
            singleText(database, "SELECT content_hash FROM story_bible_revision WHERE bible_revision_id = '$BIBLE_ID'"),
        )
        assertEquals(
            OUTLINE_HASH,
            singleText(database, "SELECT content_hash FROM outline_revision WHERE outline_revision_id = '$OUTLINE_ID'"),
        )
        assertEquals(
            CHAPTER_VERSION_ID,
            singleText(database, "SELECT chapter_version_id FROM chapter_summary WHERE chapter_summary_id = '$SUMMARY_ID'"),
        )
    }

    private fun assertLatestSchemaAvailable(database: SupportSQLiteDatabase) {
        listOf(
            "template",
            "template_revision",
            "template_use_snapshot",
            "template_tag",
            "provider_capability",
            "connection_profile",
            "current_connection_selection",
            "chapter_tracking_projection",
            "foreshadow_transition",
            "memory_search_document",
            "memory_search_document_fts",
            "memory_search_backfill_state",
        ).forEach { table ->
            database.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
                arrayOf(table),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Latest table missing: $table", 1, cursor.getInt(0))
            }
        }
        database.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name = 'prevent_template_use_snapshot_update'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        listOf(
            "room_fts_content_sync_memory_search_document_fts_BEFORE_UPDATE",
            "room_fts_content_sync_memory_search_document_fts_BEFORE_DELETE",
            "room_fts_content_sync_memory_search_document_fts_AFTER_UPDATE",
            "room_fts_content_sync_memory_search_document_fts_AFTER_INSERT",
        ).forEach { trigger ->
            assertSqliteObjectExists(database, type = "trigger", name = trigger)
        }
        listOf(
            "index_memory_search_document_document_id",
            "index_memory_search_document_book_id_source_type_source_id",
            "index_memory_search_document_book_id_source_type_chapter_index",
            "index_memory_search_document_book_id_story_order",
        ).forEach { index ->
            assertSqliteObjectExists(database, type = "index", name = index)
        }
        if (database.version >= 17) {
            listOf(
                "budget_policy_revision",
                "budget_policy_head",
                "request_budget_reservation",
            ).forEach { table ->
                assertSqliteObjectExists(database, type = "table", name = table)
            }
            listOf(
                "index_budget_policy_revision_scope_scope_key_revision_no",
                "index_budget_policy_revision_parent_budget_policy_id",
                "index_budget_policy_head_current_budget_policy_id",
                "index_request_budget_reservation_attempt_id",
                "index_request_attempt_budget_reservation_id",
            ).forEach { index ->
                assertSqliteObjectExists(database, type = "index", name = index)
            }
            listOf(
                "prevent_budget_policy_revision_update",
                "prevent_budget_policy_revision_delete",
                "validate_budget_policy_revision_insert",
                "prevent_budget_policy_head_delete",
                "validate_budget_policy_head_insert",
                "validate_budget_policy_head_update",
                "prevent_request_budget_reservation_delete",
                "validate_request_budget_reservation_insert",
                "validate_request_budget_reservation_update",
                "validate_request_attempt_budget_insert",
                "validate_request_attempt_budget_update",
            ).forEach { trigger ->
                assertSqliteObjectExists(database, type = "trigger", name = trigger)
            }
            if (rowCount(database, "request_attempt") > 0) {
                assertEquals(
                    0,
                    singleInt(
                        database,
                        "SELECT budget_enforcement_version FROM request_attempt WHERE attempt_id = '$ATTEMPT_ID'",
                    ),
                )
                database.query(
                    "SELECT budget_reservation_id FROM request_attempt WHERE attempt_id = '$ATTEMPT_ID'",
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue("Legacy attempt must not carry a reservation.", cursor.isNull(0))
                }
            }
        }
        assertEquals(
            200,
            singleInt(database, "SELECT minimum_chapters FROM book WHERE book_id = '$BOOK_ID'"),
        )
        assertEquals(
            0,
            singleInt(database, "SELECT length_policy_schema_version FROM book WHERE book_id = '$BOOK_ID'"),
        )
    }

    private fun assertProductionMemorySearchSchemaAvailable(database: SupportSQLiteDatabase) {
        listOf("memory_search_document", "memory_search_document_fts").forEach { table ->
            assertSqliteObjectExists(database, type = "table", name = table)
        }
        listOf(
            "room_fts_content_sync_memory_search_document_fts_BEFORE_UPDATE",
            "room_fts_content_sync_memory_search_document_fts_BEFORE_DELETE",
            "room_fts_content_sync_memory_search_document_fts_AFTER_UPDATE",
            "room_fts_content_sync_memory_search_document_fts_AFTER_INSERT",
        ).forEach { trigger ->
            assertSqliteObjectExists(database, type = "trigger", name = trigger)
        }
    }

    private fun assertDatabaseIntegrity(database: SupportSQLiteDatabase) {
        database.query("PRAGMA integrity_check").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("ok", cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
        database.query("PRAGMA foreign_key_check").use { cursor ->
            assertEquals("Foreign-key violations remain after migration.", 0, cursor.count)
        }
    }

    private fun createEncryptedDatabase(
        databaseName: String,
        callbackVersion: Int,
        schemaAssetVersion: Int,
        passphraseStore: DatabasePassphraseStore,
    ) {
        SqlCipherRuntime.load()
        val passphrase = passphraseStore.getOrCreate()
        try {
            SupportOpenHelperFactory(passphrase, null, false)
                .create(
                    SupportSQLiteOpenHelper.Configuration.builder(context)
                        .name(databaseName)
                        .callback(
                            object : SupportSQLiteOpenHelper.Callback(callbackVersion) {
                                override fun onConfigure(db: SupportSQLiteDatabase) {
                                    db.setForeignKeyConstraintsEnabled(true)
                                }

                                override fun onCreate(db: SupportSQLiteDatabase) {
                                    createSchemaFromAsset(db, schemaAssetVersion)
                                    seedCoreNovel(db)
                                }

                                override fun onUpgrade(
                                    db: SupportSQLiteDatabase,
                                    oldVersion: Int,
                                    newVersion: Int,
                                ) = error("Unexpected fixture upgrade $oldVersion->$newVersion")
                            },
                        )
                        .build(),
                )
                .use { openHelper ->
                    openHelper.writableDatabase
                }
        } finally {
            passphrase.fill(0)
        }
    }

    private fun openExistingEncryptedDatabase(
        databaseName: String,
        version: Int,
        passphraseStore: DatabasePassphraseStore,
    ): PassphraseOwningOpenHelper {
        SqlCipherRuntime.load()
        val passphrase = passphraseStore.getOrCreate()
        val delegate = SupportOpenHelperFactory(passphrase, null, false)
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(databaseName)
                    .callback(
                        object : SupportSQLiteOpenHelper.Callback(version) {
                            override fun onCreate(db: SupportSQLiteDatabase) {
                                error("Existing encrypted fixture was unexpectedly recreated.")
                            }

                            override fun onUpgrade(
                                db: SupportSQLiteDatabase,
                                oldVersion: Int,
                                newVersion: Int,
                            ) = error("Unexpected fixture upgrade $oldVersion->$newVersion")
                        },
                    )
                    .build(),
            )
        return PassphraseOwningOpenHelper(delegate, passphrase)
    }

    private fun createSchemaFromAsset(database: SupportSQLiteDatabase, version: Int) {
        val assetName = "$SCHEMA_ASSET_DIRECTORY/$version.json"
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open(assetName)
            .bufferedReader()
            .use { JSONObject(it.readText()).getJSONObject("database") }
        val tablePlaceholder = "$" + "{TABLE_NAME}"
        val entities = json.getJSONArray("entities")
        for (entityIndex in 0 until entities.length()) {
            val entity = entities.getJSONObject(entityIndex)
            val tableName = entity.getString("tableName")
            database.execSQL(entity.getString("createSql").replace(tablePlaceholder, tableName))
            val indices = entity.optJSONArray("indices") ?: continue
            for (index in 0 until indices.length()) {
                database.execSQL(
                    indices.getJSONObject(index)
                        .getString("createSql")
                        .replace(tablePlaceholder, tableName),
                )
            }
        }
        val setupQueries = json.getJSONArray("setupQueries")
        for (index in 0 until setupQueries.length()) {
            database.execSQL(setupQueries.getString(index))
        }
    }

    private fun assertEncryptedDatabaseDoesNotExposeCanary(databaseName: String) {
        val databaseFile = context.getDatabasePath(databaseName)
        val header = ByteArray(16)
        FileInputStream(databaseFile).use { input ->
            assertEquals(header.size, input.read(header))
        }
        assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
        val canary = NOVEL_CANARY.toByteArray()
        try {
            databaseFiles(databaseName).forEach { file ->
                assertFalse(
                    "Migrated novel plaintext found in encrypted file " + file.name,
                    file.readBytes().containsSubsequence(canary),
                )
            }
        } finally {
            canary.fill(0)
        }
    }

    private fun rowCount(database: SupportSQLiteDatabase, table: String): Int =
        singleInt(database, "SELECT COUNT(*) FROM $table")

    private fun productionSearchCount(
        database: SupportSQLiteDatabase,
        bookId: String,
        matchExpression: String,
        targetChapterIndex: Int,
    ): Int = singleInt(
        database,
        """
        SELECT COUNT(*)
        FROM memory_search_document
        WHERE book_id = '$bookId'
          AND (chapter_index IS NULL OR chapter_index < $targetChapterIndex)
          AND rowid IN (
              SELECT rowid
              FROM memory_search_document_fts
              WHERE memory_search_document_fts MATCH '$matchExpression'
          )
        """.trimIndent(),
    )

    private fun assertSqliteObjectExists(
        database: SupportSQLiteDatabase,
        type: String,
        name: String,
    ) {
        database.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?",
            arrayOf(type, name),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Latest $type missing: $name", 1, cursor.getInt(0))
        }
    }

    private fun indexIsUnique(
        database: SupportSQLiteDatabase,
        table: String,
        index: String,
    ): Boolean = database.query("PRAGMA index_list($table)").use { cursor ->
        val nameColumn = cursor.getColumnIndexOrThrow("name")
        val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
        while (cursor.moveToNext()) {
            if (cursor.getString(nameColumn) == index) return@use cursor.getInt(uniqueColumn) == 1
        }
        throw AssertionError("Index missing: $index")
    }

    private fun singleInt(database: SupportSQLiteDatabase, sql: String): Int =
        database.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun singleText(database: SupportSQLiteDatabase, sql: String): String =
        database.query(sql).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun uniqueDatabaseName(label: String): String =
        ("zhijuan-migration-" + label + "-" + System.nanoTime() + ".db").also(databasesToDelete::add)

    private fun databaseFiles(databaseName: String): List<File> =
        context.getDatabasePath(databaseName).parentFile
            ?.listFiles()
            ?.filter { file -> file.name == databaseName || file.name.startsWith("$databaseName-") }
            .orEmpty()

    private fun deletePassphraseEnvelope() {
        val envelope = File(context.noBackupFilesDir, PASSPHRASE_ENVELOPE_PATH)
        envelope.delete()
        File(envelope.path + ".bak").delete()
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
    }

    private class PassphraseOwningOpenHelper(
        private val delegate: SupportSQLiteOpenHelper,
        private val passphrase: ByteArray,
    ) : AutoCloseable {
        val readableDatabase: SupportSQLiteDatabase
            get() = delegate.readableDatabase

        override fun close() {
            try {
                delegate.close()
            } finally {
                passphrase.fill(0)
            }
        }
    }

    private companion object {
        const val FUTURE_SCHEMA_VERSION = ZHIJUAN_DATABASE_SCHEMA_VERSION + 1
        const val SCHEMA_ASSET_DIRECTORY = "app.zhijuan.core.database.ZhijuanDatabase"
        const val PASSPHRASE_ENVELOPE_PATH = "security/database-passphrase.zjes"
        const val SNAPSHOT_ID = "snapshot-migration"
        const val SNAPSHOT_HASH = "snapshot-hash-preserved"
        const val BOOK_ID = "book-migration"
        const val BOOK_TITLE = "Migration preservation book"
        const val CHAPTER_ID = "chapter-migration"
        const val CHAPTER_VERSION_ID = "chapter-version-migration"
        const val NOVEL_CANARY = "ZHIJUAN_MIGRATION_NOVEL_CANARY_017"
        const val CHAPTER_HASH = "chapter-hash-preserved"
        const val JOB_ID = "job-migration"
        const val STAGE_ID = "stage-migration"
        const val ATTEMPT_ID = "attempt-migration"
        const val USAGE_ID = "usage-migration"
        const val OUTPUT_HASH = "output-hash-preserved"
        const val BIBLE_ID = "bible-migration"
        const val BIBLE_HASH = "bible-hash-preserved"
        const val OUTLINE_ID = "outline-migration"
        const val OUTLINE_HASH = "outline-hash-preserved"
        const val OUTLINE_NODE_ID = "outline-node-migration"
        const val OUTLINE_NODE_HASH = "outline-node-hash-preserved"
        const val SUMMARY_ID = "summary-migration"
    }
}
