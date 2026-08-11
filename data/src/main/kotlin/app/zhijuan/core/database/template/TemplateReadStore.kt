package app.zhijuan.core.database.template

import app.zhijuan.core.database.ZhijuanDatabase
import app.zhijuan.core.model.TemplateOriginType

data class StoredTemplateSource(
    val templateId: String,
    val revisionId: String,
    val revisionNo: Int,
    val displayName: String,
    val description: String,
    val originType: TemplateOriginType,
    val sourceBookId: String?,
    val sourceBookTitle: String?,
    val categoryTags: List<String>,
    val storySeedJson: String,
    val genreJson: String,
    val stableCharactersJson: String,
    val worldRulesJson: String,
    val writingStyleJson: String,
    val structureJson: String,
    val presentationJson: String,
    val contentRulesJson: String,
    val generationStrategyJson: String,
    val modelRolePreferencesJson: String,
    val extensionJson: String,
    val contentHash: String,
)

/** Stable read facade used by the template feature; DAO and entity mutability stay in data. */
class TemplateReadStore(private val database: ZhijuanDatabase) {
    suspend fun list(): List<StoredTemplateSource> = database.templateDao()
        .activeTemplates()
        .mapNotNull { template -> source(template) }

    suspend fun find(templateId: String): StoredTemplateSource? {
        require(templateId.isNotBlank()) { "Template id must not be blank." }
        return database.templateDao().findTemplate(templateId)?.let { source(it) }
    }

    private suspend fun source(template: TemplateEntity): StoredTemplateSource? {
        val revisionId = template.currentRevisionId ?: return null
        val revision = database.templateDao().findRevision(revisionId) ?: return null
        check(revision.templateId == template.templateId) { "Template points to a foreign revision." }
        val tags = database.templateDao().tagsForTemplate(template.templateId)
            .sortedWith(compareByDescending<TemplateTagEntity> { it.isPrimary }.thenBy { it.displayName })
            .map { it.displayName }
        return StoredTemplateSource(
            templateId = template.templateId,
            revisionId = revision.templateRevisionId,
            revisionNo = revision.revisionNo,
            displayName = template.displayName,
            description = template.description,
            originType = template.originType,
            sourceBookId = revision.sourceBookId,
            sourceBookTitle = revision.sourceBookTitleSnapshot,
            categoryTags = tags,
            storySeedJson = revision.storySeedJson,
            genreJson = revision.genreJson,
            stableCharactersJson = revision.stableCharactersJson,
            worldRulesJson = revision.worldRulesJson,
            writingStyleJson = revision.writingStyleJson,
            structureJson = revision.structureJson,
            presentationJson = revision.presentationJson,
            contentRulesJson = revision.contentRulesJson,
            generationStrategyJson = revision.generationStrategyJson,
            modelRolePreferencesJson = revision.modelRolePreferencesJson,
            extensionJson = revision.extensionJson,
            contentHash = revision.contentHash,
        )
    }
}
