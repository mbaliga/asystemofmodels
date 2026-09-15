package xyz.mdhv.asom.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
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

/** Records the AIDL callback so tests can assert what the client was told. */
private class Decision {
    var status: Int? = null
    var token: String? = null
    val sink: (Int, String?) -> Unit = { s, t ->
        status = s
        token = t
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

    private fun pairFully(): String {
        registry.requestPairing(caller, Decision().sink)
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
    fun `raw token is delivered exactly once`() {
        val token = pairFully()

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

        val decision = Decision()
        registry.requestPairing(caller, decision.sink)

        assertIs<TokenCheck.Revoked>(registry.check(token))
        assertEquals(
            PairingStatusCode.REVOKED,
            requireNotNull(dao.find(caller.packageName, caller.certHash)).status,
        )
        assertEquals(PairingStatusCode.REVOKED, decision.status)
        assertNull(decision.token)
        assertTrue(registry.pending().isEmpty(), "a revoked app must not reach the consent sheet")
    }

    @Test
    fun `a revoked row cannot be approved back into service`() {
        val token = pairFully()
        registry.revoke(caller.packageName, caller.certHash)
        registry.requestPairing(caller, Decision().sink)

        assertNull(registry.approve(caller.packageName, caller.certHash))
        assertIs<TokenCheck.Revoked>(registry.check(token))
    }

    @Test
    fun `owner Remove is the only way back after revocation`() {
        val oldToken = pairFully()
        registry.revoke(caller.packageName, caller.certHash)
        registry.remove(caller.packageName, caller.certHash)

        registry.requestPairing(caller, Decision().sink)
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
        val decision = Decision()
        registry.requestPairing(caller, decision.sink)
        registry.deny(caller.packageName, caller.certHash)

        assertNull(dao.find(caller.packageName, caller.certHash))
        assertEquals(PairingStatusCode.NOT_PAIRED, registry.status(caller))
        assertEquals(PairingStatusCode.NOT_PAIRED, decision.status)
        assertNull(decision.token)
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
        registry.takeToken(caller)

        // Reinstall / data clear: same (package, certHash), no local token.
        val decision = Decision()
        registry.requestPairing(caller, decision.sink)

        val row = requireNotNull(dao.find(caller.packageName, caller.certHash))
        assertEquals(PairingStatusCode.PENDING, row.status)
        assertNull(row.tokenHash)
        assertIs<TokenCheck.Unknown>(registry.check(oldToken))
        assertEquals(listOf(caller.packageName), registry.pending().map { it.packageName })
        assertNull(decision.status, "re-pair must wait for consent, not answer immediately")

        val newToken = requireNotNull(registry.approve(caller.packageName, caller.certHash))
        assertNotEquals(oldToken, newToken)
        assertEquals(PairingStatusCode.PAIRED, decision.status)
        assertEquals(newToken, decision.token)
        assertEquals(TokenCheck.Valid(caller.packageName), registry.check(newToken))
        assertIs<TokenCheck.Unknown>(registry.check(oldToken))
    }

    @Test
    fun `a re-pair request retires any undelivered token from the previous approval`() {
        pairFully()

        registry.requestPairing(caller, Decision().sink)

        assertNull(registry.takeToken(caller))
    }

    @Test
    fun `re-pairing keeps the original createdAt so the row is not duplicated`() {
        val fixed = PairingRegistry(dao, clock = { 4242L })
        fixed.requestPairing(caller, Decision().sink)
        fixed.approve(caller.packageName, caller.certHash)

        fixed.requestPairing(caller, Decision().sink)

        assertEquals(1, dao.rows.size)
        assertEquals(4242L, requireNotNull(dao.find(caller.packageName, caller.certHash)).createdAt)
    }

    // ------------------------------------------------------------- isolation

    @Test
    fun `a different signing cert is a different pairing`() {
        val token = pairFully()
        val impostor = caller.copy(certHash = "b".repeat(64))

        registry.requestPairing(impostor, Decision().sink)
        registry.approve(impostor.packageName, impostor.certHash)

        assertEquals(2, dao.rows.size)
        assertEquals(TokenCheck.Valid(caller.packageName), registry.check(token))

        registry.revoke(impostor.packageName, impostor.certHash)
        assertEquals(TokenCheck.Valid(caller.packageName), registry.check(token))
    }
}
