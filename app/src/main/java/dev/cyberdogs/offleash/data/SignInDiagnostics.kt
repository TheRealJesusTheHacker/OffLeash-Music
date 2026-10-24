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
        val missing = mutableListOf<String>()
        if (SessionCookies.hashSource(cookies) == null) {
            missing += "hash source (SAPISID or __Secure-3PAPISID)"
        }
        if (cookies["SID"]?.isNotBlank() != true && cookies["LOGIN_INFO"]?.isNotBlank() != true) {
            missing += "session id (SID or LOGIN_INFO)"
        }
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
        _snapshot.value = lines.toList()
    }
}
