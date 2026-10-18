package xyz.mdhv.asom.ut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** INDEX.json detects an incomplete or edited checkout of the vector files. It authenticates nothing. */
class UtcIndexTest {
    @Test
    fun theIndexListsEveryVectorFileWithItsCurrentSha256() {
        val index = parseJ(UtcVectors.root.resolve("INDEX.json").readText(Charsets.UTF_8)) as xyz.mdhv.asom.lab.json.JArray
        val entries = index.items.map { it.asObj() }
        val paths = entries.map { it.str("path") }
        assertEquals(paths.sorted(), paths, "INDEX.json is not sorted")
        val files = UtcVectors.root.listFiles { f -> f.name.startsWith("UTC") && f.name.endsWith(".json") }!!.map { it.name }.sorted()
        assertEquals(files, paths.sorted(), "INDEX.json does not list exactly the vector files")
        assertEquals(listOf("UTC01", "UTC02", "UTC03", "UTC04", "UTC05"), entries.map { it.str("family") })
        for (e in entries) {
            assertEquals(e.str("sha256"), UtcVectors.sha256(UtcVectors.root.resolve(e.str("path"))), "${e.str("path")} differs from INDEX.json")
        }
        assertTrue(UtcVectors.version().matches(Regex("""\d+\.\d+\.\d+""")))
    }

    @Test
    fun everyVectorCarriesAnOracleTagAndNoneIsMoreThanSelfYet() {
        val oracles = listOf(
            "UTC01-framing.json" to "UTC01", "UTC02-lifecycle.json" to "UTC02", "UTC03-projection.json" to "UTC03",
            "UTC04-errors.json" to "UTC04", "UTC05-hygiene.json" to "UTC05",
        ).flatMap { (f, fam) -> UtcVectors.load(f, fam) }.map { it.oracle }
        assertTrue(oracles.isNotEmpty())
        assertEquals(setOf("self"), oracles.toSet(), "an oracle tag may become 'independent' only when an independent implementation agreed (LAB_SPEC 4.10)")
    }
}
