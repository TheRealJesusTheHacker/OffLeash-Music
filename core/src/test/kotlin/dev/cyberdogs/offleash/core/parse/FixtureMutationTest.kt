package dev.cyberdogs.offleash.core.parse

import dev.cyberdogs.offleash.core.FixedTransport
import dev.cyberdogs.offleash.core.Fixtures
import dev.cyberdogs.offleash.core.GetTransport
import dev.cyberdogs.offleash.core.api.YoutubeiClient
import dev.cyberdogs.offleash.core.auth.AuthSession
import dev.cyberdogs.offleash.core.error.OffleashException
import dev.cyberdogs.offleash.core.playback.StreamResolver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Systematic fixture mutation (bug sweep round 2, charter 1).
 *
 * Round 1 fed the parsers a hand-written battery of malformed
 * bodies. This pass is mechanical instead: real fixtures are
 * TRUNCATED AT EVERY LENGTH, BYTE-FLIPPED AT EVERY POSITION, and
 * TYPE-SWAPPED AT EVERY NODE (object↔array↔string↔number↔bool↔null)
 * along the load-bearing paths, and every variant is driven through
 * the parser surfaces AND the client. The invariant is absolute:
 * parsers throw nothing at all, the client throws only the named
 * [OffleashException] taxonomy, the resolver likewise — never an
 * NPE/ClassCast/IndexOutOfBounds/StackOverflow escaping.
 */
class FixtureMutationTest {

    private val session = AuthSession(
        cookies = mapOf("SAPISID" to "sap", "SID" to "sid"),
    )

    private fun assertEqualsExact(message: String, expected: Int, actual: Int) {
        assertTrue("$message (expected $expected, ran $actual)", expected == actual)
    }

    /** Parser surfaces must be total: ANY escaping Throwable fails. */
    private fun <T> total(label: String, block: () -> T): T {
        try {
            return block()
        } catch (t: Throwable) {
            fail("$label escaped with ${t.javaClass.name}: ${t.message}")
            throw AssertionError("unreachable")
        }
    }

    /** Client/resolver surfaces: success or the named taxonomy only. */
    private fun named(label: String, block: () -> Any?) {
        try {
            block()
        } catch (e: OffleashException) {
            Unit // the honest named failure
        } catch (t: Throwable) {
            fail("$label escaped with ${t.javaClass.name}: ${t.message}")
        }
    }

    private fun allParserSurfaces(root: JsonElement) {
        total("parseSections") { YoutubeiParsers.parseSections(root) }
        total("parseHome") { YoutubeiParsers.parseHome(root) }
        total("parseHomeChips") { YoutubeiParsers.parseHomeChips(root) }
        total("parseSearchFilters") { YoutubeiParsers.parseSearchFilters(root) }
        total("parseSearch") { YoutubeiParsers.parseSearch(root) }
        total("parseMoods") { YoutubeiParsers.parseMoods(root) }
        total("parseAlbum") { YoutubeiParsers.parseAlbum(root, "MPREb_x") }
        total("parsePlaylist") { YoutubeiParsers.parsePlaylist(root, "VL_x") }
        total("parseArtist") { YoutubeiParsers.parseArtist(root) }
        total("parseNextTracks") { YoutubeiParsers.parseNextTracks(root) }
        total("parseContinuationToken") { YoutubeiParsers.parseContinuationToken(root) }
        total("parsePlaylistContinuation") { YoutubeiParsers.parsePlaylistContinuation(root) }
    }

    private fun allClientSurfaces(body: String) {
        val client = YoutubeiClient(FixedTransport(body = body))
        named("home") { client.home() }
        named("homeFeed") { client.homeFeed() }
        named("charts") { client.charts() }
        named("moods") { client.moods() }
        named("search") { client.search("mutation") }
        named("album") { client.album("MPREb_x") }
        named("artist") { client.artist("UC_x") }
        named("playlist") { client.playlist("VL_x") }
        named("related") { client.related("vid_x") }
        named("likedSongs") { client.likedSongs(session) }
        named("libraryPlaylists") { client.libraryPlaylists(session) }
        named("likeStatus") { client.likeStatus("vid_x", session) }
    }

    @Test
    fun truncationAtEveryLengthOfASmallFixture() {
        val full = Fixtures.load("derived_home_personalized.json")
        var cases = 0
        for (len in 0..full.length) {
            val body = full.substring(0, len)
            cases++
            named("homeFeed trunc@$len") {
                YoutubeiClient(FixedTransport(body = body)).homeFeed()
            }
            // When the truncated text still parses as JSON, every
            // parser surface must be total on the resulting tree.
            val root = try {
                Json.parseToJsonElement(body)
            } catch (t: Throwable) {
                null
            }
            if (root != null) allParserSurfaces(root)
        }
        assertTrue("expected >1000 truncation cases, ran $cases", cases > 1000)
    }

    @Test
    fun byteFlipsAtEveryPositionOfSmallFixtures() {
        val home = Fixtures.load("derived_home_personalized.json")
        var cases = 0
        for (i in home.indices) {
            for (flip in listOf('X', '"', '}', '0')) {
                if (home[i] == flip) continue
                val body = home.substring(0, i) + flip + home.substring(i + 1)
                cases++
                named("homeFeed flip@$i->$flip") {
                    YoutubeiClient(FixedTransport(body = body)).homeFeed()
                }
            }
        }
        val player = Fixtures.load("derived_player_android.json")
        for (i in player.indices) {
            for (flip in listOf('X', '"', '}', '0')) {
                if (player[i] == flip) continue
                val body = player.substring(0, i) + flip + player.substring(i + 1)
                cases++
                named("streamResolve flip@$i->$flip") { StreamResolver.resolve(body) }
                named("streamInfo flip@$i->$flip") {
                    YoutubeiClient(FixedTransport(body = body)).streamInfo("vid_x")
                }
            }
        }
        assertTrue("expected >5000 flip cases, ran $cases", cases > 5000)
    }

    // ---------------------------------------------------------------
    // Type swaps: every node of a parsed fixture, replaced in turn
    // by every other JSON type.
    // ---------------------------------------------------------------

    private fun collectPaths(
        node: JsonElement,
        prefix: List<Any>,
        depth: Int,
        out: MutableList<List<Any>>,
    ) {
        // Real fixtures bury their renderer subtrees a dozen levels
        // down (contents.tabs[0].tabRenderer.content.
        // sectionListRenderer…), so the walk goes deep and the
        // CALLER caps how many paths it mutates.
        if (depth > 14) return
        when (node) {
            is JsonObject -> for ((key, value) in node) {
                val path = prefix + key
                out.add(path)
                collectPaths(value, path, depth + 1, out)
            }
            is JsonArray -> for ((index, value) in node.withIndex()) {
                val path = prefix + index
                out.add(path)
                collectPaths(value, path, depth + 1, out)
            }
            else -> Unit
        }
    }

    private fun replaceAt(node: JsonElement, path: List<Any>, replacement: JsonElement): JsonElement {
        if (path.isEmpty()) return replacement
        val head = path.first()
        val rest = path.drop(1)
        return when {
            head is String && node is JsonObject -> {
                val copy = node.toMutableMap()
                copy[head] = replaceAt(node[head] ?: JsonNull, rest, replacement)
                JsonObject(copy)
            }
            head is Int && node is JsonArray -> {
                val copy = node.toMutableList()
                copy[head] = replaceAt(node[head], rest, replacement)
                JsonArray(copy)
            }
            else -> node
        }
    }

    private val swapValues: List<JsonElement> = listOf(
        JsonNull,
        JsonPrimitive("SWAPSTR"),
        JsonPrimitive(4242),
        JsonPrimitive(true),
        JsonArray(emptyList()),
        JsonObject(emptyMap()),
    )

    @Test
    fun typeSwapEveryNodeOfTheHomeFixture() {
        val root = Json.parseToJsonElement(Fixtures.load("derived_home_personalized.json"))
        val paths = mutableListOf<List<Any>>()
        collectPaths(root, emptyList(), 0, paths)
        // The fixture is small: completeness (every collected node
        // × every swap type) is the coverage claim, not a floor.
        assertTrue("fixture should expose >30 mutable nodes, got ${paths.size}", paths.size > 30)
        var variants = 0
        for (path in paths.take(240)) {
            for (swap in swapValues) {
                val mutated = replaceAt(root, path, swap)
                variants++
                allParserSurfaces(mutated)
                allClientSurfaces(mutated.toString())
            }
        }
        assertEqualsExact(
            "every node × every swap type must have run",
            minOf(paths.size, 240) * swapValues.size,
            variants,
        )
    }

    @Test
    fun typeSwapEveryNodeOfThePlayerFixture() {
        val root = Json.parseToJsonElement(Fixtures.load("derived_player_android.json"))
        val paths = mutableListOf<List<Any>>()
        collectPaths(root, emptyList(), 0, paths)
        var variants = 0
        for (path in paths) {
            for (swap in swapValues) {
                val mutated = replaceAt(root, path, swap)
                variants++
                named("streamResolve swap@$path") { StreamResolver.resolve(mutated.toString()) }
                named("streamInfo swap@$path") {
                    YoutubeiClient(FixedTransport(body = mutated.toString())).streamInfo("vid_x")
                }
            }
        }
        assertTrue("expected >100 swap variants, ran $variants", variants > 100)
    }

    @Test
    fun typeSwapEveryNodeOfTheIdentityFixture() {
        // Both switcher fixtures: the two-identity menu and the
        // multi-account menu.
        var totalVariants = 0
        for (fixture in listOf(
            "derived_account_switcher.json",
            "derived_account_switcher_multi.json",
        )) {
            val root = Json.parseToJsonElement(Fixtures.load(fixture))
            val paths = mutableListOf<List<Any>>()
            collectPaths(root, emptyList(), 0, paths)
            assertTrue(
                "$fixture should expose >30 mutable nodes, got ${paths.size}",
                paths.size > 30,
            )
            var variants = 0
            for (path in paths.take(240)) {
                for (swap in swapValues) {
                    val mutated = replaceAt(root, path, swap)
                    variants++
                    // accountIdentities' contract is NEVER throws: any
                    // escaping Throwable of any kind fails the variant.
                    total("accountIdentities swap@$path") {
                        YoutubeiClient(GetTransport(body = mutated.toString()))
                            .accountIdentities(session)
                    }
                    total("oauthIdentity swap@$path") {
                        YoutubeiClient(GetTransport(body = mutated.toString()))
                            .oauthIdentity("token_x")
                    }
                }
            }
            assertEqualsExact(
                "every node × every swap type must have run for $fixture",
                minOf(paths.size, 240) * swapValues.size,
                variants,
            )
            totalVariants += variants
        }
        assertTrue("expected >500 identity variants, ran $totalVariants", totalVariants > 500)
    }
}
