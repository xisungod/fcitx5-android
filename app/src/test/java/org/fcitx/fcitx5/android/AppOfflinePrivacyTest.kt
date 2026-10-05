/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AppOfflinePrivacyTest {
    @Test fun optionalModelDownloadCanUseNetworkAndLocalDictationRequiresMicrophone() {
        val app = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val permissions = app.packageManager.getPackageInfo(app.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue("The optional model needs an explicit package download", Manifest.permission.INTERNET in permissions)
        assertTrue("On-device dictation requires the explicit microphone permission", Manifest.permission.RECORD_AUDIO in permissions)
    }

    @Test fun mergedManifestDoesNotQuerySpeechRecognitionServices() {
        // Use the same merged manifest supplied by AGP to Robolectric, not the source manifest.
        val configuration = Properties()
        requireNotNull(javaClass.classLoader?.getResourceAsStream("com/android/tools/test_config.properties"))
            .use { configuration.load(it) }
        val manifest = File(requireNotNull(configuration.getProperty("android_merged_manifest")))
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(manifest)
        val queries = document.getElementsByTagName("queries")
        for (i in 0 until queries.length) {
            val actions = (queries.item(i) as Element).getElementsByTagName("action")
            for (j in 0 until actions.length) {
                val name = (actions.item(j) as Element)
                    .getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                assertNotEquals("Local dictation must not discover system or network speech services",
                    "android.speech.RecognitionService", name)
            }
        }
    }

    @Test fun mergedApplicationDisablesAutomaticBackupOfInputData() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun cloudBackupRulesExcludeAllCredentialAndDeviceProtectedInputStorage() {
        val app = RuntimeEnvironment.getApplication()
        val excluded = mutableMapOf<String, String>()
        app.resources.getXml(R.xml.data_extraction_rules).use { parser ->
            var inCloudBackup = false
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "cloud-backup" -> inCloudBackup = true
                        "exclude" -> if (inCloudBackup) {
                            excluded[parser.getAttributeValue(null, "domain")] = parser.getAttributeValue(null, "path")
                        }
                    }
                } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "cloud-backup") {
                    inCloudBackup = false
                }
                parser.next()
            }
        }
        for (domain in listOf("root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref")) {
            assertEquals("Cloud backup must exclude the complete $domain domain", ".", excluded[domain])
        }
    }
}
