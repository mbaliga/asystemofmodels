package xyz.mdhv.asom.desktop.linux.control

import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import xyz.mdhv.asom.desktop.control.ControlCode
import xyz.mdhv.asom.desktop.control.ControlCommand
import xyz.mdhv.asom.desktop.control.ControlFrames
import xyz.mdhv.asom.desktop.control.ControlRequest
import xyz.mdhv.asom.desktop.control.ControlResponse
import xyz.mdhv.asom.desktop.json.StrictJson

/** The server at the other end is not who it must be (T17(d), the "both directions" check). Nothing was sent to it. */
class ServerIdentityException(message: String) : IOException(message)

/** A command that needs an owner confirmation was not confirmed. Nothing was sent (T17(e)). */
class ConfirmationRequiredException(val command: ControlCommand, message: String) : IllegalStateException(message)

/**
 * The CLI side of the control socket. It reads the SERVER's SO_PEERCRED before it sends anything and refuses a server
 * whose user is not [expectedServerUser] (`asom` in SYSTEM mode, the caller itself in USER mode), so a same-uid or
 * other-uid impostor that bound the path first receives no request. A command that needs an owner confirmation
 * (`pair-confirm`, `restore`, `lan-confirm`) is sent only after [confirm] returned true: the caller wires that to
 * `TtyConfirm`, so the confirmation comes from the controlling terminal and never from the socket.
 */
class ControlClient(
    private val path: Path,
    private val expectedServerUser: String,
    private val peerCreds: PeerCredentialReader = SoPeerCred,
    private val timeoutMs: Long = 10_000,
) {
    fun call(request: ControlRequest, confirm: ((ControlCommand) -> Boolean)? = null): ControlResponse {
        if (request.command.requiresTtyConfirmation) {
            val ok = confirm?.invoke(request.command) ?: false
            if (!ok) throw ConfirmationRequiredException(request.command, "${request.command.wire} needs a confirmation from the controlling terminal")
        }
        if (!peerCreds.isSupported()) throw ServerIdentityException("SO_PEERCRED is not available on this runtime")
        val ch = SocketChannel.open(UnixDomainSocketAddress.of(path))
        val watchdog: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "asom-ctl-client-watchdog").apply { isDaemon = true } }
        try {
            watchdog.schedule({ runCatching { ch.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
            val server = peerCreds.read(ch) ?: throw ServerIdentityException("the server's credentials cannot be read")
            if (server.user != expectedServerUser) {
                throw ServerIdentityException("the socket at $path is served by \"${server.user}\", expected \"$expectedServerUser\"; nothing was sent")
            }
            val input = Channels.newInputStream(ch)
            try {
                ControlFrames.write(Channels.newOutputStream(ch), ControlFrames.encodeRequest(request))
            } catch (e: IOException) {
                // A server that refuses us sends one frame and closes, so our write can fail after its answer was sent.
                val answer = runCatching { ControlFrames.read(input) }.getOrNull() ?: throw e
                return decodeResponse(answer)
            }
            return decodeResponse(ControlFrames.read(input))
        } finally {
            watchdog.shutdownNow()
            runCatching { ch.close() }
        }
    }

    companion object {
        fun decodeResponse(text: String): ControlResponse {
            val o = StrictJson.parse(text) as? JsonObject ?: throw IOException("response is not an object")
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: throw IOException("response has no id")
            val ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: throw IOException("response has no ok")
            val code = (o["code"] as? JsonPrimitive)?.contentOrNull?.let { name -> ControlCode.entries.firstOrNull { it.name == name } }
            val message = (o["message"] as? JsonPrimitive)?.contentOrNull
            return ControlResponse(id, ok, code, message, o["result"] as? JsonObject)
        }
    }
}
