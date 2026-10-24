package dev.cyberdogs.offleash.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet6Address
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure helper: the family of the address embedded in a stream URL's
 * `ip=` query value — "IPv6" when it contains ':', "IPv4" when it is a
 * plain dotted value, and "none" when the URL carries no `ip=` value.
 * Only the family is ever recorded; the address itself is never stored
 * or shown.
 */
fun embeddedIpFamily(url: String): String {
    val ip = queryParam(url, "ip") ?: return "none"
    if (ip.isBlank()) return "none"
    return if (ip.contains(':')) "IPv6" else "IPv4"
}

/** Pure helper: the host of a URL, or "unknown" when it cannot be read. */
fun streamHost(url: String): String =
    try {
        URI(url).host ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }

private fun queryParam(url: String, name: String): String? {
    val q = url.substringAfter('?', "")
    if (q.isEmpty()) return null
    for (part in q.split('&')) {
        val eq = part.indexOf('=')
        if (eq <= 0) continue
        if (part.substring(0, eq) == name) {
            return java.net.URLDecoder.decode(part.substring(eq + 1), "UTF-8")
        }
    }
    return null
}

/**
 * Thread-safe recorder for the LAST playback attempt.
 *
 * Holds a short, timestamped stage log plus the named fields the
 * diagnostics screen shows: resolver outcome, chosen format, stream
 * host, embedded-ip family, first-fetch HTTP status, player error, and
 * a coarse network summary (transport + whether a global IPv6 address
 * exists — booleans only, no addresses, no identifiers).
 *
 * Nothing here leaves the device; the screen exists so the owner can
 * read it or screenshot it.
 */
class PlaybackDiagnostics {

    private val lock = Any()
    private val lines = mutableListOf<String>()

    private val _snapshot = MutableStateFlow<List<String>>(emptyList())
    val snapshot: StateFlow<List<String>> = _snapshot.asStateFlow()

    @Volatile
    var resolverOutcome: String? = null
        private set

    @Volatile
    var chosenFormat: String? = null
        private set

    @Volatile
    var streamHost: String? = null
        private set

    @Volatile
    var embeddedIpFamily: String? = null
        private set

    @Volatile
    var firstFetchStatus: Int? = null
        private set

    @Volatile
    var playerError: String? = null
        private set

    @Volatile
    var networkSummary: String? = null
        private set

    fun startAttempt(label: String) {
        synchronized(lock) {
            lines.clear()
            resolverOutcome = null
            chosenFormat = null
            streamHost = null
            embeddedIpFamily = null
            firstFetchStatus = null
            playerError = null
            addLocked("attempt", label)
        }
    }

    fun log(stage: String, detail: String) {
        synchronized(lock) { addLocked(stage, detail) }
    }

    fun recordResolverOutcome(
        contextClientName: String,
        contextClientVersion: String,
        exceptionClass: String?,
    ) {
        val outcome = if (exceptionClass == null) {
            "OK (context $contextClientName $contextClientVersion)"
        } else {
            "$exceptionClass (context $contextClientName $contextClientVersion)"
        }
        resolverOutcome = outcome
        log("resolver", outcome)
    }

    fun recordChosenFormat(mimeType: String, bitrate: Int, url: String) {
        val host = streamHost(url)
        val family = embeddedIpFamily(url)
        chosenFormat = "$mimeType, $bitrate bps"
        streamHost = host
        embeddedIpFamily = family
        log("format", "$mimeType bitrate=$bitrate host=$host embedded-ip=$family")
    }

    fun recordFirstFetch(status: Int, host: String) {
        firstFetchStatus = status
        log("first-fetch", "HTTP $status host=$host")
    }

    fun recordPlayerError(errorCode: Int, exceptionClass: String, rootCauseClasses: List<String>) {
        val detail = "errorCode=$errorCode exception=$exceptionClass " +
            "causes=${rootCauseClasses.joinToString(" -> ")}"
        playerError = detail
        log("player-error", detail)
    }

    fun recordNetworkSummary(summary: String) {
        networkSummary = summary
        log("network", summary)
    }

    fun recordReady() {
        log("player", "STATE_READY")
    }

    /** Coarse network summary from ConnectivityManager — booleans only. */
    fun recordNetworkFrom(context: Context) {
        val summary = try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val network = cm?.activeNetwork
            val caps = network?.let { cm?.getNetworkCapabilities(it) }
            val transport = when {
                caps == null -> "unknown"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "other"
            }
            val link = network?.let { cm?.getLinkProperties(it) }
            val hasGlobalIpv6 = link?.linkAddresses?.any { la ->
                val a = la.address
                a is Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress
            } ?: false
            "transport=$transport globalIPv6=$hasGlobalIpv6"
        } catch (e: Exception) {
            "unavailable (${e.javaClass.simpleName})"
        }
        recordNetworkSummary(summary)
    }

    fun lines(): List<String> = synchronized(lock) { lines.toList() }

    fun renderText(): String {
        val recorded = lines()
        return if (recorded.isEmpty()) {
            "No playback attempt recorded yet. Play a track, or tap Test playback."
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
