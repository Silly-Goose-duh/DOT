# DOT — Milestone Status

Last updated: 2026-10-02

**Current state:** All eight milestones are implemented. The AI fallback is wired
into the command runtime, voice feeds the same pipeline as typed text, and the
background-agent framework is in place behind replaceable interfaces.

Re-run `./gradlew test` rather than trusting the numbers here if you are checking.

| | |
|---|---|
| Tests | 582 distinct, 1159 executions (debug + release variants), 0 failing, 0 skipped |
| Modules | 11 Gradle modules |
| Schema | Room v2, with an explicit v1→v2 migration |
| Live Gemini check | runs in the debug variant; skipped without a key |

Android modules run their unit tests twice (debug and release variants), so the
execution count is roughly double the distinct count. The five live-provider tests
in `agent:llm` report as skipped on a checkout with no `local-secrets.properties`.

---

## Milestone 0 — Architecture contract ✅

- [x] Project skeleton (Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, version catalog)
- [x] Module/package boundaries (`core:*`, `agent:*`)
- [x] Domain entities (7)
- [x] Repository contracts in `core:model`
- [x] Tool contracts (`DotTool<I, O>` with typed schema, risk, timeout, retry, audit flags)
- [x] Agent contracts (`AgentDefinition`, `AgentRun`, `AgentTask`, `AgentRunOutcome`)
- [x] Risk policy contract (`PolicyEngine` + `RiskLevel` + `ToolCategory`)
- [x] Security threat model (`SECURITY.md`)
- [x] Test baseline across all modules

## Milestone 1 — Local productivity ✅

- [x] Room database — 7 entities, 7 DAOs, schema v2
- [x] DataStore settings (`DotSettings`)
- [x] Tasks CRUD — ports, DAOs, Compose screen
- [x] Notes CRUD — ports, DAOs, Compose screen
- [x] Reminder scheduling — AlarmManager, exact/inexact degradation, boot reschedule
- [x] Upcoming/event cache — entity, DAO, provider-scoped invalidation
- [x] Home aggregation — `TodayAggregator` (pure, testable)
- [x] Notification permission UX on Android 13+
- [x] Local search — task + note `LIKE` queries

## Milestone 2 — Command runtime ✅

- [x] Intent taxonomy — 10 intents
- [x] Deterministic fast router — no network, no model
- [x] Date/time parser
- [x] Tool registry — unknown tools rejected, never executed
- [x] Structured result model (`RoutedCommand` + `CommandArguments`)
- [x] Direct response formatter — one short sentence
- [x] Risk policy enforced independently of the model
- [x] Cancellation/timeouts + bounded retries
- [x] Latency instrumentation

## Milestone 3 — AI fallback ✅ (now wired)

- [x] `ModelProvider` abstraction
- [x] Strict structured response schema (`ModelPlan`)
- [x] `ModelPlanParser` — validates before anything executes
- [x] `AiFallback` — provider → parser → `PolicyEngine` → `ToolRegistry`
- [x] Timeout/failure handling — typed codes, never silent success
- [x] Ambiguity handling — asks one question rather than guessing
- [x] Prompt-injection fixtures
- [x] Real network provider — `GeminiProvider`, TLS-only, key in a header not a URL
- [x] **Wired into `CommandRuntime`** — `RoutingOutcome.NeedsAi` reaches the model

The wiring is deliberately narrow. A model may *propose* a tool call; the proposal
is re-validated by the parser, re-gated by `PolicyEngine`, and re-checked against
registry membership and the app allowlist inside `CommandRuntime` before it runs.
No provider, or the toggle off, yields an honest sentence — never a guess.

The key comes from the gitignored `local-secrets.properties` into `BuildConfig`. An
absent key leaves the app offline-only, and Settings says so rather than showing a
dead switch.

## Milestone 4 — Voice ✅

- [x] `SpeechToTextPort` seam — no `android.speech` type crosses the interface
- [x] `AndroidSpeechToText` — main-thread bound, releases the recognizer
- [x] Every platform error mapped to a typed `SpeechErrorCode`; no raw int, no error becomes success
- [x] `VoiceSessionStateMachine` — pure; illegal transitions rejected, not ignored
- [x] `TranscriptNormalizer` — empty/over-length/punctuation handled; dates left to the router
- [x] `SpeechToTextAvailability` — disabled push-to-talk explains why
- [x] Mic permission UX with a settings retry path for permanent denial
- [x] **Transcript routed through the identical `CommandRuntime.handle` path as typed text**
- [x] Optional TTS behind `GatedTextToSpeech`, default OFF
- [x] Accessibility — content descriptions, state descriptions, 48dp targets
- [ ] Wake word — out of scope for v0.1 (PRD 18 calls it experimental)

A spoken transcript is data. It gains no authority, no tool access and no
confirmation bypass for having been spoken.

## Milestone 5 — Background agents ✅ (framework; no live provider)

- [x] `AgentDefinition`/`AgentRun` persistence — ports in `core:model`, Room in `core:data`
- [x] `AgentSchedulePlanner` + `WorkManagerAgentScheduler` — unique work, KEEP on schedule, REPLACE on reschedule
- [x] `AgentRunWorker` + `DotWorkerFactory`
- [x] Run-now, in its own unique-work namespace so it cannot be confused with the periodic chain
- [x] Enable/disable — a disabled agent has no scheduled work, and one that runs anyway is a no-op
- [x] `BackoffPolicy` — bounded exponential, injected jitter, deterministic under test
- [x] `AgentRunner` — always reaches a terminal status; idempotent via `InFlightGuard`; bounded history
- [x] `InboxProviderPort` seam + `InboxAgent` + `InboxSummariser`
- [x] `ModelInboxSummariser` sees counts and sender domains only — never subjects or snippets
- [x] `LocalInboxSummariser` — needs no model
- [x] `EncryptedPrefsOAuthTokenStore` over an `AndroidKeystoreCipher`; expiry → `AUTH_REQUIRED`; disconnect removes credentials
- [x] `InboxSummaryCache` on the 24 h `INBOX_SUMMARY` namespace
- [x] `ImportantResultNotifier` — the notify *decision* is pure and tested without a device
- [x] No raw message body persisted or placed in notification text
- [ ] **No live inbox provider is configured.** `AppContainer.inboxProviderOrNull()`
      returns null, so the task list is empty and a scheduled run resolves to "nothing
      to do" rather than reporting a fabricated empty inbox. That is the honest state.

## Milestone 6 — Android action layer ✅

- [x] `open_app` tool with a 13-app alias allowlist
- [x] PackageManager/Intent resolution
- [x] Action result verification — not-installed / no-launch-intent are typed failures
- [x] Allowlist closed: membership only, so a well-formed unlisted package is rejected

## Milestone 7 — Hardening ✅

- [x] Room v1→v2 migration + `MigrationV1ToV2Test`. No `destructiveMigration()`
      anywhere, and `AppContainer` registers `DOT_MIGRATIONS` explicitly, so a
      forgotten migration is loud rather than silent data loss.
- [x] Process-death tests — `ProcessRecovery`, `ProcessDeathRecovery`
- [x] Reboot/reschedule tests
- [x] Offline tests — local CRUD, search, reminders, deterministic commands
- [x] OAuth revoke contract tests
- [x] `PerformanceBudgetTest` — routing, task query, purge
- [x] `DiagnosticsRecorder` — redacting, bounded ring buffer, JVM-testable
- [x] `SECURITY_REVIEW.md`, `HARDENING.md`, `RELEASE_CHECKLIST.md`

---

## Security properties under test

Enforced by passing tests, not just documented. `SECURITY_REVIEW.md` has the full
rule-by-rule audit.

| Property | Enforced by |
|---|---|
| Prompt-injection fixtures inert | `PromptInjectionFixtureTest`, `AiFallbackWiringTest` |
| Unknown tools rejected, not executed | `ToolRegistryTest`, `AiFallbackWiringTest` |
| Model cannot invent a tool | `ModelPlanParserTest`, `AiFallbackWiringTest` |
| Model cannot reach the app allowlist | `AiFallbackWiringTest` |
| Malicious args rejected before execution | `ToolRegistryTest` |
| Missing permission → `PERMISSION_REQUIRED` | `ToolRegistryTest` |
| Missing auth → `AUTH_REQUIRED` | `ToolRegistryTest` |
| Tool failure typed, never silent success | `ToolRegistryTest` |
| External writes require confirmation | `PolicyEngineTest` |
| v0.1 consequential actions refused | `PolicyEngineTest` |
| Tokens/JWTs/keys redacted | `LogRedactorTest`, `DiagnosticsRecorderTest` |
| IDs hashed in logs | `LogRedactorTest` |
| Every cache namespace has a TTL | `MemoryPolicyTest` |
| Nested/array tool args rejected | `ModelPlanParserTest` |
| Exactly one action key required | `ModelPlanParserTest` |
| Provider only sees registered tools | `AiFallbackWiringTest` |
| Provider that throws is a failure | `AiFallbackWiringTest`, `AiFallbackTest` |
| Local fast path never reaches a model | `AiFallbackWiringTest` |
| User toggle off means no provider call | `AiFallbackWiringTest` |
| Clear-local-memory works | `MemoryRepositoryTest` |
| Data survives a schema migration | `MigrationV1ToV2Test` |

## Bugs found by these tests, and fixed

1. **Date parser read year digits as a clock time.** `2026-10-05` parsed as 20:26.
2. **Bare "at 8" resolved to 08:00, not 20:00.** The PM heuristic covered only 1–6.
3. **Multi-clause question routed to local search** instead of falling through to AI.
4. **Room mapper threw on malformed UUIDs.** One bad row killed the whole query.
5. **Memory invalidation didn't purge.** Strict `<` missed an exactly-equal timestamp.
6. **`open_app` accepted any well-formed package name.** `InputGuards.packageRef`
   matched a regex instead of checking allowlist membership, so `open com.evil.backdoor`
   was accepted and the UI reported "Opening."
7. **`get_today` never ran.** The registry cast every tool to a map input, so the
   `DotTool<Unit, _>` got an empty map and threw.
8. **A dangling `MULTI_SPACE_RUN` reference** in the notification sanitizer, and a
   summariser handed `LocalInboxSummariser` where a `ModelProvider` was required.
9. **The AI toggle was a no-op at startup.** `CommandRuntime.aiFallbackEnabled`
   defaulted to `true` while the persisted setting defaulted to `false`, and nothing
   pushed the persisted value in at startup — so on a fresh install with a key
   compiled in, user utterances were sent to a remote model while the Settings switch
   displayed "off". The runtime default now fails closed and `DotApplication` applies
   the persisted value during `onCreate`.
10. **`open_app` reported "Opening." for apps that never opened.** `AppLauncher`
    returns a typed `UNAVAILABLE`/`not_installed` or `no_launch_intent`, but the result
    was discarded. The side effect's outcome now replaces the tool's success.
11. **The confirmations toggle did nothing.** It drove only its own switch. It now
    gates `CommandRuntime`'s confirmation gate, defaulting on.
12. **`DiagnosticsRecorder` echoed credentials.** The redactor only matched assignment
    shapes (`password: x`), so a spoken/typed "my password is hunter2" passed through
    verbatim. Credential-phrase and 12–20 digit-run patterns are now scrubbed.
13. **Notification text could carry a whole email body.** `MAX_BODY_CHARS = 160` let a
    75-char sentence through intact; the first-line-only rule was dead code because
    `sanitize()` ran before `lineSequence()` and had already collapsed the newlines.
14. **The inbox summariser did not fall back** when the model returned a failure or
    unparseable JSON, failing a whole run for a cosmetic rewrite — and it passed raw
    subjects to the model despite a comment claiming otherwise.
15. **The voice collector never terminated** on a terminal event, leaking a coroutine
    that hung `runTest` for 60 s per test, and `Processing` rejected the final
    transcript — so tap-to-stop silently discarded what the user had just said.

Two of my own tests were also wrong and were corrected rather than worked around: an
invalid `MemoryTtl` tuple, and a filler-word assertion that matched "hi" inside
"Nothing" until given word boundaries.


## Known gaps

Found by the read-only security audit and **not yet fixed**. Listed here rather
than left to be discovered, because each one is a real limitation:

- **`DotTool.timeoutMs` is declared on every tool and never enforced.** `ToolRegistry.invoke`
  applies bounded retries but no `withTimeout`, so a hung tool suspends the command
  indefinitely. The Gemini provider bounds its own network calls; the tool path does not.
- **`DotLogEvent` and `LogRedactor`'s redaction entry points are dead code** outside tests.
  Redaction is exercised by the diagnostics recorder and notification factory, but the
  structured-logging layer has no production call site.
- **`MemoryRepository.remember()` has zero production call sites.** The memory system is
  built and tested but nothing writes to it, so "clear local memory" clears an empty store.
- **`open_app`'s launcher branches have no direct tests** for `not_installed`,
  `no_launch_intent` or `launch_denied`; the runtime now propagates them but the
  individual `AppLauncher` outcomes are covered only indirectly.
- **`ReminderScheduler`, `BootReceiver` and `SettingsStore` have no unit tests.**
- **`ToolDescriptor` is bypassed** — `AiFallback` uses its own `SCHEMA_HINTS` map, so a
  tool's real description and the description the model is shown can drift apart.
- **A single write of the compiled-in key ships inside the APK.** Not a Git leak, but a
  distribution concern for any real release.

## Verification limits

Verified by **JVM unit tests and Robolectric only**. No emulator or device was
available, so these remain unverified on real hardware:

- Real notification delivery and deep links
- Exact-alarm timing and reboot rescheduling on a device
- Compose rendering and accessibility in a real screen reader
- `PackageManager`/Intent resolution against real installed apps
- `SpeechRecognizer` behaviour and the actual push-to-talk UI
- WorkManager execution under real battery/doze constraints
- OAuth token round-trip against a live provider

Latency figures are unit-level. On-device numbers will differ and should be read
from `DiagnosticsRecorder` once a device is available.

## Build setup

Requires JDK 17, Android SDK platform 35 + build-tools 35.0.0, Gradle 8.11+.
Create `local.properties` with your SDK path (`sdk.dir=...`), then:

```bash
gradle test
```

`local.properties` and `local-secrets.properties` are intentionally gitignored —
they are machine-specific, and the second one holds the provider key.
