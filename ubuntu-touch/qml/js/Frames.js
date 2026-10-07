.pragma library

// asom-ut-ctl/1, UI side (ubuntu-touch.md 7.3): one JSON object per line. Integers only; a string never holds a lone
// surrogate (the node's strict tokenizer would end the session on one), so text typed or pasted is scrubbed first.

var MAX_LINE_BYTES = 1048576

function scrub(s) {
    var out = ""
    for (var i = 0; i < s.length; i++) {
        var c = s.charCodeAt(i)
        if (c >= 0xD800 && c <= 0xDBFF) {
            var d = i + 1 < s.length ? s.charCodeAt(i + 1) : 0
            if (d >= 0xDC00 && d <= 0xDFFF) {
                out += s.charAt(i) + s.charAt(i + 1)
                i++
            } else {
                out += "�"
            }
        } else if (c >= 0xDC00 && c <= 0xDFFF) {
            out += "�"
        } else {
            out += s.charAt(i)
        }
    }
    return out
}

function line(obj) {
    return JSON.stringify(obj)
}

function hello() { return line({ t: "hello", v: 1 }) }
function lifecycle(state) { return line({ t: "lifecycle", state: state }) }
function borrow(rid, model, prompt, maxTokens) {
    return line({ t: "borrow", rid: rid, model: scrub(model), messages: [{ role: "user", content: scrub(prompt) }],
                  maxTokens: Math.floor(maxTokens), stream: true })
}
function cancel(rid) { return line({ t: "cancel", rid: rid }) }
function peers(open) { return line({ t: "peers", op: open ? "open" : "close" }) }
function pairBegin(value) { return line({ t: "pair", op: "begin", value: scrub(value) }) }
function pairConfirm(value) { return line({ t: "pair", op: "confirm", value: scrub(value) }) }
function pairApprove() { return line({ t: "pair", op: "approve" }) }
function revoke(peer) { return line({ t: "revoke", peer: scrub(peer) }) }
function ledger(since, limit) { return line({ t: "ledger", since: Math.floor(since), limit: Math.floor(limit) }) }
function exportKind(kind) { return line({ t: "export", kind: kind }) }
function selftest() { return line({ t: "selftest" }) }
function shutdown() { return line({ t: "shutdown" }) }

// A node line as an object, or null when it is not a frame. The node only ever sends frames; null means the pipe is broken.
function parseNode(text) {
    try {
        var o = JSON.parse(text)
        if (o !== null && typeof o === "object" && typeof o.t === "string")
            return o
    } catch (e) {
    }
    return null
}
