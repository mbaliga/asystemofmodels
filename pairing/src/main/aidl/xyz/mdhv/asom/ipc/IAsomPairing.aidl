package xyz.mdhv.asom.ipc;

import xyz.mdhv.asom.ipc.IAsomPairingCallback;

/**
 * The AI-hotspot pairing interface (brief §5.7), exported with action
 * xyz.mdhv.asom.PAIR. Caller identity is ALWAYS derived from
 * Binder.getCallingUid() inside the daemon — never from parameters.
 */
interface IAsomPairing {
    /** Records/refreshes a pending pairing request for the verified caller. */
    void requestPairing(IAsomPairingCallback callback);

    /**
     * Returns the raw token exactly once after approval (daemon stores only
     * its SHA-256); null on every later call and in every other state.
     */
    @nullable String getToken();

    /** One of the PairingStatusCode ints (0..3). */
    int getStatus();
}
