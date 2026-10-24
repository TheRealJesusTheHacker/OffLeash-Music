package dev.cyberdogs.offleash.core.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * findFirst/findAll document-order contract (Fleet Sweep #5).
 *
 * findFirst was rewritten to short-circuit at the first match
 * instead of collecting every match in the tree; these tests pin
 * that its result is still exactly findAll's first element — the
 * order callers were built against — on shapes where a naive
 * depth-first rewrite would diverge (an object's own entry vs. a
 * nested one, an early sibling's subtree vs. a later sibling).
 */
class JsonNavTest {

    private fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    @Test
    fun objectsOwnEntryWinsOverNestedOnes() {
        val root = parse("""{"target": "own", "child": {"target": "nested"}}""")
        assertEquals(findAll(root, "target").firstOrNull(), findFirst(root, "target"))
        assertEquals("\"own\"", findFirst(root, "target").toString())
    }

    @Test
    fun objectsOwnEntryWinsEvenWhenItIsTheLastKey() {
        val root = parse(
            """{"a": {"deep": {"target": "deep-first"}}, "target": "later"}""",
        )
        // findAll adds an object's own entry BEFORE walking any of
        // its values, so the root's own "target" is the first match
        // even though a nested one sits in an earlier key's subtree.
        assertEquals("\"later\"", findFirst(root, "target").toString())
        assertEquals(findAll(root, "target").firstOrNull(), findFirst(root, "target"))
    }

    @Test
    fun arrayElementsAreSearchedInOrder() {
        val root = parse("""[{"x": 1}, {"target": "second"}, {"target": "third"}]""")
        assertEquals("\"second\"", findFirst(root, "target").toString())
        assertEquals(findAll(root, "target").firstOrNull(), findFirst(root, "target"))
    }

    @Test
    fun nestedMatchInsideMatchedValueIsNotTheFirst() {
        val root = parse("""{"wrap": {"target": {"target": "inner"}}}""")
        assertEquals("""{"target":"inner"}""".replace(" ", ""), findFirst(root, "target").toString().replace(" ", ""))
        assertEquals(findAll(root, "target").firstOrNull(), findFirst(root, "target"))
    }

    @Test
    fun absentKeyAndNullNodeYieldNull() {
        assertNull(findFirst(parse("""{"a": [{"b": 1}]}"""), "missing"))
        assertNull(findFirst(null, "anything"))
        assertNull(findFirst(parse("""42"""), "anything"))
    }

    @Test
    fun findFirstMatchesFindAllFirstOnEveryFixtureShape() {
        // A mixed tree exercising objects, arrays and nesting depth.
        val root = parse(
            """
            {"l1": [
              {"renderer": {"target": "one"}},
              {"other": [{"target": "two"}]}
            ], "mid": {"target": "three"}}
            """.trimIndent(),
        )
        assertEquals("\"one\"", findFirst(root, "target").toString())
        assertEquals(findAll(root, "target").firstOrNull(), findFirst(root, "target"))
        assertEquals(findAll(root, "renderer").firstOrNull(), findFirst(root, "renderer"))
        assertEquals(findAll(root, "other").firstOrNull(), findFirst(root, "other"))
    }
}
