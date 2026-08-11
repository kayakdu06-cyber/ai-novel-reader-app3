package app.zhijuan.reader.m1

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.annotation.XmlRes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.zhijuan.reader.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class M1BackupExclusionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun installedApplicationIsNotEligibleForSystemBackup() {
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertEquals(0, applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertNull(applicationInfo.backupAgentName)
    }

    @Test
    fun android12PlusRulesExcludeEveryBackupDomainFromCloudAndDeviceTransfer() {
        val document = parseRules(R.xml.data_extraction_rules)

        assertEquals("data-extraction-rules", document.root)
        assertEquals(setOf("cloud-backup", "device-transfer"), document.sections)
        assertEquals(emptyList<ParsedRule>(), document.includes)
        assertCompleteExclusion(document.excludes.filter { it.section == "cloud-backup" })
        assertCompleteExclusion(document.excludes.filter { it.section == "device-transfer" })
    }

    @Test
    fun android11AndLowerRulesExcludeEveryBackupDomain() {
        val document = parseRules(R.xml.backup_rules)

        assertEquals("full-backup-content", document.root)
        assertEquals(setOf("full-backup-content"), document.sections)
        assertEquals(emptyList<ParsedRule>(), document.includes)
        assertCompleteExclusion(document.excludes)
    }

    private fun assertCompleteExclusion(rules: List<ParsedRule>) {
        assertEquals(EXPECTED_DOMAINS.size, rules.size)
        assertEquals(EXPECTED_DOMAINS, rules.map(ParsedRule::domain).toSet())
        assertFalse(rules.any { it.path != "." })
    }

    private fun parseRules(@XmlRes resourceId: Int): ParsedDocument {
        val parser = context.resources.getXml(resourceId)
        var root: String? = null
        var section: String? = null
        val sections = linkedSetOf<String>()
        val includes = mutableListOf<ParsedRule>()
        val excludes = mutableListOf<ParsedRule>()
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        if (root == null) {
                            root = name
                            if (name == "full-backup-content") {
                                section = name
                                sections += name
                            }
                        } else if (name == "cloud-backup" || name == "device-transfer") {
                            section = name
                            sections += name
                        } else if (name == "include" || name == "exclude") {
                            val rule = ParsedRule(
                                section = requireNotNull(section),
                                domain = requireNotNull(parser.getAttributeValue(null, "domain")),
                                path = requireNotNull(parser.getAttributeValue(null, "path")),
                            )
                            if (name == "include") includes += rule else excludes += rule
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "cloud-backup" || parser.name == "device-transfer") {
                            section = null
                        }
                    }
                }
                parser.next()
            }
        } finally {
            parser.close()
        }
        return ParsedDocument(requireNotNull(root), sections, includes, excludes)
    }

    private data class ParsedDocument(
        val root: String,
        val sections: Set<String>,
        val includes: List<ParsedRule>,
        val excludes: List<ParsedRule>,
    )

    private data class ParsedRule(
        val section: String,
        val domain: String,
        val path: String,
    )

    private companion object {
        val EXPECTED_DOMAINS = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
    }
}
