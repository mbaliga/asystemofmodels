package xyz.mdhv.asom.ipc;

/** Async decision delivery (§5.7). token is non-null only on approval. */
oneway interface IAsomPairingCallback {
    void onDecision(int status, @nullable String token);
}
