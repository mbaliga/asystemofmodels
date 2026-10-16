import Foundation
import AsomDSSE
// usage: es256check <manifest-vectors dir>; prints "<id> sigValidUnderKey1=<bool>" in the same form as VerifyDsse.java
let dir = URL(fileURLWithPath: CommandLine.arguments[1])
let spki = try Data(contentsOf: dir.appendingPathComponent("test-key1-spki.der"))
for f in ["M02-verify-accept.json", "M03-verify-reject.json"] {
    let top = try JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent(f))) as! [String: Any]
    for v in (top["vectors"] as! [[String: Any]]).sorted(by: { ($0["id"] as! String) < ($1["id"] as! String) }) {
        let id = v["id"] as! String
        let doc = (v["input"] as! [String: Any])["document"] as! String
        guard let c = try? JSONSerialization.jsonObject(with: Data(doc.utf8)) as? [String: Any],
              let d = c["dsse"] as? [String: Any], let p = d["payload"] as? String, let pt = d["payloadType"] as? String,
              let s = (d["signatures"] as? [[String: Any]])?.first?["sig"] as? String else { print("\(id) skipped (unparsed)"); continue }
        guard let payload = DSSE.b64(p), let sig = DSSE.b64(s) else { print("\(id) base64-error"); continue }
        print("\(id) sigValidUnderKey1=\(DSSE.verifyES256(spki: spki, payloadType: pt, payload: payload, rawSig: sig))")
    }
}
#if canImport(CryptoKit)
print("crypto: CryptoKit")
#else
print("crypto: swift-crypto")
#endif
