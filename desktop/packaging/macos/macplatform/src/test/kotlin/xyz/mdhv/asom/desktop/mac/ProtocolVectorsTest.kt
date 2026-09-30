package xyz.mdhv.asom.desktop.mac

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.TestInstance
import xyz.mdhv.asom.desktop.mac.helper.HelperCodec
import xyz.mdhv.asom.desktop.mac.helper.HelperJson
import xyz.mdhv.asom.desktop.mac.helper.HValue
import xyz.mdhv.asom.desktop.mac.helper.JV
import xyz.mdhv.asom.desktop.mac.helper.ProtocolSpec
import xyz.mdhv.asom.desktop.mac.helper.Reject
import xyz.mdhv.asom.desktop.mac.helper.RejectCode
import xyz.mdhv.asom.desktop.mac.helper.Reply

/** One record of the .jsonl files of helper-protocol/vectors (SCHEMA section 7). Shared with the Swift lane. */
class Vector(
    val id: String,
    val kind: String,
    val op: String?,
    val line: ByteArray,
    val canonical: String?,
    val verdict: String,
    val code: String,
    val request: ByteArray?,
    val requestVerdict: String?,
    val requestCode: String?,
    val response: ByteArray?,
    val file: String,
)

object Vectors {
    val files = listOf(
        "events-accept.jsonl", "events-reject.jsonl", "exchanges.jsonl", "requests-accept.jsonl", "requests-reject.jsonl",
        "responses-accept.jsonl", "responses-reject.jsonl",
    )

    val directory: File get() = File(System.getProperty("asom.vectors") ?: error("asom.vectors not set"))

    private fun expand(s: String, pad: Int?, zeros: Int?): String {
        var out = s
        if (pad != null) out = out.replace("{PAD}", "A".repeat(pad))
        if (zeros != null) out = out.replace("{ZEROS}", HelperCodec.base64Encode(ByteArray(zeros)))
        return out
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun load(file: String): List<Vector> {
        val text = File(directory, file).readText(Charsets.UTF_8)
        return text.split('\n').filter { it.isNotEmpty() }.map { raw ->
            val o = HelperJson.parse(raw) as JV.Obj
            fun str(k: String) = (o[k] as? JV.Str)?.value
            fun int(k: String) = (o[k] as? JV.Num)?.value?.toInt()
            val pad = int("pad")
            val zeros = int("zeros")
            val line = str("lineHex")?.let(::hex) ?: expand(str("line") ?: "", pad, zeros).toByteArray(Charsets.UTF_8)
            Vector(
                id = str("id")!!, kind = str("kind")!!, op = str("op"), line = line,
                canonical = str("canonical")?.let { expand(it, pad, zeros) },
                verdict = str("verdict")!!, code = str("code")!!,
                request = str("request")?.let { expand(it, pad, zeros).toByteArray(Charsets.UTF_8) },
                requestVerdict = str("requestVerdict"), requestCode = str("requestCode"),
                response = str("response")?.let { expand(it, pad, zeros).toByteArray(Charsets.UTF_8) },
                file = file,
            )
        }
    }

    fun all(): List<Vector> = files.flatMap { load(it) }
}

/**
 * The Kotlin lane over the shared vectors. It prints one line per vector, `id TAB verdict TAB code TAB canonical`, to
 * `build/reports/desktop/helper-protocol.lines`; `scripts/check-protocol-lanes.sh` diffs it with the Swift lane's output.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProtocolVectorsTest {
    private fun s(b: ByteArray) = String(b, Charsets.UTF_8)

    private fun laneLine(v: Vector): String = when (v.kind) {
        "request" -> try {
            val r = HelperCodec.decodeRequest(v.line)
            assertEquals("accept", v.verdict, "${v.id} decoded but the vector says reject")
            val enc = s(HelperCodec.encode(r))
            assertEquals(v.canonical, enc, "${v.id} canonical")
            "${v.id}\taccept\t-\t$enc"
        } catch (e: Reject) {
            assertEquals("reject", v.verdict, "${v.id} rejected as ${e.code.wire} but the vector says accept")
            assertEquals(v.code, e.code.wire, "${v.id} reject code")
            "${v.id}\treject\t${e.code.wire}\t-"
        }
        "response" -> try {
            val r = HelperCodec.decodeReply(v.op!!, v.line)
            assertEquals("accept", v.verdict, "${v.id} decoded but the vector says reject")
            val enc = s(HelperCodec.encode(r))
            assertEquals(v.canonical, enc, "${v.id} canonical")
            "${v.id}\taccept\t-\t$enc"
        } catch (e: Reject) {
            assertEquals("reject", v.verdict, "${v.id} rejected as ${e.code.wire} but the vector says accept")
            assertEquals(v.code, e.code.wire, "${v.id} reject code")
            "${v.id}\treject\t${e.code.wire}\t-"
        }
        "event" -> try {
            val r = HelperCodec.decodeEvent(v.line)
            assertEquals("accept", v.verdict, "${v.id} decoded but the vector says reject")
            val enc = s(HelperCodec.encode(r))
            assertEquals(v.canonical, enc, "${v.id} canonical")
            "${v.id}\taccept\t-\t$enc"
        } catch (e: Reject) {
            assertEquals("reject", v.verdict, "${v.id} rejected as ${e.code.wire} but the vector says accept")
            assertEquals(v.code, e.code.wire, "${v.id} reject code")
            "${v.id}\treject\t${e.code.wire}\t-"
        }
        "exchange" -> {
            var op: String? = null
            try {
                op = HelperCodec.decodeRequest(v.request!!).op
                assertEquals("accept", v.requestVerdict, "${v.id} request decoded but the vector says reject")
            } catch (e: Reject) {
                assertEquals("reject", v.requestVerdict, "${v.id} request rejected as ${e.code.wire}")
                assertEquals(v.requestCode, e.code.wire, "${v.id} request reject code")
            }
            val reply = HelperCodec.decodeReply(op ?: "hello", v.response!!)
            val canonical = s(HelperCodec.encode(reply))
            assertEquals(s(v.response), canonical, "${v.id} recorded response is canonical")
            "${v.id}\texchange\t-\t$canonical"
        }
        else -> error("${v.id}: unknown kind ${v.kind}")
    }

    @Test
    fun `every vector agrees with the Kotlin codec and the lane lines are written for the diff`() {
        val lines = ArrayList<String>()
        val perFile = LinkedHashMap<String, Int>()
        val perKindVerdict = HashMap<String, Int>()
        for (f in Vectors.files) {
            for (v in Vectors.load(f)) {
                lines += laneLine(v)
                perFile.merge(f, 1, Int::plus)
                perKindVerdict.merge("${v.kind}/${v.verdict}", 1, Int::plus)
            }
        }
        // non-vacuity: every family exercised, every reject code and every op seen
        for (f in Vectors.files) assertTrue((perFile[f] ?: 0) > 0, "family $f exercised zero vectors")
        for (k in listOf("request/accept", "request/reject", "response/accept", "response/reject", "event/accept", "event/reject", "exchange/accept")) {
            assertTrue((perKindVerdict[k] ?: 0) > 0, "kind/verdict $k exercised zero vectors")
        }
        for (code in RejectCode.values()) assertTrue(lines.any { "\treject\t${code.wire}\t" in it }, "reject code ${code.wire} has no vector")
        for (op in ProtocolSpec.requestOps) assertTrue(lines.any { "\taccept\t-\t{\"op\":\"$op\"" in it }, "op $op has no accepted request vector")
        for (name in ProtocolSpec.eventNames) assertTrue(lines.any { "\taccept\t-\t{\"ev\":\"$name\"" in it }, "event $name has no accepted vector")
        Report.line("helper-protocol vectors (Kotlin lane): ${lines.size} vectors: " + perFile.entries.joinToString(" ") { "${it.key}=${it.value}" })
        val out = File(System.getProperty("asom.lines") ?: error("asom.lines not set"))
        out.parentFile.mkdirs()
        out.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
    }

    @Test
    fun `the fixture digest in the exchange vectors is the sha256 of the fixture text`() {
        val ex = Vectors.load("exchanges.jsonl").first { s(it.request ?: ByteArray(0)) == "{\"op\":\"platform.uuid\"}" }
        val reply = HelperCodec.decodeReply("platform.uuid", ex.response!!) as Reply.Success
        val expected = java.security.MessageDigest.getInstance("SHA-256").digest("fixture-platform-uuid".toByteArray(Charsets.US_ASCII))
        assertEquals(HValue.Bytes(expected), reply.fields["digest"])
        assertNotNull(reply.fields.bytes("digest"))
    }
}
