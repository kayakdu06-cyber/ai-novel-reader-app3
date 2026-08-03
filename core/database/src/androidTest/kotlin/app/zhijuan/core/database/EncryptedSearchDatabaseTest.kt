package app.zhijuan.core.database

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Insert
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.core.database.search.SearchDocumentEntity
import app.zhijuan.core.database.search.SearchIndexText
import app.zhijuan.core.security.AndroidKeystoreAesGcm
import app.zhijuan.core.security.DatabasePassphraseStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@RunWith(AndroidJUnit4::class)
class EncryptedSearchDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "zhijuan-m0-spike.db"
    private val keyAlias = "app.zhijuan.reader.test.database.${System.nanoTime()}"
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
    fun encryptedDatabaseReopensAndHeaderIsNotPlainSQLite() = runBlocking {
        val firstHandle = EncryptedSearchSpikeDatabaseFactory(context, passphraseStore).open(databaseName)
        val first = firstHandle.database
        first.searchDocumentDao().insertAll(listOf(document(1, 1, "长安城落下了初雪。")))
        firstHandle.close()

        val databaseBytes = context.getDatabasePath(databaseName).readBytes()
        assertFalse(databaseBytes.containsSubsequence("长安城落下了初雪".toByteArray()))
        val header = ByteArray(16)
        FileInputStream(context.getDatabasePath(databaseName)).use { input ->
            assertEquals(header.size, input.read(header))
        }
        assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))

        val reopenedHandle = EncryptedSearchSpikeDatabaseFactory(context, passphraseStore).open(databaseName)
        val reopened = reopenedHandle.database
        assertEquals(1, reopened.searchDocumentDao().count())
        val results = reopened.searchDocumentDao().search(
            bookId = BOOK_ID,
            matchExpression = requireNotNull(SearchIndexText.matchExpression("长安城")),
            limit = 10,
        )
        assertEquals(listOf("doc-1"), results.map(SearchDocumentEntity::documentId))
        reopenedHandle.close()
    }

    @Test
    fun ChineseRecallAndIncrementalUpdateWorkAtTenThousandDocuments() = runBlocking {
        val handle = EncryptedSearchSpikeDatabaseFactory(context, passphraseStore).open(databaseName)
        val database = handle.database
        val fixtureByIndex = mapOf(
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
        val documents = (1..10_000).map { index ->
            val special = fixtureByIndex[index]?.second
                ?: "第${index}章，风从山谷吹过，众人继续赶路。"
            document(index.toLong(), index, special)
        }
        val insertStart = SystemClock.elapsedRealtimeNanos()
        database.searchDocumentDao().insertAll(documents)
        val insertMillis = elapsedMillis(insertStart)

        val queries = fixtureByIndex.map { (index, fixture) -> fixture.first to "doc-$index" }.toMap()
        var coldSlowestQueryMillis = 0.0
        queries.forEach { (query, expectedId) ->
            val queryStart = SystemClock.elapsedRealtimeNanos()
            val result = database.searchDocumentDao().search(
                bookId = BOOK_ID,
                matchExpression = requireNotNull(SearchIndexText.matchExpression(query)),
                limit = 20,
            )
            coldSlowestQueryMillis = maxOf(coldSlowestQueryMillis, elapsedMillis(queryStart))
            assertTrue(result.any { it.documentId == expectedId })
        }

        repeat(2) {
            queries.keys.forEach { query ->
                database.searchDocumentDao().search(
                    bookId = BOOK_ID,
                    matchExpression = requireNotNull(SearchIndexText.matchExpression(query)),
                    limit = 20,
                )
            }
        }
        val warmQueryTimings = mutableListOf<Double>()
        repeat(3) {
            queries.forEach { (query, expectedId) ->
                val queryStart = SystemClock.elapsedRealtimeNanos()
                val result = database.searchDocumentDao().search(
                    bookId = BOOK_ID,
                    matchExpression = requireNotNull(SearchIndexText.matchExpression(query)),
                    limit = 20,
                )
                warmQueryTimings += elapsedMillis(queryStart)
                assertTrue(result.any { it.documentId == expectedId })
            }
        }
        val sortedWarmTimings = warmQueryTimings.sorted()
        val warmMedianQueryMillis = sortedWarmTimings[sortedWarmTimings.size / 2]
        val warmSlowestQueryMillis = sortedWarmTimings.last()

        val original = documents[136]
        database.searchDocumentDao().update(
            original.copy(
                content = "顾南舟改把玄铁剑交给了苏晚。",
                searchTerms = SearchIndexText.indexTerms("顾南舟改把玄铁剑交给了苏晚。"),
            ),
        )
        val updatedResult = database.searchDocumentDao().search(
            bookId = BOOK_ID,
            matchExpression = requireNotNull(SearchIndexText.matchExpression("苏晚")),
            limit = 20,
        )
        assertTrue(updatedResult.any { it.documentId == "doc-137" })

        Log.i(
            "ZhijuanM0Benchmark",
            "documents=10000 fixtures=${queries.size} recall=100% insertMs=$insertMillis " +
                "coldSlowestQueryMs=$coldSlowestQueryMillis " +
                "warmMedianQueryMs=$warmMedianQueryMillis " +
                "warmSlowestQueryMs=$warmSlowestQueryMillis",
        )
        handle.close()
    }

    @Test
    fun twoHundredThousandCharacterChapterCanBeStoredAndFound() = runBlocking {
        val handle = EncryptedSearchSpikeDatabaseFactory(context, passphraseStore).open(databaseName)
        val database = handle.database
        val seed = "长安城外风雪渐急，顾南舟仍在寻找玄铁剑的下落。"
        val content = seed.repeat((200_000 / seed.length) + 1).take(200_000)

        val indexStart = SystemClock.elapsedRealtimeNanos()
        val searchTerms = SearchIndexText.indexTerms(content)
        val indexMillis = elapsedMillis(indexStart)
        val insertStart = SystemClock.elapsedRealtimeNanos()
        database.searchDocumentDao().insertAll(
            listOf(document(1, 1, content).copy(searchTerms = searchTerms)),
        )
        val insertMillis = elapsedMillis(insertStart)
        val queryStart = SystemClock.elapsedRealtimeNanos()
        val result = database.searchDocumentDao().search(
            bookId = BOOK_ID,
            matchExpression = requireNotNull(SearchIndexText.matchExpression("玄铁剑")),
            limit = 10,
        )
        val queryMillis = elapsedMillis(queryStart)

        assertEquals(200_000, result.single().content.length)
        Log.i(
            "ZhijuanM0LargeChapter",
            "characters=200000 indexMs=$indexMillis insertMs=$insertMillis queryMs=$queryMillis " +
                "derivedIndexChars=${searchTerms.length}",
        )
        handle.close()
    }

    @Test
    fun encryptedRoomMigrationPreservesExistingSearchData() = runBlocking {
        System.loadLibrary("sqlcipher")
        val passphrase = passphraseStore.getOrCreate()
        val legacy = Room.databaseBuilder(
            context,
            LegacySearchDatabase::class.java,
            databaseName,
        )
            .openHelperFactory(SupportOpenHelperFactory(passphrase, null, false))
            .build()
        try {
            legacy.openHelper.writableDatabase
            legacy.dao().insert(
                LegacySearchDocumentEntity(
                    rowId = 42,
                    documentId = "legacy-doc",
                    bookId = BOOK_ID,
                    chapterIndex = 42,
                    content = "旧版本里的长安城仍然存在。",
                    searchTerms = SearchIndexText.indexTerms("旧版本里的长安城仍然存在。"),
                ),
            )
        } finally {
            legacy.close()
            passphrase.fill(0)
        }

        val migratedHandle = EncryptedSearchSpikeDatabaseFactory(context, passphraseStore).open(databaseName)
        val result = migratedHandle.database.searchDocumentDao().search(
            bookId = BOOK_ID,
            matchExpression = requireNotNull(SearchIndexText.matchExpression("长安城")),
            limit = 10,
        )
        assertEquals(listOf("legacy-doc"), result.map(SearchDocumentEntity::documentId))
        assertEquals("", result.single().contentHash)
        migratedHandle.close()
    }

    private fun document(
        rowId: Long,
        chapterIndex: Int,
        content: String,
    ): SearchDocumentEntity = SearchDocumentEntity(
        rowId = rowId,
        documentId = "doc-$rowId",
        bookId = BOOK_ID,
        chapterIndex = chapterIndex,
        content = content,
        contentHash = "fixture-$rowId",
        searchTerms = SearchIndexText.indexTerms(content),
    )

    private fun elapsedMillis(startNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0

    private fun secretEnvelopeFile(): File =
        File(context.noBackupFilesDir, "security/database-passphrase.zjes")

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return indices.any { start ->
            start + needle.size <= size && needle.indices.all { offset ->
                this[start + offset] == needle[offset]
            }
        }
    }

    private companion object {
        const val BOOK_ID = "book-fixed-recall-fixture"
    }
}

@Entity(
    tableName = "search_document",
    indices = [
        Index(value = ["document_id"], unique = true),
        Index(value = ["book_id", "chapter_index"]),
    ],
)
data class LegacySearchDocumentEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowId: Long,
    @ColumnInfo(name = "document_id")
    val documentId: String,
    @ColumnInfo(name = "book_id")
    val bookId: String,
    @ColumnInfo(name = "chapter_index")
    val chapterIndex: Int,
    val content: String,
    @ColumnInfo(name = "search_terms")
    val searchTerms: String,
)

@Entity(tableName = "search_document_fts")
@Fts4(contentEntity = LegacySearchDocumentEntity::class)
data class LegacySearchDocumentFtsEntity(
    @ColumnInfo(name = "search_terms")
    val searchTerms: String,
)

@Dao
interface LegacySearchDocumentDao {
    @Insert
    suspend fun insert(document: LegacySearchDocumentEntity)
}

@Database(
    entities = [LegacySearchDocumentEntity::class, LegacySearchDocumentFtsEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LegacySearchDatabase : RoomDatabase() {
    abstract fun dao(): LegacySearchDocumentDao
}
