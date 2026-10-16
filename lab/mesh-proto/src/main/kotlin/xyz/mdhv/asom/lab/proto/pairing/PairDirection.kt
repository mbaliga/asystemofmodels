package xyz.mdhv.asom.lab.proto.pairing

import xyz.mdhv.asom.lab.proto.wire.FrameTypes
import xyz.mdhv.asom.lab.proto.wire.PairPayloadHook

/** The two ends of a pairing ceremony (trust.md 4.1): S scans and dials, D displays the QR and listens. */
enum class PairSide { S, D }

enum class PairOrientation {
    /**
     * The side that sends the first `PAIR_HELLO` is S. Works whichever end the host calls S; a first frame that is not a hello is refused. Kept for hosts that do
     * not know the TLS role; since revision 4 it is not the default.
     */
    FROM_HELLO,

    /** S is the TLS client and D the TLS server, as trust.md 4.3 draws it. The default since revision 4 (R4-T-10). */
    FROM_TLS_ROLE,
}

/** Who may send each `PAIR_*` frame (trust.md 4.4): `HELLO` and `COMMIT_ACK` come from S, `CHALLENGE` and `COMMIT` from D, `DECISION` from both. */
object PairDirection {
    fun senderMay(side: PairSide, type: Int): Boolean = when (side) {
        PairSide.S -> type == FrameTypes.PAIR_HELLO || type == FrameTypes.PAIR_DECISION || type == FrameTypes.PAIR_COMMIT_ACK
        PairSide.D -> type == FrameTypes.PAIR_CHALLENGE || type == FrameTypes.PAIR_DECISION || type == FrameTypes.PAIR_COMMIT
    }
}

/**
 * The [PairPayloadHook] of one pairing channel (ERRATA ERR-PW-4, ERR-FX2-3; R4-T-10). Inbound frames are checked against what the peer's side may send; frames
 * this end sends are checked against its own side. Wrong-direction, reflected and cold frames are refused with a constant reason, never peer text. A frame is cold
 * while no `PAIR_HELLO` has crossed: D's first inbound `PAIR_*` frame must be the hello, and S accepts nothing before it has sent its own hello. A further hello
 * is not cold: D's machine answers it with `PAIRING_WINDOW_CLOSED`.
 */
class PairDirectionGuard(ownSide: PairSide?) : PairPayloadHook {
    private var own: PairSide? = ownSide
    private var helloCrossed = false

    val ownSide: PairSide? get() = own

    private fun PairSide.other() = if (this == PairSide.S) PairSide.D else PairSide.S

    override fun check(type: Int, payload: ByteArray): String? {
        val mine = own
        if (mine == null) {
            if (type != FrameTypes.PAIR_HELLO) return "FIRST_PAIR_FRAME_NOT_HELLO"
            own = PairSide.D
            helloCrossed = true
            return null
        }
        if (!PairDirection.senderMay(mine.other(), type)) return "WRONG_DIRECTION"
        if (!helloCrossed) {
            // S has sent no hello yet, so anything D may send is cold; a hello cannot reach S (wrong direction, refused above).
            if (type != FrameTypes.PAIR_HELLO) return "FIRST_PAIR_FRAME_NOT_HELLO"
            helloCrossed = true
        }
        return null
    }

    /** False when this end may not send [type] now. A first outbound `PAIR_HELLO` makes this end S when the orientation is inferred. */
    fun allowOutbound(type: Int): Boolean {
        val mine = own
        if (mine == null) {
            if (type != FrameTypes.PAIR_HELLO) return false
            own = PairSide.S
            helloCrossed = true
            return true
        }
        if (!PairDirection.senderMay(mine, type)) return false
        if (mine == PairSide.S && type == FrameTypes.PAIR_HELLO) helloCrossed = true
        return true
    }
}
