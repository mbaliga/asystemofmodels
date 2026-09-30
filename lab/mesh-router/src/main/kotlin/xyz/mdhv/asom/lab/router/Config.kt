package xyz.mdhv.asom.lab.router

import xyz.mdhv.asom.contract.Policy
import xyz.mdhv.asom.lab.ledger.PeerPath

/** Bytes per token of one file's tokenizer (LAB_SPEC 5: the class defaults 4000 and 8000 are PROVISIONAL [A37]). */
data class Bpt(val bptPermille: Long, val bptCapPermille: Long)

/**
 * A ceiling on a claimed rate, in milliTok/s. NO value is defined by the spec (R3-CLOSURE-5 (1)): the maps below are empty by default, so the
 * `capRef` term is absent unless a caller supplies it. Nothing here is invented.
 */
data class RateCeiling(val prefillMilliTokPerSec: Long, val decodeMilliTokPerSec: Long, val steadyMilliTokPerSec: Long)

data class CeilingKey(val modelId: String, val backend: String, val deviceClass: DeviceClass)

/**
 * Every value is PROVISIONAL [RA12]: the laws of LAB_SPEC 6.8 are normative, the numbers are not. The tables are the ones of LAB_SPEC 6.4.
 */
data class MeshConfig(
    val defaultPolicy: Policy = Policy.AUTO,
    val peerBiasMs: Long = 1_000,
    val msPerBatteryPermille: Long = 2_000,
    val heatPermilleUserActive: Long = 500,
    val heatPermille: Long = 250,
    val stalePermille: Long = 250,
    val expiredPermille: Long = 500,
    val minDecodeMilliTokPerSec: Long = 4_000,
    val maxTtftMs: Long = 20_000,
    val defaultDeadlineMs: Long = 120_000,
    val handshakeExtraMs: Long = 40,
    val loadBytesPerMs: Map<DeviceClass, Long> = mapOf(
        DeviceClass.PHONE to 500_000, DeviceClass.TABLET to 500_000, DeviceClass.HANDHELD to 1_000_000,
        DeviceClass.SBC to 1_000_000, DeviceClass.LAPTOP to 2_000_000, DeviceClass.DESKTOP to 2_000_000,
    ),
    val pathKbps: Map<PeerPath, Long> = mapOf(PeerPath.LAN to 100_000, PeerPath.OVERLAY to 20_000),
    val powerMilliW: Map<DeviceClass, Long> = mapOf(
        DeviceClass.PHONE to 5_000, DeviceClass.TABLET to 7_000, DeviceClass.HANDHELD to 15_000,
        DeviceClass.LAPTOP to 30_000, DeviceClass.DESKTOP to 150_000, DeviceClass.SBC to 8_000,
    ),
    val queueBucketMs: List<Long> = listOf(0, 10_000, 30_000),
    val quantPenaltyMs: Map<String, Long> = mapOf(
        "F16" to 0, "BF16" to 0, "Q8_0" to 0, "Q6_K" to 200, "Q5_K_M" to 400, "Q4_K_M" to 800, "Q4_0" to 1_000, "Q3_K_M" to 2_500, "Q2_K" to 5_000,
    ),
    val unknownQuantPenaltyMs: Long = 1_000,
    val msPerRankStep: Long = 5_000,
    val unrankedRankOffset: Int = 10,
    val autoRankFloor: Int? = null,
    val cloudDecodePriorMilliTokPerSec: Long = 50_000,
    val maxAttempts: Int = 6,
    val maxPeerAttempts: Int = 3,
    val offerTimeoutMs: Long = 2_000,
    val headTimeoutFloorMs: Long = 5_000,
    val defaultBpt: Bpt = Bpt(4_000, 8_000),
    val bptByFile: Map<String, Bpt> = emptyMap(),
    val classCeilings: Map<CeilingKey, RateCeiling> = emptyMap(),
    /** Only entries the requester verified as signed by the compiled-in reference key (RT-12 fails closed); the router never verifies a signature. */
    val signedReferenceP90: Map<CeilingKey, RateCeiling> = emptyMap(),
    val outTokensDefault: Long = 256,
    val outTokensMax: Long = 32_768,
    val memorySlackBytes: Long = 268_435_456,
    val cloudEwmaRoundsUp: Boolean = true,
) {
    fun bpt(fileSha256: String): Bpt = bptByFile[fileSha256] ?: defaultBpt

    fun quantPenalty(quant: String?): Long = if (quant == null) unknownQuantPenaltyMs else quantPenaltyMs[quant] ?: unknownQuantPenaltyMs

    fun loadRate(c: DeviceClass): Long = loadBytesPerMs.getValue(c)

    fun headTimeoutMs(ttftMs: Long): Long = maxOf(headTimeoutFloorMs, Sat.mul(2, ttftMs))
}
