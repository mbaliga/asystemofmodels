package xyz.mdhv.asom.lab.proto.pairing

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterAll
import xyz.mdhv.asom.lab.json.Hex
import xyz.mdhv.asom.lab.json.JArray
import xyz.mdhv.asom.lab.json.JObject
import xyz.mdhv.asom.lab.json.JString
import xyz.mdhv.asom.lab.json.ParseResult
import xyz.mdhv.asom.lab.json.StrictJson
import xyz.mdhv.asom.lab.proto.trust.LawCounters
import xyz.mdhv.asom.lab.proto.trust.Pin
import xyz.mdhv.asom.lab.proto.trust.VectorOutcome

/**
 * The W04 runner understands the optional `profile` and `expiry` members and the R3 event members, so R3 vectors can be added to `W04-pairing.json`
 * (outside this track's write set, ERRATA ERR-FX2-7) without more code. Here the vectors are built in memory. A vector without the members is the frozen reading
 * and is exercised by the conformance runner against the committed file.
 */
class W04R3RunnerTest {
    companion object {
        val laws = LawCounters("w04-r3-runner")

        @JvmStatic
        @AfterAll
        fun done() = laws.finish(setOf("fsm-D-r3-wrong-code", "fsm-D-r3-missing-code", "fsm-D-r3-happy", "fsm-D-r3-bad-reveal", "fsm-S-r3", "qr-expiry-none", "frozen-reading-unchanged"))

        private fun pin(label: String) = Pin.ofHash(MessageDigest.getInstance("SHA-256").digest(label.toByteArray()))
    }

    private val pinD = pin("asom-vector/spki/D")
    private val pinS = pin("asom-vector/spki/S")
    private val secret = ByteArray(32) { it.toByte() }
    private val rS = ByteArray(32) { 0xA5.toByte() }
    private val nonceD = ByteArray(32) { 0x5A.toByte() }
    private val commitment = PairCrypto.commitNonce(rS)

    private fun obj(json: String): JObject = (StrictJson.parse(json.toByteArray()) as ParseResult.Ok).value as JObject

    private fun trace(v: JObject): List<String> {
        val ev = W04Vectors.evaluate(v)
        val out = ev.outcome as VectorOutcome.Ok
        return ((out.value as JObject)["trace"] as JArray).items.map { (it as JString).value }
    }

    private fun dVector(extra: String, profile: String = "r3"): JObject {
        val proof = Hex.encode(PairCrypto.proof(secret, pinD, pinS, commitment))
        return obj(
            """{"kind":"fsm","machine":"D","profile":"$profile","pinOwn":"${pinD.nodeId}","script":[
              {"e":"UserOpenWindow","secretHex":"${Hex.encode(secret)}","nowMs":1000},
              {"e":"HelloReceived","pinS":"${pinS.nodeId}","nonceSHex":"${Hex.encode(commitment)}","proofHex":"$proof","status":"ABSENT","nonceDHex":"${Hex.encode(nonceD)}","nowMs":2000},
              {"e":"ChallengeSent"}$extra]}""",
        )
    }

    @Test
    fun aWrongTypedCodeVector() {
        val t = trace(dVector(""",{"e":"RemoteDecision","approve":true,"revealHex":"${Hex.encode(rS)}"},{"e":"LocalDecision","approve":true,"typedCode":"000 000"}"""))
        assertEquals("AWAIT_REMOTE:Warn(wrong-code)", t.last())
        assertTrue(t.none { it.contains("ShowConsent") && !it.contains("Refuse") }, "D shows no code")
        assertEquals("AWAIT_NONE:PromptTypedCode", t[2])
        laws.bump("fsm-D-r3-wrong-code")
    }

    @Test
    fun aMissingTypedCodeVector() {
        val t = trace(dVector(""",{"e":"RemoteDecision","approve":true,"revealHex":"${Hex.encode(rS)}"},{"e":"LocalDecision","approve":true}"""))
        assertEquals("AWAIT_REMOTE:Warn(typed-code-required)", t.last())
        laws.bump("fsm-D-r3-missing-code")
    }

    @Test
    fun theHappyVectorWritesTheRowOnlyAfterTheCodeAndTheReveal() {
        val sas = PairCrypto.sas(pinD, pinS, rS, nonceD)
        val t = trace(dVector(""",{"e":"RemoteDecision","approve":true,"revealHex":"${Hex.encode(rS)}"},{"e":"LocalDecision","approve":true,"typedCode":"$sas"}"""))
        assertEquals("COMMITTING:SendDecision(true),WritePairedRow", t.last())
        laws.bump("fsm-D-r3-happy")
    }

    @Test
    fun aRevealThatDoesNotOpenTheCommitmentVector() {
        val other = ByteArray(32) { 1 }
        val t = trace(dVector(""",{"e":"RemoteDecision","approve":true,"revealHex":"${Hex.encode(other)}"}"""))
        assertEquals("CLOSED:Abort(PROTOCOL,PROTOCOL_ERROR)", t.last())
        laws.bump("fsm-D-r3-bad-reveal")
    }

    @Test
    fun theSameScriptWithoutTheProfileIsTheFrozenReadingAndShowsTheCode() {
        val t = trace(dVector("", profile = "r0"))
        assertTrue(t.last().startsWith("AWAIT_NONE:ShowConsent("), t.last())
        laws.bump("frozen-reading-unchanged")
    }

    @Test
    fun anSVectorSendsTheCommitmentAndOpensItWithTheApproval() {
        val uri = QrUri.encode(QrPayload(pinD, listOf(Endpoints.parse("192.168.1.40:11436")!!), secret, 1_790_000_120, "Dell tower"))
        val v = obj(
            """{"kind":"fsm","machine":"S","profile":"r3","pinOwn":"${pinS.nodeId}","script":[
              {"e":"Scanned","uri":"$uri","nowSec":1790000000,"policy":"mesh"},
              {"e":"UserConfirmConnect","yes":true,"nowMs":1000},
              {"e":"DialResult","presentedPin":"${pinD.nodeId}","nonceSHex":"${Hex.encode(rS)}","nowMs":2000},
              {"e":"ChallengeReceived","nonceDHex":"${Hex.encode(nonceD)}","nowMs":3000},
              {"e":"LocalDecision","approve":true}]}""",
        )
        val t = trace(v)
        val proof = Hex.encode(PairCrypto.proof(secret, pinD, pinS, commitment).copyOf(4))
        assertEquals("SENT_HELLO:SendHello($proof)", t[2])
        assertEquals("AWAIT_LOCAL:SendDecision(true,reveal)", t.last())
        laws.bump("fsm-S-r3")
    }

    @Test
    fun aQrVectorCanSelectTheNoExpiryReading() {
        val uri = "asom-pair:1?k=jZcpQhuRMg6yNp81ynXIGjfGCcZeStuotMWTOtRFPgs&a=192.168.1.40:11436&s=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&x=1790000120&n=Dell%20tower"
        val frozen = W04Vectors.evaluate(obj("""{"kind":"qrParse","uri":"$uri","nowSec":1790000180,"policy":"mesh"}"""))
        assertEquals("EXPIRED", (frozen.outcome as VectorOutcome.Reject).code)
        val none = W04Vectors.evaluate(obj("""{"kind":"qrParse","uri":"$uri","nowSec":1790000180,"policy":"mesh","expiry":"none"}"""))
        assertTrue(none.outcome is VectorOutcome.Ok)
        laws.bump("qr-expiry-none")
    }
}
