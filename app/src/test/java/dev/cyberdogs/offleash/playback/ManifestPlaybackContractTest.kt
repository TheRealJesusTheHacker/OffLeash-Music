package dev.cyberdogs.offleash.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Contract test over the shipped AndroidManifest.xml: the background
 * playback declarations the runtime depends on must stay present.
 * Plain JUnit 4 + JDK XML parsing, no Android runtime needed.
 */
class ManifestPlaybackContractTest {

    private fun locateManifest(): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile
        var hops = 0
        while (dir != null && hops < 8) {
            candidates += File(dir, "src/main/AndroidManifest.xml")
            candidates += File(dir, "app/src/main/AndroidManifest.xml")
            dir = dir.parentFile
            hops++
        }
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "AndroidManifest.xml not found from working dir " +
                    File(".").absolutePath + " (tried: " +
                    candidates.joinToString { it.path } + ")",
            )
    }

    private fun parseManifest(): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        return factory.newDocumentBuilder().parse(locateManifest())
    }

    private fun androidAttr(element: Element, localName: String): String {
        val ns = element.getAttributeNS(ANDROID_NS, localName)
        if (ns.isNotEmpty()) return ns
        return element.getAttribute("android:$localName")
    }

    @Test
    fun requiredPermissionsAreDeclared() {
        val doc = parseManifest()
        val declared = mutableSetOf<String>()
        val nodes = doc.getElementsByTagName("uses-permission")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            declared += androidAttr(el, "name")
        }
        val required = listOf(
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.WAKE_LOCK",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.POST_NOTIFICATIONS",
        )
        for (permission in required) {
            assertTrue("missing uses-permission: $permission", permission in declared)
        }
    }

    @Test
    fun playbackServiceContractIsDeclared() {
        val doc = parseManifest()
        var service: Element? = null
        val nodes = doc.getElementsByTagName("service")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (androidAttr(el, "name").endsWith(".playback.PlaybackService")) {
                service = el
                break
            }
        }
        assertTrue("PlaybackService <service> not found", service != null)
        val svc = service!!
        assertTrue(
            "foregroundServiceType must contain mediaPlayback",
            androidAttr(svc, "foregroundServiceType").contains("mediaPlayback"),
        )
        assertEquals("true", androidAttr(svc, "exported"))

        val actions = mutableSetOf<String>()
        val filters = svc.getElementsByTagName("intent-filter")
        for (i in 0 until filters.length) {
            val filter = filters.item(i) as Element
            val actionNodes = filter.getElementsByTagName("action")
            for (j in 0 until actionNodes.length) {
                actions += androidAttr(actionNodes.item(j) as Element, "name")
            }
        }
        assertTrue(
            "missing MediaSessionService action",
            "androidx.media.session.MediaSessionService" in actions,
        )
        assertTrue(
            "missing MediaButtonReceiver action",
            "android.media.session.MediaButtonReceiver" in actions,
        )
    }

    @Test
    fun batteryCardDecisionMatrix() {
        assertTrue(shouldShowBatteryCard(isRestricted = true, dismissed = false))
        assertFalse(shouldShowBatteryCard(isRestricted = true, dismissed = true))
        assertFalse(shouldShowBatteryCard(isRestricted = false, dismissed = false))
        assertFalse(shouldShowBatteryCard(isRestricted = false, dismissed = true))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
