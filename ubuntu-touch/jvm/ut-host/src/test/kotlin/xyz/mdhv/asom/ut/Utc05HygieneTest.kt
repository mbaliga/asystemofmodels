package xyz.mdhv.asom.ut

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue

/** What one run of the node produced. */
class RunResult(val stdout: ByteArray, val stderr: ByteArray, val exit: Int)

/**
 * The hygiene rules of ubuntu-touch.md 3.1 (only frames on stdout, nothing identifying on stderr), as a checker that can be
 * pointed at any captured run, including a deliberately leaky one (UtcNegativeControls).
 */
object Hygiene {
    private val allowedStderr: Set<String> = DiagCode.entries.flatMap { c -> listOf(c.text) + BadFrame.entries.map { "${c.text} ${it.name}" } }.toSet()

    fun violations(run: RunResult, secrets: List<String>, stdoutIsSelfTestLine: Boolean = false): List<String> {
        val out = ArrayList<String>()
        val stdout = String(run.stdout, Charsets.ISO_8859_1)
        val stderr = String(run.stderr, Charsets.ISO_8859_1)
        if (run.stdout.isNotEmpty() && run.stdout.last() != '\n'.code.toByte()) out += "stdout does not end with a newline"
        if ('\r' in stdout) out += "stdout holds a carriage return"
        for (line in stdoutLines(run)) {
            if (stdoutIsSelfTestLine) {
                if (!line.startsWith("{\"selftest\":")) out += "the self-test line does not start with the selftest member"
            } else if (FrameCodec.decodeNode(line.toByteArray(Charsets.UTF_8)) !is Decoded.Ok) {
                out += "a stdout line is not a protocol frame"
            }
        }
        for (line in stderr.lines().filter { it.isNotEmpty() }) if (line !in allowedStderr) out += "a stderr line is outside the fixed set"
        for (secret in secrets) {
            if (secret in stdout || secret in stderr) out += "secret $secret appears in the output"
            val utf8 = secret.toByteArray(Charsets.UTF_8)
            if (indexOf(run.stdout, utf8) >= 0 || indexOf(run.stderr, utf8) >= 0) out += "secret bytes of $secret appear in the output"
        }
        return out
    }

    fun stdoutLines(run: RunResult): List<String> = String(run.stdout, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }

    fun stderrLines(run: RunResult): List<String> = String(run.stderr, Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

/** UTC05: stdout and stderr hygiene, in process and in a real child JVM. */
class Utc05HygieneTest {
    private fun tempDir(): File = Files.createTempDirectory("asom-ut-utc05-").toFile()

    private fun buildInput(lines: List<JValue>): ByteArray {
        val out = ByteArrayOutputStream()
        for (l in lines) {
            val bytes = when (l) {
                is JString -> l.value.toByteArray(Charsets.UTF_8)
                is JObject -> ByteArray(l.int("padTo").toInt()) { 'a'.code.toByte() }
                else -> fail("bad line spec")
            }
            out.write(bytes)
            out.write('\n'.code)
        }
        return out.toByteArray()
    }

    private fun runInProcess(input: ByteArray, ledgerUnavailable: Boolean): RunResult {
        val home = tempDir()
        try {
            val vars = mapOf("HOME" to home.absolutePath, "TMPDIR" to home.absolutePath)
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val writer = FrameWriter(out)
            val session = NodeSession(
                reader = LineReader(ByteArrayInputStream(input)),
                out = { writer.write(it) },
                lifecycle = NodeLifecycle(FakeClock()),
                diag = Diag(err),
                openLedger = { if (ledgerUnavailable) null else FileLedger.open(home.resolve("ledger.jsonl").toPath()) },
                selfTest = { SelfTest(vars).run() },
            )
            val exit = session.run()
            return RunResult(out.toByteArray(), err.toByteArray(), exit)
        } finally {
            home.deleteRecursively()
        }
    }

    private fun runChild(mainClass: String, args: List<String>, stdin: ByteArray, user: String, home: String?): RunResult {
        val tmp = tempDir()
        try {
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val cp = System.getProperty("asom.testClasspath") ?: error("asom.testClasspath is not set")
            val pb = ProcessBuilder(listOf(java, "-Duser.name=$user", "-Dfile.encoding=UTF-8", "-Xmx256m", "-cp", cp, mainClass) + args)
            val env = pb.environment()
            env.remove("JAVA_TOOL_OPTIONS")
            env.remove("JDK_JAVA_OPTIONS")
            env.remove("_JAVA_OPTIONS")
            env["HOME"] = home ?: tmp.absolutePath
            env["TMPDIR"] = tmp.absolutePath
            val p = pb.start()
            val outBuf = ByteArrayOutputStream()
            val errBuf = ByteArrayOutputStream()
            val t1 = Thread { p.inputStream.copyTo(outBuf) }.also { it.start() }
            val t2 = Thread { p.errorStream.copyTo(errBuf) }.also { it.start() }
            try {
                p.outputStream.use { it.write(stdin) }
            } catch (_: java.io.IOException) {
            }
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                fail("the child JVM did not finish in 90 s")
            }
            t1.join()
            t2.join()
            return RunResult(outBuf.toByteArray(), errBuf.toByteArray(), p.exitValue())
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun typesOf(run: RunResult): List<String> = Hygiene.stdoutLines(run).map { line ->
        val d = FrameCodec.decodeNode(line.toByteArray(Charsets.UTF_8)) as Decoded.Ok
        ((parseJ(String(FrameCodec.encode(d.frame), Charsets.UTF_8)) as JObject)["t"] as JString).value
    }

    @Test
    fun vectors() {
        val vectors = UtcVectors.load("UTC05-hygiene.json", "UTC05")
        val laws = Laws("UTC05")
        for (v in vectors) {
            val expect = v.expectOk!!.asObj()
            val secrets = v.input.let { i -> (i["secrets"] as? xyz.mdhv.asom.lab.json.JArray)?.items?.map { it.asStr() } ?: emptyList() }
            val child = v.input.bool("child")
            val mode = v.input.str("mode")
            val run: RunResult = when (mode) {
                "session" -> {
                    val input = buildInput(v.input.arr("lines"))
                    val inProc = runInProcess(input, v.input.strOrNull("ledger") == "unavailable")
                    if (!child) inProc else {
                        val c = runChild("xyz.mdhv.asom.ut.MainKt", listOf("--profile=ut"), input, "tester", null)
                        val stable = { r: RunResult -> Hygiene.stdoutLines(r).map { if (it.startsWith("{\"t\":\"selftest\"")) "{\"t\":\"selftest\"...}" else it } }
                        assertEquals(stable(inProc), stable(c), "${v.id}: child stdout differs from the in-process run (a self-test result differs by design: heap, RSS and timings)")
                        assertEquals(Hygiene.stderrLines(inProc), Hygiene.stderrLines(c), "${v.id}: child stderr differs from the in-process run")
                        assertEquals(inProc.exit, c.exit, "${v.id}: child exit differs")
                        laws.bump("child-equals-inprocess")
                        c
                    }
                }
                "selftest" -> runChild("xyz.mdhv.asom.ut.MainKt", listOf("--selftest"), ByteArray(0), "tester", null)
                "args" -> runChild(
                    "xyz.mdhv.asom.ut.MainKt", v.input.arr("args").map { it.asStr() }, ByteArray(0), v.input.str("user"),
                    v.input.strOrNull("home"),
                )
                else -> fail("${v.id}: unknown mode $mode")
            }
            val selfTestLine = mode == "selftest"
            val problems = Hygiene.violations(run, secrets, stdoutIsSelfTestLine = selfTestLine)
            assertTrue(problems.isEmpty(), "${v.id}: ${v.description}: $problems")
            laws.bump(if (child) "checked-child-jvm" else "checked-in-process")
            if (secrets.isNotEmpty()) laws.bump("secrets-absent")
            val wantStdout = expect.arr("stdout").map { it.asStr() }
            if (selfTestLine) {
                assertEquals(1, Hygiene.stdoutLines(run).size, "${v.id}: --selftest must print exactly one line")
                assertTrue(Hygiene.stdoutLines(run)[0].startsWith("{\"selftest\":\"ok\""), "${v.id}: ${Hygiene.stdoutLines(run)[0]}")
                laws.bump("selftest-one-line")
            } else {
                assertEquals(wantStdout, typesOf(run), "${v.id}: ${v.description}: stdout frame types")
                laws.bump("stdout-only-frames")
            }
            assertEquals(expect.arr("stderr").map { it.asStr() }, Hygiene.stderrLines(run), "${v.id}: ${v.description}: stderr")
            if (run.stderr.isNotEmpty()) laws.bump("stderr-fixed-line")
            assertEquals(expect.int("exit").toInt(), run.exit, "${v.id}: ${v.description}: exit code")
            laws.bump("exit-${run.exit}")
        }
        laws.requireAll(
            setOf(
                "checked-child-jvm", "checked-in-process", "secrets-absent", "child-equals-inprocess", "stdout-only-frames", "selftest-one-line",
                "stderr-fixed-line", "exit-0", "exit-2", "exit-65", "exit-78",
            ),
            minimumVectors = 28, vectors = vectors.size,
        )
    }

    @Test
    fun strayPrintsFromAnyLibraryCannotReachTheChannel() {
        val run = runChild("xyz.mdhv.asom.ut.StrayPrintChild", emptyList(), ByteArray(0), "tester", null)
        assertEquals(0, run.exit)
        assertEquals("""{"t":"sas","code":"123 456"}""" + "\n", String(run.stdout, Charsets.UTF_8), "stdout must hold the one frame and nothing else")
        assertEquals(0, run.stderr.size, "stderr must be empty, not ${String(run.stderr)}")
        assertTrue(Hygiene.violations(run, listOf("SECRET-STRAY", "SECRET-IN-TRACE", "SECRET-NO-NEWLINE")).isEmpty())
    }

    @Test
    fun anUncaughtExceptionSaysOneFixedLineAndNeverItsMessage() {
        val run = runChild("xyz.mdhv.asom.ut.CrashChild", emptyList(), ByteArray(0), "tester", null)
        assertEquals(CrashGuard.EXIT_INTERNAL, run.exit)
        assertEquals(0, run.stdout.size)
        assertEquals(listOf("asom-ut: internal error"), Hygiene.stderrLines(run))
        assertTrue(Hygiene.violations(run, listOf("SECRET-IN-EXCEPTION-MESSAGE")).isEmpty())
    }

    @Test
    fun aRunThatLeaksIsCaughtByTheChecker() {
        val secrets = listOf("SECRET-PROMPT-7f3a91")
        val cases = mapOf(
            "a secret in a frame" to RunResult("""{"t":"error","rid":"SECRET-PROMPT-7f3a91","code":"NO_PROVIDER_KEY"}""".toByteArray() + '\n'.code.toByte(), ByteArray(0), 0),
            "a secret on stderr" to RunResult(ByteArray(0), "asom-ut: internal error SECRET-PROMPT-7f3a91\n".toByteArray(), 70),
            "a stray line on stdout" to RunResult("hello world\n".toByteArray(), ByteArray(0), 0),
            "stderr outside the fixed set" to RunResult(ByteArray(0), "java.lang.NullPointerException\n".toByteArray(), 1),
            "a frame with no newline" to RunResult("""{"t":"sas","code":"1"}""".toByteArray(), ByteArray(0), 0),
            "a secret in a chunk" to RunResult("""{"t":"chunk","rid":"r","delta":"SECRET-PROMPT-7f3a91"}""".toByteArray() + '\n'.code.toByte(), ByteArray(0), 0),
        )
        for ((name, run) in cases) assertTrue(Hygiene.violations(run, secrets).isNotEmpty(), "the checker missed: $name")
        assertTrue(Hygiene.violations(RunResult("""{"t":"sas","code":"1"}""".toByteArray() + '\n'.code.toByte(), ByteArray(0), 0), secrets).isEmpty(), "a clean run was flagged")
    }
}
