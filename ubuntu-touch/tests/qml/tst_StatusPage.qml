import QtQuick 2.12
import QtTest 1.2
import Asom.Bridge 1.0
import "../../qml"

// The pages against a fake node. In CI this runs with the real Lomiri.Components (clickable test); locally
// tools/run_qml_tests_local.sh puts a tiny stand-in module on the import path, which proves the bindings but not the styling.
TestCase {
    name: "Pages"
    id: tc
    width: 400
    height: 700
    visible: true
    when: windowShown

    Component { id: modelComponent; NodeModel {} }
    property var m: null

    property var status: null
    property var borrow: null

    function fakeNode() {
        return String(Qt.resolvedUrl("../fake_node.py")).replace(/^file:\/\//, "")
    }

    function initTestCase() {
        m = modelComponent.createObject(tc)
        m.lifecycle.simulate("active")
        m.testCommand = ["python3", fakeNode()]
        verify(m.begin())
        tryVerify(function () { return m.ready }, 5000)
        wait(50)
    }

    function cleanupTestCase() {
        m.end()
    }

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

    function test_1_statusPageSelfTestButton() {
        var page = Qt.createComponent("../../qml/StatusPage.qml").createObject(tc, { model: m, width: 400, height: 600 })
        verify(page, "StatusPage did not load")
        var button = child(page, "selfTestButton")
        verify(button.enabled)
        mouseClick(button)
        tryVerify(function () { return child(page, "selfTestText").text.indexOf("self-test: ok") === 0 }, 5000)
        page.destroy()
    }

    function test_2_headerShowsShapeAndLabel() {
        var h = Qt.createComponent("../../qml/StatusHeader.qml").createObject(tc, { nodeState: "active", sessions: 2, ready: true })
        verify(h)
        compare(child(h, "nodeLabel").text, "node: active (2 sessions)")
        compare(child(h, "nodeShape").shape, "circle-filled")
        h.nodeState = "interrupted"
        compare(child(h, "nodeShape").shape, "square")
        h.destroy()
    }

    function test_3_borrowPageShowsTheProvenanceLineWithShapeAndLabel() {
        var page = Qt.createComponent("../../qml/BorrowPage.qml").createObject(tc, { model: m, width: 400, height: 600 })
        verify(page)
        m.borrow("fake", "hello")
        tryVerify(function () { return m.answer.done }, 5000)
        compare(child(page, "answerText").text, "Hello from the fake node.")
        compare(child(page, "provenanceShape").shape, "triangle")
        verify(child(page, "provenanceLabel").text.indexOf("peer") === 0)
        verify(child(page, "provenanceText").text.indexOf("served by peer:fake-deck/fake") === 0)
        page.destroy()
    }

    function test_4_errorIsShownAsWords() {
        var page = Qt.createComponent("../../qml/BorrowPage.qml").createObject(tc, { model: m, width: 400, height: 600 })
        m.borrow("no-such-model", "hello")
        tryVerify(function () { return m.answer.done }, 5000)
        compare(child(page, "answerError").text, "no paired device has this model")
        verify(child(page, "answerError").visible)
        page.destroy()
    }
}
