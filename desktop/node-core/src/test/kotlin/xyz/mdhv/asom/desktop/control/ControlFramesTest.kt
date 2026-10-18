package xyz.mdhv.asom.desktop.control

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.mdhv.asom.desktop.Report

class ControlFramesTest {
    @Test
    fun `the command enum is closed and matches the spec names`() {
        assertEquals(
            listOf("status", "watch", "chat", "lend", "lan-confirm", "pair-confirm", "peers", "ledger-export", "bench", "unlock", "shutdown", "restore"),
            ControlCommand.entries.map { it.wire },
        )
        assertEquals(setOf(ControlCommand.LAN_CONFIRM, ControlCommand.PAIR_CONFIRM, ControlCommand.RESTORE), ControlCommand.entries.filter { it.requiresTtyConfirmation }.toSet())
        assertEquals(null, ControlCommand.fromWire("admin-register"))
    }

    @Test
    fun `a request round-trips through a length-prefixed frame`() {
        val req = ControlRequest(7, ControlCommand.LEND, JsonObject(mapOf("mode" to JsonPrimitive("on"))))
        val out = ByteArrayOutputStream()
        ControlFrames.write(out, ControlFrames.encodeRequest(req))
        val bytes = out.toByteArray()
        assertEquals(bytes.size - 4, ((bytes[0].toInt() and 255) shl 24) or ((bytes[1].toInt() and 255) shl 16) or ((bytes[2].toInt() and 255) shl 8) or (bytes[3].toInt() and 255))
        assertEquals(req, ControlFrames.decodeRequest(ControlFrames.read(ByteArrayInputStream(bytes))))
    }

    @Test
    fun `hostile frames are refused before any allocation and malformed requests are typed errors`() {
        var refused = 0
        val tooBig = byteArrayOf(0x7f, -1, -1, -1)
        assertFailsWith<ControlFrameException> { ControlFrames.read(ByteArrayInputStream(tooBig)) }.also { assertEquals(ControlCode.FRAME_TOO_LARGE, it.code); refused++ }
        val negative = byteArrayOf(-1, -1, -1, -1)
        assertFailsWith<ControlFrameException> { ControlFrames.read(ByteArrayInputStream(negative)) }.also { assertEquals(ControlCode.FRAME_TOO_LARGE, it.code); refused++ }
        val justOver = byteArrayOf(0, 0x10, 0, 1)
        assertFailsWith<ControlFrameException> { ControlFrames.read(ByteArrayInputStream(justOver)) }.also { refused++ }
        assertFailsWith<EOFException> { ControlFrames.read(ByteArrayInputStream(byteArrayOf(0, 0, 0, 9, 1, 2))) }.also { refused++ }
        assertFailsWith<ControlFrameException> { ControlFrames.frame(ByteArray(ControlFrames.MAX_FRAME_BYTES + 1)) }.also { refused++ }
        listOf(
            """{"id":1,"cmd":"status","extra":1}""" to ControlCode.BAD_REQUEST,
            """{"id":1.5,"cmd":"status"}""" to ControlCode.BAD_REQUEST,
            """{"id":"1","cmd":"status"}""" to ControlCode.BAD_REQUEST,
            """{"id":1,"cmd":"rm-rf"}""" to ControlCode.UNKNOWN_COMMAND,
            """{"id":1,"cmd":"status","args":[]}""" to ControlCode.BAD_REQUEST,
            """{"id":1,"id":2,"cmd":"status"}""" to ControlCode.BAD_REQUEST,
            """[]""" to ControlCode.BAD_REQUEST,
        ).forEach { (text, code) ->
            assertEquals(code, assertFailsWith<ControlFrameException>(text) { ControlFrames.decodeRequest(text) }.code, text)
            refused++
        }
        Report.line("control frames: $refused hostile or malformed inputs refused")
        assertTrue(refused > 0)
    }

    @Test
    fun `a not-implemented response is typed`() {
        val r = ControlFrames.encodeResponse(ControlResponse.notImplemented(3, "chat", "DL6"))
        assertEquals("""{"id":3,"ok":false,"code":"NOT_IMPLEMENTED","message":"chat (DL6)"}""", r)
    }

    private fun framed(body: ByteArray): ByteArrayInputStream = ByteArrayInputStream(ControlFrames.frame(body))

    @Test
    fun `a frame body that is not strict UTF-8 is refused, never repaired into U+FFFD`() {
        val bad = mapOf(
            "overlong C0 AF" to byteArrayOf(0x7b, 0x22, 0x78, 0x22, 0x3a, 0x22, 0xC0.toByte(), 0xAF.toByte(), 0x22, 0x7d),
            "overlong C0 80" to byteArrayOf(0x22, 0xC0.toByte(), 0x80.toByte(), 0x22),
            "encoded surrogate ED A0 80" to byteArrayOf(0x22, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte(), 0x22),
            "above U+10FFFF F4 90 80 80" to byteArrayOf(0x22, 0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte(), 0x22),
            "stray FF" to byteArrayOf(0x22, 0xFF.toByte(), 0x22),
            "truncated sequence E2 82" to byteArrayOf(0x22, 0xE2.toByte(), 0x82.toByte(), 0x22),
            "lone continuation 80" to byteArrayOf(0x22, 0x80.toByte(), 0x22),
        )
        for ((why, body) in bad) {
            val e = assertFailsWith<ControlFrameException>(why) { ControlFrames.read(framed(body)) }
            assertEquals(ControlCode.BAD_REQUEST, e.code, why)
        }
        val ok = ControlFrames.read(framed("""{"x":"€ 😀"}""".toByteArray(Charsets.UTF_8)))
        assertEquals("""{"x":"€ 😀"}""", ok)
        Report.line("control frames: ${bad.size} invalid UTF-8 bodies refused, valid multi-byte text accepted")
        assertTrue(bad.size >= 7)
    }
}
