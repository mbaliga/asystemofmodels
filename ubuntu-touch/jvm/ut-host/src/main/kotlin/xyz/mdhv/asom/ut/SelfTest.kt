package xyz.mdhv.asom.ut

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Comparator
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedTrustManager
import xyz.mdhv.asom.lab.conformance.FamilyResult
import xyz.mdhv.asom.lab.conformance.VectorLoader
import xyz.mdhv.asom.lab.conformance.checkerFor
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.ledger.JsonlReader
import xyz.mdhv.asom.lab.ledger.JsonlSink
import xyz.mdhv.asom.lab.ledger.LabEgress
import xyz.mdhv.asom.lab.ledger.LabRouteRecord
import xyz.mdhv.asom.lab.manifest.EcKeyPair
import xyz.mdhv.asom.lab.manifest.Es256
import xyz.mdhv.asom.lab.manifest.Spki

/**
 * UT0.3: facts about the runtime the node actually runs on, and the checks that need no network. It opens no socket: the TLS
 * 1.3 handshake is between two in-memory `SSLEngine`s. The result is one JSON object whose first member is `"selftest"`.
 * `thermalReadable` and `batteryReadable` are recorded, not asserted: a confined app is expected to get `false` (UF11).
 *
 * The lab families run here are M01 to M03 (pure). W00 to W03 and W01b drive a real server on a loopback listener, and UT-0
 * ships no listener even for a test, so they are not run (ERRATA ERR-UT-SELFTEST-1).
 */
class SelfTest(private val env: Map<String, String>, private val ledgerRows: Int = LEDGER_APPENDS) {
    private val failures = ArrayList<String>()
    private val details = ArrayList<String>()

    fun run(): JObject {
        val layout = try {
            UtPaths.resolve(env)
        } catch (e: xyz.mdhv.asom.desktop.HostRefusedException) {
            null
        }
        val tmpRoot = scratchRoot(layout)
        val work = Files.createTempDirectory(tmpRoot, "asom-ut-selftest-")
        try {
            val members = ArrayList<Pair<String, JValue>>()
            members += "selftest" to JString("pending")
            members += "profile" to JString("ut")
            members += "runtime" to runtimeFacts()
            members += "sha256" to step("sha256") { sha256Known() }
            members += "es256" to step("es256") { es256() }
            members += tlsMembers()
            members += "vectors" to vectors(work)
            members += "jsonl" to jsonl(work)
            members += "paths" to paths(layout)
            members += "thermalReadable" to JBool(readableUnder("/sys/class/thermal", "thermal_zone", "temp"))
            members += "batteryReadable" to JBool(readableUnder("/sys/class/power_supply", "", "capacity"))
            members += "rssKiB" to JInt(rssKiB())
            members[0] = "selftest" to JString(if (failures.isEmpty()) "ok" else "fail")
            if (failures.isNotEmpty()) {
                members += "failed" to JArray(failures.map { JString(it) })
                members += "failedDetail" to JArray(details.map { JString(it) })
            }
            return JObject(members)
        } finally {
            deleteTree(work)
        }
    }

    private fun scratchRoot(layout: UtLayout?): Path {
        if (layout != null) {
            try {
                UtPaths.privateDir(layout.tmpDir)
                return layout.tmpDir
            } catch (_: Exception) {
            }
        }
        return Paths.get(System.getProperty("java.io.tmpdir"))
    }

    private fun step(name: String, body: () -> Unit): JString {
        return try {
            body()
            JString("ok")
        } catch (e: Throwable) {
            failures += name
            details += "$name: ${e::class.simpleName}: ${e.message?.take(160)}"
            JString("fail")
        }
    }

    private fun runtimeFacts(): JObject = JObject(
        listOf(
            "javaVersion" to JString(System.getProperty("java.version") ?: "?"),
            "vm" to JString(System.getProperty("java.vm.name") ?: "?"),
            "osArch" to JString(System.getProperty("os.arch") ?: "?"),
            "osName" to JString(System.getProperty("os.name") ?: "?"),
            "cpus" to JInt(Runtime.getRuntime().availableProcessors().toLong()),
            "maxHeapMiB" to JInt(Runtime.getRuntime().maxMemory() / (1024 * 1024)),
            "utf8" to JBool(java.nio.charset.Charset.defaultCharset() == Charsets.UTF_8),
        ),
    )

    private fun sha256Known() {
        val got = Hex.encode(MessageDigest.getInstance("SHA-256").digest("abc".toByteArray(Charsets.US_ASCII)))
        check(got == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad") { "SHA-256 of abc is wrong" }
    }

    private fun es256() {
        val key = Es256.generate()
        val msg = "asom-ut selftest".toByteArray(Charsets.US_ASCII)
        val sig = Es256.sign(key.private, msg)
        val pub = Spki.strict(key.spki) ?: error("strict SPKI refused the JDK's own key")
        check(Es256.verify(pub, msg, sig)) { "signature did not verify" }
        val other = Es256.generate()
        check(!Es256.verify(Spki.strict(other.spki)!!, msg, sig)) { "signature verified under another key" }
        val tampered = sig.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        check(!Es256.verify(pub, msg, tampered)) { "a tampered signature verified" }
    }

    private fun tlsMembers(): List<Pair<String, JValue>> {
        var tls = "none"
        var alpn = "none"
        var clientAuth = false
        var mismatchRefused = false
        val status = step("tls") {
            val result = TlsInMemory.handshake(pinMismatch = false)
            tls = result.protocol
            alpn = result.alpn
            clientAuth = result.clientCertificateSeen
            check(tls == "TLSv1.3") { "protocol is $tls" }
            check(alpn == "asom-mesh/1") { "alpn is $alpn" }
            check(clientAuth) { "the server saw no client certificate" }
            mismatchRefused = TlsInMemory.handshakeRefused()
            check(mismatchRefused) { "a client pinned to another key completed the handshake" }
        }
        return listOf(
            "tls" to JString(tls), "alpn" to JString(alpn), "clientAuth" to JBool(clientAuth),
            "pinMismatchRefused" to JBool(mismatchRefused), "tlsCheck" to status,
        )
    }

    private fun vectors(work: Path): JObject {
        val counts = ArrayList<Pair<String, JValue>>()
        var failed = 0L
        val status = step("vectors") {
            val root = extractVectors(work.resolve("vectors"))
            val loaded = VectorLoader.loadAll(root.toFile())
            check(loaded.problems.isEmpty()) { "vector envelope problems: ${loaded.problems.size}" }
            for (family in listOf("M01", "M02", "M03")) {
                val checker = checkerFor(family) ?: error("no checker for $family")
                val results = loaded.forFamily(family).map { it to checker.check(it) }
                val r = FamilyResult(family, checker, results)
                counts += family to JInt(r.normativeCount.toLong())
                failed += r.fail
                check(r.vacuity().isEmpty()) { "family $family is vacuous" }
                check(r.normativeCount > 0) { "family $family has no vectors" }
            }
            check(failed == 0L) { "$failed vectors failed" }
        }
        counts += "failed" to JInt(failed)
        counts += "vectorsCheck" to status
        return JObject(counts)
    }

    private fun extractVectors(into: Path): Path {
        Files.createDirectories(into)
        val loader = SelfTest::class.java
        val names = loader.getResourceAsStream("/asom-ut/vectors/FILES.txt")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?.lines()?.filter { it.isNotBlank() } ?: error("the vector list is missing from the jar")
        for (name in names + "VERSION") {
            val bytes = loader.getResourceAsStream("/asom-ut/vectors/$name")?.use { it.readBytes() } ?: error("missing vector file $name")
            val target = into.resolve(name)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }
        return into
    }

    private fun jsonl(work: Path): JObject {
        var elapsedMs = 0L
        val status = step("jsonl") {
            val file = work.resolve("ledger").resolve("ledger.jsonl")
            Files.createDirectories(file.parent)
            val t0 = System.nanoTime()
            JsonlSink(file).use { sink ->
                for (i in 0 until ledgerRows) sink.append(sampleRow(i))
            }
            elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val read = JsonlReader.read(file)
            check(read.rows.size == ledgerRows && read.tornTailBytes == 0) { "read back ${read.rows.size} rows, torn tail ${read.tornTailBytes}" }
            check(read.rows.first() == sampleRow(0)) { "the first row did not round-trip" }
        }
        return JObject(listOf("appends" to JInt(ledgerRows.toLong()), "forced" to JBool(status.value == "ok"), "ms" to JInt(elapsedMs), "check" to status))
    }

    private fun sampleRow(i: Int) = LabRouteRecord(
        ts = 1_000_000L + i, callerPkg = "self-ui:${UtPaths.PKG}", requestedModel = "selftest", egress = LabEgress.LOCAL, latencyMs = 0, status = 200,
    )

    private fun paths(layout: UtLayout?): JObject {
        if (layout == null) {
            failures += "paths"
            return JObject(listOf("resolved" to JBool(false)))
        }
        val dirs = listOf(
            "data" to layout.paths.dataDir, "ledger" to layout.paths.ledgerDir, "identity" to layout.paths.identityDir,
            "cache" to layout.cacheDir, "config" to layout.configDir, "tmp" to layout.tmpDir,
        )
        val members = ArrayList<Pair<String, JValue>>()
        members += "resolved" to JBool(true)
        for ((name, dir) in dirs) {
            val state = try {
                UtPaths.privateDir(dir)
                val probe = Files.createTempFile(dir, ".probe-", ".tmp")
                Files.delete(probe)
                "writable"
            } catch (e: Exception) {
                failures += "path-$name"
                "unwritable"
            }
            members += name to JString(state)
        }
        return JObject(members)
    }

    private fun readableUnder(dir: String, prefix: String, leaf: String): Boolean = try {
        Files.newDirectoryStream(Paths.get(dir)).use { s ->
            s.any { entry ->
                entry.fileName.toString().startsWith(prefix) && runCatching { Files.readAllBytes(entry.resolve(leaf)).isNotEmpty() }.getOrDefault(false)
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun rssKiB(): Long = try {
        Files.readAllLines(Paths.get("/proc/self/status")).firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun deleteTree(dir: Path) {
        try {
            Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val LEDGER_APPENDS = 100
    }
}

/** What an in-memory handshake observed. */
class TlsResult(val protocol: String, val alpn: String, val clientCertificateSeen: Boolean)

/**
 * A TLS 1.3 handshake between two `SSLEngine`s in one process, mutual authentication, ALPN `asom-mesh/1`, each side pinned to
 * the other's public key by a custom trust manager and no hostname check. Nothing here is the trust.md 3.2 verifier: it is a
 * self-test of the JSSE behaviours the requester will rely on, run on the runtime that will run it. Certificates are
 * self-signed with a minimal hand-written DER encoder (no BouncyCastle, no `sun.*` classes).
 */
object TlsInMemory {
    private val PASSWORD = "selftest".toCharArray()

    fun handshake(pinMismatch: Boolean): TlsResult {
        val a = Es256.generate()
        val b = Es256.generate()
        val other = Es256.generate()
        val certA = SelfSigned.certificate(a)
        val certB = SelfSigned.certificate(b)
        val client = engine(a, certA, if (pinMismatch) other.spki else b.spki, clientMode = true)
        val server = engine(b, certB, a.spki, clientMode = false)
        val cSide = Side(client)
        val sSide = Side(server)
        client.beginHandshake()
        server.beginHandshake()
        var guard = 0
        do {
            val p1 = pump(cSide, sSide)
            val p2 = pump(sSide, cSide)
            check(++guard < 200) { "handshake did not settle" }
        } while (p1 || p2)
        check(client.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) { "client handshake unfinished: ${client.handshakeStatus}" }
        check(server.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) { "server handshake unfinished: ${server.handshakeStatus}" }
        val ping = "ping".toByteArray(Charsets.US_ASCII)
        val src = ByteBuffer.wrap(ping)
        var wraps = 0
        while (src.hasRemaining()) {
            val r = client.wrap(src, cSide.out)
            check(r.status == SSLEngineResult.Status.OK) { "application wrap: ${r.status}" }
            check(++wraps < 20) { "the client never accepted the application data" }
        }
        cSide.out.flip()
        val got = ByteBuffer.allocate(server.session.applicationBufferSize)
        var unwraps = 0
        while (cSide.out.hasRemaining()) {
            val u = server.unwrap(cSide.out, got)
            check(u.status == SSLEngineResult.Status.OK) { "application unwrap: ${u.status}" }
            check(++unwraps < 50) { "application data did not arrive" }
            while (server.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) server.delegatedTask?.run()
        }
        got.flip()
        val received = ByteArray(got.remaining()).also { got.get(it) }
        check(received.contentEquals(ping)) { "application data changed in transit (${received.size} bytes)" }
        val clientCert = try {
            server.session.peerCertificates.size == 1
        } catch (e: SSLException) {
            false
        }
        return TlsResult(client.session.protocol, client.applicationProtocol ?: "", clientCert)
    }

    /** A client pinned to the wrong server key must not complete: JSSE surfaces the trust manager's refusal as an SSLException. */
    fun handshakeRefused(): Boolean = try {
        handshake(pinMismatch = true)
        false
    } catch (e: SSLException) {
        true
    } catch (e: IllegalStateException) {
        false
    }

    private class Side(val engine: SSLEngine) {
        val out: ByteBuffer = ByteBuffer.allocate(1 shl 16)
        val app: ByteBuffer = ByteBuffer.allocate(1 shl 16)
    }

    private fun pump(a: Side, b: Side): Boolean {
        var progressed = false
        while (true) {
            when (a.engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var t = a.engine.delegatedTask
                    while (t != null) {
                        t.run()
                        t = a.engine.delegatedTask
                    }
                    progressed = true
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val r = a.engine.wrap(ByteBuffer.allocate(0), a.out)
                    check(r.status == SSLEngineResult.Status.OK) { "wrap: ${r.status}" }
                    progressed = true
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP, SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN -> {
                    b.out.flip()
                    if (!b.out.hasRemaining()) {
                        b.out.compact()
                        return progressed
                    }
                    val r = a.engine.unwrap(b.out, a.app)
                    b.out.compact()
                    if (r.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) return progressed
                    check(r.status == SSLEngineResult.Status.OK) { "unwrap: ${r.status}" }
                    progressed = true
                }
                else -> return progressed
            }
        }
    }

    private fun engine(key: EcKeyPair, cert: X509Certificate, trustedSpki: ByteArray, clientMode: Boolean): SSLEngine {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("node", key.private, PASSWORD, arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, PASSWORD)
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(kmf.keyManagers, arrayOf(PinnedTrust(trustedSpki)), SecureRandom())
        val engine = ctx.createSSLEngine()
        engine.useClientMode = clientMode
        val p = engine.sslParameters
        p.protocols = arrayOf("TLSv1.3")
        p.applicationProtocols = arrayOf("asom-mesh/1")
        if (!clientMode) p.needClientAuth = true
        engine.sslParameters = p
        return engine
    }

    private class PinnedTrust(private val spki: ByteArray) : X509ExtendedTrustManager() {
        private fun verify(chain: Array<X509Certificate>?) {
            if (chain == null || chain.size != 1) throw CertificateException("exactly one certificate expected")
            if (!MessageDigest.isEqual(chain[0].publicKey.encoded, spki)) throw CertificateException("public key is not the pinned key")
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = verify(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = verify(chain)
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, socket: java.net.Socket?) = verify(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, socket: java.net.Socket?) = verify(chain)
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?, engine: SSLEngine?) = verify(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}

/** A self-signed ES256 certificate from a few dozen lines of DER. */
object SelfSigned {
    private val OID_ECDSA_SHA256 = byteArrayOf(0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)
    private val OID_COMMON_NAME = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)

    fun certificate(key: EcKeyPair): X509Certificate {
        val algorithm = tlv(0x30, OID_ECDSA_SHA256)
        val name = tlv(0x30, tlv(0x31, tlv(0x30, OID_COMMON_NAME + tlv(0x0C, "asom-ut-selftest".toByteArray(Charsets.US_ASCII)))))
        val now = System.currentTimeMillis()
        val validity = tlv(0x30, utc(now - DAY_MS) + utc(now + DAY_MS))
        val serial = tlv(0x02, byteArrayOf(0x01) + ByteArray(8).also { SecureRandom().nextBytes(it) })
        val version = tlv(0xA0, tlv(0x02, byteArrayOf(0x02)))
        val tbs = tlv(0x30, version + serial + algorithm + name + validity + name + key.spki)
        val sig = java.security.Signature.getInstance("SHA256withECDSA")
        sig.initSign(key.private)
        sig.update(tbs)
        val der = tlv(0x30, tbs + algorithm + tlv(0x03, byteArrayOf(0x00) + sig.sign()))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        cert.verify(cert.publicKey)
        return cert
    }

    private const val DAY_MS = 86_400_000L

    private fun utc(ms: Long): ByteArray {
        val f = java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss").withZone(java.time.ZoneOffset.UTC)
        return tlv(0x17, (f.format(java.time.Instant.ofEpochMilli(ms)) + "Z").toByteArray(Charsets.US_ASCII))
    }

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val n = body.size
        val len = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + len + body
    }
}
