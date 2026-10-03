package com.dot.agent.agents

import java.time.Instant

/**
 * Encrypted persistence for OAuth grants.
 *
 * The port exists so the agent layer can be tested without Android, and so the
 * encrypted implementation is the only class that ever touches a token. Nothing
 * in this file is logged, and no accessor returns a token for diagnostics.
 */
interface OAuthTokenStore {

    /** Stores or replaces the grant. Implementations must encrypt at rest. */
    suspend fun save(token: OAuthToken)

    /** Returns the grant for [accountId], or null when nothing is stored. */
    suspend fun load(accountId: String): OAuthToken?

    /**
     * Returns a usable access token or a typed failure. A token that is expired,
     * revoked or past its refresh window resolves to AUTH_REQUIRED — never to an
     * empty-but-successful result, which would read as "no new mail".
     */
    suspend fun accessToken(accountId: String, now: Instant): OAuthTokenResult

    /**
     * Removes every locally retained credential for [accountId]. Required by
     * PRD 21: disconnect must actually delete the credential, not just forget it.
     */
    suspend fun revoke(accountId: String)

    /** Account ids with a stored grant. Ids only — never tokens. */
    suspend fun connectedAccounts(): List<String>

    companion object {
        /** Account id for the single-account v0.1 inbox agent. */
        const val DEFAULT_ACCOUNT = "inbox.primary"
    }
}

data class OAuthToken(
    val accountId: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant,
    /**
     * Scopes the user actually granted. Recorded so the settings screen can show
     * what DOT has access to (PRD 21 requires explaining requested access).
     */
    val scopes: Set<String>,
)

sealed interface OAuthTokenResult {

    data class Valid(val token: OAuthToken) : OAuthTokenResult

    /** Nothing stored: the user has not connected this account. */
    data object NotConnected : OAuthTokenResult

    /** Stored but expired or revoked. The user must reconnect. */
    data class AuthRequired(val reason: String) : OAuthTokenResult

    /** Readable failure, e.g. corrupt ciphertext or a Keystore error. Never a token. */
    data class Unavailable(val reason: String) : OAuthTokenResult
}

/**
 * The narrowest scopes v0.1 is allowed to request.
 *
 * Read-only and metadata-only. Nothing that can send, delete or modify a message
 * appears here, so a leak of this set cannot be escalated into mailbox mutation.
 * `gmail.metadata` returns headers without bodies, which is what the agent needs
 * to classify; body fetch is a separate, explicitly-connected capability.
 */
object LeastPrivilegeScopes {

    /** Read message metadata (sender, subject, date) without bodies. */
    const val META_READ = "https://www.googleapis.com/auth/gmail.metadata"

    val ALLOWED: Set<String> = setOf(META_READ)

    /** Scopes v0.1 must never request, whatever the provider offers. */
    private val FORBIDDEN_MARKERS = listOf(
        "send",
        "modify",
        "delete",
        "compose",
        "drafts",
        "settings",
        "mail.google.com",
    )

    fun isLeastPrivilege(requested: Set<String>): Boolean =
        requested.isNotEmpty() &&
            requested.all { scope -> ALLOWED.contains(scope) } &&
            requested.none { scope -> FORBIDDEN_MARKERS.any { scope.contains(it) } }

    fun rejectionReason(requested: Set<String>): String? = when {
        requested.isEmpty() -> "no scope requested"
        !isLeastPrivilege(requested) -> "requested scope is not least-privilege"
        else -> null
    }
}

/** Adapts [OAuthTokenResult] to the ActionOutcome taxonomy the runner expects. */
fun OAuthTokenResult.toActionResultValue(): com.dot.core.model.ActionResult<OAuthToken> = when (this) {
    is OAuthTokenResult.Valid -> com.dot.core.model.ActionResult.success(token)
    is OAuthTokenResult.NotConnected ->
        com.dot.core.model.ActionResult.failure(
            com.dot.core.model.ActionOutcome.AUTH_REQUIRED,
            AgentErrorCodes.INBOX_AUTH_REQUIRED,
        )
    // An expired or revoked grant is AUTH_REQUIRED. Reporting it as UNAVAILABLE
    // would suggest "try again later", and the user's only real fix is to reconnect.
    is OAuthTokenResult.AuthRequired ->
        com.dot.core.model.ActionResult.failure(
            com.dot.core.model.ActionOutcome.AUTH_REQUIRED,
            AgentErrorCodes.INBOX_AUTH_REQUIRED,
        )
    is OAuthTokenResult.Unavailable ->
        com.dot.core.model.ActionResult.failure(
            com.dot.core.model.ActionOutcome.UNAVAILABLE,
            AgentErrorCodes.INBOX_UNAVAILABLE,
        )
}