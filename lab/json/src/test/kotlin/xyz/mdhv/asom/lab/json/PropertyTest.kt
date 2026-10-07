package xyz.mdhv.asom.lab.json

import java.io.File
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Deterministic property tests: fixed seeds, so a failure reproduces byte for byte. */
class PropertyTest {
    private val stringAtoms = listOf(
        "a", "b", "Z", "0", " ", "/", "\\", "\"", "\u0000", "\u0001", "\b", "\t", "\n", "\u000c", "\r", "\u001f", "\u007f", "\u0080", " ", "é",
        "é", " ", " ", "퟿", "", "﻿", "Ａ", "�", "￿", "😀", "𐀀", "􏿿", "<", "&",
    )

    private fun randomString(r: Random, maxAtoms: Int = 6): String =
        (0 until r.nextInt(maxAtoms + 1)).joinToString("") { stringAtoms[r.nextInt(stringAtoms.size)] }

    private fun randomInt(r: Random): Long = when (r.nextInt(8)) {
        0 -> 0L
        1 -> MAX_SAFE_INT
        2 -> -MAX_SAFE_INT
        3 -> (r.nextInt(21) - 10).toLong()
        4 -> MAX_SAFE_INT - r.nextInt(5)
        5 -> -MAX_SAFE_INT + r.nextInt(5)
        6 -> r.nextInt(1_000_000).toLong()
        else -> (r.nextLong() % (MAX_SAFE_INT + 1))
    }

    private fun randomValue(r: Random, depth: Int): JValue {
        val k = if (depth >= StrictJson.MAX_DEPTH) r.nextInt(4) else r.nextInt(7)
        return when (k) {
            0 -> JNull
            1 -> JBool(r.nextBoolean())
            2 -> JInt(randomInt(r))
            3 -> JString(randomString(r))
            4, 5 -> JArray((0 until r.nextInt(5)).map { randomValue(r, depth + 1) })
            else -> {
                val names = LinkedHashSet<String>()
                repeat(r.nextInt(6)) { names += randomString(r, 3) }
                JObject(names.toList().shuffled(r).map { it to randomValue(r, depth + 1) })
            }
        }
    }

    private fun deepValue(r: Random, depth: Int): JValue {
        var v: JValue = randomValue(r, StrictJson.MAX_DEPTH)
        repeat(depth) { v = if (r.nextBoolean()) JArray(listOf(v)) else JObject(listOf(randomString(r, 2) to v)) }
        return v
    }

    /** A non-canonical rendering: random whitespace, member order, and escapes for characters that need none. */
    private fun render(v: JValue, r: Random, sb: StringBuilder) {
        fun ws() {
            repeat(r.nextInt(3)) { sb.append(" \t\r\n"[r.nextInt(4)]) }
        }
        fun str(s: String) {
            sb.append('"')
            var k = 0
            while (k < s.length) {
                val cp = s.codePointAt(k)
                k += Character.charCount(cp)
                val esc = r.nextInt(4) == 0
                when {
                    cp == '"'.code -> sb.append("\\\"")
                    cp == '\\'.code -> sb.append("\\\\")
                    cp == '/'.code && esc -> sb.append("\\/")
                    cp < 0x20 && cp != 0x08 && cp != 0x09 && cp != 0x0A && cp != 0x0C && cp != 0x0D -> sb.append("\\u%04x".format(cp))
                    cp == 0x08 -> sb.append("\\b")
                    cp == 0x09 -> sb.append("\\t")
                    cp == 0x0A -> sb.append("\\n")
                    cp == 0x0C -> sb.append("\\f")
                    cp == 0x0D -> sb.append("\\r")
                    esc && cp < 0x10000 -> sb.append((if (r.nextBoolean()) "\\u%04x" else "\\u%04X").format(cp))
                    esc -> {
                        val hi = Character.highSurrogate(cp).code
                        val lo = Character.lowSurrogate(cp).code
                        sb.append((if (r.nextBoolean()) "\\u%04x\\u%04x" else "\\u%04X\\u%04X").format(hi, lo))
                    }
                    else -> sb.appendCodePoint(cp)
                }
            }
            sb.append('"')
        }
        ws()
        when (v) {
            is JNull -> sb.append("null")
            is JBool -> sb.append(v.value)
            is JInt -> sb.append(v.value)
            is JString -> str(v.value)
            is JArray -> {
                sb.append('[')
                v.items.forEachIndexed { k, x ->
                    if (k > 0) sb.append(',')
                    render(x, r, sb)
                }
                ws()
                sb.append(']')
            }
            is JObject -> {
                sb.append('{')
                v.members.shuffled(r).forEachIndexed { k, (name, x) ->
                    if (k > 0) sb.append(',')
                    ws()
                    str(name)
                    ws()
                    sb.append(':')
                    render(x, r, sb)
                }
                ws()
                sb.append('}')
            }
        }
        ws()
    }

    private fun assertMembersInUtf16Order(v: JValue) {
        when (v) {
            is JObject -> {
                val names = v.members.map { it.first }
                for (k in 1 until names.size) assertTrue(Jcs.compareUtf16Units(names[k - 1], names[k]) < 0, "keys out of UTF-16 order: $names")
                v.members.forEach { assertMembersInUtf16Order(it.second) }
            }
            is JArray -> v.items.forEach { assertMembersInUtf16Order(it) }
            else -> Unit
        }
    }

    @Test
    fun parseOfJcsIsTheIdentityAndJcsIsIdempotent() {
        val c = Cases("round trip")
        for (seed in 1L..3000L) {
            val r = Random(seed)
            val v = if (seed % 10 == 0L) deepValue(r, r.nextInt(12)) else randomValue(r, 0)
            val bytes = Jcs.serialize(v)
            val back = StrictJson.parse(bytes)
            if (back !is ParseResult.Ok) fail("seed $seed: JCS output was rejected: $back for ${String(bytes, Charsets.UTF_8)}")
            c.run { assertEquals(v, back.value, "seed $seed: parse(JCS(v)) == v") }
            c.run { assertContentEquals(bytes, Jcs.serialize(back.value), "seed $seed: JCS(parse(JCS(v))) == JCS(v)") }
            c.run { assertMembersInUtf16Order(back.value) }
            c.run { assertTrue(Utf8Strict.isValid(bytes)) }
        }
        c.requireNonVacuous(12_000)
    }

    @Test
    fun anyRenderingOfTheSameValueCanonicalisesToTheSameBytes() {
        val c = Cases("renderings")
        for (seed in 1L..3000L) {
            val r = Random(seed * 7919)
            val v = randomValue(r, 0)
            val want = Jcs.serialize(v)
            repeat(3) {
                val sb = StringBuilder()
                render(v, r, sb)
                val text = sb.toString().toByteArray(Charsets.UTF_8)
                when (val out = Canonicalizer.canonicalize(text)) {
                    is Canonicalizer.Result.Canonical -> {
                        c.run { assertContentEquals(want, out.bytes, "seed $seed: rendering ${sb}") }
                        c.run { assertEquals(v, out.value) }
                    }
                    is Canonicalizer.Result.Rejected -> fail("seed $seed: a valid rendering was rejected ${out.code}: $sb")
                }
            }
        }
        c.requireNonVacuous(18_000)
    }

    @Test
    fun keyOrderOfSerialisedObjectsIsIndependentOfMemberOrder() {
        val c = Cases("member permutation")
        for (seed in 1L..500L) {
            val r = Random(seed)
            val names = LinkedHashSet<String>()
            repeat(2 + r.nextInt(8)) { names += randomString(r, 3) }
            val members = names.map { it to JInt(r.nextInt(100).toLong()) }
            val want = Jcs.serialize(JObject(members))
            repeat(5) { c.run { assertContentEquals(want, Jcs.serialize(JObject(members.shuffled(r)))) } }
        }
        c.requireNonVacuous(2500)
    }

    private fun mutate(seed: ByteArray, r: Random): ByteArray {
        var b = seed
        repeat(1 + r.nextInt(3)) {
            b = when (r.nextInt(12)) {
                0 -> if (b.isEmpty()) b else b.copyOf().also { it[r.nextInt(it.size)] = r.nextInt(256).toByte() }
                1 -> b.copyOfRange(0, r.nextInt(b.size + 1))
                2 -> if (b.isEmpty()) b else r.nextInt(b.size).let { k -> b.copyOfRange(0, k) + b.copyOfRange(k + 1, b.size) }
                3 -> insertAt(b, r, byteArrayOf(r.nextInt(256).toByte()))
                4 -> b + " x".toByteArray()
                5 -> insertAt(b, r, "\\ud800".toByteArray())
                6 -> insertAt(b, r, pick(r, ".5", "e2", "9999999999999999999", "-0", "01", "NaN", "9007199254740992"))
                7 -> insertAt(b, r, byteArrayOf(0xFF.toByte()))
                8 -> insertAt(b, r, pick(r, "\"a\":1,\"a\":2,", ",\"k\":0,\"k\":0", "[[[[[[[[[[[[[[[[[", "\uFEFF"))
                9 -> if (b.size < 2) b else r.nextInt(b.size - 1).let { k -> insertAt(b, r, b.copyOfRange(k, k + 1 + r.nextInt(b.size - k - 1))) }
                10 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + b
                else -> insertAt(b, r, byteArrayOf(0xC0.toByte(), 0x80.toByte()))
            }
        }
        return b
    }

    private fun pick(r: Random, vararg options: String): ByteArray = options[r.nextInt(options.size)].toByteArray(Charsets.UTF_8)

    private fun insertAt(b: ByteArray, r: Random, piece: ByteArray): ByteArray {
        val k = r.nextInt(b.size + 1)
        return b.copyOfRange(0, k) + piece + b.copyOfRange(k, b.size)
    }

    @Test
    fun mutatedInputsNeverThrowAndEveryAcceptedOneRoundTrips() {
        val c = Cases("fuzz")
        val counts = java.util.EnumMap<JsonRejectCode, Int>(JsonRejectCode::class.java)
        var accepted = 0
        val corpus = StringBuilder()
        for (seed in 1L..4000L) {
            val r = Random(seed * 31)
            val base = Jcs.serialize(randomValue(r, 0))
            val input = mutate(base, r)
            val res = try {
                StrictJson.parse(input)
            } catch (t: Throwable) {
                fail("seed $seed: parse threw $t on ${Hex.encode(input)}")
            }
            when (res) {
                is ParseResult.Reject -> {
                    counts.merge(res.code, 1, Int::plus)
                    corpus.append(Hex.encode(input)).append('\t').append("reject ").append(res.code.name).append('\n')
                }
                is ParseResult.Ok -> {
                    accepted++
                    val canon = Jcs.serialize(res.value)
                    c.run { assertEquals(res.value, (StrictJson.parse(canon) as ParseResult.Ok).value) }
                    corpus.append(Hex.encode(input)).append('\t').append("ok ").append(Hex.encode(canon)).append('\n')
                }
            }
            c.run { }
        }
        for (code in JsonRejectCode.entries) assertTrue((counts[code] ?: 0) > 0, "the fuzz corpus never produced $code: $counts")
        assertTrue(accepted > 100, "the fuzz corpus accepted only $accepted inputs")
        c.requireNonVacuous(4000)
        writeCorpus(corpus.toString())
    }

    private fun writeCorpus(text: String) {
        try {
            val dir = File(repoRoot(), "lab/build")
            dir.mkdirs()
            File(dir, "json-fuzz-corpus.tsv").writeText(text, Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    @Test
    fun aLargeFlatDocumentParsesQuickly() {
        val n = 200_000
        val text = "[" + (0 until n).joinToString(",") { (it - 100_000).toString() } + "]"
        val v = assertAccepts(text) as JArray
        assertEquals(n, v.items.size)
        assertEquals(JInt(-100_000), v.items.first())
        assertEquals(text, Jcs.serializeToString(v))
    }
}
