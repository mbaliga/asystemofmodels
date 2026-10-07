import QtQuick 2.12
import Asom.Bridge 1.0
import "js/Frames.js" as Frames
import "js/ErrorText.js" as ErrorText

// The UI's view of the node: it owns the bridge objects, speaks asom-ut-ctl/1 through Frames.js, and exposes plain properties
// to the pages. The node is the only source of truth: nothing here is computed from anything but a frame.
Item {
    id: model
    visible: false

    property string nodeState: "idle"
    property int sessions: 0
    property bool ready: false
    property string nodeTag: ""
    property string keyStorage: ""
    property string lending: "off"
    property string failure: ""
    property string failureText: ""
    property string notice: ""
    property var answer: ({ rid: "", text: "", record: null, error: "", done: true })
    property var peers: []
    property var rows: []
    property var selfTestResult: null
    property int ridCounter: 0
    property bool inflight: false

    property alias process: proc
    property alias display: keeper
    property alias lifecycle: lc
    // Tests only. Production QML never sets it, so the bundled runtime is always what runs.
    property alias testCommand: proc.testCommand

    signal frame(var frame)

    function begin() {
        var installDir = String(Qt.resolvedUrl("..")).replace(/^file:\/\//, "").replace(/\/$/, "")
        if (proc.installDir === "")
            proc.installDir = installDir
        if (!proc.start())
            return false
        return true
    }

    function end() {
        keeper.release()
        proc.stop()
    }

    function send(text) {
        return proc.send(text)
    }

    function borrow(modelName, prompt) {
        ridCounter += 1
        var rid = "r" + ridCounter
        answer = { rid: rid, text: "", record: null, error: "", done: false }
        inflight = true
        keeper.hold()
        if (!proc.send(Frames.borrow(rid, modelName, prompt, 256))) {
            finish("INTERRUPTED_BY_SUSPEND")
            return ""
        }
        return rid
    }

    function cancel() {
        if (answer.rid === "" || answer.done)
            return
        proc.send(Frames.cancel(answer.rid))
        answer = { rid: answer.rid, text: answer.text, record: null, error: "cancelled", done: true }
        inflight = false
        keeper.release()
    }

    function finish(code) {
        keeper.release()
        inflight = false
        answer = { rid: answer.rid, text: answer.text, record: answer.record, error: code === "" ? "" : ErrorText.messageFor(code), done: true }
    }

    function handleLine(text) {
        var f = Frames.parseNode(text)
        if (f === null) {
            failure = "bad-frame"
            failureText = ErrorText.failureText("bad-frame")
            proc.stop()
            return
        }
        frame(f)
        if (f.t === "hello_ack") {
            nodeTag = f.nodeTag
            keyStorage = f.keyStorage
            ready = true
        } else if (f.t === "state") {
            nodeState = f.node
            sessions = f.sessions
            lending = f.lending
        } else if (f.t === "chunk") {
            if (f.rid === answer.rid && !answer.done)
                answer = { rid: answer.rid, text: answer.text + f.delta, record: null, error: "", done: false }
        } else if (f.t === "end") {
            if (f.rid === answer.rid) {
                keeper.release()
                inflight = false
                answer = { rid: answer.rid, text: answer.text, record: f.record, error: "", done: true }
            }
        } else if (f.t === "error") {
            if (f.rid !== null && f.rid === answer.rid)
                finish(f.code)
            else
                notice = ErrorText.messageFor(f.code)
        } else if (f.t === "peers") {
            peers = f.list
        } else if (f.t === "rows") {
            rows = f.rows
        } else if (f.t === "selftest") {
            selfTestResult = f.result
        }
    }

    NodeProcess {
        id: proc
        onLineReceived: model.handleLine(line)
        onStarted: {
            proc.send(Frames.hello())
            proc.send(Frames.lifecycle(lc.state))
        }
        onFailed: {
            model.failure = reason
            model.failureText = ErrorText.failureText(reason)
        }
        onExited: {
            model.ready = false
            model.nodeState = "interrupted"
            model.keeperReleaseAll()
        }
    }

    function keeperReleaseAll() {
        keeper.release()
        inflight = false
    }

    DisplayKeeper {
        id: keeper
    }

    LifecycleForwarder {
        id: lc
        onReport: {
            if (proc.running)
                proc.send(Frames.lifecycle(state))
        }
        onStateChanged: {
            // L-UT2: leaving the foreground ends the hold at once, without waiting for the node.
            if (state !== "active")
                keeper.release()
        }
    }
}
