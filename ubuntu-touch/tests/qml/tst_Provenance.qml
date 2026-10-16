import QtQuick 2.12
import QtTest 1.2
import "../../qml/tokens"
import "../../qml/js/Provenance.js" as Provenance
import "../../qml/js/ErrorText.js" as ErrorText

TestCase {
    name: "Provenance"

    function test_peerHasTheTriangleAndTheWordPeer() {
        var d = Provenance.describe({ servedClass: "peer", peerPath: "lan", provenance: "served by peer:Deck/m · via lan" }, Tokens)
        compare(d.shape, "triangle")
        compare(d.colorName, "cyan")
        verify(d.label.indexOf("peer") === 0 && d.label.indexOf("lan") > 0)
        compare(d.text, "served by peer:Deck/m · via lan")
    }

    function test_thisDeviceHasTheDiamond() {
        var d = Provenance.describe({ servedClass: "local", provenance: "served by this device/m" }, Tokens)
        compare(d.shape, "diamond")
        compare(d.colorName, "violet")
        compare(d.label, "this device")
    }

    function test_notServedHasNeitherColourNorAClass() {
        var d = Provenance.describe({ servedClass: null, provenance: "not served" }, Tokens)
        compare(d.cls, "none")
        compare(d.colorName, "muted")
        compare(Provenance.describe(null, Tokens).cls, "none")
    }

    function test_colourIsNeverTheOnlySignal() {
        var a = Provenance.describe({ servedClass: "peer" }, Tokens)
        var b = Provenance.describe({ servedClass: "local" }, Tokens)
        verify(a.shape !== b.shape && a.label !== b.label)
    }

    function test_nodeStates() {
        compare(Provenance.nodeStateLabel("idle", 0), "idle")
        compare(Provenance.nodeStateLabel("active", 1), "active (1 session)")
        compare(Provenance.nodeStateLabel("active", 3), "active (3 sessions)")
        compare(Provenance.nodeStateLabel("interrupted", 0), "interrupted")
        verify(Provenance.nodeStateShape("idle", Tokens) !== Provenance.nodeStateShape("active", Tokens))
        verify(Provenance.nodeStateShape("active", Tokens) !== Provenance.nodeStateShape("interrupted", Tokens))
    }

    function test_errorTextsKnownAndUnknown() {
        compare(ErrorText.messageFor("NO_PROVIDER_KEY"), "pair a device to use asom here")
        compare(ErrorText.messageFor("ALL_PROVIDERS_COOLING"), "all your paired devices are unavailable")
        verify(ErrorText.messageFor("PEER_SOMETHING") === ErrorText.FALLBACK)
        verify(ErrorText.messageFor("hasOwnProperty") === ErrorText.FALLBACK)
        verify(ErrorText.failureText("runtime-hash-mismatch").indexOf("checksum") > 0)
        verify(ErrorText.failureText("nonsense").length > 0)
    }
}
