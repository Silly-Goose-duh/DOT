package com.dot.agent.agents

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
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
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic, obviously-fake credential material.
 *
 * Every value here is a placeholder generated for this test file. No real token,
 * account or key appears anywhere in the suite.
 */
private object FakeCredentials {
    const val ACCOUNT = "inbox.primary"
    val ISSUED_AT: Instant = Instant.parse("2026-01-01T09:00:00Z")

    /** Obviously not a real token: no provider prefix, no length that implies one. */
    const val ACCESS = "TEST-ACCESS-0000-not-a-real-token"
    const val REFRESH = "TEST-REFRESH-0000-not-a-real-token"

    fun token(
        expiresAt: Instant = Instant.parse("2026-01-01T11:00:00Z"),
        scopes: Set<String> = setOf(LeastPrivilegeScopes.META_READ),
    ) = OAuthToken(ACCOUNT, ACCESS, REFRESH, expiresAt, scopes)
}

/**
 * Real AES-GCM with a locally generated key.
 *
 * Uses the JVM crypto provider rather than a mock so "no plaintext on disk" is a
 * genuine assertion about a genuine encryption round-trip, not a tautology about
 * a fake. Robolectric's AndroidKeyStore is not available, which is why
 * [AndroidKeystoreCipher] sits behind [TokenCipher].
 */
private class LocalAesGcmCipher : TokenCipher {

    private val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(cipher: ByteArray): ByteArray {
        val instance = Cipher.getInstance("AES/GCM/NoPadding")
        instance.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, cipher, 0, 12),
        )
        return instance.doFinal(cipher, 12, cipher.size - 12)
    }
}

/** Reversible-but-not-secure stand-in, used only to prove the store never writes raw. */
private class ReversibleCipher : TokenCipher {
    override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
    override fun decrypt(cipher: ByteArray): ByteArray = cipher.reversedArray()
}

@RunWith(RobolectricTestRunner::class)
class EncryptedPrefsOAuthTokenStoreTest {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var cipher: TokenCipher

    private val now = FakeCredentials.ISSUED_AT
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = context.getSharedPreferences("dot_test_oauth", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        cipher = LocalAesGcmCipher()
    }

    private fun store(c: TokenCipher = cipher) = EncryptedPrefsOAuthTokenStore(prefs, c, clock)

    @Test
    fun `a saved grant round trips through encryption`() = runTest {
        val s = store()
        s.save(FakeCredentials.token())

        val loaded = s.load(FakeCredentials.ACCOUNT)

        assertThat(loaded).isNotNull()
        assertThat(loaded!!.accessToken).isEqualTo(FakeCredentials.ACCESS)
        assertThat(loaded.refreshToken).isEqualTo(FakeCredentials.REFRESH)
        assertThat(loaded.expiresAt).isEqualTo(Instant.parse("2026-01-01T11:00:00Z"))
        assertThat(loaded.scopes).containsExactly(LeastPrivilegeScopes.META_READ)
    }

    @Test
    fun `no plaintext token is present anywhere in the underlying prefs`() = runTest {
        store().save(FakeCredentials.token())

        // Inspect the raw store, not the decoded object: this is the file an
        // attacker with filesystem access would read.
        val everything = prefs.all.entries.joinToString(" | ") { "${it.key}=${it.value}" }
        assertThat(everything).doesNotContain(FakeCredentials.ACCESS)
        assertThat(everything).doesNotContain(FakeCredentials.REFRESH)
    }

    @Test
    fun `the stored blob is opaque base64 rather than JSON`() = runTest {
        store().save(FakeCredentials.token())

        val blob = prefs.all.values.first().toString()
        assertThat(blob).doesNotContain("{")
        assertThat(blob).doesNotContain("accessToken")
        assertThat(blob).doesNotContain(FakeCredentials.ACCOUNT)
    }

    @Test
    fun `two identical grants produce different ciphertext`() = runTest {
        // GCM uses a fresh IV per encryption, so identical plaintext must not
        // produce identical ciphertext.
        store().save(FakeCredentials.token())
        val first = prefs.all.values.first().toString()

        store().save(FakeCredentials.token())
        val second = prefs.all.values.first().toString()

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun `an expired token resolves to AUTH_REQUIRED not an empty success`() = runTest {
        val s = store()
        // Genuinely in the past. A token with 60s still on the clock is *valid* — that
        // boundary is the leeway test below — so a future expiry here would assert the
        // opposite of what this test claims to prove.
        s.save(FakeCredentials.token(expiresAt = now.minusSeconds(60)))

        val result = s.accessToken(FakeCredentials.ACCOUNT, now)

        // The whole point: an expired grant must never look like "no new mail".
        assertThat(result).isInstanceOf(OAuthTokenResult.AuthRequired::class.java)
    }

    @Test
    fun `a valid token resolves to Valid`() = runTest {
        val s = store()
        s.save(FakeCredentials.token(expiresAt = now.plusSeconds(3600)))

        val result = s.accessToken(FakeCredentials.ACCOUNT, now)

        assertThat(result).isInstanceOf(OAuthTokenResult.Valid::class.java)
    }

    @Test
    fun `a token expiring inside the leeway window is treated as expired`() = runTest {
        val s = store()
        // 10s of validity left, leeway is 30s: using it now risks failing mid-call.
        s.save(FakeCredentials.token(expiresAt = now.plusSeconds(10)))

        val result = s.accessToken(FakeCredentials.ACCOUNT, now)

        assertThat(result).isInstanceOf(OAuthTokenResult.AuthRequired::class.java)
    }

    @Test
    fun `a missing grant is NotConnected, not AuthRequired`() = runTest {
        val result = store().accessToken("never.connected", now)

        assertThat(result).isInstanceOf(OAuthTokenResult.NotConnected::class.java)
    }

    @Test
    fun `a grant with an unparseable expiry fails closed instead of becoming valid forever`() = runTest {
        val s = store()
        s.save(FakeCredentials.token())
        // Rewrite the ciphertext so it decrypts cleanly but carries a garbage
        // expiresAtIso. This is the shape of a corrupted-but-decryptable record.
        val garbage = Base64.encodeToString(
            cipher.encrypt(
                """{"accountId":"${FakeCredentials.ACCOUNT}","accessToken":"${FakeCredentials.ACCESS}","refreshToken":"${FakeCredentials.REFRESH}","expiresAtIso":"not-an-instant","scopes":["${LeastPrivilegeScopes.META_READ}"]}"""
                    .toByteArray(),
            ),
            Base64.NO_WRAP,
        )
        prefs.edit().putString("dot.oauth.grant.${FakeCredentials.ACCOUNT}", garbage).commit()

        val result = s.accessToken(FakeCredentials.ACCOUNT, now)

        // Fail closed. An expiry we cannot read must not be treated as "no expiry",
        // which would hand out an unlimited-lifetime credential; an unreadable
        // record degrades to "absent", which the runner maps to AUTH_REQUIRED.
        assertThat(result).isInstanceOf(OAuthTokenResult.NotConnected::class.java)
        assertThat(result.toActionResultValue().outcome)
            .isEqualTo(com.dot.core.model.ActionOutcome.AUTH_REQUIRED)
    }

    @Test
    fun `revoke removes the credential so it can no longer be loaded`() = runTest {
        val s = store()
        s.save(FakeCredentials.token())
        assertThat(s.load(FakeCredentials.ACCOUNT)).isNotNull()

        s.revoke(FakeCredentials.ACCOUNT)

        assertThat(s.load(FakeCredentials.ACCOUNT)).isNull()
        assertThat(s.accessToken(FakeCredentials.ACCOUNT, now))
            .isInstanceOf(OAuthTokenResult.NotConnected::class.java)
    }

    @Test
    fun `revoke leaves no recoverable material in the prefs file`() = runTest {
        val s = store()
        s.save(FakeCredentials.token())

        s.revoke(FakeCredentials.ACCOUNT)

        val everything = prefs.all.entries.joinToString(" | ") { "${it.key}=${it.value}" }
        assertThat(everything).doesNotContain(FakeCredentials.ACCESS)
        assertThat(everything).doesNotContain(FakeCredentials.REFRESH)
    }

    @Test
    fun `connected accounts lists ids only and never tokens`() = runTest {
        val s = store()
        s.save(FakeCredentials.token())
        s.save(FakeCredentials.token().copy(accountId = "inbox.secondary"))

        val accounts = s.connectedAccounts()

        assertThat(accounts).containsExactly("inbox.primary", "inbox.secondary").inOrder()
        assertThat(accounts.joinToString()).doesNotContain(FakeCredentials.ACCESS)
    }

    @Test
    fun `a corrupt blob decodes to null rather than throwing`() = runTest {
        val s = store()
        s.save(FakeCredentials.token())
        prefs.edit().putString("dot.oauth.grant.${FakeCredentials.ACCOUNT}", "not-valid-base64!!")
            .commit()

        // A corrupted credential must degrade to "absent", never crash a background run.
        assertThat(s.load(FakeCredentials.ACCOUNT)).isNull()
    }

    @Test
    fun `a cipher that cannot decrypt yields null not a garbage token`() = runTest {
        store(ReversibleCipher()).save(FakeCredentials.token())

        // Reading back through a different key proves we never fall back to plaintext.
        val reloaded = store(LocalAesGcmCipher()).load(FakeCredentials.ACCOUNT)

        assertThat(reloaded).isNull()
    }

    @Test
    fun `an over-privileged grant is refused at the boundary`() = runTest {
        val broad = FakeCredentials.token(
            scopes = setOf("https://www.googleapis.com/auth/gmail.modify"),
        )

        val thrown = runCatching { store().save(broad) }.exceptionOrNull()

        // Storing it would launder an over-broad request into a local credential.
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store().load(FakeCredentials.ACCOUNT)).isNull()
    }
}

class LeastPrivilegeScopesTest {

    @Test
    fun `the metadata read scope is accepted`() {
        assertThat(
            LeastPrivilegeScopes.isLeastPrivilege(setOf(LeastPrivilegeScopes.META_READ)),
        ).isTrue()
    }

    @Test
    fun `an empty scope set is rejected`() {
        // Requesting nothing is either a bug or an attempt to imply full access.
        assertThat(LeastPrivilegeScopes.isLeastPrivilege(emptySet())).isFalse()
        assertThat(LeastPrivilegeScopes.rejectionReason(emptySet())).isNotNull()
    }

    @Test
    fun `send modify and full-access scopes are all rejected`() {
        val broad = listOf(
            "https://www.googleapis.com/auth/gmail.send",
            "https://www.googleapis.com/auth/gmail.modify",
            "https://www.googleapis.com/auth/gmail.compose",
            "https://www.googleapis.com/auth/mail.google.com",
            "https://www.googleapis.com/auth/gmail.settings.basic",
        )
        broad.forEach { scope ->
            assertThat(LeastPrivilegeScopes.isLeastPrivilege(setOf(scope))).isFalse()
            assertThat(LeastPrivilegeScopes.rejectionReason(setOf(scope))).isNotNull()
        }
    }

    @Test
    fun `a mixed set containing one broad scope is rejected wholesale`() {
        // No partial acceptance: one over-broad scope taints the whole grant.
        val mixed = setOf(
            LeastPrivilegeScopes.META_READ,
            "https://www.googleapis.com/auth/gmail.send",
        )
        assertThat(LeastPrivilegeScopes.isLeastPrivilege(mixed)).isFalse()
    }

    @Test
    fun `the allowed set contains nothing that can modify a mailbox`() {
        LeastPrivilegeScopes.ALLOWED.forEach { scope ->
            assertThat(scope).doesNotContain("send")
            assertThat(scope).doesNotContain("modify")
            assertThat(scope).doesNotContain("delete")
        }
    }
}

class OAuthTokenResultMappingTest {

    @Test
    fun `NotConnected maps to AUTH_REQUIRED with the inbox code`() {
        val mapped = OAuthTokenResult.NotConnected.toActionResultValue()

        assertThat(mapped.outcome).isEqualTo(com.dot.core.model.ActionOutcome.AUTH_REQUIRED)
        assertThat(mapped.errorCode).isEqualTo(AgentErrorCodes.INBOX_AUTH_REQUIRED)
    }

    @Test
    fun `AuthRequired maps to AUTH_REQUIRED not UNAVAILABLE`() {
        val mapped = OAuthTokenResult.AuthRequired("token_expired").toActionResultValue()

        assertThat(mapped.outcome).isEqualTo(com.dot.core.model.ActionOutcome.AUTH_REQUIRED)
    }

    @Test
    fun `Unavailable maps to UNAVAILABLE`() {
        val mapped = OAuthTokenResult.Unavailable("keystore_busy").toActionResultValue()

        assertThat(mapped.outcome).isEqualTo(com.dot.core.model.ActionOutcome.UNAVAILABLE)
        assertThat(mapped.errorCode).isEqualTo(AgentErrorCodes.INBOX_UNAVAILABLE)
    }

    @Test
    fun `Valid maps to a success carrying the token`() {
        val token = FakeCredentials.token()
        val mapped = OAuthTokenResult.Valid(token).toActionResultValue()

        assertThat(mapped.isSuccess).isTrue()
        assertThat(mapped.value).isEqualTo(token)
    }
}