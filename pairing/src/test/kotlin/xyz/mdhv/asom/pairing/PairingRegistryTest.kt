package xyz.mdhv.asom.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import xyz.mdhv.asom.contract.PairingStatusCode

/** In-memory PairingDao with the same (packageName, certHash) primary key Room uses. */
private class FakePairingDao : PairingDao {
    val rows = linkedMapOf<Pair<String, String>, PairingEntity>()

    override fun upsert(entity: PairingEntity) {
        rows[entity.packageName to entity.certHash] = entity
    }

    override fun find(pkg: String, certHash: String): PairingEntity? = rows[pkg to certHash]

    override fun all(): List<PairingEntity> = rows.values.sortedByDescending { it.createdAt }

    override fun observeAll(): Flow<List<PairingEntity>> = flowOf(all())

    override fun allWithTokens(): List<PairingEntity> = rows.values.filter { it.tokenHash != null }

    override fun setStatus(pkg: String, certHash: String, status: Int) {
        val key = pkg to certHash
        rows[key]?.let { rows[key] = it.copy(status = status) }
    }

    override fun delete(pkg: String, certHash: String) {
        rows.remove(pkg to certHash)
    }
}

/**
 * Stands in for the AIDL callback proxy: records what the client was told and
 * models binder liveness, so eviction can be asserted through the registry's
 * own behaviour rather than its internals.
 */
private class FakeClient(var alive: Boolean = true) : PendingClient {
    var status: Int? = null
    var token: String? = null

    /** Delivery attempts, successful or not — a dead client must see zero. */
    var deliveries = 0
    var linked = false
    var unlinks = 0
    private var deathHandler: (() -> Unit)? = null

    override fun deliver(status: Int, token: String?): Boolean {
        deliveries++
        if (!alive) return false
        this.status = status
        this.token = token
        return true
    }

    override fun linkToDeath(onDeath: () -> Unit): Boolean {
        if (!alive) return false
        deathHandler = onDeath
        linked = true
        return true
    }

    override fun unlink() {
        unlinks++
        linked = false
    }

    /** The client process dies while the registry is still watching it. */
    fun die() {
        alive = false
        if (linked) deathHandler?.invoke()
    }

    /**
     * A death notification already dispatched when unlink() ran — a real
     * binder race, and the reason eviction has to be value-guarded.
     */
    fun raceDeathNotification() {
        alive = false
        deathHandler?.invoke()
    }
}

class PairingRegistryTest {

    private val dao = FakePairingDao()
    private val registry = PairingRegistry(dao)

    private val caller = VerifiedCaller(
        uid = 10123,
        packageName = "xyz.mdhv.fonebru",
        certHash = "a".repeat(64),
        label = "Fonebru",
    )

    private fun pairFully(client: FakeClient = FakeClient()): String {
        registry.requestPairing(caller, client)
        return requireNotNull(registry.approve(caller.packageName, caller.certHash))
    }

    // ------------------------------------------------------------ happy path

    @Test
    fun `approve mints a token, stores only its hash, and check authorizes it`() {
        val token = pairFully()

        val row = requireNotNull(dao.find(caller.packageName, caller.certHash))
        assertEquals(PairingStatusCode.PAIRED, row.status)
        assertEquals(PairingRegistry.sha256Hex(token), row.tokenHash)
        assertNotEquals(token, row.tokenHash)

        assertEquals(TokenCheck.Valid(caller.packageName), registry.check(token))
    }

    @Test
    fun `raw token is fetched exactly once when the callback could not deliver it`() {
        // The fetch-later path: the client was registered (so it linked), but its
        // process was gone by the time the decision was pushed.
        val client = FakeClient()
        registry.requestPairing(caller, client)
        client.alive = false
        val token = requireNotNull(registry.approve(caller.packageName, caller.certHash))

        assertEquals(1, client.deliveries, "the push must have been attempted")
        assertEquals(token, registry.takeToken(caller))
        assertNull(registry.takeToken(caller))
    }

    @Test
    fun `approve only acts on a PENDING row`() {
        pairFully()
        assertNull(registry.approve(caller.packageName, caller.certHash))
    }

    @Test
    fun `unknown token is Unknown`() {
        pairFully()
        assertIs<TokenCheck.Unknown>(registry.check("00".repeat(32)))
    }

    // ------------------------------------------------------------- revocation

    @Test
    fun `revoke then check is Revoked`() {
        val token = pairFully()
        registry.revoke(caller.packageName, caller.certHash)

        assertIs<TokenCheck.Revoked>(registry.check(token))
    }

    @Test
    fun `revoke then requestPairing then check is still Revoked`() {
        val token = pairFully()
        registry.revoke(caller.packageName, caller.certHash)

        val client = FakeClient()
        registry.requestPairing(caller, client)

        assertIs<TokenCheck.Revoked>(registry.check(token))
        assertEquals(
            PairingStatusCode.REVOKED,
            requireNotNull(dao.find(caller.packageName, caller.certHash)).status,
        )
        assertEquals(PairingStatusCode.REVOKED, client.status)
        assertNull(client.token)
        assertTrue(registry.pending().isEmpty(), "a revoked app must not reach the consent sheet")
    }

    @Test
    fun `a revoked row cannot be approved back into service`() {
        val token = pairFully()
        registry.revoke(caller.packageName, caller.certHash)
        registry.requestPairing(caller, FakeClient())

        assertNull(registry.approve(caller.packageName, caller.certHash))
        assertIs<TokenCheck.Revoked>(registry.check(token))
    }

    @Test
    fun `owner Remove is the only way back after revocation`() {
        val oldToken = pairFully()
        registry.revoke(caller.packageName, caller.certHash)
        registry.remove(caller.packageName, caller.certHash)

        registry.requestPairing(caller, FakeClient())
        val newToken = requireNotNull(registry.approve(caller.packageName, caller.certHash))

        assertNotEquals(oldToken, newToken)
        assertEquals(TokenCheck.Valid(caller.packageName), registry.check(newToken))
        assertIs<TokenCheck.Unknown>(registry.check(oldToken))
    }

    @Test
    fun `check fails closed — a token hash on a non-PAIRED row never authorizes`() {
        val token = "deadbeef"
        dao.upsert(
            PairingEntity(
                packageName = caller.packageName,
                certHash = caller.certHash,
                label = caller.label,
                tokenHash = PairingRegistry.sha256Hex(token),
                status = PairingStatusCode.PENDING,
                createdAt = 0L,
                approvedAt = null,
            ),
        )

        assertIs<TokenCheck.Unknown>(registry.check(token))
    }

    // ------------------------------------------------------------------ deny

    @Test
    fun `deny drops the row and the caller is told NOT_PAIRED`() {
        val client = FakeClient()
        registry.requestPairing(caller, client)
        registry.deny(caller.packageName, caller.certHash)

        assertNull(dao.find(caller.packageName, caller.certHash))
        assertEquals(PairingStatusCode.NOT_PAIRED, registry.status(caller))
        assertEquals(PairingStatusCode.NOT_PAIRED, client.status)
        assertNull(client.token)
    }

    @Test
    fun `deny after approval kills the token — check is Unknown`() {
        val token = pairFully()
        registry.deny(caller.packageName, caller.certHash)

        assertIs<TokenCheck.Unknown>(registry.check(token))
    }

    // -------------------------------------------------------- reinstall path

    @Test
    fun `a paired app that lost its token can re-pair through fresh consent`() {
        val oldToken = pairFully()

        // Reinstall / data clear: same (package, certHash), no local token.
        val client = FakeClient()
        registry.requestPairing(caller, client)

        val row = requireNotNull(dao.find(caller.packageName, caller.certHash))
        assertEquals(PairingStatusCode.PENDING, row.status)
        assertNull(row.tokenHash)
        assertIs<TokenCheck.Unknown>(registry.check(oldToken))
        assertEquals(listOf(caller.packageName), registry.pending().map { it.packageName })
        assertNull(client.status, "re-pair must wait for consent, not answer immediately")

        val newToken = requireNotNull(registry.approve(caller.packageName, caller.certHash))
        assertNotEquals(oldToken, newToken)
        assertEquals(PairingStatusCode.PAIRED, client.status)
        assertEquals(newToken, client.token)
        assertEquals(TokenCheck.Valid(caller.packageName), registry.check(newToken))
        assertIs<TokenCheck.Unknown>(registry.check(oldToken))
    }

    @Test
    fun `a re-pair request retires any undelivered token from the previous approval`() {
        // Undelivered: the push failed, so the token is still staged for getToken().
        val stranded = FakeClient()
        registry.requestPairing(caller, stranded)
        stranded.alive = false
        registry.approve(caller.packageName, caller.certHash)

        registry.requestPairing(caller, FakeClient())

        assertNull(registry.takeToken(caller))
    }

    @Test
    fun `re-pairing keeps the original createdAt so the row is not duplicated`() {
        val fixed = PairingRegistry(dao, clock = { 4242L })
        fixed.requestPairing(caller, FakeClient())
        fixed.approve(caller.packageName, caller.certHash)

        fixed.requestPairing(caller, FakeClient())

        assertEquals(1, dao.rows.size)
        assertEquals(4242L, requireNotNull(dao.find(caller.packageName, caller.certHash)).createdAt)
    }

    // ------------------------------------------------------------- isolation

    @Test
    fun `a different signing cert is a different pairing`() {
        val token = pairFully()
        val impostor = caller.copy(certHash = "b".repeat(64))

        registry.requestPairing(impostor, FakeClient())
        registry.approve(impostor.packageName, impostor.certHash)

        assertEquals(2, dao.rows.size)
        assertEquals(TokenCheck.Valid(caller.packageName), registry.check(token))

        registry.revoke(impostor.packageName, impostor.certHash)
        assertEquals(TokenCheck.Valid(caller.packageName), registry.check(token))
    }

    // ------------------------------------------------------- client liveness

    @Test
    fun `a dead client binder is evicted and never delivered to`() {
        val client = FakeClient()
        registry.requestPairing(caller, client)
        assertTrue(client.linked, "a waiting client must be watched for death")

        client.die()
        val token = requireNotNull(registry.approve(caller.packageName, caller.certHash))

        assertEquals(0, client.deliveries, "a dead client's proxy must not be retained")
        assertNull(client.status)
        assertEquals(token, registry.takeToken(caller))
    }

    @Test
    fun `a client that is already dead is never retained`() {
        val client = FakeClient(alive = false)
        registry.requestPairing(caller, client)

        assertFalse(client.linked)
        registry.approve(caller.packageName, caller.certHash)
        assertEquals(0, client.deliveries)
    }

    @Test
    fun `the normal delivery path unlinks instead of leaking death recipients`() {
        val client = FakeClient()
        registry.requestPairing(caller, client)
        registry.approve(caller.packageName, caller.certHash)

        assertEquals(1, client.unlinks, "the death recipient must be dropped on delivery")
        assertFalse(client.linked)
    }

    @Test
    fun `deny unlinks the waiting client too`() {
        val client = FakeClient()
        registry.requestPairing(caller, client)
        registry.deny(caller.packageName, caller.certHash)

        assertEquals(1, client.unlinks)
        assertFalse(client.linked)
    }

    @Test
    fun `a superseding request releases the previous client`() {
        val first = FakeClient()
        registry.requestPairing(caller, first)
        val second = FakeClient()
        registry.requestPairing(caller, second)

        assertEquals(1, first.unlinks)
        assertFalse(first.linked)

        registry.approve(caller.packageName, caller.certHash)
        assertEquals(0, first.deliveries, "only the current client is owed a decision")
        assertEquals(PairingStatusCode.PAIRED, second.status)
    }

    @Test
    fun `a death notification arriving after release cannot evict the current client`() {
        val first = FakeClient()
        registry.requestPairing(caller, first)
        val second = FakeClient()
        registry.requestPairing(caller, second)

        first.raceDeathNotification()

        registry.approve(caller.packageName, caller.certHash)
        assertEquals(PairingStatusCode.PAIRED, second.status)
        assertNull(registry.takeToken(caller), "the live client was still delivered to")
    }

    // ------------------------------------------------------ plaintext hygiene

    @Test
    fun `an approved token is cleared from undelivered once the push succeeds`() {
        val client = FakeClient()
        val token = pairFully(client)

        assertEquals(token, client.token)
        assertNull(
            registry.takeToken(caller),
            "a delivered plaintext token must not linger in the daemon's heap",
        )
    }

    // ------------------------------------------------- unverifiable + dismiss

    @Test
    fun `an unverifiable caller gets a definitive refusal and creates no row`() {
        val client = FakeClient()
        registry.refuseUnverified(client)

        assertEquals(PairingStatusCode.NOT_PAIRED, client.status)
        assertNull(client.token)
        assertTrue(dao.rows.isEmpty(), "an unverified caller must never reach the consent sheet")
        assertFalse(client.linked, "an unverified caller's proxy must not be retained")
    }

    @Test
    fun `dismissal answers the waiting client with PENDING and decides nothing`() {
        val client = FakeClient()
        registry.requestPairing(caller, client)

        registry.dismiss()

        assertEquals(PairingStatusCode.PENDING, client.status)
        assertNull(client.token)
        assertEquals(PairingStatusCode.PENDING, registry.status(caller))
        assertNull(requireNotNull(dao.find(caller.packageName, caller.certHash)).tokenHash)
        assertEquals(1, client.unlinks)
    }

    @Test
    fun `an out-of-band approval after dismissal is still fetchable`() {
        val client = FakeClient()
        registry.requestPairing(caller, client)
        registry.dismiss()

        val token = requireNotNull(registry.approve(caller.packageName, caller.certHash))

        assertEquals(1, client.deliveries, "the dismissal was the only push")
        assertEquals(token, registry.takeToken(caller))
    }

    @Test
    fun `dismissal after a decision does not push a second answer`() {
        val client = FakeClient()
        registry.requestPairing(caller, client)
        registry.approve(caller.packageName, caller.certHash)

        registry.dismiss()

        assertEquals(1, client.deliveries)
        assertEquals(PairingStatusCode.PAIRED, client.status)
    }
}
