package dev.cyberdogs.offleash.data

import java.io.File

/**
 * Crash-safe replacement of a small file's whole contents
 * (Fleet Sweep #5, 2026-10-24).
 *
 * Every store in this package persists by replacing one small file
 * (playlists/recents/downloads JSON, the sealed session blob), and
 * every store reads a corrupt file as "empty". The old writes went
 * straight at the target file: a process death (Android kills, a
 * flat battery) mid-write left a torn file behind, and the next
 * launch then read the whole store — every playlist, every
 * download record — as empty. The bytes were lost to a timing
 * window of milliseconds.
 *
 * This write goes to a sibling temp file first and renames it over
 * the target. A rename within one directory is atomic on the
 * filesystems Android uses, so a reader (including a post-crash
 * next launch) sees either the whole old contents or the whole new
 * contents, never a torn mix. If the rename cannot be made to work
 * (an exotic filesystem), it falls back to the old direct write —
 * a completed write is still better than none, and the fallback is
 * the pre-fix behavior, never worse.
 */
fun writeFileAtomically(file: File, bytes: ByteArray) {
    val parent = file.parentFile
    parent?.mkdirs()
    val tmp = File(parent, file.name + ".tmp")
    tmp.writeBytes(bytes)
    if (renameReplacing(tmp, file)) return
    // Fallback: direct write, exactly the pre-fix behavior.
    try {
        file.writeBytes(bytes)
    } finally {
        deleteQuietly(tmp)
    }
}

/** [writeFileAtomically] for text payloads (UTF-8, File.writeText's charset). */
fun writeTextAtomically(file: File, text: String) {
    writeFileAtomically(file, text.toByteArray(Charsets.UTF_8))
}

private fun renameReplacing(from: File, to: File): Boolean {
    if (from.renameTo(to)) return true
    // Some filesystems refuse to rename over an existing target;
    // retry once after removing the target. (This path is not the
    // Android norm — POSIX rename replaces — it exists so the
    // helper never strands a fully-written temp file over a quirk.)
    return try {
        if (to.exists()) to.delete()
        from.renameTo(to)
    } catch (e: Exception) {
        false
    }
}

private fun deleteQuietly(file: File) {
    try {
        if (file.exists()) file.delete()
    } catch (e: Exception) {
        // A leftover .tmp sibling is inert: no store ever reads it.
    }
}
