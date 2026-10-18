.pragma library

// What the owner reads for each `error{code}` of asom-ut-ctl/1. The wording is the node's own (ErrorMapping.kt, UiErrorCode);
// tools/check_texts.py fails the build when the two lists differ.
var MESSAGES = {
    "NOT_PAIRED": "this app is not paired",
    "TOKEN_REVOKED": "this pairing was revoked",
    "NO_PROVIDER_KEY": "pair a device to use asom here",
    "MODEL_UNKNOWN": "no paired device has this model",
    "ALL_PROVIDERS_COOLING": "all your paired devices are unavailable",
    "LOCAL_ENGINE_ABSENT": "this device has no local engine",
    "UNSUPPORTED_BY_DRIVER": "this build cannot do that yet",
    "LEDGER_UNAVAILABLE": "the ledger cannot be written, so nothing was sent",
    "MESH_STREAM_INTERRUPTED": "the paired device stopped in the middle of the answer",
    "INTERRUPTED_BY_SUSPEND": "the app was paused, so the answer was cut"
}

var FALLBACK = "the node reported an error this build does not know"

function messageFor(code) {
    return Object.prototype.hasOwnProperty.call(MESSAGES, code) ? MESSAGES[code] : FALLBACK
}

// The owner-facing reason for a plugin failure (NodeProcess.failed): fixed words, never a path.
var FAILURES = {
    "runtime-missing": "the bundled Java runtime is missing from this install",
    "runtime-hash-mismatch": "the bundled Java runtime does not match its checksum list, so it was not started",
    "jar-missing": "the node program is missing from this install",
    "jar-hash-mismatch": "the node program does not match its checksum, so it was not started",
    "options-missing": "the runtime options file is missing from this install",
    "spawn-failed": "the node could not be started",
    "line-too-long": "the node sent a line over 1 MiB, so it was stopped",
    "bad-frame": "the node sent something that is not a frame, so it was stopped"
}

function failureText(reason) {
    return Object.prototype.hasOwnProperty.call(FAILURES, reason) ? FAILURES[reason] : "the node stopped for an unknown reason"
}
