package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Contract test over the backup posture: the manifest must point at
 * both rule files, both rule files must exclude the no-backup
 * directory, and the session store's file must physically live in
 * noBackupFilesDir. This is the layered close of the API 26–30
 * backup hole found in the Phase 2 research (legacy rules there
 * included the credential store in Auto Backup).
 * Plain JUnit 4 + JDK XML parsing, following ManifestPlaybackContractTest.
 */
class BackupRulesContractTest {

    private fun locateMainDir(): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main")
            candidates += File(dir, "app/src/main")
            dir = dir.parentFile
            hops++
        }
        return candidates.firstOrNull { File(it, "AndroidManifest.xml").isFile }
            ?: throw AssertionError(
                "src/main not found from working dir " + File(".").absolutePath +
                    " (tried: " + candidates.joinToString { it.path } + ")",
            )
    }

    private fun parseXml(file: File): Document {
        assertTrue("expected file to exist: ${file.path}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder().parse(file)
    }

    private fun androidAttr(element: Element, localName: String): String {
        val ns = element.getAttributeNS(ANDROID_NS, localName)
        if (ns.isNotEmpty()) return ns
        return element.getAttribute("android:$localName")
    }

    private fun excludesOf(section: Element): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = section.getElementsByTagName("exclude")
        for (i in 0 until nodes.length) {
            out += nodes.item(i) as Element
        }
        return out
    }

    private fun assertExcludesNoBackup(section: Element, where: String) {
        val matches = excludesOf(section).filter { el ->
            el.getAttribute("domain") == "file" && el.getAttribute("path") == "no_backup/"
        }
        assertTrue(
            "$where must exclude domain=file path=no_backup/ " +
                "(found: " + excludesOf(section).map {
                    it.getAttribute("domain") + ":" + it.getAttribute("path")
                } + ")",
            matches.isNotEmpty(),
        )
    }

    @Test
    fun manifestReferencesBothRuleFiles() {
        val mainDir = locateMainDir()
        val doc = parseXml(File(mainDir, "AndroidManifest.xml"))
        val apps = doc.getElementsByTagName("application")
        assertTrue("manifest must have an <application>", apps.length > 0)
        val app = apps.item(0) as Element
        assertEquals("@xml/backup_rules", androidAttr(app, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", androidAttr(app, "dataExtractionRules"))
    }

    @Test
    fun dataExtractionRulesExcludeNoBackupFromCloudAndTransfer() {
        val mainDir = locateMainDir()
        val doc = parseXml(File(mainDir, "res/xml/data_extraction_rules.xml"))
        for (sectionName in listOf("cloud-backup", "device-transfer")) {
            val sections = doc.getElementsByTagName(sectionName)
            assertTrue("data_extraction_rules must have a <$sectionName> section", sections.length > 0)
            assertExcludesNoBackup(sections.item(0) as Element, "data_extraction_rules/$sectionName")
        }
    }

    @Test
    fun legacyBackupRulesExcludeNoBackup() {
        val mainDir = locateMainDir()
        val doc = parseXml(File(mainDir, "res/xml/backup_rules.xml"))
        assertEquals("full-backup-content", doc.documentElement.tagName)
        assertExcludesNoBackup(doc.documentElement, "backup_rules")
    }

    @Test
    fun sessionStoreFileLivesInTheNoBackupDirectory() {
        val mainDir = locateMainDir()
        val source = File(
            mainDir,
            "java/dev/cyberdogs/offleash/data/SessionStore.kt",
        )
        assertTrue("SessionStore.kt not found at ${source.path}", source.isFile)
        val text = source.readText()
        assertTrue(
            "the session file must be created under noBackupFilesDir",
            text.contains("noBackupFilesDir"),
        )
        assertTrue(
            "the session file must be named by the pinned constant",
            text.contains("SESSION_FILE_NAME"),
        )
        assertTrue(
            "the session file name must stay session.bin",
            text.contains("\"session.bin\""),
        )
        assertEquals("session.bin", SESSION_FILE_NAME)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
