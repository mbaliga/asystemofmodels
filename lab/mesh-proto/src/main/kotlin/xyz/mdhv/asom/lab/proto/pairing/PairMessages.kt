package xyz.mdhv.asom.lab.proto.pairing

import xyz.mdhv.asom.lab.json.B64Result
import xyz.mdhv.asom.lab.json.Base64Strict
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JBool
import xyz.mdhv.asom.lab.json.JInt
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.JValue
import xyz.mdhv.asom.lab.json.Jcs
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson

/** The pairing frame types of LAB_SPEC 7.2. All travel on stream 1 of a pairing-mode connection. */
object PairFrameType {
    const val HELLO = 0x30
    const val CHALLENGE = 0x31
    const val DECISION = 0x32
    const val COMMIT = 0x33
    const val COMMIT_ACK = 0x34
}

object PairEnums {
    /** The LAB_SPEC 7.2 platform enum (r3: the older `android|ios|ipados|macos|linux` list of trust.md 4.4 is superseded). */
    val PLATFORMS = listOf("android", "ios", "ipados", "macos", "linux", "windows", "ubuntu-touch")
    val KEY_TIERS = listOf("strongbox", "tee", "secure-enclave", "tpm", "os-keystore", "file")
    val VIAS = listOf("lan", "overlay")
    const val MAX_NAME_CODE_POINTS = 32
}

class PairEndpoint(val addr: String, val port: Int, val via: String)

class PairHello(val nonceS: ByteArray, val proof: ByteArray, val name: String, val platform: String, val keyTier: String, val endpoints: List<PairEndpoint>)
class PairChallenge(val nonceD: ByteArray, val name: String, val platform: String, val keyTier: String)
class PairDecision(val approve: Boolean)

/** `PAIR_COMMIT` carries only the transcript: `locSeed` is withdrawn in r3 (D12.6), and a received one is ignored like any unknown member. */
class PairCommit(val transcript: ByteArray)
class PairCommitAck(val transcript: ByteArray)

/** A refusal while reading a pairing message. [code] is the `MeshError` the receiver would send; [why] never carries peer-authored text. */
sealed interface MsgParse<out T> {
    class Ok<T>(val value: T) : MsgParse<T>
    class Reject(val code: String, val why: String) : MsgParse<Nothing>
}

object PairMessages {
    private fun cps(s: String) = s.codePointCount(0, s.length)

    private fun b64(bytes: ByteArray) = Base64Strict.encodeUrlNoPad(bytes)

    private fun checkName(name: String) {
        require(cps(name) in 1..PairEnums.MAX_NAME_CODE_POINTS) { "name is 1 to ${PairEnums.MAX_NAME_CODE_POINTS} code points" }
        require(QrUri.decodeName(QrUri.percentEncode(name)) != null) { "name holds a control or bidirectional-control character" }
    }

    private fun obj(vararg m: Pair<String, JValue>) = Jcs.serialize(JObject(m.toList()))

    private fun endpointValue(e: PairEndpoint): JValue = JObject(listOf("addr" to JString(e.addr), "port" to JInt(e.port.toLong()), "via" to JString(e.via)))

    fun encodeHello(m: PairHello): ByteArray {
        require(m.nonceS.size == 32 && m.proof.size == 32 && m.platform in PairEnums.PLATFORMS && m.keyTier in PairEnums.KEY_TIERS && m.endpoints.size <= 4)
        checkName(m.name)
        return obj(
            "v" to JInt(1), "nonceS" to JString(b64(m.nonceS)), "proof" to JString(b64(m.proof)), "name" to JString(m.name),
            "platform" to JString(m.platform), "keyTier" to JString(m.keyTier), "endpoints" to JArray(m.endpoints.map { endpointValue(it) }),
        )
    }

    fun encodeChallenge(m: PairChallenge): ByteArray {
        require(m.nonceD.size == 32 && m.platform in PairEnums.PLATFORMS && m.keyTier in PairEnums.KEY_TIERS)
        checkName(m.name)
        return obj("v" to JInt(1), "nonceD" to JString(b64(m.nonceD)), "name" to JString(m.name), "platform" to JString(m.platform), "keyTier" to JString(m.keyTier))
    }

    fun encodeDecision(m: PairDecision): ByteArray = obj("v" to JInt(1), "approve" to JBool(m.approve))

    fun encodeCommit(m: PairCommit): ByteArray {
        require(m.transcript.size == 32)
        return obj("v" to JInt(1), "transcript" to JString(b64(m.transcript)))
    }

    fun encodeCommitAck(m: PairCommitAck): ByteArray {
        require(m.transcript.size == 32)
        return obj("v" to JInt(1), "transcript" to JString(b64(m.transcript)))
    }

    private fun root(payload: ByteArray): Pair<JObject?, MsgParse.Reject?> {
        val r = StrictJson.parse(payload)
        if (r !is ParseResult.Ok) return null to MsgParse.Reject("PROTOCOL_ERROR", "the payload is not strict JSON")
        val o = r.value as? JObject ?: return null to MsgParse.Reject("PROTOCOL_ERROR", "the payload is not a JSON object")
        val v = o["v"]
        if (v !is JInt) return null to MsgParse.Reject("PROTOCOL_ERROR", "v is missing or not an integer")
        if (v.value != 1L) return null to MsgParse.Reject("VERSION_UNSUPPORTED", "only v 1 exists")
        return o to null
    }

    private fun str(o: JObject, key: String): String? = (o[key] as? JString)?.value

    private fun fixed32(o: JObject, key: String): ByteArray? {
        val s = str(o, key) ?: return null
        if (s.length != 43) return null
        val r = Base64Strict.decodeUrlNoPad(s) as? B64Result.Ok ?: return null
        return if (r.bytes.size == 32) r.bytes else null
    }

    private fun name(o: JObject): String? {
        val n = str(o, "name") ?: return null
        if (cps(n) !in 1..PairEnums.MAX_NAME_CODE_POINTS) return null
        if (QrUri.decodeName(QrUri.percentEncode(n)) == null) return null
        return n
    }

    private fun bad(what: String) = MsgParse.Reject("PROTOCOL_ERROR", "$what is missing or invalid")

    fun parseHello(payload: ByteArray): MsgParse<PairHello> {
        val (o, rej) = root(payload)
        if (o == null) return rej!!
        val nonceS = fixed32(o, "nonceS") ?: return bad("nonceS")
        val proof = fixed32(o, "proof") ?: return bad("proof")
        val name = name(o) ?: return bad("name")
        val platform = str(o, "platform")?.takeIf { it in PairEnums.PLATFORMS } ?: return bad("platform")
        val tier = str(o, "keyTier")?.takeIf { it in PairEnums.KEY_TIERS } ?: return bad("keyTier")
        val eps = o["endpoints"] as? JArray ?: return bad("endpoints")
        if (eps.items.size > 4) return bad("endpoints")
        val list = ArrayList<PairEndpoint>()
        for (e in eps.items) {
            val eo = e as? JObject ?: return bad("an endpoint")
            val addr = str(eo, "addr")?.takeIf { Endpoints.ipv4Octets(it) != null || Endpoints.ipv6Groups(it) != null } ?: return bad("an endpoint addr")
            val port = (eo["port"] as? JInt)?.value?.takeIf { it in 1..65535 } ?: return bad("an endpoint port")
            val via = str(eo, "via")?.takeIf { it in PairEnums.VIAS } ?: return bad("an endpoint via")
            list += PairEndpoint(addr, port.toInt(), via)
        }
        return MsgParse.Ok(PairHello(nonceS, proof, name, platform, tier, list))
    }

    fun parseChallenge(payload: ByteArray): MsgParse<PairChallenge> {
        val (o, rej) = root(payload)
        if (o == null) return rej!!
        val nonceD = fixed32(o, "nonceD") ?: return bad("nonceD")
        val name = name(o) ?: return bad("name")
        val platform = str(o, "platform")?.takeIf { it in PairEnums.PLATFORMS } ?: return bad("platform")
        val tier = str(o, "keyTier")?.takeIf { it in PairEnums.KEY_TIERS } ?: return bad("keyTier")
        return MsgParse.Ok(PairChallenge(nonceD, name, platform, tier))
    }

    fun parseDecision(payload: ByteArray): MsgParse<PairDecision> {
        val (o, rej) = root(payload)
        if (o == null) return rej!!
        val a = o["approve"] as? JBool ?: return bad("approve")
        return MsgParse.Ok(PairDecision(a.value))
    }

    fun parseCommit(payload: ByteArray): MsgParse<PairCommit> {
        val (o, rej) = root(payload)
        if (o == null) return rej!!
        return MsgParse.Ok(PairCommit(fixed32(o, "transcript") ?: return bad("transcript")))
    }

    fun parseCommitAck(payload: ByteArray): MsgParse<PairCommitAck> {
        val (o, rej) = root(payload)
        if (o == null) return rej!!
        return MsgParse.Ok(PairCommitAck(fixed32(o, "transcript") ?: return bad("transcript")))
    }
}
