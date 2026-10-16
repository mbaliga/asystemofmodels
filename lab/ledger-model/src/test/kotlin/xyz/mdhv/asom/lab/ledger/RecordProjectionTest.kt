package xyz.mdhv.asom.lab.ledger

import java.io.File
import java.util.SplittableRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.mdhv.asom.contract.AsomHeaders
import xyz.mdhv.asom.contract.Egress
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JNull
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

class RecordProjectionTest {
    private val specMeshColumns = listOf(
        "requestId", "attemptId", "phase", "attemptIndex", "reach", "terminal", "servedClass", "peerNode", "peerAlias", "peerPath", "meshKind", "bytesIn",
        "meshCode", "destAddr", "addrSource", "routeReason", "routeDetail", "droppedFields", "overheadBytes", "overheadBasis",
    )

    @Test
    fun theRowHasTheThirteenV1FieldsTheTwentySpecColumnsAndExactlyOneMore() {
        val cols = LabRouteRecord.COLUMNS
        assertEquals(13 + 20 + 1, cols.size)
        assertEquals(listOf("ts", "callerPkg", "requestedModel", "servedProvider", "servedModel", "egress", "bytesOut", "tokensIn", "tokensOut", "costEst", "costBasis", "latencyMs", "status"), cols.take(13))
        assertEquals(specMeshColumns.toSet() + "sessionId", LabRouteRecord.MESH_COLUMNS.toSet(), "the 20 columns of design 8.4 and the one session column (ERR-LL-2)")
        val members = LabRouteRecord::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }
        assertEquals(cols.toSet(), members.toSet(), "the data class and the row column list agree")
    }

    @Test
    fun theFrozenV1EgressEnumIsUntouchedAndTheLabEnumIsSeparate() {
        assertEquals(listOf("local", "cloud", "catalogue", "download"), Egress.entries.map { it.wire })
        assertEquals(listOf("local", "cloud", "catalogue", "download", "peer", "contribution"), LabEgress.entries.map { it.wire })
        assertEquals("peer", LabEgress.peerClass.wire)
    }

    @Test
    fun noLabFileSpellsTheFrozenEnumNameWithAPeerMember() {
        val needle = "Egress" + "." + "PEER"
        val lab = File(repoRoot(), "lab")
        var scanned = 0
        val hits = lab.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".gradle" && it.name != ".kotlin" }
            .filter { it.isFile && (it.extension in setOf("kt", "kts", "md", "json", "py", "sh", "yml", "toml", "properties")) }
            .filter { f ->
                scanned++
                f.readText().contains(needle)
            }.toList()
        assertTrue(scanned > 20, "the scan must actually read files")
        assertEquals(emptyList(), hits.map { it.path }, "R4: the frozen enum name must never get a peer member anywhere in lab/")
    }

    @Test
    fun rowsRoundTripThroughTheirJcsBytes() {
        val rng = SplittableRandom(7)
        var n = 0
        repeat(300) {
            val r = randomRecord(rng)
            val bytes = r.toRowBytes()
            assertTrue(bytes.none { it == '\n'.code.toByte() }, "a JSONL row is one line")
            val parsed = (StrictJson.parse(bytes) as ParseResult.Ok).value as JObject
            assertEquals(r, LabRouteRecord.fromRow(parsed))
            assertEquals(r.toRow(), parsed)
            n++
        }
        assertTrue(n > 0)
    }

    @Test
    fun theRowHoldsIntegersOnlyAndTheCostAsAString() {
        val r = LabRouteRecord(ts = 5, callerPkg = "c", requestedModel = "m", egress = LabEgress.CLOUD, costEst = 2.9E-6, costBasis = "usage", status = 200)
        val row = r.toRow()
        assertEquals(JString("2.9E-6"), row["costEst"])
        assertEquals(JInt(5), row["ts"])
        assertEquals(JNull, row["requestId"])
        val jcs = String(r.toRowBytes(), Charsets.UTF_8)
        assertTrue(!jcs.contains(Regex("[0-9]\\.[0-9]")) || jcs.contains("\"2.9E-6\""), "no bare fraction in a row")
    }

    @Test
    fun headersOfTheRowEqualTheEchoHeadersOfTheRecord() {
        val rng = SplittableRandom(11)
        var cases = 0
        var withCost = 0
        var terminalReach = 0
        repeat(500) {
            val r = randomRecord(rng)
            assertEquals(r.toEchoHeaders(), RowProjection.headersOf(r.toRow()), "row: ${r.toRow()}")
            cases++
            if (AsomHeaders.COST_EST in r.toEchoHeaders()) withCost++
            if (r.terminal == true) terminalReach++
        }
        assertTrue(cases > 0 && withCost > 0 && terminalReach > 0, "the property must exercise cost headers and terminal rows: $withCost, $terminalReach")
    }

    @Test
    fun theTerminalRowsEgressHeaderIsItsReach() {
        val r = LabRouteRecord(ts = 1, callerPkg = "c", requestedModel = "m", servedProvider = "local", servedModel = "m", egress = LabEgress.peerClass, requestId = "r", reach = LabEgress.peerClass, terminal = true, servedClass = LabEgress.LOCAL)
        assertEquals("peer", r.toEchoHeaders()[AsomHeaders.EGRESS])
        val notTerminal = r.copy(terminal = false, reach = LabEgress.CLOUD, egress = LabEgress.LOCAL)
        assertEquals("local", notTerminal.toEchoHeaders()[AsomHeaders.EGRESS], "only the terminal row projects reach")
    }

    @Test
    fun everyW01VectorPassesUnchangedThroughTheLabProjection() {
        val file = File(repoRoot(), "lab/conformance/wire/W01-echo-headers.json")
        val doc = (StrictJson.parse(file.readBytes()) as ParseResult.Ok).value as JObject
        val vectors = (doc["vectors"] as JArray).items.map { it as JObject }
        var ran = 0
        for (v in vectors) {
            val inp = v["input"] as JObject
            fun s(k: String): String? = (inp[k] as? JString)?.value
            val egress = LabEgress.fromWire(s("egress")!!)!!
            val cost = s("costEst")?.let { java.lang.Double.parseDouble(it) }
            val expected = ((v["expect"] as JObject)["ok"] as JObject).members.associate { it.first.lowercase() to (it.second as JString).value }
            for (terminal in listOf(false, true)) {
                val r = LabRouteRecord(
                    ts = 0, callerPkg = "w01", requestedModel = "m", servedProvider = s("servedProvider"), servedModel = s("servedModel"), egress = egress, costEst = cost,
                    costBasis = s("costBasis")!!, status = 200, requestId = if (terminal) "r" else null, reach = if (terminal) egress else null, terminal = if (terminal) true else null,
                )
                assertEquals(expected, r.toEchoHeaders().mapKeys { it.key.lowercase() }, "${(v["id"] as JString).value} terminal=$terminal")
                assertEquals(r.toEchoHeaders(), RowProjection.headersOf(r.toRow()))
                ran++
            }
        }
        assertTrue(vectors.size >= 9 && ran == 2 * vectors.size, "W01 vectors run: $ran")
    }

    @Test
    fun aRowThatBreaksTheSchemaRulesIsRefusedBeforeAnySinkTakesIt() {
        val ok = LabRouteRecord(ts = 1, callerPkg = "p", requestedModel = "", egress = LabEgress.peerClass, meshKind = MeshKind.CONTROL, meshCode = "HELLO", sessionId = "s")
        assertEquals(emptyList(), ok.violations())
        fun bad(r: LabRouteRecord) = assertTrue(r.violations().isNotEmpty(), "should be refused: $r")
        bad(ok.copy(phase = Phase.INTENT, bytesOut = 3, bytesIn = null))
        bad(ok.copy(destAddr = "10.0.0.1:1", addrSource = "qr"))
        bad(ok.copy(egress = LabEgress.CLOUD))
        bad(ok.copy(sessionId = null))
        bad(ok.copy(costEst = 1.0, costBasis = "usage"))
        bad(ok.copy(overheadBytes = 5))
        bad(ok.copy(meshKind = MeshKind.DIAL, destAddr = null, addrSource = null))
        bad(ok.copy(meshKind = MeshKind.INFER_SERVED, phase = Phase.OUTCOME, attemptId = "a", requestId = "r"))
        bad(LabRouteRecord(ts = 1, callerPkg = "c", requestedModel = "m", egress = LabEgress.LOCAL, terminal = true, reach = LabEgress.DOWNLOAD))
        assertFailsWith<IllegalArgumentException> { MemorySink().append(ok.copy(sessionId = null)) }
    }

    private fun randomRecord(rng: SplittableRandom): LabRouteRecord {
        fun <T> pick(xs: List<T>) = xs[rng.nextInt(xs.size)]
        val terminal = rng.nextInt(3) == 0
        val egress = pick(listOf(LabEgress.LOCAL, LabEgress.CLOUD, LabEgress.peerClass))
        val cost = if (rng.nextBoolean()) StrictMath.pow(10.0, -9.0 + 11.0 * rng.nextDouble()) else null
        val basis = if (cost != null) pick(listOf("usage", "heuristic")) else "none"
        val prov = if (rng.nextBoolean()) "openrouter" else null
        val model = if (rng.nextBoolean()) "llama-3.3-70b" else null
        return LabRouteRecord(
            ts = rng.nextLong(1, 2_000_000_000_000L), callerPkg = "com.example.a${rng.nextInt(5)}", requestedModel = "auto", servedProvider = prov, servedModel = model, egress = egress,
            bytesOut = rng.nextLong(0, 100_000), tokensIn = if (rng.nextBoolean()) rng.nextLong(0, 9_000) else null, tokensOut = if (rng.nextBoolean()) rng.nextLong(0, 9_000) else null,
            costEst = cost, costBasis = basis, latencyMs = rng.nextLong(0, 30_000), status = pick(listOf(200, 429, 502, 503, 599)),
            requestId = if (terminal) "req${rng.nextInt(100)}" else null, reach = if (terminal) pick(listOf(LabEgress.LOCAL, LabEgress.peerClass, LabEgress.CLOUD)) else null,
            terminal = if (terminal) true else null, servedClass = if (terminal && rng.nextBoolean()) egress else null,
            droppedFields = if (rng.nextInt(4) == 0) listOf("user", "metadata") else null,
        )
    }
}
