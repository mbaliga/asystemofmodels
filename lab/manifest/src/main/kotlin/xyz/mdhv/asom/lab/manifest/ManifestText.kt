package xyz.mdhv.asom.lab.manifest

import java.time.Instant
import java.time.ZoneOffset
import xyz.mdhv.asom.lab.bench.Audience
import xyz.mdhv.asom.lab.bench.BenchArithmeticException
import xyz.mdhv.asom.lab.bench.Derive
import xyz.mdhv.asom.lab.bench.RenderOptions
import xyz.mdhv.asom.lab.bench.TextRender

/** Where the report is being read: a peer's presentation, an exported file, or this device's own report. */
enum class ViewContext { MESH, FILE, OWN }

/**
 * The verification block is computed by the viewer from what it checked, never taken from the payload (law LM-6). [signerSpki] is the key
 * the verifier used. [reject] is set when a report is shown despite a reject at steps 13-16 (LAB_SPEC 4.6 display rule).
 */
class ViewerVerification(
    val context: ViewContext,
    val pin: PinState?,
    val signerSpki: ByteArray,
    val unknownFields: Int,
    val reject: RejectCode? = null,
)

/**
 * `asom.manifest-text/1` (LAB_SPEC 4.8, manifest.md 14.2 as amended in r3): the header, the verification block computed by the viewer,
 * then the `asom.text/1` body rendered by `:bench-core` from `body.bench`. ASCII 0x20-0x7E plus LF, times in UTC, integer arithmetic only.
 * There is exactly one text source (law LM-3): the derived bench document.
 */
object ManifestText {
    private val STORAGE = mapOf(
        "strongbox" to "StrongBox secure element", "tee" to "trusted execution environment", "secure-enclave" to "Secure Enclave", "tpm" to "TPM",
        "os-keystore" to "operating-system keystore (software)", "file" to "file on disk (software)",
        "ephemeral" to "per-export software key (discarded after signing)", "unknown" to "unknown",
    )
    private val KNOWN_CLASS = setOf("phone", "tablet", "handheld", "laptop", "desktop", "server", "sbc")
    const val NOT_PROVEN_1 = "- Not proven: that the measurements were honest or typical, that the device model is true,"
    const val NOT_PROVEN_2 = "  or that the benchmark software was unmodified."

    fun utc(ms: Long): String =
        Instant.ofEpochMilli(ms / 60_000L * 60_000L).atZone(ZoneOffset.UTC).let { "%04d-%02d-%02d %02d:%02d UTC".format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute) }

    fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()

    private fun a(s: String): String = TextRender.asciiOnly(s)

    fun viewerFor(v: Verified, mode: Mode): ViewerVerification =
        ViewerVerification(if (mode == Mode.MESH) ViewContext.MESH else ViewContext.FILE, v.pin, v.signerSpki, v.unknownFields)

    fun viewerFor(r: Rejected, mode: Mode, pin: PinState?): ViewerVerification? {
        if (!r.displayable || r.obj == null || r.signerSpki == null) return null
        return ViewerVerification(if (mode == Mode.MESH) ViewContext.MESH else ViewContext.FILE, pin, r.signerSpki, r.obj.unknownFields, r.code)
    }

    /** `M05(parse(P), vr)`. */
    fun render(obj: ManifestObj, vr: ViewerVerification, opts: RenderOptions = RenderOptions()): String {
        val b = obj.body
        val d = b.device
        val cls = if (d.cls in KNOWN_CLASS) d.cls else "other (${a(d.cls)})"
        val L = mutableListOf<String>()
        L += "ASOM CAPABILITY REPORT"
        L += "Device: ${a(d.model)} by ${a(d.vendor)} ($cls)"
        L += if (b.audience == Audience.FILE) {
            "Report exported ${day(obj.presentation.issuedAtMs)} (day only)"
        } else {
            "Report ${b.seq}, signed ${utc(obj.presentation.issuedAtMs)}, valid until ${utc(obj.presentation.expiresAtMs!!)}"
        }
        L += when (vr.context) {
            ViewContext.MESH -> "Signer: node ${Spki.displayFingerprint(vr.signerSpki)}"
            ViewContext.OWN -> "Signer: this device"
            ViewContext.FILE -> "Signer: key ${Spki.exportFingerprint(vr.signerSpki)}"
        }
        L += ""
        L += "VERIFICATION (checked by this viewer, not stated by the device)"
        L += "- Signature: valid. The report has not changed since this key signed it."
        L += when (vr.context) {
            ViewContext.MESH -> "- Signer key: matches the key you paired with."
            ViewContext.OWN -> "- Signer key: this device's own key."
            ViewContext.FILE -> when (val p = vr.pin) {
                is PinState.ByFingerprint -> "- Signer key: matches the fingerprint you compared (${if (p.method == CompareMethod.QR) "scanned" else "typed"})."
                else -> "- Signer key: signed, but the signer is unverified: anyone could have made this key."
            }
        }
        L += "- Key storage: ${STORAGE.getValue(b.subject.keyStorage)} (self-reported, not attested)."
        if (vr.reject != null) {
            L += "- Freshness: not confirmed."
            L += "- REJECTED: ${vr.reject.name}. Do not rely on this report."
        } else {
            L += when (vr.context) {
                ViewContext.FILE -> "- Freshness: not applicable: an exported file answers no request."
                else -> "- Freshness: signed for your request."
            }
        }
        L += NOT_PROVEN_1
        L += NOT_PROVEN_2
        L += ""
        L += body(obj, opts).trimEnd('\n').split("\n")
        if (vr.unknownFields > 0) {
            L += ""
            L += "This report has ${vr.unknownFields} item${if (vr.unknownFields != 1) "s" else ""} from a newer format that this viewer does not show."
        }
        val text = L.joinToString("\n") + "\n"
        check(text.all { it == '\n' || it.code in 0x20..0x7E }) { "non-ASCII byte in the manifest text" }
        check(!text.contains(TextRender.FORBIDDEN_LABEL)) { "forbidden label rendered" }
        return text
    }

    /** The exported plain text: the `asom.text/1` body only. It never contains a verification block; no signed `.txt` exists. */
    fun renderExport(obj: ManifestObj, opts: RenderOptions = RenderOptions()): String = body(obj, opts)

    private fun body(obj: ManifestObj, opts: RenderOptions): String = try {
        TextRender.render(Derive.derive(obj.body.bench), opts)
    } catch (e: BenchArithmeticException) {
        "The report body could not be computed (arithmetic overflow).\n"
    }
}
