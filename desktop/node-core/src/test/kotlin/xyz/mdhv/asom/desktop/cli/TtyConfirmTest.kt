package xyz.mdhv.asom.desktop.cli

import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TtyConfirmTest {
    private class FakeTty(private val answer: String?) : TtyIo {
        val shown = ArrayList<String>()
        var closed = false
        override fun println(text: String) { shown += text }
        override fun readLine(): String? = answer
        override fun close() { closed = true }
    }

    /** Fails the test if anything reads standard input. */
    private class PoisonedStdin : InputStream() {
        var reads = 0
        override fun read(): Int { reads++; error("stdin must never be read for a confirmation") }
    }

    @Test
    fun `the exact phrase from the tty confirms and the tty is closed`() {
        val tty = FakeTty("yes")
        assertEquals(Confirmation.Confirmed, TtyConfirm { tty }.confirm("Pair with Deck?"))
        assertTrue(tty.closed)
        assertTrue(tty.shown.first().contains("Pair with Deck?"))
    }

    @Test
    fun `anything other than the phrase, and end of input, decline`() {
        for (a in listOf("no", "y", "YES", "yes please", "", "yes\u0000")) {
            assertEquals(Confirmation.Declined, TtyConfirm { FakeTty(a) }.confirm("x"), "'$a'")
        }
        assertEquals(Confirmation.Declined, TtyConfirm { FakeTty(null) }.confirm("x"))
        assertEquals(Confirmation.Confirmed, TtyConfirm { FakeTty("  yes  ") }.confirm("x"), "surrounding blanks are ignored")
    }

    @Test
    fun `without a controlling tty the confirmation is refused and stdin is never consulted`() {
        val poisoned = PoisonedStdin()
        val old = System.`in`
        System.setIn(poisoned)
        try {
            assertEquals(Confirmation.NoTty, TtyConfirm { null }.confirm("Restore identity?"))
        } finally {
            System.setIn(old)
        }
        assertEquals(0, poisoned.reads)
    }

    @Test
    fun `a custom phrase is required exactly`() {
        assertEquals(Confirmation.Confirmed, TtyConfirm { FakeTty("delete identity") }.confirm("x", expected = "delete identity"))
        assertEquals(Confirmation.Declined, TtyConfirm { FakeTty("yes") }.confirm("x", expected = "delete identity"))
    }
}
