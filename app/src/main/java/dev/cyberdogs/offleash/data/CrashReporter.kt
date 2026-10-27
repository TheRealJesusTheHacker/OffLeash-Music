package dev.cyberdogs.offleash.data

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-crash capture for the sign-in panel (2026-10-27 crash hunt).
 *
 * Until this existed the app kept NO persisted record of its own
 * crashes: when 0.6.3 died ~1s into the owner's first real sign-in
 * confirmation, the process vanished and nothing anywhere said why —
 * the hunt had to reconstruct the corridor from code alone. This
 * reporter is the deliberate opposite: a default uncaught-exception
 * handler that, on its way down, writes the dying trace to a small
 * local file, so the next launch's sign-in screen can show exactly
 * what stopped the app.
 *
 * Local-only by construction: the trace is written to the app's
 * no-backup directory (the same platform-excluded home as the
 * session file), is never transmitted anywhere, and needs no new
 * permission. Traces carry class names, messages, and stack frames —
 * never cookie values or account data, which this codebase never
 * puts in exception messages in the first place.
 *
 * The handler always delegates to the previously installed default
 * handler after writing, so the platform's own crash handling (and
 * the process's death) proceeds exactly as it would have without us.
 */
object CrashReporter {

    /** The trace file's name, pinned so tests can assert it. */
    const val CRASH_FILE_NAME: String = "last_crash.txt"

    /** Traces are evidence, not archives: keep the file small. */
    private const val MAX_TRACE_CHARS = 12_000

    /** Where the trace lives: the app's no-backup directory. */
    fun fileIn(context: Context): File = File(context.noBackupFilesDir, CRASH_FILE_NAME)

    /**
     * Installs the recording handler as the default, ahead of the
     * platform's. Called once, first thing in Application.onCreate.
     * The file is resolved eagerly so the handler itself never has
     * to touch a Context while the process is dying.
     */
    fun install(context: Context) {
        val file = fileIn(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = formatTrace(thread.name, throwable, System.currentTimeMillis())
                file.outputStream().use { out ->
                    out.write(trace.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (ignored: Throwable) {
                // A crash recorder must never become the crash.
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }
    }

    /** The recorded trace, or null when no crash has been recorded (or it was cleared). */
    fun read(file: File): String? = try {
        file.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** Removes the recorded trace (the panel's dismiss action). */
    fun clear(file: File) {
        try {
            file.delete()
        } catch (e: Exception) {
            // Best effort — a stale trace is a display issue, never a failure.
        }
    }

    /**
     * The trace text: when, on which thread, and the full stack.
     * Pure, so the format is unit-testable off-device.
     */
    fun formatTrace(threadName: String, throwable: Throwable, epochMs: Long): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
        val writer = StringWriter()
        PrintWriter(writer).use { pw ->
            pw.println("The app stopped unexpectedly at $stamp (thread: $threadName).")
            throwable.printStackTrace(pw)
        }
        val text = writer.toString().trimEnd()
        return if (text.length <= MAX_TRACE_CHARS) {
            text
        } else {
            text.substring(0, MAX_TRACE_CHARS) + "\n… (trace truncated)"
        }
    }
}
