import QtQuick 2.12
import QtTest 1.2
import "../../qml/js/Frames.js" as Frames

TestCase {
    name: "Frames"

    function test_hello() {
        compare(Frames.hello(), '{"t":"hello","v":1}')
    }

    function test_everyFrameIsOneLineOfJson() {
        var lines = [
            Frames.hello(), Frames.lifecycle("active"), Frames.borrow("r1", "auto", "a\nb c", 64), Frames.cancel("r1"),
            Frames.peers(true), Frames.peers(false), Frames.pairBegin("x\ny"), Frames.pairConfirm("865 412"), Frames.pairApprove(),
            Frames.revoke("n1"), Frames.ledger(0, 50), Frames.exportKind("ledger"), Frames.selftest(), Frames.shutdown()
        ]
        for (var i = 0; i < lines.length; i++) {
            verify(lines[i].indexOf("\n") < 0, "line " + i + " holds a raw line break")
            verify(lines[i].indexOf("\r") < 0)
            var o = JSON.parse(lines[i])
            verify(typeof o.t === "string")
        }
    }

    function test_borrowShape() {
        var o = JSON.parse(Frames.borrow("r7", "fake", "hi", 256.9))
        compare(o.t, "borrow")
        compare(o.rid, "r7")
        compare(o.model, "fake")
        compare(o.messages.length, 1)
        compare(o.messages[0].role, "user")
        compare(o.messages[0].content, "hi")
        compare(o.maxTokens, 256)
        compare(o.stream, true)
    }

    function test_integersOnly() {
        compare(Frames.ledger(1.9, 50.2), '{"t":"ledger","since":1,"limit":50}')
        compare(Frames.borrow("r", "m", "p", 3.5).indexOf("."), -1)
    }

    function test_loneSurrogatesAreScrubbedBeforeTheNodeSeesThem() {
        compare(Frames.scrub("a\uD800b"), "a�b")
        compare(Frames.scrub("a\uDC00b"), "a�b")
        compare(Frames.scrub("😀"), "😀")
        compare(Frames.scrub("x\uD83D"), "x�")
        var o = JSON.parse(Frames.borrow("r", "m", "bad\uD800", 1))
        compare(o.messages[0].content, "bad�")
    }

    function test_parseNode() {
        compare(Frames.parseNode('{"t":"state","node":"idle"}').node, "idle")
        compare(Frames.parseNode("not json"), null)
        compare(Frames.parseNode("[]"), null)
        compare(Frames.parseNode('{"x":1}'), null)
        compare(Frames.parseNode('{"t":5}'), null)
    }

    function test_theLimitIsOneMiB() {
        compare(Frames.MAX_LINE_BYTES, 1048576)
    }
}
