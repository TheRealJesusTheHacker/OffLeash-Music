package dev.cyberdogs.offleash.playback

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import dev.cyberdogs.offleash.data.StreamFetchShape
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

private val BOUNDED_RANGE_PATTERN = Regex("""^bytes=(\d+)-(\d+)$""")

/**
 * Pure helper: the (offset, window) pair a bounded Range header
 * names — `bytes=1048576-1310719` is offset 1048576 with a 262144
 * window. Returns null for "none", open-ended, or unparseable
 * shapes (post-interceptor stream loads are always bounded, so a
 * null here itself names a shape worth seeing in diagnostics).
 */
fun rangeOffsetAndWindow(range: String): Pair<Long, Long>? {
    val match = BOUNDED_RANGE_PATTERN.matchEntire(range.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (end < start) return null
    return start to (end - start + 1)
}

/**
 * Pure formatter: one stream load's diagnostics detail — its
 * per-attempt sequence number, status, host, offset and window
 * (parsed out of the sent Range), the Range itself, and the UA /
 * Accept-Encoding classes. Round 3: EVERY load of an attempt gets
 * one of these lines (old on-device logs showed four player loads
 * per attempt; all of them must be readable, not only the first).
 */
fun streamLoadLine(index: Int, status: Int, host: String, shape: StreamFetchShape): String {
    val offsetWindow = rangeOffsetAndWindow(shape.range)
    val offset = offsetWindow?.first?.toString() ?: "none"
    val window = offsetWindow?.second?.toString() ?: "none"
    return "#$index HTTP $status host=$host offset=$offset window=$window " +
        "range=${shape.range} ua=${shape.userAgent} ae=${shape.acceptEncoding}"
}

/**
 * Pure formatter: one ladder probe window's diagnostics detail —
 * which pair and context tried which window, the exact Range that
 * window produces from offset 0, and what the gate answered (an
 * HTTP status, or the probe's transport-failure class).
 */
fun probeWindowLine(
    pair: Int,
    pairs: Int,
    contextName: String,
    windowBytes: Long,
    status: Int?,
    exceptionClass: String?,
): String {
    val answer = when {
        status != null -> "HTTP $status"
        exceptionClass != null -> "probe failed ($exceptionClass)"
        else -> "probe failed"
    }
    return "pair $pair/$pairs context $contextName window=$windowBytes " +
        "range=bytes=0-${windowBytes - 1} $answer"
}

/**
 * Pure formatter: the winning-window line recorded when a resolved
 * stream is handed to the player — the window its loads of that
 * stream will be shaped with, and the Range that window produces
 * from offset 0.
 */
fun winningWindowLine(windowBytes: Long): String =
    "winner window=$windowBytes range=bytes=0-${windowBytes - 1} " +
        "— player loads of this stream use this window"

/** Label for the tracked PlaybackService lifecycle flag. */
fun serviceRunningLabel(running: Boolean?): String = when (running) {
    true -> "running"
    false -> "not running"
    null -> "unknown"
}

/** Label for battery-optimization state. */
fun batteryStatusLabel(isIgnoringBatteryOptimizations: Boolean): String =
    if (isIgnoringBatteryOptimizations) {
        "unrestricted"
    } else {
        "restricted (battery optimization on)"
    }

/** True when the app is exempt from battery optimizations, false otherwise. */
fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
    val pm = context.getSystemService(PowerManager::class.java)
    pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
} catch (e: Exception) {
    false
}

/** Battery line for the diagnostics screen. */
fun batteryStatusText(context: Context): String =
    batteryStatusLabel(isIgnoringBatteryOptimizations(context))

/** Service-running line, from the recorder's tracked lifecycle flag. */
fun serviceRunningText(diagnostics: PlaybackDiagnostics): String =
    serviceRunningLabel(diagnostics.serviceRunning)

/**
 * Foreground line: whether our PlaybackService is currently reported as a
 * foreground service. Own-app services are visible to this API; any
 * failure reads as "unknown" rather than guessing.
 */
fun foregroundStatusText(context: Context): String = try {
    val am = context.getSystemService(ActivityManager::class.java)
    val services = am?.getRunningServices(Int.MAX_VALUE) ?: return "unknown"
    val ours = services.firstOrNull {
        it.service.className == PlaybackService::class.java.name
    } ?: return "no"
    if (ours.foreground) "yes" else "no"
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

    /** How many stream loads this attempt has recorded so far (round 3). */
    @Volatile
    var streamLoadCount: Int = 0
        private set

    @Volatile
    var playerError: String? = null
        private set

    @Volatile
    var networkSummary: String? = null
        private set

    /** Tracked PlaybackService lifecycle: true after onCreate, false after onDestroy, null if never recorded. */
    @Volatile
    var serviceRunning: Boolean? = null
        private set

    fun recordServiceRunning(running: Boolean) {
        serviceRunning = running
        log("service", if (running) "PlaybackService running" else "PlaybackService stopped")
    }

    fun startAttempt(label: String) {
        synchronized(lock) {
            lines.clear()
            resolverOutcome = null
            chosenFormat = null
            streamHost = null
            embeddedIpFamily = null
            firstFetchStatus = null
            streamLoadCount = 0
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

    /**
     * One road-switching attempt, as a single log line
     * (`road: account refused (PlayerRefused)`,
     * `road: anonymous probe 2/3 HTTP 403`). The caller composes the
     * outcome string; this recorder stays dumb. By construction the
     * outcome carries only road names, statuses and exception class
     * names — never URLs, cookies or any credential material.
     */
    fun recordRoadAttempt(road: String, outcome: String) {
        log("road", "$road $outcome")
    }

    fun recordChosenFormat(mimeType: String, bitrate: Int, url: String) {
        val host = streamHost(url)
        val family = embeddedIpFamily(url)
        chosenFormat = "$mimeType, $bitrate bps"
        streamHost = host
        embeddedIpFamily = family
        log("format", "$mimeType bitrate=$bitrate host=$host embedded-ip=$family")
    }

    /**
     * One googlevideo fetch's answer, with the exact request shape
     * that was sent for it (Range bounds, UA class, Accept-Encoding)
     * — so a probe/player divergence is visible in a single
     * diagnostics screenshot instead of needing a second hunt
     * (2026-10-24 regression: probe 206 vs player 403 on one URL).
     */
    fun recordFirstFetch(status: Int, host: String, shape: StreamFetchShape) {
        firstFetchStatus = status
        log(
            "first-fetch",
            "HTTP $status host=$host range=${shape.range} " +
                "ua=${shape.userAgent} ae=${shape.acceptEncoding}",
        )
    }

    /**
     * EVERY googlevideo load's answer (round 3), numbered per
     * attempt: the shared stack's interceptor callback routes here,
     * so probe fetches, the player's first fetch, its seek/segment
     * loads, and any other stream load through the shared client
     * each land as their own `load:` line carrying offset + window
     * + status — the four-loads-per-attempt pattern from the old
     * on-device logs is fully readable now. The FIRST load of an
     * attempt additionally records the legacy `first-fetch` line
     * and [firstFetchStatus], exactly as before.
     */
    fun recordStreamLoad(status: Int, host: String, shape: StreamFetchShape) {
        val index = synchronized(lock) {
            streamLoadCount += 1
            streamLoadCount
        }
        if (index == 1) {
            recordFirstFetch(status, host, shape)
        }
        log("load", streamLoadLine(index, status, host, shape))
    }

    /**
     * One ladder probe window tried (round 3): every window of
     * every pair lands as its own `probe-window:` line, so the
     * descent 1 MiB → 256 KiB → 64 KiB → 2 KiB (and where it
     * stopped) is readable from one screenshot.
     */
    fun recordProbeWindow(
        pair: Int,
        pairs: Int,
        contextName: String,
        windowBytes: Long,
        status: Int?,
        exceptionClass: String?,
    ) {
        log(
            "probe-window",
            probeWindowLine(pair, pairs, contextName, windowBytes, status, exceptionClass),
        )
    }

    /**
     * The window that won for the stream being handed to the
     * player (round 3) — recorded at the hand-off, so the
     * diagnostics show which window the player's loads carry.
     */
    fun recordWinningWindow(windowBytes: Long) {
        log("window", winningWindowLine(windowBytes))
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
        // Bounded ring (bug sweep round 2): this recorder lives for
        // the whole process and the shared stack's interceptor logs
        // EVERY stream load — a long, seek-heavy session (or a track
        // that keeps re-resolving) appended lines without limit, and
        // every append copied the whole list into the snapshot, so
        // memory and per-load cost grew for the process lifetime.
        // The diagnostics screen reads the LAST attempt; the newest
        // [MAX_LOG_LINES] lines are that attempt's story, and the
        // oldest are dropped as new ones land.
        while (lines.size > MAX_LOG_LINES) lines.removeAt(0)
        _snapshot.value = lines.toList()
    }

    companion object {
        /** Most stage lines retained; see [addLocked]. */
        const val MAX_LOG_LINES: Int = 200
    }
}
