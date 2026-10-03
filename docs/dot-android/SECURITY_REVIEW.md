# SECURITY_REVIEW.md — DOT v0.1

Audit date: 2026-10-02. Auditor: Milestone 7 hardening agent.
Scope: every source file under `core/`, `agent/`, and `app/src/main`, plus the
Milestone 7 test suite. Audited against `docs/dot-android/SECURITY.md` (the 15
rules) and PRD sections 21, 22, 23, 24, 25, 26.

**Read the two columns.** A rule marked "test" is enforced by an automated test
that will fail if the property is broken. A rule marked "doc" is enforced only by
code review and a comment — which is exactly as strong as the next person who
edits that file. The gap between the two is the real finding of this document.

---

## 1. Rule-by-rule coverage

| # | SECURITY.md rule | Enforcement | Where |
|---|---|---|---|
| 1 | Never execute arbitrary code/commands produced by the model | **test** | `ModelPlanParserTest.model injection attempting tool invention is rejected`; `ModelPlanParserTest.nested object argument is rejected`; `ModelPlanParserTest.array argument is rejected`; `PromptInjectionFixtureTest.injected tool names are never resolvable`; `RouterTest.router never returns executable code or shell strings` |
| 2 | All model actions go through registered typed tools | **test** | `ModelPlanParserTest.unregistered tool is rejected`; `AiFallbackTest.model cannot invent an unregistered tool`; `AiFallbackTest.provider only ever sees registered tool names`; `AiFallbackWiringTest.model cannot invent an unregistered tool`; `CommandRuntime.kt:157` (`aiSpecFor` re-checks `toolRegistry.contains`) |
| 3 | Policy layer independently enforces confirmation/risk | **test** | `PolicyEngineTest.external writes require confirmation`; `PolicyEngineTest.consequential actions are out of scope for v01`; `AiFallbackTest.external write demands confirmation even when the model is confident`; `AiFallbackTest.consequential tool is refused even if a provider offered it` |
| 4 | External content is data, never authority | **test** | `PromptInjectionFixtureTest.external content is stored as opaque data`; `PromptInjectionFixtureTest.injected app packages fail the allowlist`; `PromptInjectionFixtureTest.a legitimate alias still works after injection attempts`; `ModelPlanParserTest.two action keys are ambiguous and rejected` |
| 5 | Never place tokens/API keys in Git or ordinary logs | **test** (partial) | `LogRedactorTest` (all cases); `DiagnosticsRecorderTest.bearer token in any field is redacted`; `DiagnosticsRecorderTest.google oauth token is redacted`; `DiagnosticsRecorderTest.api key assignment is redacted`; `GeminiRequestBodyTest.body never contains the api key`; `GeminiProviderTest.live call does not leak the key in its request body`. **Doc-only:** the repo-wide secret scan is a manual procedure, not a CI gate — see F-09. |
| 6 | Use official OAuth flows and least privilege | **doc** | Not implemented. `:agent:agents` is under construction; no OAuth flow exists in the tree. **Not testable yet.** |
| 7 | Protect local secrets with Keystore-backed mechanisms | **doc** | `app/build.gradle.kts:80` declares `androidx.security.crypto`, and `agent/agents/.../OAuthTokenStore.kt` is being written against it, but nothing in the shipped `:app` path stores a secret today. **Not verifiable on the JVM** — Robolectric has no Android Keystore, so any test asserting Keystore encryption would pass while proving nothing. |
| 8 | Store the minimum personal data needed | **test** | `AgentRunEntity.compactResult` documented as never raw external bodies; `MemoryRepositoryTest.session context expires`; `MemoryRepositoryTest.recent tool results expire`; `DotDatabaseTest.clear all wipes every memory row`; `MemoryDao.trimHistory` caps run history at 20 |
| 9 | Do not persist full email bodies by default | **doc** | No email integration ships in v0.1, so this holds by absence rather than by control. **Becomes a real obligation the moment an inbox agent lands** — see F-07. |
| 10 | Provide disconnect and clear-local-data flows | **test** | `OAuthRevokeContractTest.a revoked grant is gone locally, not merely marked`; `OAuthRevokeContractTest.revoke removes the credential key from the backing store`; `OAuthRevokeContractTest.revoke is idempotent`; `OAuthRevokeContractTest.reconnecting after a revoke works`; `DotViewModel.clearLocalMemory`; `MemoryRepositoryTest.clear all wipes everything`. These drive the real `EncryptedPrefsOAuthTokenStore`. **Caveat:** the cipher is injected, so keystore *deletion* is not covered — see F-01. |
| 11 | Use TLS; never disable certificate verification | **test** | `GeminiProvider.kt:97` catches `SSLException` and fails closed — there is no permissive-trust-manager code path anywhere in the tree (verified by grep for `TrustManager`/`HostnameVerifier`: zero hits). `GeminiProvider` base URL is an `https://` constant (`GeminiProvider.kt:155`). **Gap:** no test asserts TLS-only for the URL scheme, and there is no `network_security_config.xml` — see F-05. |
| 12 | Validate/parse structured model output before tool execution | **test** | `ModelPlanParserTest` (13 cases); `AiFallbackTest.prose reply is a malformed-output failure`; `AiFallbackWiringTest.malformed model output is refused not half-applied`; `OfflineBehaviorTest.the plan parser is enforced before any tool runs on an offline path` |
| 13 | Apply timeouts and bounded retries | **test** | `GeminiProvider.kt:156-157` (5 s connect / 10 s read); `GeminiProvider.kt:33` `maxRetries = 1`; `OfflineBehaviorTest.a timeout is reported as a timeout`; `AiFallbackTest.provider that throws is a failure not a crash`. **Gap:** no test asserts the retry *count* is bounded — see F-04. |
| 14 | No infinite agent loops | **doc** | `ToolRegistry.invoke` retries are bounded by `tool.maxRetries` (`ToolRegistry.kt:54`) and `AiFallback` is a single provider call. **Not directly tested** — see F-06. |
| 15 | Consequential operations out of scope for v0.1 | **test** | `PolicyEngine.OUT_OF_SCOPE_V01` hard block; `PolicyEngineTest.consequential actions are out of scope for v01`; `CommandRuntimeTest.unknown app alias never reaches the launcher`; `ToolRegistryTest.well formed but unlisted package is rejected` |

---

## 2. Findings, ranked by severity

Severity is impact-if-exploited × likelihood-it-is-still-unfixed-at-ship.

### F-01 — HIGH — The app will crash on first launch for any existing install
`app/src/main/java/com/dot/app/AppContainer.kt:41-43`

```kotlin
val database: DotDatabase = Room
    .databaseBuilder(appContext, DotDatabase::class.java, "dot.db")
    .build()
```

Schema is now `version = 2` (`core/database/.../DotDatabase.kt:184`) but this
builder registers **no migrations** and has **no destructive fallback**. Any
device that installed a v0.1 build and upgrades will hit
`IllegalStateException: A migration from 1 to 2 was required but not found` and
the app will not start.

**This is a launch-blocking regression, not a theoretical one.** It is also the
*correct* failure mode — a loud crash rather than silent data loss — which is
exactly why the wiring must land before any build is tagged.

**Fix (must be applied by the parent):**
```kotlin
import com.dot.core.database.MIGRATION_1_2
// ...
val database: DotDatabase = Room
    .databaseBuilder(appContext, DotDatabase::class.java, "dot.db")
    .addMigrations(MIGRATION_1_2)
    .build()
```

The same omission exists at `app/src/main/java/com/dot/app/reminder/ReminderScheduler.kt:159`
(`BootReceiver`'s private builder) — see F-02.

---

### F-02 — HIGH — `BootReceiver` opens the database without migrations
`app/src/main/java/com/dot/app/reminder/ReminderScheduler.kt:158-160`

```kotlin
val db = androidx.room.Room
    .databaseBuilder(appContext, DotDatabase::class.java, "dot.db")
    .build()
```

Two problems in three lines:

1. Same missing-migration crash as F-01, but on the boot path, where there is no
   UI to show an error and the process dies silently in the background. The user
   just finds their reminders stopped working.
2. It opens a **second, independent database instance** with its own connection
   pool and no WAL coordination against `AppContainer`'s instance. Two writers to
   the same SQLite file from two `RoomDatabase` objects in one process is a
   documented source of `SQLITE_BUSY` under load.

**Fix:** do not open a database here at all. The process already has one — see
the wiring section of the parent report. If a receiver-local instance is
unavoidable, it must at minimum call `.addMigrations(MIGRATION_1_2)`.

---

### F-03 — MEDIUM — `BootReceiver` duplicates the restore logic it should be sharing
`app/src/main/java/com/dot/app/reminder/ReminderScheduler.kt:167`

```kotlin
.filter { it.state == ReminderState.SCHEDULED && it.remindAt.isAfter(now) }
```

This inline filter silently **drops** any reminder whose moment passed while the
device was off. The user asked to be reminded; the reminder vanishes with no
indication it ever existed. `ReminderRecovery.plan` (added in Milestone 7,
`app/src/main/java/com/dot/app/diagnostics/ProcessRecovery.kt`) classifies these
as `Missed` instead, and is covered by
`BootRescheduleTest.a long-past reminder is reported missed, not re-armed`.

**Fix:** replace the inline filter with `ReminderRecovery.plan(...).rearm`, and
surface `.missed` to the user.


### F-04 — MEDIUM — Retry bound is declared but never asserted
`agent/llm/src/main/java/com/dot/agent/llm/gemini/GeminiProvider.kt:33,51`

`maxRetries = 1` is a constructor parameter with a default, and the loop at
line 51 is `while (attempt <= maxRetries)`. Nothing prevents a caller from
constructing `GeminiProvider(key, maxRetries = Int.MAX_VALUE)` — which is
SECURITY.md rule 14 (no unbounded retry) with a two-character change.

`GeminiProviderTest` covers timeouts and blank keys but never asserts the retry
count.

**Fix:** clamp in the constructor (`coerceAtMost(3)`), and add a test that counts
attempts against a stub server.

---

### F-05 — MEDIUM — No `network_security_config.xml`; cleartext is not explicitly denied
`app/src/main/AndroidManifest.xml` (no `android:networkSecurityConfig`)

`targetSdk = 35` means cleartext is denied by default, so this is not currently
exploitable. It is still a gap against SECURITY.md rule 11 because the posture is
implicit — it changes if the SDK is lowered, and nothing in the repo states the
intent.

**Fix:** add `res/xml/network_security_config.xml` with
`cleartextTrafficPermitted="false"` and reference it from the manifest. Make it
`debug-overrides`-free; there should be no debug bypass to review away.

---

### F-06 — MEDIUM — "No infinite agent loops" is asserted by construction, not by test
`agent/tools/src/main/java/com/dot/agent/tools/ToolRegistry.kt:54`

The bound is `while (attempt <= tool.maxRetries)`. Each tool declares its own
`maxRetries`, and the declaration is trusted. A tool that declares
`maxRetries = 1000` would be retried 1001 times, each attempt re-running `execute`
— for a tool with a side effect, that is a duplicate-execution bug, and PRD 23
requires idempotency where duplicate execution would be harmful.

Nothing tests the bound, and `ToolRegistry` does not clamp it the way
`GeminiProvider` should (F-04).

**Fix:** clamp `maxRetries` in `DotTool` implementations or centrally in
`ToolRegistry.invoke`; add a test that a throwing tool is attempted exactly
`maxRetries + 1` times.

---

### F-07 — MEDIUM — The inbox agent has no data-minimisation enforcement point yet
`agent/agents/` (in progress)

SECURITY.md rule 9 ("do not persist full email bodies") currently holds because
no email integration exists. The moment `AgentScheduler` starts fetching and
storing inbox content, `AgentRunEntity.compactResult` (`Entities.kt:196`) becomes
the field where a full body could land, and nothing constrains its size or
content.

**Fix:** before the inbox agent is enabled, add a length cap and a redaction pass
on `compactResult`, and a test that a 10 KB email body cannot be stored verbatim.

---

### F-08 — LOW — Diagnostics recorder is built but not wired
`app/src/main/java/com/dot/app/diagnostics/DiagnosticsRecorder.kt`

The class satisfies PRD 26 and is fully tested, but nothing constructs it. PRD 26's
"may expose" is optional, so this is not a defect — it is dead code until the
parent wires it. Recorded here so it is not mistaken for a shipped feature.

**Fix:** see the wiring section of the parent report.

---

### F-09 — LOW — The secret scan is a habit, not a gate
`.gitignore` covers `local-secrets.properties`, `*.keystore`, and
`keystore.properties`, and `git ls-files` confirms no secret-shaped filename is
tracked. A grep for `AIza…`, `sk-…` and `ya29.…` across tracked sources returns
exactly one hit: `DiagnosticsRecorderTest.kt:76`, which is a deliberately fake
fixture string used to prove the redactor fires.

But this was run by hand. Nothing in the build fails on a committed secret.

**Fix:** add a `gitleaks`/`detect-secrets` step to the release checklist as a
blocking gate (see `RELEASE_CHECKLIST.md`).

---

### F-10 — LOW — `PermissionBinding` is a real capability, not a hypothetical
`app/src/main/java/com/dot/app/DotApplication.kt:26-29`

```kotlin
container.bindCapabilityFlags(
    hasPermission = hasNotificationPermission(),
    isAuthenticated = false,
)
```

`isAuthenticated` is hard-coded `false`. That is honest for v0.1 (no provider
ships), and the comment says so. It is flagged only because it is the line that
must change when OAuth lands, and if it is left `false` after that point every
auth-gated tool will refuse forever — a functional bug that looks like correct
security.

**Fix:** derive from the real token store when it exists; add a test that a
`VALID` credential flips the flag.

---

### F-11 — INFO — `RECORD_AUDIO` is requested at install time
`app/src/main/AndroidManifest.xml:11`

PRD 22 requires justification, denial behaviour, a retry path and a settings path
per permission. Push-to-talk is a stated v0.1 feature, so the permission is
justified, and the receiver-side behaviour on denial is handled elsewhere. No
action beyond noting that the runtime-request path (not the manifest) is the one
that matters on API 33+.

### F-12 — HIGH (for any public distribution) — The Gemini API key is baked into the release APK
`app/build.gradle.kts:45` and `app/build.gradle.kts:52`

```kotlin
debug   { buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"") }
release { buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"") }
```

The key is read from the gitignored `local-secrets.properties`, which is why the
repo secret scan is clean and why `GeminiProviderTest.live call does not leak the
key in its request body` passes. **But gitignoring protects the repository, not
the artefact.** `BuildConfig` is a compiled-in constant; anyone who unpacks the
release APK and runs `strings` on it recovers the key. The `release` block
embedding it is a deliberate choice by whoever wrote it, and it is the right
default for an internal test build — but it is not shippable.

**Fix, in order of preference:**
1. Move the Gemini call behind a backend proxy that holds the key. The phone
   never sees it. This is the only option that survives a public release.
2. Use a Play App Signing / app-bound key so the credential is bound to the
   signing cert and is not extractable from the APK.
3. Failing both: ship with the key **omitted** from `release`. The app already
   degrades honestly — `GeminiProvider` returns `model_not_configured` on a blank
   key (`GeminiProvider.kt:41`) and the settings screen says so. A release with no
   AI fallback is a shippable v0.1; a release with a leaked key is not.

**If any build with an embedded key has already been distributed, rotate the key
before doing anything else.**

---

### F-13 — MEDIUM — `revoke` destroys the shared keystore key, corrupting other accounts
`agent/agents/src/main/java/com/dot/agent/agents/EncryptedPrefsOAuthTokenStore.kt:174-178`

```kotlin
override suspend fun revoke(accountId: String) {
    prefs.edit().remove(keyFor(accountId)).apply()
    (cipher as? AndroidKeystoreCipher)?.deleteKey()
}
```

`deleteKey()` deletes the **fixed** alias `dot.oauth.gcm.v1`, which encrypts every
account's grant, not just the one being revoked. Revoking account A therefore
renders account B's stored grant undecryptable — it decrypts to garbage and
`load()` returns null, i.e. a silent disconnect of an account the user never
touched.

Today only `OAuthTokenStore.DEFAULT_ACCOUNT` exists, so this is invisible. It
becomes a data-loss bug the moment a second account is connected.

Related, and worth stating plainly: `OAuthRevokeContractTest` includes
`revoking one account leaves another connected`, and that test **passes for the
wrong reason** — it injects a test `TokenCipher`, so the `as?` cast is null and
`deleteKey()` never runs. The test proves the prefs entry is removed
individually; it proves nothing about key isolation. A green test here is not
evidence against this finding.

**Fix:** make the alias per account —
`AndroidKeystoreCipher(alias = "${DEFAULT_ALIAS}.$accountId")` — so a revoke
destroys only that account's key. Then add a device test (real Keystore)
asserting the other account's grant still decrypts after one is revoked.

---

## 3. What is genuinely strong

Worth stating plainly, because a review that only lists problems is not a review:

- **The app allowlist is a real boundary, not a regex.** `InputGuards.isAllowedApp`
  (`DotTool.kt:74`) is membership in a 13-entry map. The comment at
  `DotTool.kt:66-68` records that the *previous* implementation matched a package
  regex, which admitted `com.evil.backdoor` — the current code is the corrected
  version, and `ToolRegistryTest.well formed but unlisted package is rejected`
  locks it in.
- **Model output cannot smuggle structure.** `ModelPlanParser` rejects nested
  objects, arrays, unknown top-level keys, and two simultaneous action keys. That
  last one is subtle and correct: two action keys is ambiguous, and resolving in
  the model's favour would let prose ride alongside an innocuous `"answer"`.
- **Failure is typed everywhere.** `ActionOutcome` has eight values, and there is
  no path in `CommandRuntime` or `AiFallback` where a throw becomes a success.
- **`AppLauncher` refuses to infer success** (`AppLauncher.kt:38-39`) — an
  installed-but-unlaunchable package returns `AMBIGUOUS`, not `SUCCESS`.
- **Redaction is applied at the boundary, not at the call site**, and
  `DiagnosticsRecorder` re-applies it even though its field set is already
  constrained — defence in depth for a surface whose output ends up in bug
  reports.

---

## 4. Sign-off status

**NOT APPROVED for release.** Four blocking items:

| ID | Severity | One-line fix? |
|---|---|---|
| F-01 | HIGH | yes — add `.addMigrations(MIGRATION_1_2)` in `AppContainer` |
| F-02 | HIGH | yes — same, plus stop opening a second DB in `BootReceiver` |
| F-12 | HIGH (public dist.) | no — a decision about how Gemini is reached at all |
| F-03 | MEDIUM | yes — swap the inline filter for `ReminderRecovery.plan` |

Then five medium hardening gaps (F-04, F-05, F-06, F-07, F-13), four low (F-08
through F-11) and one informational (F-11's neighbour, `RECORD_AUDIO`).

F-01, F-02 and F-03 are copy-pasteable in the parent report. F-04 and F-06 are
small clamps plus one test each. F-05 is a new resource file. None require
redesign. F-12 is a product decision and should be made before any build leaves
the team.

The OAuth store is genuinely in better shape than most of this list: it is now
covered by tests against the real implementation, and the two remaining gaps
(F-13's key alias, and the unverifiable-keystore-deletion) are both narrow.
