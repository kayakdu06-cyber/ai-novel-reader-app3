package app.zhijuan.core.database.template

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.zhijuan.core.database.library.BookCreationSnapshotEntity
import app.zhijuan.core.database.library.BookEntity
import app.zhijuan.core.model.TemplateOriginType

@Dao
internal interface TemplateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTemplate(template: TemplateEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRevision(revision: TemplateRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUseSnapshot(snapshot: TemplateUseSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTag(tag: TemplateTagEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCreationSnapshot(snapshot: BookCreationSnapshotEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBook(book: BookEntity)

    @Query("SELECT * FROM template WHERE template_id = :templateId")
    suspend fun findTemplate(templateId: String): TemplateEntity?

    @Query(
        """
        SELECT * FROM template
        WHERE archived_at IS NULL
        ORDER BY is_pinned DESC, is_favorite DESC, updated_at DESC, template_id ASC
        """,
    )
    suspend fun activeTemplates(): List<TemplateEntity>

    @Query("SELECT * FROM template_revision WHERE template_revision_id = :revisionId")
    suspend fun findRevision(revisionId: String): TemplateRevisionEntity?

    @Query("SELECT * FROM template_revision WHERE derivation_key = :derivationKey")
    suspend fun findRevisionByDerivationKey(derivationKey: String): TemplateRevisionEntity?

    @Query("SELECT title FROM book WHERE book_id = :bookId")
    suspend fun findBookTitle(bookId: String): String?

    @Query("SELECT * FROM template_use_snapshot WHERE book_id = :bookId")
    suspend fun findUseSnapshotForBook(bookId: String): TemplateUseSnapshotEntity?

    @Query("SELECT * FROM template_tag WHERE template_id = :templateId ORDER BY dimension, normalized_value")
    suspend fun tagsForTemplate(templateId: String): List<TemplateTagEntity>

    @Query("SELECT COALESCE(MAX(revision_no), 0) FROM template_revision WHERE template_id = :templateId")
    suspend fun maximumRevision(templateId: String): Int

    @Query("SELECT COUNT(*) FROM template")
    suspend fun templateCount(): Int

    @Query("SELECT COUNT(*) FROM template_revision")
    suspend fun revisionCount(): Int

    @Query(
        """
        UPDATE template
        SET current_revision_id = :revisionId, updated_at = :updatedAt
        WHERE template_id = :templateId AND current_revision_id IS :expectedRevisionId
        """,
    )
    suspend fun compareAndSetCurrentRevision(
        templateId: String,
        expectedRevisionId: String?,
        revisionId: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE template
        SET display_name = :displayName,
            description = :description,
            is_favorite = :isFavorite,
            is_pinned = :isPinned,
            archived_at = :archivedAt,
            updated_at = :updatedAt
        WHERE template_id = :templateId
        """,
    )
    suspend fun updatePresentation(
        templateId: String,
        displayName: String,
        description: String,
        isFavorite: Boolean,
        isPinned: Boolean,
        archivedAt: Long?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE template
        SET last_used_at = :usedAt,
            updated_at = CASE WHEN updated_at < :usedAt THEN :usedAt ELSE updated_at END
        WHERE template_id = :templateId
        """,
    )
    suspend fun markTemplateUsed(templateId: String, usedAt: Long): Int

    @Transaction
    suspend fun createTemplate(template: TemplateEntity, initialRevision: TemplateRevisionEntity) {
        validateInitialRevision(template, initialRevision)
        insertTemplate(template)
        insertRevision(initialRevision)
        check(
            compareAndSetCurrentRevision(
                template.templateId,
                expectedRevisionId = null,
                revisionId = initialRevision.templateRevisionId,
                updatedAt = initialRevision.createdAt,
            ) == 1,
        )
    }

    @Transaction
    suspend fun getOrCreateBookDerivedTemplate(
        template: TemplateEntity,
        initialRevision: TemplateRevisionEntity,
    ): TemplateRevisionEntity {
        require(template.originType == TemplateOriginType.BOOK_DERIVED)
        val key = requireNotNull(initialRevision.derivationKey) {
            "A book-derived revision requires a stable derivation key."
        }
        findRevisionByDerivationKey(key)?.let { existing ->
            require(
                existing.sourceBookId == initialRevision.sourceBookId &&
                    existing.contentHash == initialRevision.contentHash,
            ) { "Derivation key collision does not match the same source and payload." }
            return existing
        }
        createTemplate(template, initialRevision)
        return initialRevision
    }

    @Transaction
    suspend fun createRevision(revision: TemplateRevisionEntity) {
        val template = requireNotNull(findTemplate(revision.templateId)) { "Template does not exist." }
        val currentId = requireNotNull(template.currentRevisionId) { "Template has no current revision." }
        val parent = requireNotNull(findRevision(currentId))
        require(revision.revisionNo == maximumRevision(revision.templateId) + 1) {
            "Template revision number must be the next number."
        }
        require(revision.parentTemplateRevisionId == currentId) {
            "A new revision must descend from the current revision."
        }
        require(parent.templateId == revision.templateId) { "Normal edits cannot cross template ownership." }
        require(revision.originRootRevisionId == parent.originRootRevisionId) {
            "A template edit must preserve its origin root."
        }
        require(revision.derivationKey == null) { "Only initial extracted revisions use a derivation key." }
        require(revision.sourceBookId == parent.sourceBookId) { "Template source book is immutable across edits." }
        require(revision.sourceBookTitleSnapshot == parent.sourceBookTitleSnapshot) {
            "Template source title snapshot is immutable across edits."
        }
        validateOriginChainSnapshot(revision, parent)
        validateRevisionPayload(revision)
        insertRevision(revision)
        check(compareAndSetCurrentRevision(revision.templateId, currentId, revision.templateRevisionId, revision.createdAt) == 1) {
            "Template changed while creating a new revision."
        }
    }

    @Transaction
    suspend fun createBookFromTemplate(
        creationSnapshot: BookCreationSnapshotEntity,
        book: BookEntity,
        useSnapshot: TemplateUseSnapshotEntity,
    ) {
        require(book.creationSnapshotId == creationSnapshot.snapshotId)
        require(useSnapshot.bookId == book.bookId)
        require(book.branchedFromBookId == null && book.branchedFromChapterVersionId == null) {
            "A template-created book is not a chapter branch."
        }
        val revision = requireNotNull(findRevision(useSnapshot.templateRevisionId))
        require(revision.templateId == useSnapshot.templateId)
        require(useSnapshot.contentHash == creationSnapshot.contentHash) {
            "Book creation and template-use snapshots must freeze the same final payload hash."
        }
        validateUseSnapshot(useSnapshot)
        insertCreationSnapshot(creationSnapshot)
        insertBook(book)
        insertUseSnapshot(useSnapshot)
        check(markTemplateUsed(useSnapshot.templateId, useSnapshot.createdAt) == 1)
    }

    private suspend fun validateInitialRevision(
        template: TemplateEntity,
        revision: TemplateRevisionEntity,
    ) {
        require(template.templateId.isNotBlank() && template.displayName.isNotBlank())
        require(template.currentRevisionId == null) { "A new template cannot already point to a revision." }
        require((template.originType == TemplateOriginType.SYSTEM_PRESET) == (template.systemPresetKey != null)) {
            "Only system presets have a system preset key."
        }
        require(revision.templateId == template.templateId && revision.revisionNo == 1)
        val parent = revision.parentTemplateRevisionId?.let { requireNotNull(findRevision(it)) }
        if (template.originType == TemplateOriginType.TEMPLATE_FORK) {
            requireNotNull(parent) { "A template fork requires an existing parent revision." }
            require(parent.templateId != template.templateId)
            require(revision.originRootRevisionId == parent.originRootRevisionId)
        } else {
            require(parent == null) { "Only a template fork can start from another template revision." }
            require(revision.originRootRevisionId == revision.templateRevisionId)
        }
        if (template.originType == TemplateOriginType.BOOK_DERIVED) {
            require(revision.sourceBookId != null && !revision.sourceBookTitleSnapshot.isNullOrBlank())
            require(findBookTitle(revision.sourceBookId) == revision.sourceBookTitleSnapshot) {
                "The source-book title snapshot must match the existing source book at extraction time."
            }
            require(!revision.derivationKey.isNullOrBlank())
        } else {
            require(revision.sourceBookId == null && revision.sourceBookTitleSnapshot == null)
            require(revision.derivationKey == null)
        }
        validateOriginChainSnapshot(revision, parent)
        validateRevisionPayload(revision)
    }

    private fun validateOriginChainSnapshot(
        revision: TemplateRevisionEntity,
        parent: TemplateRevisionEntity?,
    ) {
        require(revision.originChainJson.contains(revision.originRootRevisionId)) {
            "Origin-chain snapshot must contain the origin root."
        }
        require(parent == null || revision.originChainJson.contains(parent.templateRevisionId)) {
            "Origin-chain snapshot must contain the direct parent."
        }
    }

    private fun validateRevisionPayload(revision: TemplateRevisionEntity) {
        require(revision.contentHash.isNotBlank())
        require(revision.templateSchemaVersion > 0 && revision.contentControlSchemaVersion > 0)
        require(revision.promptBundleVersion.isNotBlank() && revision.createdByAppVersion.isNotBlank())
        TemplatePayloadContract.validate(revision)
    }

    private fun validateUseSnapshot(snapshot: TemplateUseSnapshotEntity) {
        require(snapshot.contentHash.isNotBlank())
        require(snapshot.templateSchemaVersion > 0 && snapshot.contentControlSchemaVersion > 0)
        require(snapshot.promptBundleVersion.isNotBlank())
        TemplatePayloadContract.validate(snapshot)
    }
}
