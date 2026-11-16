import QtQuick 2.12
import Lomiri.Components 1.3
import "tokens"
import "js/ErrorText.js" as ErrorText

// Node state, the self-test (UT0.3 and the S-UT1 spike, DV-UT01) and the About block.
Item {
    id: page

    property var model

    function selfTestSummary(r) {
        if (r === null || r === undefined)
            return ""
        var lines = ["self-test: " + r.selftest]
        if (r.tls !== undefined) lines.push("TLS " + r.tls + " · ALPN " + r.alpn + " · client certificate " + (r.clientAuth ? "seen" : "NOT seen"))
        if (r.vectors !== undefined) lines.push("vectors M01 " + r.vectors.M01 + " · M02 " + r.vectors.M02 + " · M03 " + r.vectors.M03 + " · failed " + r.vectors.failed)
        if (r.jsonl !== undefined) lines.push("ledger appends " + r.jsonl.appends + " in " + r.jsonl.ms + " ms")
        if (r.thermalReadable !== undefined) lines.push("thermal readable: " + r.thermalReadable + " · battery readable: " + r.batteryReadable)
        if (r.rssKiB !== undefined) lines.push("memory (RSS): " + r.rssKiB + " KiB")
        if (r.failed !== undefined) lines.push("FAILED: " + r.failed.join(", "))
        return lines.join("\n")
    }

    // What is true of this build only: UT-0 sends nodeTag "none" and has no keys and no release (ERRATA ERR-UT-CTL-2), so it must not
    // describe a key file, a copied identity or published release values (ERRATA ERR-FX-UT-4).
    function aboutSummary() {
        var m = page.model
        var sums = "Program checksum " + m.process.jarSha256 + ". Runtime list checksum " + m.process.runtimeManifestSha256 + "."
        if (m.nodeTag === "")
            return "About: the node has not started. " + sums
        if (m.nodeTag === "none")
            return "About: no node identity in this build (UT-0): it holds no keys, so there is nothing to copy and nothing to pair with yet. " + sums
        var storage = m.keyStorage === "file"
            ? "node key storage \"file\" (a file in this app's private folder; anything running as you outside the app's confinement can read it). "
            : "node key storage \"" + m.keyStorage + "\". "
        return "About: " + storage + sums + " Compare them with the values published beside the release. " +
               "Copying this app's data folder to another device copies the node's identity: pair again instead."
    }

    Column {
        anchors { fill: parent; margins: Tokens.gu }
        spacing: Tokens.gu

        Label {
            objectName: "failureText"
            textFormat: Text.PlainText
            visible: page.model.failure !== ""
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: page.model.failureText
        }
        Label {
            textFormat: Text.PlainText
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontBody
            text: "This app runs only while it is open on screen. Nothing runs in the background, and nothing leaves this phone until you ask for an answer."
        }
        Button {
            objectName: "selfTestButton"
            text: "Run self-test"
            enabled: page.model.ready
            onClicked: page.model.send('{"t":"selftest"}')
        }
        Label {
            objectName: "selfTestText"
            textFormat: Text.PlainText
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.text
            font.pixelSize: Tokens.fontSmall
            text: page.selfTestSummary(page.model.selfTestResult)
        }
        Label {
            objectName: "aboutText"
            textFormat: Text.PlainText
            width: parent.width
            wrapMode: Text.WordWrap
            color: Tokens.textMuted
            font.pixelSize: Tokens.fontSmall
            text: page.aboutSummary()
        }
    }
}
