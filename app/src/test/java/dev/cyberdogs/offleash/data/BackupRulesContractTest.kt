package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun legacySessionFileIsGoneAndNamedOnlyByTheUpgradeCleanup() {
        // Re-based 0.9.0 (owner order, 2026-11-04): the sealed
        // session store is deleted with the sign-in product. What
        // must stay pinned is the other half of the old contract —
        // a pre-0.9.0 build's session.bin lived in the no-backup
        // directory, and the account-free build still names it,
        // in exactly one place: the startup cleanup that destroys
        // it (AppContainer.LEGACY_ACCOUNT_FILES).
        val mainDir = locateMainDir()
        val store = File(
            mainDir,
            "java/dev/cyberdogs/offleash/data/SessionStore.kt",
        )
        assertFalse("SessionStore.kt must not exist in 0.9.0", store.exists())
        val container = File(
            mainDir,
            "java/dev/cyberdogs/offleash/OffleashApp.kt",
        ).readText()
        assertTrue(
            "the upgrade cleanup must name the legacy session file",
            container.contains("\"session.bin\""),
        )
        assertTrue(
            "the cleanup must run over the no-backup directory",
            container.contains("noBackupFilesDir"),
        )
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
