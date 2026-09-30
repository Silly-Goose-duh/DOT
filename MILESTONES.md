# DOT v0.1 — Milestone Status

Last updated: 2026-09-30

**Current state:** Milestones 0–2 complete and verified, plus an installable `:app`
module (Compose UI, DI, command runtime, AlarmManager reminders). Build is green.

| | |
|---|---|
| Unique tests | 115 passing, 0 failing, 0 skipped |
| Kotlin files | 22 (14 main + 8 test) |
| Main code | ~1,661 lines |
| Build | `BUILD SUCCESSFUL`, `GRADLE_EXIT=0` |
| Router latency | p50 = 0 ms, p95 = 0 ms (budget: 150 ms) |

---

## Milestone 0 — Architecture contract ✅

- [x] Project skeleton (Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, version catalog)
- [x] Module/package boundaries (`core:*`, `agent:*`)
- [x] Domain entities (`Task`, `Reminder`, `CachedEvent`, `Note`, `AgentDefinition`, `AgentRun`, `MemoryItem`)
- [x] Repository contracts (`*RepositoryPort` interfaces in `agent:tools`)
- [x] Tool contracts (`DotTool<I, O>` with typed schema, risk, timeout, retry, audit flags)
- [x] Agent contracts (`AgentDefinition`, `AgentRun`, `ActionResult`/`ActionOutcome`)
- [x] Risk policy contract (`PolicyEngine` + `RiskLevel` + `ToolCategory`)
- [x] Security threat model (`SECURITY.md`; enforced by tests, not just documented)
- [x] CI/test baseline (Gradle `test` task, all 7 modules wired)

## Milestone 1 — Local productivity ✅ (partial)

- [x] Room database — 7 entities, 7 DAOs, schema v1
- [ ] DataStore settings — **not built**
- [x] Tasks CRUD — repository ports + DAOs; UI not built
- [x] Notes CRUD — repository ports + DAOs; UI not built
- [ ] Reminder scheduling (AlarmManager) — model + DAO only, no scheduler
- [x] Upcoming/event cache — entity, DAO, provider-scoped invalidation
- [x] Home aggregation — `TodayAggregator` (pure, testable)
- [ ] Notification permission UX — **not built**
- [x] Local search — task + note `LIKE` queries

## Milestone 2 — Command runtime ✅

- [x] Intent taxonomy — 10 intents in `CommandIntent`
- [x] Deterministic fast router — regex rules, no network, no model
- [x] Date/time parser — relative days, weekdays, ISO, `d/m`, meridiem handling
- [x] Tool registry — unknown tools rejected, never executed
- [x] Structured result model — `RoutedCommand` + serializable `CommandArguments`
- [x] Direct response formatter — one short sentence, no filler (asserted by test)
- [x] Risk policy — confirmation enforced independently of the model
- [x] Cancellation/timeouts — per-tool `timeoutMs` + bounded `maxRetries`
- [x] Latency instrumentation — measured in `RouterLatencyTest`

## Milestone 3 — AI fallback ❌

Still nothing built — no `ModelProvider`. `RoutingOutcome.NeedsAi` is now wired into
`CommandRuntime`, where it degrades to an explicit "I can only handle simple commands
offline." instead of a silent failure. `DotSettings.aiFallbackEnabled` is persisted
but not yet acted on.

## App module ✅ (new)

- [x] `:app` module — first installable APK
- [x] Compose UI: Today / Ask / Tasks / Notes / Settings
- [x] `AppContainer` composition root (no DI framework)
- [x] `CommandRuntime` — router → policy → registry → `ActionResult` → one sentence
- [x] Reminder scheduling via AlarmManager + notification channel + boot reschedule
- [x] Notification permission UX on Android 13+
- [x] DataStore settings, clear-local-memory, purge-expired

## Milestone 4 — Voice ❌

Nothing built. The router accepts text, so a transcript can feed it unchanged once
STT exists.

## Milestone 5 — Background agents ❌

Entities (`AgentDefinition`, `AgentRun`) and a bounded-history DAO query exist.
No scheduler, no WorkManager, no OAuth, no Inbox Agent.

## Milestone 6 — Android action layer ✅

- [x] `open_app` tool with a 13-app alias allowlist
- [x] PackageManager/Intent resolution (`PackageVisibility`, `AppLauncher`)
- [x] Action result verification (not-installed / no-launch-intent are typed failures)
- [x] Allowlist closed: a well-formed but unlisted package is now rejected

## Milestone 7 — Hardening ❌

Nothing built beyond the security tests listed below.

---

## Security properties under test

These are enforced by passing tests, not just documented:

| Property | Test |
|---|---|
| Prompt-injection fixtures rejected | `PromptInjectionFixtureTest` (5 payloads) |
| Unknown tools rejected, not executed | `ToolRegistryTest` |
| Malicious args rejected before execution | `ToolRegistryTest` |
| Missing permission → `PERMISSION_REQUIRED`, not success | `ToolRegistryTest` |
| Missing auth → `AUTH_REQUIRED`, not success | `ToolRegistryTest` |
| Tool failure is typed, never silent success | `ToolRegistryTest` |
| External writes require confirmation | `PolicyEngineTest` |
| v0.1 consequential actions refused | `PolicyEngineTest` |
| Tokens/JWTs/keys redacted from logs | `LogRedactorTest` |
| IDs hashed in logs | `LogRedactorTest` |
| Every cache namespace has an explicit TTL | `MemoryPolicyTest` |
| Clear-local-memory works | `MemoryRepositoryTest` |

## Bugs found by these tests, and fixed

1. **Date parser read year digits as a clock time.** `2026-10-05` parsed as 20:26.
   Fixed by stripping date expressions before time extraction.
2. **Bare "at 8" resolved to 08:00, not 20:00.** The PM heuristic covered only 1–6.
   Now covers 1–11.
3. **Multi-clause question routed to local search.** "Which report … and why did it
   fail?" hit the `which report` rule instead of falling through to AI. Now requires a
   single clause.
4. **Room mapper threw on malformed UUIDs.** `toDomain()` called `UUID.fromString()`
   unguarded, so one bad row killed the whole query. All 7 mappers now degrade via
   `parseUuidOrNull`; enums via `parseEnumOr`.
5. **Memory invalidation didn't purge.** Strict `<` comparison missed an exactly-equal
   timestamp. Now inclusive.

Two of my own tests were also wrong and were corrected rather than worked around: the
`MemoryTtl` tuple syntax was invalid Kotlin, and a filler-word assertion matched "hi"
inside "Nothing" until given word boundaries.

## Bugs found by the app-module tests, and fixed

8. **`open_app` accepted any well-formed package name.** `InputGuards.packageRef`
   passed anything matching `PACKAGE_REGEX`, so `open com.evil.backdoor` was accepted,
   returned the raw string, and the UI reported "Opening." — the opposite of the
   documented allowlist. Now membership-only; covered by a regression test.
9. **`get_today` never ran.** `ToolRegistry` cast every tool to a `Map<String,String?>`
   input, so the `DotTool<Unit, _>` tool got an empty map and threw
   `ClassCastException` on the synthetic bridge, surfacing as `tool_error`. Added the
   `UnitInputTool` marker so the registry dispatches on the real input type.

## Not installable yet

This now produces an APK: `app/build/outputs/apk/debug/app-debug.apk`
(`com.dot.app`, minSdk 26, targetSdk 35).

## Verification limits

Everything above is verified by **JVM unit tests and Robolectric only**. No emulator
or device was available in the build environment, so the following are unverified:

- Real notification delivery and deep-links
- Exact-alarm timing and reboot rescheduling on a real device
- Compose rendering and accessibility
- `PackageManager`/Intent resolution against real installed apps
- OAuth flows and token revocation

Latency figures are unit-level. On-device numbers will differ and should be measured
in the diagnostics screen once an `app` module exists.

## Build setup

Requires JDK 17, Android SDK platform 35 + build-tools 35.0.0, Gradle 8.11+.
Create `local.properties` with your SDK path (`sdk.dir=...`), then:

```bash
gradle test
```

`local.properties` is intentionally gitignored — it is machine-specific.
