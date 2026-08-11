package app.zhijuan.reader.creation

import android.content.Context
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.library.BookCreationRepository
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.database.library.StoredBookCreationSummary
import app.zhijuan.core.model.BookLengthMode
import app.zhijuan.core.database.ZHIJUAN_DATABASE_NAME
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class BookCreationConfirmation(
    val bookId: String,
    val snapshotId: String,
    val title: String,
    val lengthMode: BookLengthMode,
    val minimumChapterCount: Int,
    val targetChapterCount: Int,
    val modelId: String,
    val contentHash: String,
)

sealed interface BookCreationResult {
    data class Created(
        val bookId: String,
    ) : BookCreationResult

    data object Failed : BookCreationResult
}

interface BookCreationActions {
    suspend fun create(
        draft: MinimalBookDraft,
        connection: CreationConnectionSelection,
    ): BookCreationResult

    suspend fun loadConfirmation(bookId: String): BookCreationConfirmation?
}

@Singleton
class BookCreationGateway @Inject constructor(
    @ApplicationContext context: Context,
) : BookCreationActions {
    private val applicationContext = context.applicationContext
    private val databaseHandle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EncryptedZhijuanDatabaseFactory(applicationContext).open(ZHIJUAN_DATABASE_NAME)
    }
    private val repository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BookCreationRepository(databaseHandle.database)
    }

    override suspend fun create(
        draft: MinimalBookDraft,
        connection: CreationConnectionSelection,
    ): BookCreationResult = try {
        val now = System.currentTimeMillis()
        val prepared = CreationStandardizerV1.prepare(
            draft = draft,
            connection = connection,
            snapshotId = UUID.randomUUID().toString(),
            bookId = UUID.randomUUID().toString(),
            createdAt = now,
        )
        val committed = repository.create(
            snapshot = prepared.toSnapshotEntity(),
            book = prepared.toBookEntity(),
        )
        BookCreationResult.Created(
            bookId = committed.bookId,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        BookCreationResult.Failed
    }

    override suspend fun loadConfirmation(bookId: String): BookCreationConfirmation? = try {
        repository.findCreationSummary(bookId)?.toBookCreationConfirmation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun PreparedBookCreation.toSnapshotEntity() = BookCreationSnapshotEntity(
        snapshotId = snapshotId,
        rawInputJson = rawInputJson,
        normalizedInputJson = normalizedInputJson,
        inferenceProvenanceJson = inferenceProvenanceJson,
        genrePayloadJson = genrePayloadJson,
        presentationProfileJson = presentationProfileJson,
        modelPreferenceJson = modelPreferenceJson,
        schemaVersion = snapshotSchemaVersion,
        promptBundleVersion = promptBundleVersion,
        contentControlSchemaVersion = contentControlSchemaVersion,
        contentHash = contentHash,
        createdAt = createdAt,
    )

    private fun PreparedBookCreation.toBookEntity() = BookEntity(
        bookId = bookId,
        creationSnapshotId = snapshotId,
        title = title,
        titleSource = titleSource,
        status = status,
        lengthMode = lengthMode,
        targetCharacters = null,
        targetChapters = targetChapterCount,
        minimumChapters = minimumChapterCount,
        lengthPolicySchemaVersion = lengthPolicySchemaVersion,
        generationStatusSummary = "AWAITING_USAGE_CONFIRMATION",
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}

internal fun StoredBookCreationSummary.toBookCreationConfirmation(): BookCreationConfirmation? {
    val modelId = runCatching {
        Json.parseToJsonElement(modelPreferenceJson)
            .jsonObject["modelId"]
            ?.jsonPrimitive
            ?.content
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return BookCreationConfirmation(
        bookId = bookId,
        snapshotId = snapshotId,
        title = title,
        lengthMode = lengthMode,
        minimumChapterCount = minimumChapterCount,
        targetChapterCount = targetChapterCount,
        modelId = modelId,
        contentHash = contentHash,
    )
}
