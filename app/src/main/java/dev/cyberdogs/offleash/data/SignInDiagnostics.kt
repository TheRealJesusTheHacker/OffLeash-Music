package dev.cyberdogs.offleash.data

import dev.cyberdogs.offleash.core.auth.SESSION_COOKIE_NAMES
import dev.cyberdogs.offleash.core.auth.SessionCookies
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stage recorder for the LAST sign-in confirmation attempt.
 *
 * Exists because of the 2026-10-24 bounce: a real, healthy sign-in
 * was refused and nothing anywhere recorded WHICH check had failed —
 * the landing, the cookie-subset stage, or the identity endpoint —
 * so the failure was undiagnosable from the phone. This recorder
 * names the stage and the outcome of every attempt in the
 * confirmation window, in the same spirit as PlaybackDiagnostics.
 *
 * Privacy contract, same as the rest of the session layer: cookie
 * NAMES are recorded (names are not credentials); cookie VALUES,
 * headers, and identity details beyond the confirmed display name
 * are never recorded.
 */
class SignInDiagnostics {

    private val lock = Any()
    private val lines = mutableListOf<String>()

    private val _snapshot = MutableStateFlow<List<String>>(emptyList())
    val snapshot: StateFlow<List<String>> = _snapshot.asStateFlow()

    fun startAttempt() {
        synchronized(lock) {
            lines.clear()
            addLocked("sign-in", "confirmation started")
        }
    }

    fun log(stage: String, detail: String) {
        synchronized(lock) { addLocked(stage, detail) }
    }

    /**
     * The cookies stage for one attempt: how many of the named
     * session cookies the capture carried (names listed — they
     * carry no credential material), and which essential groups the
     * plausibility rule is missing, if any.
     */
    fun recordCookieStage(cookies: Map<String, String>) {
        val present = SESSION_COOKIE_NAMES.filter { name -> cookies[name]?.isNotBlank() == true }
        // The wording of the gaps lives in core (one source of
        // truth for every surface that names them).
        val missing = SessionCookies.missingSessionPieces(cookies)
        val detail = buildString {
            append("${present.size} of ${SESSION_COOKIE_NAMES.size} session cookies present")
            if (present.isNotEmpty()) {
                append(": ")
                append(present.joinToString(", "))
            }
            if (missing.isNotEmpty()) {
                append(" — missing ")
                append(missing.joinToString(", "))
            }
        }
        log("cookies", detail)
    }

    fun lines(): List<String> = synchronized(lock) { lines.toList() }

    fun renderText(): String {
        val recorded = lines()
        return if (recorded.isEmpty()) {
            "No sign-in attempt recorded yet."
        } else {
            recorded.joinToString("\n")
        }
    }

    private fun addLocked(stage: String, detail: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        lines.add("$stamp  $stage: $detail")
        // Bounded ring (bug sweep round 2): [startAttempt] clears
        // per attempt, but stages are also logged OUTSIDE attempts —
        // the poll loop logs every poll (a 30-minute grant at a 5s
        // cadence is ~360 lines), and token refresh / sign-out log
        // for the whole process lifetime. Unbounded, the list (and
        // the full-list copy into the snapshot on every add) grew
        // until the process died. The panel reads the LAST attempt;
        // keep the newest [MAX_LOG_LINES] lines and drop the rest.
        while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        _snapshot.value = lines.toList()
    }

    companion object {
        /** Most stage lines retained; see [addLocked]. */
        const val MAX_LOG_LINES: Int = 200
    }
}
