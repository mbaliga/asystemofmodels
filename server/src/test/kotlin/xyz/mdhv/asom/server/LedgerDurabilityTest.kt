package xyz.mdhv.asom.server

import java.io.File
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import xyz.mdhv.asom.catalogue.CatalogueParser
import xyz.mdhv.asom.catalogue.ProviderKind
import xyz.mdhv.asom.contract.RouteRecord
import xyz.mdhv.asom.server.auth.InMemoryTokenRegistry
import xyz.mdhv.asom.server.driver.FakeDriver
import xyz.mdhv.asom.server.driver.ProviderDriver
import xyz.mdhv.asom.server.keys.InMemoryKeyProvider
import xyz.mdhv.asom.server.ledger.LedgerSink

/**
 * §1.3: a ledger row is owed for egress that ALREADY happened, so the row must
 * be committed before the response is considered complete. A sink that takes
 * real time to commit — as the Room-backed Android one does — proves it: if
 * the server treated `append` as fire-and-forget, the row would still be in
 * flight when the caller has its answer, and a process kill would lose it.
 */
class LedgerDurabilityTest {

    /** Models a durable store: the row exists only after a real commit delay. */
    private class SlowLedger(private val commitMs: Long) : LedgerSink {
        private val rows = CopyOnWriteArrayList<RouteRecord>()

        override suspend fun append(record: RouteRecord) {
            delay(commitMs)
            rows.add(record)
        }

        fun all(): List<RouteRecord> = rows.toList()
    }

    private val catalogue = CatalogueParser.parse(File("../fixtures/catalogue.v1.json").readText())
    private val ledger = SlowLedger(commitMs = 300)
    private val fake = FakeDriver()

    private val server = AsomServer(
        AsomServerConfig(
            port = 0,
            catalogue = { catalogue },
            tokens = InMemoryTokenRegistry().apply { issue("test-token", "test.caller") },
            keys = InMemoryKeyProvider(catalogue.providers.associate { it.id to "fake-key-${it.id}" }),
            drivers = { _: ProviderKind -> fake },
            ledger = ledger,
        ),
    )

    private var port = 0

    @BeforeTest
    fun startServer() {
        server.start(wait = false)
        port = runBlocking { server.resolvedPort() }
    }

    @AfterTest
    fun stopServer() {
        server.stop()
    }

    private fun chatBody(stream: Boolean = false) =
        """{"model":"cheapest","stream":$stream,"messages":[{"role":"user","content":"hi"}]}"""

    @Test
    fun `the row is committed before the response reaches the caller`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/chat/completions"))
                .header("Authorization", "Bearer test-token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(chatBody()))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode())
        // No polling on purpose: the row must ALREADY be durable here.
        assertEquals(1, ledger.all().size, "the response outran its own ledger row")
    }

    @Test
    fun `a client that hangs up mid-stream still gets its row committed`() {
        val body = chatBody(stream = true)
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply {
                write(
                    (
                        "POST /v1/chat/completions HTTP/1.1\r\n" +
                            "Host: 127.0.0.1:$port\r\n" +
                            "Authorization: Bearer test-token\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\n" +
                            "Connection: close\r\n\r\n" + body
                        ).toByteArray(),
                )
                flush()
            }
            // Read just the status line, then vanish mid-body.
            socket.getInputStream().read(ByteArray(64))
        }

        // The stream's row is written after the socket dies, and committing it
        // now suspends. CIO surfaces a hang-up as a write failure rather than
        // cancellation, so the NonCancellable wrapper around the commit is the
        // guard for the paths that DO cancel (engine shutdown mid-stream).
        val deadline = System.currentTimeMillis() + 5_000
        while (ledger.all().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(ledger.all().isNotEmpty(), "a hung-up stream lost the row for egress that happened")
    }
}
