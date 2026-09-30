import QtQuick 2.12
import QtTest 1.2
import Asom.Bridge 1.0
import "../../qml"

// The UI's view of the node, driven through the REAL Asom.Bridge plugin (QProcess pipes, line cap, lifecycle forwarder) against a
// scripted fake node. Nothing here proves anything about the JVM node or a device: tools/check_fake_nodes.py ties the fake to
// the JVM `--fake-ui`, and the UTC vectors pin the JVM node itself.
TestCase {
    name: "NodeModel"

    Component { id: modelComponent; NodeModel {} }
    property var m: null

    function init() {
        m = modelComponent.createObject(this)
    }

    function fakeNode() {
        return String(Qt.resolvedUrl("../fake_node.py")).replace(/^file:\/\//, "")
    }

    function cleanup() {
        m.end()
        wait(50)
        m.destroy()
        m = null
    }

    function startFake() {
        // Under qmltestrunner the application is not "active" by itself; a phone's foreground app is.
        m.lifecycle.simulate("active")
        m.testCommand = ["python3", fakeNode()]
        verify(m.begin(), "the fake node did not start")
        tryVerify(function () { return m.ready }, 5000)
    }

    function test_helloAckAndFirstState() {
        startFake()
        compare(m.nodeState, "idle")
        compare(m.nodeTag, "fake0fake0fake0fa")
        compare(m.keyStorage, "file")
        compare(m.failure, "")
    }

    function test_aBorrowStreamsAndKeepsTheNodesProvenanceRecord() {
        startFake()
        m.lifecycle.simulate("active")
        wait(50)
        m.borrow("fake", "hello")
        tryVerify(function () { return m.answer.done }, 5000)
        compare(m.answer.text, "Hello from the fake node.")
        compare(m.answer.error, "")
        compare(m.answer.record.servedClass, "peer")
        compare(m.answer.record.provenance, "served by peer:fake-deck/fake · via lan")
        compare(m.answer.record.egress, m.answer.record.reach)
        compare(m.nodeState, "active")
        verify(!m.inflight)
    }

    function test_errorsArriveAsPlainWords() {
        startFake()
        m.lifecycle.simulate("active")
        m.borrow("no-such-model", "hi")
        tryVerify(function () { return m.answer.done }, 5000)
        compare(m.answer.error, "no paired device has this model")
        m.borrow("fake-cooling", "hi")
        tryVerify(function () { return m.answer.done && m.answer.error !== "" && m.answer.rid === "r2" }, 5000)
        compare(m.answer.error, "all your paired devices are unavailable")
        m.borrow("local-only", "hi")
        tryVerify(function () { return m.answer.done && m.answer.rid === "r3" }, 5000)
        compare(m.answer.error, "this device has no local engine")
        m.borrow("fake-interrupt", "hi")
        tryVerify(function () { return m.answer.done && m.answer.rid === "r4" }, 5000)
        compare(m.answer.error, "the paired device stopped in the middle of the answer")
        compare(m.answer.text, "Hello ")
    }

    function test_theDisplayIsHeldOnlyWhileAnAnswerIsInFlight() {
        m.display.service = "org.invalid.NoSuchScreenService"
        startFake()
        m.lifecycle.simulate("active")
        verify(!m.display.held)
        m.borrow("fake", "hi")
        tryVerify(function () { return m.answer.done }, 5000)
        verify(!m.display.held, "released after the terminal frame")
        verify(!m.inflight)
    }

    function test_leavingTheForegroundIsForwardedAndTheNextRequestIsRefused() {
        startFake()
        m.lifecycle.simulate("active")
        wait(100)
        m.lifecycle.simulate("inactive")
        tryCompare(m, "nodeState", "interrupted", 5000)
        m.borrow("fake", "hi")
        tryVerify(function () { return m.answer.done }, 5000)
        compare(m.answer.error, "the app was paused, so the answer was cut")
        m.lifecycle.simulate("active")
        tryCompare(m, "nodeState", "idle", 5000)
    }

    function test_peersAndLedgerAndUnsupportedFrames() {
        startFake()
        m.lifecycle.simulate("active")
        wait(50)
        m.send('{"t":"peers","op":"open"}')
        tryVerify(function () { return m.peers.length === 2 }, 5000)
        compare(m.peers[0].alias, "fake-deck")
        compare(m.nodeState, "active")
        m.send('{"t":"ledger","since":0,"limit":10}')
        tryVerify(function () { return m.rows.length === 1 }, 5000)
        compare(m.rows[0].requestId, "fake-1")
        m.send('{"t":"pair","op":"begin","value":"x"}')
        tryVerify(function () { return m.notice !== "" }, 5000)
        compare(m.notice, "this build cannot do that yet")
    }

    function test_selfTestResultIsKept() {
        startFake()
        m.send('{"t":"selftest"}')
        tryVerify(function () { return m.selfTestResult !== null }, 5000)
        compare(m.selfTestResult.selftest, "ok")
    }

    function test_aLineOverOneMiBStopsTheNodeWithAFixedReason() {
        m.testCommand = ["/bin/sh", "-c", "head -c 2000000 /dev/zero | tr '\\0' a; sleep 30"]
        m.begin()
        tryVerify(function () { return m.failure === "line-too-long" }, 10000)
        compare(m.failureText, "the node sent a line over 1 MiB, so it was stopped")
    }

    function test_aBrokenPipeEndsUpAsInterruptedNotAsAHang() {
        m.testCommand = ["/bin/sh", "-c", "echo 'not a frame'; sleep 30"]
        m.begin()
        tryVerify(function () { return m.failure === "bad-frame" }, 5000)
        tryVerify(function () { return !m.process.running }, 5000)
        compare(m.nodeState, "interrupted")
    }

    function test_theBundledRuntimeIsRefusedWhenItIsAbsent() {
        m.testCommand = []
        m.process.installDir = "/nonexistent-asom-install"
        verify(!m.begin())
        compare(m.failure, "runtime-missing")
    }
}
