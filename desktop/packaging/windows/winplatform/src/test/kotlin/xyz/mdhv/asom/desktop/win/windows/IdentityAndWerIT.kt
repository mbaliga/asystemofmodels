package xyz.mdhv.asom.desktop.win.windows

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import xyz.mdhv.asom.desktop.win.acl.WellKnownSid
import xyz.mdhv.asom.desktop.win.fakes.Report
import xyz.mdhv.asom.desktop.win.jna.JnaIdentity
import xyz.mdhv.asom.desktop.win.jna.JnaSidResolver
import xyz.mdhv.asom.desktop.win.jna.JnaWer

/** CI-ONLY (hosted Windows runner; SKIPPED elsewhere). */
@EnabledOnOs(OS.WINDOWS)
class IdentityIT {
    @Test
    fun `the current user SID and the well-known SID lookups resolve`() {
        val sid = assertNotNull(JnaIdentity.currentUserSid())
        Report.line("IT IdentityIT: current user SID has the shape ${sid.replace(Regex("\\d+"), "N")}")
        assertTrue(Regex("^S-1-5-(21-\\d+-\\d+-\\d+-\\d+|\\d+)$").matches(sid), sid)
        val r = JnaSidResolver()
        assertEquals(WellKnownSid.ADMINISTRATORS, r.sidOf("BUILTIN\\Administrators"))
        assertTrue(r.accountNameOf(WellKnownSid.SYSTEM)!!.contains("SYSTEM", ignoreCase = true))
        assertTrue(r.accountNameOf(WellKnownSid.EVERYONE)!!.contains("Everyone", ignoreCase = true))
    }
}

/** CI-ONLY. Uses the per-user (HKCU) exclusion list with a made-up executable name; the machine-wide list is the installer's. */
@EnabledOnOs(OS.WINDOWS)
class WerIT {
    @Test
    fun `WerAddExcludedApplication and its removal succeed for a made-up name, and the JVM can never be excluded`() {
        val exe = "asom-it-wer-test.exe"
        assertEquals(0, JnaWer.add(exe), "S_OK")
        assertEquals(0, JnaWer.remove(exe), "S_OK")
        assertFailsWith<IllegalArgumentException> { JnaWer.add("java.exe") }
        assertFailsWith<IllegalArgumentException> { JnaWer.add("JAVAW.EXE") }
    }
}
