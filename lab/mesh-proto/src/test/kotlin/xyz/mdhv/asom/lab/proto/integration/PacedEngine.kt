package xyz.mdhv.asom.lab.proto.integration

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import xyz.mdhv.asom.lab.proto.session.EngineEvent
import xyz.mdhv.asom.lab.proto.session.EnginePort
import xyz.mdhv.asom.lab.proto.session.EngineRequest
import xyz.mdhv.asom.lab.proto.session.EngineRun
import xyz.mdhv.asom.lab.proto.session.Ev
import xyz.mdhv.asom.lab.proto.session.Log
import xyz.mdhv.asom.lab.proto.session.ModelOffer
import xyz.mdhv.asom.lab.proto.wire.Terminal

/**
 * The scripted fake engine of the session track, with one addition for real sockets: [paceNanos] of pause before each event, so that a `CANCEL` or a reset that
 * the test sends after it saw the Nth chunk reaches the lender while the stream is still running. Nothing generates tokens.
 */
class PacedEngine(private val node: String, private val log: Log) : EnginePort {
    val models = linkedMapOf("m1" to ModelOffer("m1", "a".repeat(64)), "m2" to ModelOffer("m2", "b".repeat(64)))
    var script: (EngineRequest) -> List<Any> = {
        listOf(EngineEvent.Head(200, "m1"), EngineEvent.Chunk("hello".toByteArray()), EngineEvent.Chunk(" world".toByteArray()), EngineEvent.End(Terminal.DONE, 200, 12))
    }

    @Volatile
    var paceNanos: Long = 0
    val opened = CopyOnWriteArrayList<EngineRequest>()
    val cancelled = CopyOnWriteArrayList<String>()

    override fun offered(model: String): ModelOffer? = models[model]

    override fun open(request: EngineRequest): EngineRun {
        opened += request
        log.add(Ev.EngineOpen(node, request.attemptId))
        return Run(request.attemptId, script(request))
    }

    private inner class Run(private val id: String, private val steps: List<Any>) : EngineRun {
        private var i = 0

        @Volatile
        private var stopped = false

        override fun next(): EngineEvent {
            if (paceNanos > 0) LockSupport.parkNanos(paceNanos)
            if (stopped) return EngineEvent.End(Terminal.CANCELLED, 499, null)
            if (i >= steps.size) return EngineEvent.End(Terminal.DONE, 200, 0)
            val s = steps[i++]
            if (s is Throwable) throw s
            return s as EngineEvent
        }

        override fun cancel() {
            stopped = true
            cancelled += id
            log.add(Ev.EngineCancel(node, id))
        }
    }

    companion object {
        val DEFAULT_PACE_NANOS: Long = TimeUnit.MICROSECONDS.toNanos(800)
    }
}
