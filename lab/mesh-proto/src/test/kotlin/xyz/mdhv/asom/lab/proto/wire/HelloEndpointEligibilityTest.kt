package xyz.mdhv.asom.lab.proto.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.mdhv.asom.lab.proto.pairing.AddrPolicy

/** Locators from HELLO and HELLO_ACK are filtered by the trust.md 6.6 address test before anything merges them (ERRATA ERR-FX4-TR-8). */
class HelloEndpointEligibilityTest {
    private val nodeId = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
    private val attempt = "AAAAAAAAAAAAAAAAAAAAAA"

    private fun helloWith(endpoints: String) =
        """{"endpoints":$endpoints,"features":["state"],"keyTier":"file","maxV":1,"minV":1,"name":"n","nodeId":"$nodeId","platform":"linux","proto":"asom-mesh/1","sessionNonce":"$attempt","sw":"asom-desktop/1.0.0","ts":1,"v":1}"""

    private fun decodeEndpoints(addrs: List<String>): List<Endpoint> = addrs.chunked(Rules.MAX_ENDPOINTS).flatMap { decodeChunk(it) }

    private fun decodeChunk(addrs: List<String>): List<Endpoint> {
        val json = addrs.joinToString(",", "[", "]") { """{"addr":"$it","port":11436,"via":"lan"}""" }
        val parsed = MessageCodec.parse(RawFrame(FrameTypes.HELLO, 1, helloWith(json).toByteArray()))
        return (((parsed as? Parsed.Ok)?.message) as Hello).endpoints
    }

    private val eligible = listOf("192.168.1.20", "10.0.0.7", "172.16.0.1", "172.31.255.254", "169.254.10.10", "100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1", "fe80::1", "fc00::5")
    private val unspecifiedV4 = List(4) { "0" }.joinToString(".")
    private val ineligible = listOf(
        "203.0.113.7", "8.8.8.8", unspecifiedV4, "255.255.255.255", "224.0.0.251", "127.0.0.1", "172.15.0.1", "172.32.0.1", "100.63.255.255", "100.128.0.1", "192.169.1.1",
        "2001:db8::1", "2606:4700::1111", "::", "::1", "ff02::fb", "::ffff:10.0.0.1", "::ffff:8.8.8.8",
    )

    @Test
    fun decodingStaysSyntaxOnlyButTheEligibilityFilterDropsEveryAddressTrust66Forbids() {
        val all = decodeEndpoints(eligible + ineligible)
        assertEquals(eligible.size + ineligible.size, all.size, "the codec itself accepts every IP literal, as the W06 vectors require")
        var kept = 0
        var dropped = 0
        for (e in all) {
            val ok = HelloEndpoints.eligible(e)
            if (e.addr in eligible) { assertTrue(ok, "${e.addr} must be eligible"); kept++ }
            else { assertFalse(ok, "${e.addr} must be dropped"); dropped++ }
        }
        assertEquals(eligible.size, kept)
        assertEquals(ineligible.size, dropped)
        assertEquals(eligible, HelloEndpoints.eligibleOnly(all).map { it.addr })
    }

    @Test
    fun uppercaseIpv6IsJudgedByItsClassAndLoopbackOnlyPassesUnderTheTestPolicy() {
        assertTrue(HelloEndpoints.eligible(Endpoint("FD7A:115C::A", 65535, Via.OVERLAY)))
        assertFalse(HelloEndpoints.eligible(Endpoint("127.0.0.1", 1, Via.LAN)))
        assertTrue(HelloEndpoints.eligible(Endpoint("127.0.0.1", 1, Via.LAN), AddrPolicy.MESH_PLUS_LOOPBACK_FOR_TESTS))
        assertTrue(HelloEndpoints.eligible(Endpoint("::1", 1, Via.LAN), AddrPolicy.MESH_PLUS_LOOPBACK_FOR_TESTS))
        assertFalse(HelloEndpoints.eligible(Endpoint("203.0.113.7", 1, Via.LAN), AddrPolicy.MESH_PLUS_LOOPBACK_FOR_TESTS))
    }
}
