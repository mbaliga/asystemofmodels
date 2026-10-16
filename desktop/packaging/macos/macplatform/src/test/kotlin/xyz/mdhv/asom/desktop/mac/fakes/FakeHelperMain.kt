package xyz.mdhv.asom.desktop.mac.fakes

import java.io.File
import kotlin.system.exitProcess
import xyz.mdhv.asom.desktop.mac.helper.Event
import xyz.mdhv.asom.desktop.mac.helper.Fields
import xyz.mdhv.asom.desktop.mac.helper.HValue
import xyz.mdhv.asom.desktop.mac.helper.HelperCodec
import xyz.mdhv.asom.desktop.mac.helper.Reject
import xyz.mdhv.asom.desktop.mac.helper.RejectCode
import xyz.mdhv.asom.desktop.mac.helper.Reply
import xyz.mdhv.asom.desktop.mac.helper.Request

/**
 * A helper as a real child process: it speaks the wire protocol on stdin and stdout, answering from the fixture machine and the fake
 * enclave. Options (each one a way a real helper can go wrong):
 *   --die-after=N     exit(3) after answering N requests (a crash)
 *   --hang-on=OP      never answer OP
 *   --garbage         answer the first request with a line that is not a reply
 *   --wrong-hello     answer hello with a protocol violation (unknown field)
 *   --events          push a power event and a sleep.will (token 7) right after the hello reply
 *   --noise           write a lot to stderr (it must be discarded, never block the helper)
 *   --marker=PATH     on stdin EOF write "eof" to PATH and exit 0 (proof that closing stdin is the shutdown)
 *   --env-out=PATH    write the names and values of the environment to PATH
 *   --start-count=P   append a line to P each time the process starts (counts restarts)
 *   --overlong        answer the first request with a line longer than the protocol allows
 */
fun main(args: Array<String>) {
    val opts = args.associate { a -> a.removePrefix("--").let { it.substringBefore('=') to it.substringAfter('=', "") } }
    opts["env-out"]?.let { p -> File(p).writeText(System.getenv().entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }, Charsets.UTF_8) }
    opts["start-count"]?.let { p -> File(p).appendText("started\n") }
    if ("noise" in opts) Thread { repeat(2000) { System.err.println("noise ".repeat(20)) } }.also { it.isDaemon = true }.start()
    val dieAfter = opts["die-after"]?.toInt()
    val hangOn = opts["hang-on"]
    val enclave = FakeEnclave()
    var answered = 0
    var garbageDone = false
    val out = System.out
    fun emit(line: ByteArray) {
        synchronized(out) {
            out.write(line)
            out.write('\n'.code)
            out.flush()
        }
    }

    val input = System.`in`.bufferedReader(Charsets.UTF_8)
    while (true) {
        val text = input.readLine() ?: break
        if ("garbage" in opts && !garbageDone) {
            garbageDone = true
            emit("this is not a reply".toByteArray())
            continue
        }
        if ("overlong" in opts && !garbageDone) {
            garbageDone = true
            emit(ByteArray(200_000) { 'x'.code.toByte() })
            continue
        }
        val reply: Reply = try {
            val req: Request = HelperCodec.decodeRequest(text.toByteArray(Charsets.UTF_8))
            if (hangOn == req.op) continue
            if ("wrong-hello" in opts && req.op == "hello") {
                emit("{\"ok\":true,\"id\":${req.id},\"surprise\":1}".toByteArray())
                continue
            }
            enclave.answer(req) ?: FixtureMachine.answer(req)
        } catch (e: Reject) {
            Reply.Failure(e.id, if (e.code == RejectCode.UNKNOWN_OP) "UNKNOWN_OP" else "BAD_REQUEST", e.code.wire)
        }
        emit(HelperCodec.encode(reply))
        answered++
        if ("events" in opts && reply is Reply.Success && reply.op == "hello") {
            emit(HelperCodec.encode(Event("power", Fields.of("source" to HValue.T("battery"), "charging" to HValue.B(false), "batteryPermille" to HValue.I(420), "lowPower" to HValue.B(true)))))
            emit(HelperCodec.encode(Event("sleep.will", Fields.of("token" to HValue.I(7)))))
        }
        if (dieAfter != null && answered >= dieAfter) exitProcess(3)
    }
    opts["marker"]?.let { File(it).writeText("eof", Charsets.UTF_8) }
    exitProcess(0)
}
