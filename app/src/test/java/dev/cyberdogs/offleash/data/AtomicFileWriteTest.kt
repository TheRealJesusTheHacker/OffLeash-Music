package dev.cyberdogs.offleash.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Sweep #5 crash-safety contract for whole-file store writes:
 * contents land completely, replacing prior contents completely,
 * and no temp sibling is left behind for a store to trip over.
 * (The torn-write scenario itself — process death between the
 * temp write and the rename — is by construction: a reader sees
 * the old target untouched in that window, which the first two
 * tests pin from both sides.)
 */
class AtomicFileWriteTest {

    private fun tempTarget(): File {
        val dir = Files.createTempDirectory("offleash-atomic").toFile()
        return File(dir, "store.json")
    }

    @Test
    fun writeLandsCompleteContentsAndLeavesNoTemp() {
        val target = tempTarget()
        writeTextAtomically(target, """{"playlists": []}""")
        assertEquals("""{"playlists": []}""", target.readText())
        assertFalse("no .tmp sibling may remain", File(target.parentFile, target.name + ".tmp").exists())
    }

    @Test
    fun overwriteReplacesPriorContentsCompletely() {
        val target = tempTarget()
        writeTextAtomically(target, "a much longer previous payload that must not survive in part")
        writeTextAtomically(target, "short")
        assertEquals("short", target.readText())
        assertEquals(5L, target.length())
    }

    @Test
    fun bytesFormLandsExactBytes() {
        val target = tempTarget()
        val bytes = byteArrayOf(0, 1, 2, 127, -128, -1)
        writeFileAtomically(target, bytes)
        assertTrue(bytes.contentEquals(target.readBytes()))
    }

    @Test
    fun writeCreatesMissingParentDirectories() {
        val dir = Files.createTempDirectory("offleash-atomic").toFile()
        val target = File(File(dir, "nested/deeper"), "store.json")
        writeTextAtomically(target, "payload")
        assertEquals("payload", target.readText())
    }
}
