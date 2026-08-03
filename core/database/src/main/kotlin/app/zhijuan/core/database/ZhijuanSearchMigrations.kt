package app.zhijuan.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ZhijuanSearchMigrations {
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE search_document " +
                    "ADD COLUMN content_hash TEXT NOT NULL DEFAULT ''",
            )
        }
    }
}
