import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;
import jdk.net.ExtendedSocketOptions;
import jdk.net.UnixDomainPrincipal;

/**
 * Checks what a jlink module list can silently drop (linux.md 7.4): the modules the node needs at run time but that no
 * compiler or unit test on the build JDK will miss. Run it on the SHIPPED runtime:
 *   <image>/lib/runtime/bin/java -cp probe.jar RuntimeProbe <probe-ec.p12>
 * It prints one line per check, "runtime-probe: <check> OK ..." or "runtime-probe: <check> FAIL ...", and exits 1 on any FAIL.
 * The TLS check is an in-memory SSLEngine handshake (no network socket). It is NOT the mesh's pinned-identity handshake
 * (W08 belongs to the mesh tracks) and proves only that the runtime can do TLS 1.3 with an EC certificate and check a host name.
 * The AF_UNIX check binds a socket inside a private (0700) temp directory that it deletes.
 */
public final class RuntimeProbe {
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.out.println("runtime-probe: java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor")
            + ") modules=" + ModuleLayer.boot().modules().size());
        check("modules", RuntimeProbe::modules);
        check("jdk.net SO_PEERCRED", RuntimeProbe::peerCred);
        check("ES256", RuntimeProbe::es256);
        if (args.length == 1) check("TLS1.3 handshake", () -> tls(Path.of(args[0])));
        else fail("TLS1.3 handshake", "no keystore argument given");
        if (failures.isEmpty()) System.out.println("runtime-probe: OK");
        else {
            System.out.println("runtime-probe: FAILED " + failures);
            System.exit(1);
        }
    }

    private interface Body { String run() throws Exception; }

    private static void check(String name, Body b) {
        try {
            System.out.println("runtime-probe: " + name + " OK " + b.run());
        } catch (Throwable t) {
            fail(name, t.getClass().getName() + ": " + t.getMessage());
        }
    }

    private static void fail(String name, String why) {
        failures.add(name);
        System.out.println("runtime-probe: " + name + " FAIL " + why);
    }

    private static String modules() {
        for (String m : new String[] {"java.base", "jdk.net", "jdk.crypto.ec"}) {
            if (ModuleLayer.boot().findModule(m).isEmpty()) throw new IllegalStateException("module " + m + " is not in this runtime");
        }
        return "(java.base, jdk.net, jdk.crypto.ec present)";
    }

    private static String peerCred() throws Exception {
        Path dir = Files.createTempDirectory("asom-probe-");
        Path sock = dir.resolve("p.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(sock));
            try (SocketChannel client = SocketChannel.open(UnixDomainSocketAddress.of(sock)); SocketChannel accepted = server.accept()) {
                if (!accepted.supportedOptions().contains(ExtendedSocketOptions.SO_PEERCRED)) throw new IllegalStateException("SO_PEERCRED is not a supported option of a connected AF_UNIX channel");
                UnixDomainPrincipal p = accepted.getOption(ExtendedSocketOptions.SO_PEERCRED);
                UnixDomainPrincipal q = client.getOption(ExtendedSocketOptions.SO_PEERCRED);
                if (p == null || q == null || p.user() == null || q.user() == null) throw new IllegalStateException("no principal read");
                return "(accepted side: " + p.user().getName() + ", client side: " + q.user().getName() + ")";
            }
        } finally {
            Files.deleteIfExists(sock);
            Files.deleteIfExists(dir);
        }
    }

    private static String es256() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        byte[] msg = "asom runtime probe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (String alg : new String[] {"SHA256withECDSA", "SHA256withECDSAinP1363Format"}) {
            Signature s = Signature.getInstance(alg);
            s.initSign(kp.getPrivate());
            s.update(msg);
            byte[] sig = s.sign();
            Signature v = Signature.getInstance(alg);
            v.initVerify(kp.getPublic());
            v.update(msg);
            if (!v.verify(sig)) throw new IllegalStateException(alg + " signature did not verify");
            msg[0] ^= 1;
            v.initVerify(kp.getPublic());
            v.update(msg);
            if (v.verify(sig)) throw new IllegalStateException(alg + " verified a tampered message");
            msg[0] ^= 1;
        }
        return "(secp256r1, DER and P1363 forms sign, verify and reject a tampered message)";
    }

    private static String tls(Path p12) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(p12)) { ks.load(in, "changeit".toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext sctx = SSLContext.getInstance("TLSv1.3");
        sctx.init(kmf.getKeyManagers(), null, null);
        SSLContext cctx = SSLContext.getInstance("TLSv1.3");
        cctx.init(null, tmf.getTrustManagers(), null);

        SSLEngine server = sctx.createSSLEngine();
        server.setUseClientMode(false);
        server.setEnabledProtocols(new String[] {"TLSv1.3"});
        SSLEngine client = cctx.createSSLEngine("localhost", 443);
        client.setUseClientMode(true);
        client.setEnabledProtocols(new String[] {"TLSv1.3"});
        SSLParameters cp = client.getSSLParameters();
        cp.setEndpointIdentificationAlgorithm("HTTPS");
        client.setSSLParameters(cp);

        ByteBuffer c2s = ByteBuffer.allocate(65536);
        ByteBuffer s2c = ByteBuffer.allocate(65536);
        ByteBuffer empty = ByteBuffer.allocate(0);
        ByteBuffer sink = ByteBuffer.allocate(65536);
        client.beginHandshake();
        server.beginHandshake();
        for (int i = 0; i < 100 && !(done(client) && done(server)); i++) {
            pump(client, empty, s2c, c2s, sink);
            pump(server, empty, c2s, s2c, sink);
        }
        if (!(done(client) && done(server))) throw new IllegalStateException("handshake did not finish: client " + client.getHandshakeStatus() + ", server " + server.getHandshakeStatus());
        String proto = client.getSession().getProtocol();
        if (!"TLSv1.3".equals(proto)) throw new IllegalStateException("negotiated " + proto);
        return "(in-memory SSLEngine, " + proto + ", " + client.getSession().getCipherSuite() + ", EC certificate, host name checked; NOT the mesh pinned-identity handshake)";
    }

    private static boolean done(SSLEngine e) {
        return e.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING;
    }

    /** One step for [e]: run delegated tasks, wrap what it wants to send into [out], unwrap what the peer left in [in]. */
    private static void pump(SSLEngine e, ByteBuffer app, ByteBuffer in, ByteBuffer out, ByteBuffer sink) throws Exception {
        for (int guard = 0; guard < 20; guard++) {
            switch (e.getHandshakeStatus()) {
                case NEED_TASK -> { Runnable r; while ((r = e.getDelegatedTask()) != null) r.run(); }
                case NEED_WRAP -> { e.wrap(app, out); }
                case NEED_UNWRAP, NEED_UNWRAP_AGAIN -> {
                    in.flip();
                    if (!in.hasRemaining()) { in.compact(); return; }
                    SSLEngineResult r = e.unwrap(in, sink);
                    in.compact();
                    if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) return;
                    sink.clear();
                }
                default -> { return; }
            }
        }
    }
}
