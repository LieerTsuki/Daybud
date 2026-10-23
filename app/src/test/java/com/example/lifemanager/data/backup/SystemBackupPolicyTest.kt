package com.example.lifemanager.data.backup

import android.app.Application
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class SystemBackupPolicyTest {
    @Test fun personalRecordsDoNotOptIntoSystemCloudBackup() {
        val application = RuntimeEnvironment.getApplication()
        assertEquals(0, application.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun bothSystemBackupFormatsExcludeEveryPersistentDataDomain() {
        val resources = RuntimeEnvironment.getApplication().resources
        val expected = setOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")
        for ((name, groups) in mapOf("backup_rules" to setOf("full-backup-content"),
            "data_extraction_rules" to setOf("cloud-backup", "device-transfer"))) {
            val id = resources.getIdentifier(name, "xml", "com.example.lifemanager")
            check(id != 0) { "Missing system backup rules: $name" }
            resources.getXml(id).use { parser ->
                val exclusions = mutableMapOf<String, MutableSet<String>>()
                var group: String? = null
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        if (parser.name in groups) {
                            group = parser.name
                            exclusions.getOrPut(group) { mutableSetOf() }
                        } else if (parser.name == "exclude") {
                            assertEquals(".", parser.getAttributeValue(null, "path"))
                            exclusions.getValue(checkNotNull(group)).add(parser.getAttributeValue(null, "domain"))
                        }
                    } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == group) group = null
                    parser.next()
                }
                assertEquals(groups, exclusions.keys)
                exclusions.values.forEach { assertEquals(expected, it) }
            }
        }
    }
}
