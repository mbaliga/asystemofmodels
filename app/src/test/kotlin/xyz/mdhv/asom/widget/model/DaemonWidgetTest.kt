package xyz.mdhv.asom.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DaemonWidgetTest {

    private val copy = FakeCopy()

    private fun state(
        running: Boolean = false,
        activity: DaemonActivity = DaemonActivity.IDLE,
        provider: String? = null,
        failed: Boolean = false,
        verbose: Boolean = false,
        degraded: Boolean = false,
    ) = DaemonState(running, activity, provider, failed, verbose, degraded, "127.0.0.1", 11435)

    @Test
    fun `running shows the endpoint, the activity and a Stop pad`() {
        val face = DaemonWidget.present(state(running = true), SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("DAEMON_RUNNING", Glyph.ACTIVE, Tone.CYAN), face.value)
        assertEquals(listOf(Line("DAEMON_ENDPOINT(127.0.0.1|11435)"), Line("DAEMON_IDLE")), face.lines)
        assertEquals(DaemonAction.STOP, face.action?.kind)
        assertEquals("ACTION_STOP_DESC", face.action?.description)
        assertEquals(Destination.STATUS, face.open)
    }

    @Test
    fun `stopped shows the port it is not listening on and a Start pad`() {
        val face = DaemonWidget.present(state(), SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("DAEMON_STOPPED", Glyph.INACTIVE, Tone.VIOLET), face.value)
        assertEquals(listOf(Line("DAEMON_NOT_LISTENING(11435)")), face.lines)
        assertEquals(DaemonAction.START, face.action?.kind)
    }

    @Test
    fun `a failed start says so with a shape and a word`() {
        val face = DaemonWidget.present(state(failed = true), SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("DAEMON_FAILED", Glyph.FAILED, Tone.VIOLET), face.value)
        assertEquals(Line("DAEMON_PORT_BUSY(11435)", Glyph.FAILED, Tone.VIOLET), face.lines[1])
        assertEquals(DaemonAction.START, face.action?.kind)
    }

    @Test
    fun `streaming names the provider`() {
        val face = DaemonWidget.present(
            state(running = true, activity = DaemonActivity.STREAMING, provider = "groq"),
            SizeClass.MEDIUM,
            NOW,
            copy,
        )
        assertEquals(Line("DAEMON_STREAMING(groq)"), face.lines[1])
        val unnamed = DaemonWidget.present(state(running = true, activity = DaemonActivity.STREAMING), SizeClass.MEDIUM, NOW, copy)
        assertEquals(Line("DAEMON_SERVING"), unnamed.lines[1])
    }

    @Test
    fun `compact sizes have no pad, but still say when they were drawn`() {
        for (size in listOf(SizeClass.TINY, SizeClass.SMALL)) {
            val face = DaemonWidget.present(state(running = true), size, NOW, copy)
            assertNull(face.action)
            assertTrue(face.lines.isEmpty())
            assertEquals(Destination.STATUS, face.open)
            // A killed process leaves "● running" behind; the time shows how old it is.
            assertEquals("t$NOW", face.asOf, "$size")
            assertTrue("AS_OF(t$NOW)" in face.description, face.description)
        }
    }

    @Test
    fun `every size prints a stamp`() {
        for (size in SizeClass.entries) {
            assertNotNull(DaemonWidget.present(state(running = true), size, NOW, copy).asOf, "$size")
            assertNotNull(DaemonWidget.present(state(), size, NOW, copy).asOf, "$size")
        }
    }

    @Test
    fun `medium shows verbose capture and an incomplete ledger ahead of the endpoint`() {
        val face = DaemonWidget.present(state(running = true, verbose = true, degraded = true), SizeClass.MEDIUM, NOW, copy)
        assertEquals(
            listOf(Line("DAEMON_VERBOSE", Glyph.VERBOSE, Tone.VIOLET), Line("LEDGER_DEGRADED", Glyph.WARNING, Tone.VIOLET)),
            face.lines,
        )
        val verboseOnly = DaemonWidget.present(state(running = true, verbose = true), SizeClass.MEDIUM, NOW, copy)
        assertEquals(
            listOf(Line("DAEMON_VERBOSE", Glyph.VERBOSE, Tone.VIOLET), Line("DAEMON_ENDPOINT(127.0.0.1|11435)")),
            verboseOnly.lines,
        )
    }

    @Test
    fun `large adds verbose and ledger warnings first, then the endpoint, and the v1 note`() {
        val face = DaemonWidget.present(state(running = true, verbose = true, degraded = true), SizeClass.LARGE, NOW, copy)
        assertEquals(4, face.lines.size)
        assertEquals(Line("DAEMON_VERBOSE", Glyph.VERBOSE, Tone.VIOLET), face.lines[0])
        assertEquals(Line("LEDGER_DEGRADED", Glyph.WARNING, Tone.VIOLET), face.lines[1])
        assertEquals(Line("DAEMON_ENDPOINT(127.0.0.1|11435)"), face.lines[2])
        assertEquals(Line("DAEMON_IDLE"), face.lines[3])
        assertEquals("DAEMON_V1", face.footer)
        assertEquals("AS_OF(t$NOW)", face.asOf)
    }
}
