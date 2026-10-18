package xyz.mdhv.asom.desktop

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.mdhv.asom.desktop.cli.AsomCli
import xyz.mdhv.asom.desktop.ledger.JsonlLedgerSink
import xyz.mdhv.asom.desktop.ledger.harnessRow

/**
 * H3 / T17(c): a token, a key, or a ledger row never reaches stdout or stderr. The entry points run with secrets planted
 * in the environment, the ledger takes rows, and everything written to the injected streams AND to the process's real
 * System.out / System.err is scanned. (A forked-JVM variant that runs the real launcher lives in :node.)
 */
class NoSecretsOnStdoutTest {
    private val secrets = listOf(
        "asom-dev-token-7f3c9a1e5b2d4086", "sk-live-SECRETSECRETSECRET1234", "or-key-ZZZZ-9876-ZZZZ", "hunter2-passphrase-xyzzy",
    )
    private val vars = mapOf(
        "ASOM_DEV_TOKEN" to secrets[0], "ASOM_KEY_OPENROUTER" to secrets[1], "OPENAI_API_KEY" to secrets[2], "ASOM_PASSPHRASE" to secrets[3],
    )

    @Test
    fun `no secret, token, key or ledger row appears on any output stream`() {
        val realOut = ByteArrayOutputStream()
        val realErr = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(realOut, true, Charsets.UTF_8))
        System.setErr(PrintStream(realErr, true, Charsets.UTF_8))
        val cap = Captured()
        val dir = Files.createTempDirectory("asom-nosecrets-")
        try {
            val env = cap.env("alice", vars)
            val lookup = HostFinder.find(listOf(FakePlatform()))
            NodeMain.run(listOf("--foreground"), env, lookup) { it.enableLending(); it.tick(); it.shutdown() }
            NodeMain.run(listOf("--mode=selftest"), env, lookup) {}
            val platform = FakePlatform()
            AsomCli(env, platform).run(listOf("status", "--json"))
            AsomCli(env, platform).run(listOf("status"))
            AsomCli(env, platform).run(listOf("chat", "hello", "--json"))
            AsomCli(env, platform).run(listOf("ledger", "export"))
            JsonlLedgerSink.open(dir.resolve("ledger.jsonl")).use { sink ->
                runBlocking { sink.append(harnessRow(1).copy(callerPkg = "local-uid:alice", requestedModel = secrets[1])) }
            }
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
            dir.toFile().deleteRecursively()
        }
        val all = cap.outText + cap.errText + realOut.toString(Charsets.UTF_8) + realErr.toString(Charsets.UTF_8)
        assertTrue(all.isNotBlank(), "non-vacuity: the entry points did print (banner, status)")
        for (s in secrets) assertFalse(s in all, "secret leaked to an output stream: $s")
        assertFalse("[ledger]" in all, "a ledger row was printed")
        assertFalse("RouteRecord(" in all, "a RouteRecord was printed")
        assertFalse(Regex("(?i)bearer\\s+\\S").containsMatchIn(all), "an Authorization header value appeared")
        assertTrue(realOut.size() == 0 && realErr.size() == 0, "nothing may bypass the injected streams and write to System.out or System.err")
    }
}
