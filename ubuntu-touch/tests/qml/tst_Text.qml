import QtQuick 2.12
import QtTest 1.2
import "../../qml"

// Text the app does not control (a model's answer, a peer's self-reported alias, ledger rows) is shown as literal characters
// (ERRATA ERR-FX-UT-1). Qt's default text format would turn a string that starts with an HTML tag into StyledText, and StyledText
// fetches <img src> URLs from the UI process. Each page is driven by a stand-in model, so no node, no plugin and no network is involved.
// tools/check_qml_text.py is the static twin of this file. Where these tests ran is stated in PROGRESS.md: not on a device.
TestCase {
    name: "PlainText"
    id: tc
    width: 400
    height: 700
    visible: true
    when: windowShown

    readonly property string evil: "<img src=\"https://x.example/p.png\">"
    readonly property string bold: "<b>bold</b> <i>name</i>"

    Component {
        id: modelComponent
        QtObject {
            property bool ready: true
            property bool inflight: false
            property var answer: ({ rid: "", text: "", record: null, error: "", done: false })
            property var peers: []
            property var rows: []
            property string failure: ""
            property string failureText: ""
            property string notice: ""
            property var selfTestResult: null
            property string nodeTag: ""
            property string keyStorage: ""
            property var process: QtObject { property string jarSha256: "j"; property string runtimeManifestSha256: "r" }
            property var sent: []
            function send(line) { sent = sent.concat([line]) }
            function borrow(model, prompt) {}
            function cancel() {}
        }
    }
    Component { id: reference; Text { textFormat: Text.PlainText } }

    function child(item, name) {
        if (item.objectName === name)
            return item
        var kids = item.children
        for (var i = 0; i < kids.length; i++) {
            var f = child(kids[i], name)
            if (f)
                return f
        }
        return null
    }

    function page(file, model) {
        var p = Qt.createComponent("../../qml/" + file).createObject(tc, { model: model, width: 400, height: 600 })
        verify(p, file + " did not load")
        return p
    }

    // The width the same characters take as plain text in the same font: rich text of "<b>bold</b>" would be narrower and bold.
    function assertLiteral(label, expected) {
        verify(label, "label not found")
        compare(label.textFormat, Text.PlainText)
        compare(label.text, expected)
        var ref = reference.createObject(tc, { text: expected, font: label.font, width: label.width, wrapMode: label.wrapMode })
        compare(label.contentWidth, ref.contentWidth, "the label does not lay the string out as plain text")
        ref.destroy()
    }

    function test_peerAliasWithAnImgTagIsShownAsCharacters() {
        var m = modelComponent.createObject(tc, { peers: [{ alias: evil, keyStorage: "file" }, { alias: bold, keyStorage: "" }] })
        var p = page("PeersPage.qml", m)
        var found = []
        function walk(item) {
            if (item.objectName === "peerAlias")
                found.push(item)
            for (var i = 0; i < item.children.length; i++)
                walk(item.children[i])
        }
        walk(p)
        compare(found.length, 2)
        assertLiteral(found[0], evil + " (self-reported) · key: file (self-reported)")
        assertLiteral(found[1], bold + " (self-reported)")
        p.destroy()
    }

    function test_theModelsAnswerIsShownAsCharacters() {
        var m = modelComponent.createObject(tc, { answer: { rid: "r", text: evil, record: null, error: "", done: true } })
        var p = page("BorrowPage.qml", m)
        assertLiteral(child(p, "answerText"), evil)
        m.answer = { rid: "r", text: bold, record: null, error: "", done: true }
        assertLiteral(child(p, "answerText"), bold)
        p.destroy()
    }

    function test_aProvenanceLineWithAPeerNameIsShownAsCharacters() {
        var record = { servedClass: "peer", peerPath: "lan", provenance: "served by " + evil + " (peer)" }
        var m = modelComponent.createObject(tc, { answer: { rid: "r", text: "x", record: record, error: "", done: true } })
        var p = page("BorrowPage.qml", m)
        assertLiteral(child(p, "provenanceText"), "served by " + evil + " (peer)  (name is self-reported by the peer)")
        compare(child(p, "provenanceLabel").textFormat, Text.PlainText)
        p.destroy()
    }

    function test_ledgerRowsAreShownAsCharacters() {
        var m = modelComponent.createObject(tc, { rows: [{ ts: 1, peerAlias: evil }] })
        var p = page("LedgerPage.qml", m)
        assertLiteral(child(p, "ledgerRow"), JSON.stringify({ ts: 1, peerAlias: evil }))
        p.destroy()
    }

    function test_statusFieldsAreShownAsCharacters() {
        var m = modelComponent.createObject(tc, { failure: "bad-frame", failureText: evil, selfTestResult: { selftest: evil } })
        var p = page("StatusPage.qml", m)
        assertLiteral(child(p, "failureText"), evil)
        assertLiteral(child(p, "selfTestText"), "self-test: " + evil)
        p.destroy()
    }

    function test_theHeaderAndPairNoticeAreShownAsCharacters() {
        var h = Qt.createComponent("../../qml/StatusHeader.qml").createObject(tc, { nodeState: "idle", ready: true })
        compare(child(h, "nodeLabel").textFormat, Text.PlainText)
        compare(child(h, "lendingBanner").textFormat, Text.PlainText)
        h.destroy()
        var m = modelComponent.createObject(tc, { notice: evil })
        var p = page("PairPage.qml", m)
        assertLiteral(child(p, "pairNotice"), evil)
        p.destroy()
    }

    function test_aboutSaysOnlyWhatIsTrueOfThisBuild() {
        var m = modelComponent.createObject(tc, { nodeTag: "none", keyStorage: "unknown" })
        var p = page("StatusPage.qml", m)
        var about = child(p, "aboutText")
        verify(about.text.indexOf("no node identity in this build (UT-0)") >= 0, about.text)
        verify(about.text.indexOf("private folder") < 0, "UT-0 has no key file: " + about.text)
        verify(about.text.indexOf("copies the node's identity") < 0, about.text)
        verify(about.text.indexOf("published beside the release") < 0, "UT-0 has no release: " + about.text)
        m.nodeTag = ""
        verify(about.text.indexOf("has not started") >= 0, about.text)
        m.nodeTag = "abcd0123abcd0123"
        m.keyStorage = "file"
        verify(about.text.indexOf("a file in this app's private folder") >= 0, about.text)
        verify(about.text.indexOf("published beside the release") >= 0, about.text)
        p.destroy()
    }
}
