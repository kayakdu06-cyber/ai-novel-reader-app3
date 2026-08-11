package app.zhijuan.reader.template

import android.content.Context
import app.zhijuan.core.database.EncryptedZhijuanDatabaseFactory
import app.zhijuan.core.database.ZHIJUAN_DATABASE_NAME
import app.zhijuan.core.database.template.StoredTemplateSource
import app.zhijuan.core.database.template.TemplateReadStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TemplateCatalog @Inject constructor(
    @ApplicationContext context: Context,
    private val restartFactory: TemplateRestartDraftFactory,
) {
    private val databaseHandle by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        EncryptedZhijuanDatabaseFactory(context.applicationContext).open(ZHIJUAN_DATABASE_NAME)
    }
    private val store by lazy(LazyThreadSafetyMode.NONE) { TemplateReadStore(databaseHandle.database) }

    suspend fun list(): List<StoredTemplateSource> = store.list()

    suspend fun prepareRestart(templateId: String): TemplateRestartDraft? =
        store.find(templateId)?.let(restartFactory::create)
}
