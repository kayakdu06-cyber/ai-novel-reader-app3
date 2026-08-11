package app.zhijuan.core.database

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.search.MemorySearchDocumentEntity
import app.zhijuan.core.database.search.MemorySearchRecallRepositoryV1
import app.zhijuan.core.database.search.MemorySearchSourceTypeV1
import app.zhijuan.core.database.search.SearchIndexText
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.model.BookStatus
import app.zhijuan.core.model.TitleSource
import app.zhijuan.core.security.AndroidKeystoreAesGcm
import app.zhijuan.core.security.DatabasePassphraseStore
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemorySearchProductionBenchmarkDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "zhijuan-production-recall-benchmark.db"
    private val keyAlias = "app.zhijuan.reader.test.production-recall.${System.nanoTime()}"
    private val passphraseStore by lazy {
        DatabasePassphraseStore(context, AndroidKeystoreAesGcm(keyAlias))
    }

    @Before
    fun setUp() {
        context.deleteDatabase(databaseName)
        secretEnvelopeFile().delete()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
        AndroidKeystoreAesGcm(keyAlias).deleteKey()
        secretEnvelopeFile().delete()
    }

    @Test
    fun encryptedProductionFtsRecallsFixedChineseSetAtTenThousandDocuments() = runBlocking {
        EncryptedZhijuanDatabaseFactory(context, passphraseStore).open(databaseName).use { handle ->
            val database = handle.database
            createBook(database)
            val documents = (1..DOCUMENT_COUNT).map { index ->
                val content = FIXTURES[index]?.second
                    ?: "第${index}章，风从山谷吹过，众人沿旧路继续赶路。"
                document(index, content, importance = if (FIXTURES.containsKey(index)) 100 else 10)
            }
            val insertStarted = SystemClock.elapsedRealtimeNanos()
            database.memorySearchDao().insertAll(documents)
            val insertMillis = elapsedMillis(insertStarted)
            assertEquals(DOCUMENT_COUNT.toLong(), database.memorySearchDao().countByBook(BOOK_ID))

            val repository = MemorySearchRecallRepositoryV1(database)
            val expectedByQuery = FIXTURES.map { (index, fixture) ->
                fixture.first to "benchmark-source-$index"
            }.toMap()
            expectedByQuery.forEach { (query, expectedSourceId) ->
                val result = recall(repository, query)
                assertTrue(result.hits.any { it.document.sourceId == expectedSourceId })
            }

            repeat(WARM_UP_ROUNDS) {
                expectedByQuery.keys.forEach { query -> recall(repository, query) }
            }
            val warmTimings = mutableListOf<Double>()
            repeat(MEASUREMENT_ROUNDS) {
                expectedByQuery.forEach { (query, expectedSourceId) ->
                    val started = SystemClock.elapsedRealtimeNanos()
                    val result = recall(repository, query)
                    warmTimings += elapsedMillis(started)
                    assertTrue(result.hits.any { it.document.sourceId == expectedSourceId })
                }
            }
            val sorted = warmTimings.sorted()
            val medianMillis = percentile(sorted, 50)
            val p95Millis = percentile(sorted, 95)
            val slowestMillis = sorted.last()
            assertTrue("Warm median recall exceeded 100 ms: $medianMillis", medianMillis <= 100.0)
            assertTrue("Warm p95 recall exceeded 200 ms: $p95Millis", p95Millis <= 200.0)
            assertTrue("Warm slowest recall exceeded 500 ms: $slowestMillis", slowestMillis <= 500.0)

            val unrelated = recall(repository, "不存在的琉璃鲸")
            assertTrue(unrelated.hits.isEmpty())
            val replayA = recall(repository, "玄铁剑")
            val replayB = recall(repository, "玄铁剑")
            assertEquals(replayA.queryFingerprint, replayB.queryFingerprint)
            assertEquals(
                replayA.hits.map { it.document.sourceId },
                replayB.hits.map { it.document.sourceId },
            )

            val broadStarted = SystemClock.elapsedRealtimeNanos()
            val broad = repository.recall(
                bookId = BOOK_ID,
                targetChapterIndex = TARGET_CHAPTER_INDEX,
                targetChapterTitle = expectedByQuery.keys.take(10).joinToString(" "),
                targetChapterPlanJson = "{}",
                targetArcTitle = expectedByQuery.keys.drop(10).take(5).joinToString(" "),
                targetArcPlanJson = "{}",
                userAddition = expectedByQuery.keys.drop(15).joinToString(" "),
            )
            val broadMillis = elapsedMillis(broadStarted)
            assertEquals(FIXTURES.size, broad.hits.map { it.document.sourceId }.distinct().size)
            assertTrue(broad.executedProbeCount <= MAX_TOTAL_PROBES)
            assertTrue("Bounded multi-route recall exceeded 1,000 ms: $broadMillis", broadMillis <= 1_000.0)

            Log.i(
                LOG_TAG,
                "documents=$DOCUMENT_COUNT fixtures=${FIXTURES.size} recall=100% " +
                    "insertMs=$insertMillis warmMedianMs=$medianMillis warmP95Ms=$p95Millis " +
                    "warmSlowestMs=$slowestMillis broadMs=$broadMillis " +
                    "broadExecutedProbes=${broad.executedProbeCount}",
            )
            Unit
        }
    }

    private suspend fun recall(
        repository: MemorySearchRecallRepositoryV1,
        query: String,
    ) = repository.recall(
        bookId = BOOK_ID,
        targetChapterIndex = TARGET_CHAPTER_INDEX,
        targetChapterTitle = query,
        targetChapterPlanJson = "{}",
        targetArcTitle = "",
        targetArcPlanJson = "{}",
        userAddition = null,
    )

    private fun document(
        index: Int,
        content: String,
        importance: Int,
    ) = MemorySearchDocumentEntity(
        documentId = "benchmark-document-$index",
        bookId = BOOK_ID,
        sourceType = MemorySearchSourceTypeV1.entries[index % MemorySearchSourceTypeV1.entries.size].name,
        sourceId = "benchmark-source-$index",
        chapterIndex = index,
        storyOrder = index.toLong(),
        importance = importance,
        sourceContentHash = sha256(content),
        searchTerms = SearchIndexText.indexTerms(content),
        updatedAt = index.toLong(),
    )

    private suspend fun createBook(database: ZhijuanDatabase) {
        database.libraryDao().createBook(
            BookCreationSnapshotEntity(
                snapshotId = SNAPSHOT_ID,
                rawInputJson = "{}",
                normalizedInputJson = "{}",
                inferenceProvenanceJson = "{}",
                genrePayloadJson = "{}",
                presentationProfileJson = "{}",
                modelPreferenceJson = "{}",
                schemaVersion = 1,
                promptBundleVersion = "prompt-1",
                contentControlSchemaVersion = 1,
                contentHash = "a".repeat(64),
                createdAt = 1L,
            ),
            BookEntity(
                bookId = BOOK_ID,
                creationSnapshotId = SNAPSHOT_ID,
                title = "Production recall benchmark",
                titleSource = TitleSource.USER,
                status = BookStatus.DRAFT,
                lengthMode = BookLengthMode.LONG,
                targetCharacters = 1_000_000,
                targetChapters = 10_000,
                minimumChapters = 301,
                lengthPolicySchemaVersion = 1,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
    }

    private fun elapsedMillis(startNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0

    private fun percentile(sorted: List<Double>, percentage: Int): Double {
        val index = ((sorted.size * percentage + 99) / 100 - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun secretEnvelopeFile(): File =
        File(context.noBackupFilesDir, "security/database-passphrase.zjes")

    private companion object {
        const val LOG_TAG = "ZhijuanProductionRecall"
        const val BOOK_ID = "production-recall-benchmark-book"
        const val SNAPSHOT_ID = "production-recall-benchmark-snapshot"
        const val DOCUMENT_COUNT = 10_000
        const val TARGET_CHAPTER_INDEX = DOCUMENT_COUNT + 1
        const val WARM_UP_ROUNDS = 2
        const val MEASUREMENT_ROUNDS = 3
        const val MAX_TOTAL_PROBES = 64
        val FIXTURES = linkedMapOf(
            137 to ("玄铁剑" to "顾南舟把玄铁剑藏在长安旧城的石桥下。"),
            509 to ("白鹭客栈" to "沈知意记得白鹭客栈的暗号是春潮。"),
            888 to ("青铜铃" to "北斗司的密函提到了赤砂谷和青铜铃。"),
            1_204 to ("星河渡" to "星河渡的船夫只在月落以后开船。"),
            1_677 to ("朱雀令" to "朱雀令被封在王府西墙的暗格中。"),
            2_031 to ("夜雨灯" to "夜雨灯亮起时，旧案的证人终于现身。"),
            2_488 to ("寒山寺" to "寒山寺钟声之后，密道会短暂开启。"),
            2_945 to ("银杏巷" to "银杏巷尽头住着最后一位铸剑师。"),
            3_302 to ("逐月弓" to "逐月弓只能由北境守誓者拉开。"),
            3_759 to ("流云谱" to "流云谱缺失的第七页藏有真相。"),
            4_116 to ("听雪楼" to "听雪楼的账册记录了那笔旧债。"),
            4_573 to ("赤羽舟" to "赤羽舟昨夜停靠在南岸芦苇荡。"),
            5_030 to ("望川台" to "望川台下埋着前朝留下的石碑。"),
            5_487 to ("松烟墨" to "松烟墨遇水后显出第二层字迹。"),
            5_944 to ("归鹤谷" to "归鹤谷每逢冬至才允许外人进入。"),
            6_401 to ("惊鸿宴" to "惊鸿宴的座次暗合失踪者名单。"),
            6_858 to ("乌金钥" to "乌金钥能开启地宫最深处的铜门。"),
            7_315 to ("枕星阁" to "枕星阁保留着二十年前的观测记录。"),
            8_772 to ("照影泉" to "照影泉水会映出佩剑原来的主人。"),
            9_629 to ("霜叶帖" to "霜叶帖末尾的印章来自失传门派。"),
        )
    }
}
