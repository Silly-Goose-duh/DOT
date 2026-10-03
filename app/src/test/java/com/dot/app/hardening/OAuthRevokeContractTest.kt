package com.dot.app.hardening

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dot.agent.agents.EncryptedPrefsOAuthTokenStore
import com.dot.agent.agents.LeastPrivilegeScopes
import com.dot.agent.agents.OAuthToken
import com.dot.agent.agents.OAuthTokenResult
import com.dot.agent.agents.OAuthTokenStore
import com.dot.agent.agents.TokenCipher
import com.dot.agent.agents.toActionResultValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * OAuth revoke / disconnect against the REAL `OAuthTokenStore`.
 *
 * This supersedes the earlier local-double version of the suite. `:agent:agents`
 * now exists and is on `:app`'s test classpath (testImplementation extends
 * implementation), so these tests drive the actual production class rather than a
 * stand-in. The behaviour asserted is real.
 *
 * WHAT IS STILL NOT PROVEN, stated plainly rather than glossed:
 *
 *  - `AndroidKeystoreCipher` is NOT exercised. Robolectric has no Android Keystore
 *    and `security-crypto` falls back to a software key, so a test claiming
 *    hardware-backed encryption would pass while proving nothing about a real
 *    device. These tests inject a test [TokenCipher] instead, which proves the
 *    *store's* behaviour — what it writes, what revoke removes, how expiry is
 *    classified — and nothing about the key material.
 *  - Production constructs `EncryptedSharedPreferences`; this suite uses plain
 *    Robolectric SharedPreferences. "No plaintext token in storage" therefore
 *    covers the store's own contribution, which is the part this class controls,
 *    not the prefs layer's.
 *  - No live provider round-trip. A genuine server-side revoke — token still
 *    stored locally but no longer honoured by Google — cannot be produced without
 *    network access, so it is not tested here.
 */
@RunWith(RobolectricTestRunner::class)
class OAuthRevokeContractTest {

    private val account = OAuthTokenStore.DEFAULT_ACCOUNT
    private val now: Instant = Instant.parse("2026-10-02T10:00:00Z")

    /**
     * Stand-in for the Keystore cipher. Deliberately trivial and deliberately NOT
     * secure — its only job is to let the store round-trip on a JVM. Asserting
     * anything about real encryption here would be asserting it about this class.
     */
    private class ReversibleTestCipher : TokenCipher {
        override fun encrypt(plain: ByteArray): ByteArray =
            byteArrayOf(0x7F, 0x45, 0x4E, 0x43) + plain.reversedArray()

        override fun decrypt(cipher: ByteArray): ByteArray =
            cipher.drop(4).toByteArray().reversedArray()
    }

    /**
     * A real AES-256-GCM cipher with an in-process key. Shows that when the store
     * is backed by an actual cipher, what lands in storage is ciphertext rather
     * than the token — the property that makes at-rest protection work. The key
     * is in-process, so this says nothing about hardware backing.
     */
    private class AesGcmTestCipher : TokenCipher {
        private val key: SecretKey =
            KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

        override fun encrypt(plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            return c.iv + c.doFinal(plain)
        }

        override fun decrypt(cipher: ByteArray): ByteArray {
            require(cipher.size > GCM_IV_BYTES) { "ciphertext too short" }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, cipher, 0, GCM_IV_BYTES))
            return c.doFinal(cipher, GCM_IV_BYTES, cipher.size - GCM_IV_BYTES)
        }

        private companion object {
            const val GCM_IV_BYTES = 12
        }
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var store: EncryptedPrefsOAuthTokenStore

    private fun newStore(c: TokenCipher) =
        EncryptedPrefsOAuthTokenStore(
            prefs = prefs,
            cipher = c,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun grant(
        expiresAt: Instant = now.plusSeconds(3600),
        scopes: Set<String> = setOf(LeastPrivilegeScopes.META_READ),
    ) = OAuthToken(
        accountId = account,
        accessToken = "fake-access-token-value",
        refreshToken = "fake-refresh-token-value",
        expiresAt = expiresAt,
        scopes = scopes,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("oauth-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = newStore(ReversibleTestCipher())
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    // ------------------------------------------------------------- happy path

    @Test
    fun `a valid grant round trips and is usable`() = runTest {
        store.save(grant())

        val result = store.accessToken(account, now)

        assertThat(result).isInstanceOf(OAuthTokenResult.Valid::class.java)
        assertThat((result as OAuthTokenResult.Valid).token.accessToken)
            .isEqualTo("fake-access-token-value")
    }

    @Test
    fun `the absent case is distinguished from the valid case`() = runTest {
        // Without this, every failure test below would pass for the wrong reason
        // (e.g. a store that always refuses).
        assertThat(store.accessToken(account, now))
            .isInstanceOf(OAuthTokenResult.NotConnected::class.java)
        store.save(grant())
        assertThat(store.accessToken(account, now)).isInstanceOf(OAuthTokenResult.Valid::class.java)
    }

    // ---------------------------------------------------------------- expired

    @Test
    fun `an expired grant produces AuthRequired, not an empty success`() = runTest {
        store.save(grant(expiresAt = now.plusSeconds(3600)))

        val result = store.accessToken(account, now.plusSeconds(7200))

        // The shape this test exists to prevent: an empty-but-successful result a
        // caller would read as "no new mail" and skip the reconnect prompt for.
        assertThat(result).isInstanceOf(OAuthTokenResult.AuthRequired::class.java)
        assertThat((result as OAuthTokenResult.AuthRequired).reason).isEqualTo("token_expired")
    }

    @Test
    fun `expiry is evaluated against the injected clock, not wall time`() = runTest {
        store.save(grant(expiresAt = now.plusSeconds(3600)))

        val justBefore = store.accessToken(account, now.plusSeconds(3000))
        val justAfter = store.accessToken(account, now.plusSeconds(4200))

        assertThat(justBefore).isInstanceOf(OAuthTokenResult.Valid::class.java)
        assertThat(justAfter).isInstanceOf(OAuthTokenResult.AuthRequired::class.java)
    }

    @Test
    fun `an expired grant maps to AUTH_REQUIRED and never to UNAVAILABLE`() = runTest {
        store.save(grant(expiresAt = now.plusSeconds(60)))

        val action = store.accessToken(account, now.plusSeconds(600)).toActionResultValue()

        // UNAVAILABLE reads as "try again later". The user's only real fix is to
        // reconnect, so the outcome must say so.
        assertThat(action.outcome).isEqualTo(com.dot.core.model.ActionOutcome.AUTH_REQUIRED)
        assertThat(action.isSuccess).isFalse()
        assertThat(action.value).isNull()
    }

    @Test
    fun `an absent grant maps to AUTH_REQUIRED too`() = runTest {
        val action = store.accessToken(account, now).toActionResultValue()

        assertThat(action.outcome).isEqualTo(com.dot.core.model.ActionOutcome.AUTH_REQUIRED)
        assertThat(action.isSuccess).isFalse()
    }

    // ------------------------------------------------------- absent / revoked

    @Test
    fun `an absent credential is never reported as a valid token`() = runTest {
        assertThat(store.load(account)).isNull()
        assertThat(store.accessToken(account, now))
            .isInstanceOf(OAuthTokenResult.NotConnected::class.java)
    }

    @Test
    fun `a revoked grant is gone locally, not merely marked`() = runTest {
        store.save(grant())
        assertThat(store.load(account)).isNotNull()

        store.revoke(account)

        // SECURITY.md rule 10 / PRD 21: "Disconnect must remove locally retained
        // credentials." Checking only a state flag would pass a store that forgot
        // to delete the grant — which is the actual leak.
        assertThat(store.load(account)).isNull()
        assertThat(store.accessToken(account, now))
            .isInstanceOf(OAuthTokenResult.NotConnected::class.java)
        assertThat(store.connectedAccounts()).isEmpty()
    }

    @Test
    fun `revoke removes the credential key from the backing store`() = runTest {
        store.save(grant())
        assertThat(prefs.all.keys.any { it.startsWith("dot.oauth.grant.") }).isTrue()

        store.revoke(account)

        // The raw prefs map is inspected, not just the store API, so a store that
        // reported success while leaving the row behind would fail here.
        assertThat(prefs.all.keys.none { it.startsWith("dot.oauth.grant.") }).isTrue()
    }

    @Test
    fun `revoke is idempotent`() = runTest {
        store.save(grant())
        store.revoke(account)

        store.revoke(account)

        assertThat(store.load(account)).isNull()
        assertThat(store.accessToken(account, now))
            .isInstanceOf(OAuthTokenResult.NotConnected::class.java)
    }

    @Test
    fun `reconnecting after a revoke works`() = runTest {
        store.save(grant())
        store.revoke(account)
        store.save(grant(expiresAt = now.plusSeconds(600)))

        assertThat(store.accessToken(account, now)).isInstanceOf(OAuthTokenResult.Valid::class.java)
        assertThat(store.connectedAccounts()).containsExactly(account)
    }

    @Test
    fun `revoking one account leaves another connected`() = runTest {
        store.save(grant())
        store.save(grant().copy(accountId = "second.account"))
        assertThat(store.connectedAccounts()).hasSize(2)

        store.revoke(account)

        assertThat(store.connectedAccounts()).containsExactly("second.account")
    }

    // ------------------------------------------------------- at-rest exposure

    @Test
    fun `the stored blob does not contain the plaintext access token`() = runTest {
        store.save(grant())

        val stored = prefs.all.values.map { it.toString() }.joinToString("\n")

        assertThat(stored).doesNotContain("fake-access-token-value")
        assertThat(stored).doesNotContain("fake-refresh-token-value")
    }

    @Test
    fun `a real AES-GCM cipher yields ciphertext and still round trips`() = runTest {
        val realStore = newStore(AesGcmTestCipher())
        realStore.save(grant())

        val stored = prefs.all.values.map { it.toString() }.joinToString("\n")
        assertThat(stored).doesNotContain("fake-access-token-value")

        // Encryption must not break the grant, or the agent silently stops working.
        assertThat(realStore.accessToken(account, now))
            .isInstanceOf(OAuthTokenResult.Valid::class.java)
    }

    @Test
    fun `corrupt ciphertext degrades to null, not a crash and not a valid token`() = runTest {
        store.save(grant())

        prefs.edit().putString("dot.oauth.grant.$account", "not-valid-base64-!!").commit()

        assertThat(store.load(account)).isNull()
        assertThat(store.accessToken(account, now))
            .isInstanceOf(OAuthTokenResult.NotConnected::class.java)
    }

    // -------------------------------------------------------- least privilege

    @Test
    fun `an over-privileged grant is refused at the boundary`() = runTest {
        val overBroad = grant(scopes = setOf("https://mail.google.com/"))

        val thrown = runCatching { store.save(overBroad) }.exceptionOrNull()

        // Storing it would launder an over-broad request into a local credential.
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.load(account)).isNull()
    }

    @Test
    fun `a send-capable scope is refused`() = runTest {
        val sendScope = "https://www.googleapis.com/auth/gmail.send"

        assertThat(LeastPrivilegeScopes.isLeastPrivilege(setOf(sendScope))).isFalse()
        assertThat(runCatching { store.save(grant(scopes = setOf(sendScope))) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the allowed scope set is metadata-only`() {
        // gmail.metadata returns headers without bodies, which is what makes
        // SECURITY.md rule 9 ("do not persist full email bodies") hold by
        // construction rather than by policy.
        assertThat(LeastPrivilegeScopes.ALLOWED)
            .containsExactly("https://www.googleapis.com/auth/gmail.metadata")
        assertThat(LeastPrivilegeScopes.isLeastPrivilege(emptySet())).isFalse()
    }
}
