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
        assertEquals(
            200,
            singleInt(database, "SELECT minimum_chapters FROM book WHERE book_id = '$BOOK_ID'"),
        )
        assertEquals(
            0,
            singleInt(database, "SELECT length_policy_schema_version FROM book WHERE book_id = '$BOOK_ID'"),
        )
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
