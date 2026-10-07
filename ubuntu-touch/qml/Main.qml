import QtQuick 2.12
import Lomiri.Components 1.3
import "tokens"

// asom on Ubuntu Touch, UT-0 scaffold. One window, one node behind it, a status header on every page.
MainView {
    id: root

    applicationName: "xyz.mdhv.asom.ut"
    width: units.gu(50)
    height: units.gu(80)
    backgroundColor: Tokens.background

    property int current: 0
    readonly property var pages: [
        { title: "Status", source: "StatusPage.qml" },
        { title: "Borrow", source: "BorrowPage.qml" },
        { title: "Peers", source: "PeersPage.qml" },
        { title: "Pair", source: "PairPage.qml" },
        { title: "Ledger", source: "LedgerPage.qml" }
    ]

    Component.onCompleted: {
        Tokens.gu = units.gu(1)
        node.begin()
    }
    Component.onDestruction: node.end()

    NodeModel {
        id: node
    }

    Column {
        anchors.fill: parent

        StatusHeader {
            id: statusHeader
            width: parent.width
            nodeState: node.nodeState
            sessions: node.sessions
            ready: node.ready
            lending: node.lending
        }
        Loader {
            id: pageLoader
            width: parent.width
            height: parent.height - statusHeader.height - navBar.height
            source: root.pages[root.current].source
            onLoaded: item.model = node
        }
        Row {
            id: navBar
            width: parent.width
            Repeater {
                model: root.pages
                delegate: Button {
                    objectName: "nav" + modelData.title
                    width: navBar.width / root.pages.length
                    text: modelData.title
                    onClicked: root.current = index
                }
            }
        }
    }
}
