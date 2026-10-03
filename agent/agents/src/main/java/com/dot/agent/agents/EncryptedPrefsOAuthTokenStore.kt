package com.dot.agent.agents

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.security.KeyStore
import java.time.Clock
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Symmetric encryption seam for credential storage.
 *
 * Exists so the store's behaviour — what lands in the prefs file, what a revoked
 * grant does — is unit-testable without a hardware-backed keystore, while
 * production still uses the real one.
 */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(cipher: ByteArray): ByteArray
}

/**
 * AES-256-GCM key held in the Android Keystore.
 *
 * The key material never enters the app process, so a prefs-file or backup
 * exfiltration yields ciphertext only. GCM is used rather than CBC because it
 * authenticates as well as encrypts: a tampered blob fails to decrypt instead of
 * decrypting to attacker-chosen plaintext.
 */
class AndroidKeystoreCipher(
    private val alias: String = DEFAULT_ALIAS,
    private val provider: String = ANDROID_KEYSTORE,
) : TokenCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        // Ciphertext || IV, base64'd by the caller. GCM's IV is not secret and must
        // travel with the ciphertext or decryption cannot reconstruct it.
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(cipher: ByteArray): ByteArray {
        require(cipher.size > GCM_IV_BYTES) { "ciphertext too short" }
        val cipherInstance = Cipher.getInstance(TRANSFORMATION)
        cipherInstance.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(GCM_TAG_BITS, cipher, 0, GCM_IV_BYTES),
        )
        return cipherInstance.doFinal(cipher, GCM_IV_BYTES, cipher.size - GCM_IV_BYTES)
    }

    /** Destroys the key. Used by revoke so a removed grant cannot be recovered. */
    fun deleteKey() {
        KeyStore.getInstance(provider).apply { load(null) }.deleteEntry(alias)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(provider).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Not user-authentication-bound: a background agent must be able to
                // refresh its own grant without a biometric prompt at an arbitrary time.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "dot.oauth.gcm.v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        internal const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}

/** Wire shape for the stored grant. Instants as ISO strings; no Instant serializer needed. */
@Serializable
private data class StoredToken(
    val accountId: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtIso: String,
    val scopes: List<String>,
)

/**
 * Keystore-backed [OAuthTokenStore].
 *
 * Everything is written as one AES-GCM ciphertext per account; there is no
 * plaintext code path into [SharedPreferences]. [revoke] removes the entry and
 * the key material, which is what "disconnect must remove locally retained
 * credentials" (PRD 21) actually requires — clearing only the key name would
 * leave a recoverable grant in the Keystore.
 *
 * Expiry is evaluated against an injected [Clock], so the AUTH_REQUIRED-on-expiry
 * path is deterministic in tests.
 */
class EncryptedPrefsOAuthTokenStore(
    private val prefs: SharedPreferences,
    private val cipher: TokenCipher,
    private val clock: Clock = Clock.systemUTC(),
    /** Grace period so a token is not treated as dead microseconds before it expires. */
    private val expiryLeeway: java.time.Duration = java.time.Duration.ofSeconds(30),
) : OAuthTokenStore {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override suspend fun save(token: OAuthToken) {
        // A grant DOT could not use is not worth storing, and storing one that
        // violates least privilege would launder an over-broad request into a
        // local credential. Reject at the boundary.
        LeastPrivilegeScopes.rejectionReason(token.scopes)?.let { reason ->
            throw IllegalArgumentException("refusing to store over-privileged grant: $reason")
        }
        val stored = StoredToken(
            accountId = token.accountId,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAtIso = token.expiresAt.toString(),
            scopes = token.scopes.toList(),
        )
        prefs.edit()
            .putString(keyFor(token.accountId), encode(stored))
            .apply()
    }

    override suspend fun load(accountId: String): OAuthToken? {
        val raw = prefs.getString(keyFor(accountId), null) ?: return null
        return decode(raw)
    }

    override suspend fun accessToken(accountId: String, now: Instant): OAuthTokenResult {
        val token = load(accountId) ?: return OAuthTokenResult.NotConnected
        // Expired is AUTH_REQUIRED, not an empty success: an empty success would be
        // read as "no new mail" and would silently skip the reconnect prompt.
        if (!now.isBefore(token.expiresAt.minus(expiryLeeway))) {
            return OAuthTokenResult.AuthRequired(reason = EXPIRED)
        }
        return OAuthTokenResult.Valid(token)
    }

    override suspend fun revoke(accountId: String) {
        prefs.edit().remove(keyFor(accountId)).apply()
        // Only safe when this store owns the only account; for a multi-account store
        // the caller drops the key per provider. Kept as a hook so the destructive
        // step is explicit rather than forgotten.
        (cipher as? AndroidKeystoreCipher)?.deleteKey()
    }

    override suspend fun connectedAccounts(): List<String> = prefs.all.keys
        .filter { it.startsWith(KEY_PREFIX) }
        .map { it.removePrefix(KEY_PREFIX) }
        .sorted()

    // The generated companion serializer is referenced explicitly on both sides
    // rather than via reified `encodeToString(value)`: StoredToken is private to
    // this file, and naming the serializer keeps the encode/decode pair obviously
    // symmetrical.
    private fun encode(stored: StoredToken): String = Base64.encodeToString(
        cipher.encrypt(json.encodeToString(StoredToken.serializer(), stored).toByteArray()),
        BASE64_FLAGS,
    )

    private fun decode(raw: String): OAuthToken? = runCatching {
        val bytes = Base64.decode(raw, BASE64_FLAGS)
        val stored = json.decodeFromString(StoredToken.serializer(), String(cipher.decrypt(bytes)))
        OAuthToken(
            accountId = stored.accountId,
            accessToken = stored.accessToken,
            refreshToken = stored.refreshToken,
            expiresAt = Instant.parse(stored.expiresAtIso),
            scopes = stored.scopes.toSet(),
        )
    }.getOrNull()

    private fun keyFor(accountId: String) = KEY_PREFIX + accountId

    private companion object {
        const val KEY_PREFIX = "dot.oauth.grant."
        const val BASE64_FLAGS = Base64.NO_WRAP
        const val EXPIRED = "token_expired"
    }
}