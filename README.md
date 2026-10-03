# DOT v0.1

Android-first personal productivity agent. Local-first: simple commands are answered
deterministically from local data and never require a remote model.

## Status

All eight milestones are implemented: the deterministic command runtime, the AI
fallback wired behind a user toggle, push-to-talk voice, the background-agent
framework, the Android action layer, and the hardening pass. See
[MILESTONES.md](MILESTONES.md) for per-milestone detail and
[docs/dot-android/SECURITY_REVIEW.md](docs/dot-android/SECURITY_REVIEW.md) for the
security audit.

**Builds an installable APK** (`app/build/outputs/apk/debug/app-debug.apk`).

## Requirements

JDK 17+ · Android SDK platform 35 + build-tools 35.0.0 · Gradle 8.11+ (wrapper included).

Point `local.properties` at your SDK (gitignored, machine-specific):

```
sdk.dir=D\:\\Android\\Sdk
```

Full setup, including how to install the SDK and the gotchas that will bite you,
is in **[BUILDING.md](BUILDING.md)**.

## Build and test

```bash
./gradlew :app:assembleDebug
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
| `agent:llm` | `ModelProvider` seam, strict plan schema, `AiFallback`, `GeminiProvider` |
| `agent:agents` | Background-agent scheduling (WorkManager), backoff, Inbox Agent, OAuth token store |
| `core:data` | Room-backed implementations of the repository ports |
| `app` | Compose UI, DI container, `CommandRuntime`, AlarmManager reminders, voice, diagnostics |

## Security posture

- Model output cannot bypass `ToolRegistry`; only registered typed tools exist.
- `PolicyEngine` decides confirmation independently of model preference.
- `open_app` accepts only an allowlist of aliases/packages — never raw strings.
- External content (email, notes, filenames) is data, never authority; injection
  fixtures assert this (`PromptInjectionFixtureTest`).
- Consequential actions (`send_email`, `make_purchase`, security changes) are
  out of scope for v0.1 and refused by `PolicyEngine.isOutOfScope`.
- `LogRedactor` strips tokens/keys/JWTs and spoken/typed credential phrases from
  anything loggable; IDs are hashed.
- Every action returns a typed outcome — no silent success. A failed app launch
  reports as a failure rather than "Opening.".
- A spoken transcript reaches the command runtime as **data**, through the same path
  as typed text, gaining no extra authority for having been spoken.
- Remote calls require the user's AI toggle **and** a compiled-in key; the toggle
  defaults off and is applied at startup, so having a key is never treated as consent.

## Memory model

Persistent namespaces (`user_facts`, `preferences`) never expire. Cache namespaces
carry explicit TTLs: session context 30 min, recent tool results 15 min, inbox summary
24 h. `MemoryRepository.clearAll()` backs the "clear local memory" control.

## Full status

[BUILDING.md](BUILDING.md) covers setup and known issues.
[REPORT.txt](REPORT.txt) has the detailed build report, including the two security
and correctness bugs the app-module tests caught.
[MILESTONES.md](MILESTONES.md) tracks per-milestone state.

## What is not built

- **A live inbox provider.** The agent framework, scheduling, OAuth token store,
  summariser and cache are all built and tested, but no provider implementation is
  configured. `AppContainer.inboxProviderOrNull()` returns null, so a scheduled run
  resolves to "nothing to do" rather than reporting a fabricated empty inbox.
- **Wake-word detection** — deliberately out of scope for v0.1; push-to-talk is required.
- **Calendar provider sync** — the event cache and read path exist; no sync does.
- **Memory writes.** `MemoryRepository` is fully built and tested, but nothing in
  production calls `remember()` yet, so the persistent-memory store is empty until a
  feature starts writing to it.
- **Editing an existing task's fields.** Tasks can be created and toggled done/undone;
  there is no field-level editor.

Verified locally: JVM unit tests and Robolectric only. On-device behaviour (real
notification delivery, exact-alarm timing, Compose rendering, `PackageManager`
resolution, `SpeechRecognizer`, WorkManager under doze) is still unverified — no
device or emulator was available here.
