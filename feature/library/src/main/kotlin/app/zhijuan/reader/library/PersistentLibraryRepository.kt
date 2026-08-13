package app.zhijuan.reader.library

import android.content.Context
import app.zhijuan.core.contract.LibraryBookSummary
import app.zhijuan.core.contract.LibraryChapterSummary
import app.zhijuan.core.contract.LibraryRepository
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.ZHIJUAN_DATABASE_NAME
import app.zhijuan.core.database.library.LibraryReadStore
import app.zhijuan.core.security.AndroidProtectedArtifactStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersistentLibraryRepository @Inject constructor(
    @ApplicationContext context: Context,
) : LibraryRepository {
    private val databaseHandle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EncryptedZhijuanDatabaseFactory(context.applicationContext).open(ZHIJUAN_DATABASE_NAME)
    }
    private val store by lazy(LazyThreadSafetyMode.NONE) {
        LibraryReadStore(
            databaseHandle.database,
            AndroidProtectedArtifactStore(context.applicationContext),
        )
    }

    override suspend fun listBooks(): List<LibraryBookSummary> = store.listBooks()

    override suspend fun listChapters(bookId: String): List<LibraryChapterSummary> =
        store.listChapters(bookId)

    override suspend fun readChapter(chapterId: String): String? = store.readChapter(chapterId)

    override suspend fun readInProgressChapter(chapterId: String) =
        store.readInProgressChapter(chapterId)
}

class LibraryCatalog @Inject constructor(private val repository: LibraryRepository) {
    suspend fun shelf(): List<LibraryBookSummary> = repository.listBooks()

    suspend fun contents(bookId: String): List<LibraryChapterSummary> =
        repository.listChapters(bookId).sortedBy(LibraryChapterSummary::ordinal)
}
