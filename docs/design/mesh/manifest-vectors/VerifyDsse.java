import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.regex.*;

/** Independent JCA check of the DSSE/ES256 layer (no JSON library: fields are pulled from the JCS container by regex). */
public class VerifyDsse {
    static byte[] b64(String s) {
        s = s.replace('-', '+').replace('_', '/');
        while (s.length() % 4 != 0) s += "=";
        return Base64.getDecoder().decode(s);
    }
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }
    public static void main(String[] a) throws Exception {
        byte[] spki = Files.readAllBytes(Path.of("test-key1-spki.der"));
        PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(spki));
        // each vector's document is a JSON string inside the vector file; unescape the minimal set used
        List<Path> files = new ArrayList<>();
        try (var ds = Files.newDirectoryStream(Path.of(a[0]))) { for (Path p : ds) if (p.toString().endsWith(".json")) files.add(p); }
        Collections.sort(files);
        for (Path p : files) {
            String id = p.getFileName().toString().replace(".json", "");
            String doc = Files.readString(p);
            String payloadB64 = field(doc, "payload"), sigB64 = field(doc, "sig"), pt = field(doc, "payloadType");
            if (payloadB64 == null || sigB64 == null || pt == null) { System.out.println(id + " skipped (unparsed)"); continue; }
            byte[] payload, sig;
            try { payload = b64(payloadB64); sig = b64(sigB64); } catch (IllegalArgumentException e) { System.out.println(id + " base64-error"); continue; }
            byte[] ptb = pt.getBytes(StandardCharsets.UTF_8);
            byte[] head = ("DSSEv1 " + ptb.length + " " + pt + " " + payload.length + " ").getBytes(StandardCharsets.UTF_8);
            byte[] pae = new byte[head.length + payload.length];
            System.arraycopy(head, 0, pae, 0, head.length); System.arraycopy(payload, 0, pae, head.length, payload.length);
            boolean ok = false;
            if (sig.length == 64) {
                Signature v = Signature.getInstance("SHA256withECDSAinP1363Format");
                v.initVerify(key); v.update(pae); ok = v.verify(sig);
            }
            System.out.println(id + " sigValidUnderKey1=" + ok);
        }
        System.out.println("java " + System.getProperty("java.version"));
    }
}
