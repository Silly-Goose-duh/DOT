# DOT v0.1

Android-first personal productivity agent. Local-first: simple commands are answered
deterministically from local data and never require a remote model.

## Status

Milestones 0-2 implemented and verified by unit tests. Milestones 3-7 are **not** built
yet — see "What is not built" below.

## Requirements

- JDK 17
- Android SDK: platform 35, build-tools 35.0.0
- Gradle 8.11+

Point `local.properties` at your SDK:

```
sdk.dir=D\:\\Android\\Sdk
```

## Build and test

```bash
./gradlew :core:model:compileDebugKotlin
./gradlew test
```

## Architecture

Execution path follows `ARCHITECTURE.md` — cheapest safe path first:

```
Input -> CommandRouter
          ├─ LocalHandler -> Repository -> Result          (deterministic, no AI)
          └─ ModelProvider -> Validated ToolCall
                 -> PolicyEngine -> ToolRegistry -> Result
       -> DirectResponseFormatter -> UI/Voice
```

| Module | Responsibility |
|---|---|
| `core:model` | Domain entities, `ActionResult`/`ActionOutcome`, `TodayAggregator` |
| `core:database` | Room entities, DAOs, schema |
| `core:common` | `LogRedactor`, structured log events |
| `agent:router` | Intent taxonomy, `DeterministicRouter`, `DateTimeParser`, `DirectResponseFormatter` |
| `agent:policy` | `PolicyEngine` — risk + confirmation, independent of the model |
| `agent:tools` | Typed `DotTool` registry, input guards, app allowlist |
| `agent:memory` | Persistent memory vs. TTL cache separation |

## Security posture

- Model output cannot bypass `ToolRegistry`; only registered typed tools exist.
- `PolicyEngine` decides confirmation independently of model preference.
- `open_app` accepts only an allowlist of aliases/packages — never raw strings.
- External content (email, notes, filenames) is data, never authority; injection
  fixtures assert this (`PromptInjectionFixtureTest`).
- Consequential actions (`send_email`, `make_purchase`, security changes) are
  out of scope for v0.1 and refused by `PolicyEngine.isOutOfScope`.
- `LogRedactor` strips tokens/keys/JWTs from anything loggable; IDs are hashed.
- Every action returns a typed outcome — no silent success.

## Memory model

Persistent namespaces (`user_facts`, `preferences`) never expire. Cache namespaces
carry explicit TTLs: session context 30 min, recent tool results 15 min, inbox summary
24 h. `MemoryRepository.clearAll()` backs the "clear local memory" control.

## What is not built

Milestones 3-7 remain: AI fallback provider, push-to-talk/STT, background agents
(Inbox Agent, OAuth, WorkManager), calendar provider sync, and the Compose UI. No
`app` module exists yet, so **this is not yet an installable APK.**

Verified locally: JVM unit tests only. On-device behaviour (real notifications,
WorkManager execution, Compose rendering, package resolution) is unverified — no
device or emulator was available in the build environment.
