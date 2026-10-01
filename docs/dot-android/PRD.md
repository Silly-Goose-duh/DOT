# DOT v0.1 --- Product Requirements Document

**Status:** Build-ready draft\
**Platform:** Android first\
**Primary implementation:** Kotlin + Jetpack Compose\
**Builder/orchestrator:** Hermes Agent\
**Product principle:** *Tell DOT what needs to happen. DOT organizes it,
remembers it, and safely handles what it can.*

------------------------------------------------------------------------

## 1. Product Summary

DOT is an Android-first personal productivity AI agent. It combines a
useful daily productivity home screen with short natural-language
interaction, local memory, reminders, tasks, events, specialized
background agents, and a controlled action/tool layer.

DOT is **not primarily a chatbot**. The user should describe an outcome
in normal language. DOT should understand the intent, use the cheapest
and fastest safe path, perform or schedule the action, and return a
short result.

Examples:

-   "What do I have today?" → "2 events and 3 tasks."
-   "Remind me to submit the report at 8." → "Done."
-   "Add finish seminar to tomorrow." → "Added."
-   "Check my inbox." → "2 important emails. One needs a reply."
-   "Open YouTube." → opens the app.
-   "Which report did I use yesterday?" → searches permitted local
    context/files and returns the best match or asks one clarification.

------------------------------------------------------------------------

## 2. Product Goals

DOT v0.1 must prove seven capabilities:

1.  A polished Android app shell and productivity home.
2.  A local productivity engine for tasks, reminders, events, and quick
    notes.
3.  A controlled tool/action system.
4.  A fast command router with AI fallback.
5.  Voice input, with push-to-talk as the reliable v0.1 baseline.
6.  One working specialized background agent: Inbox Agent.
7.  A limited Android action/agent loop for supported actions.

The MVP is successful when these seven capabilities work together as one
product rather than seven disconnected demos.

------------------------------------------------------------------------

## 3. Non-Goals for v0.1

Do **not** attempt these in v0.1:

-   Universal autonomous control of every Android application.
-   Unrestricted AccessibilityService-driven AI automation.
-   Fully autonomous sending of messages, purchases, account changes,
    deletion, or other consequential actions.
-   Always-on cloud audio streaming.
-   A large plugin marketplace.
-   Social/news feeds.
-   Cross-platform iOS support.
-   A general-purpose long-form chatbot.
-   Training a custom foundation model.
-   Fully local LLM inference as a hard MVP requirement.
-   Continuous real-time inbox polling from an Android background
    process.

These may be researched later without blocking v0.1.

------------------------------------------------------------------------

## 4. User Experience Principles

### 4.1 Direct responses

Default response length: one short sentence or compact card.

Response classes:

**Action result** - "Done." - "Added." - "Reminder set for 8 PM."

**Information result** - "3 tasks remaining." - "Project review at 2
PM."

**Progress** - "Checking." - "Searching."

**Clarification** - "Which Rohan?" - "Today or tomorrow?"

Avoid greetings, filler, repeated explanations, and conversational
padding unless the user explicitly asks for detail.

### 4.2 Outcome over procedure

The user states the desired outcome. DOT chooses the appropriate
internal path.

### 4.3 Local-first fast path

Simple commands that can be answered from local state must not require a
remote LLM.

### 4.4 Progressive permissions

Ask for a permission only when a feature requiring it is first used.
Explain the reason in one sentence.

### 4.5 User control

DOT must show what its background agents can access, when they last ran,
and provide pause/disable controls.

------------------------------------------------------------------------

## 5. Primary Screens

### 5.1 DOT Home

The home screen should answer "What matters now?" without requiring a
prompt.

Sections:

-   Greeting/time context
-   Today
-   Tasks
-   Upcoming events
-   Reminders
-   Agent status
-   Ask DOT input
-   Microphone/push-to-talk button

Example:

    Today
    09:00  College
    14:00  Project review

    Tasks
    [ ] Finish seminar slides
    [ ] Submit assignment

    Reminders
    20:00  Send report

    Agents
    Inbox Agent · Last checked 8 min ago

    Ask DOT...

### 5.2 Tasks

Capabilities: - create - complete/uncomplete - edit - delete with undo -
due date/time - optional priority - recurring task can be deferred to
v0.2 if schedule risk is high

### 5.3 Calendar / Upcoming

Capabilities: - local event model - day/upcoming list - Google Calendar
connection as optional integration - read-only calendar access first -
event creation can be added only after read path is stable

### 5.4 Reminders

Capabilities: - natural-language creation - reliable local
notification - edit/cancel - mark done - notification deep-links to the
relevant item

### 5.5 Notes

Minimal quick-note capture: - text - created timestamp - optional
title - searchable - accessible through Ask DOT

### 5.6 Agents

Each specialized agent has: - name - enabled state - required
permissions/integration - last successful run - last result - run-now
action - frequency where applicable - failure state - privacy
explanation

### 5.7 Settings & Privacy

Include: - AI provider/model configuration abstraction - connected
accounts - permissions - memory controls - clear local data - agent
controls - diagnostics toggle - privacy information

------------------------------------------------------------------------

## 6. Core User Stories

### Productivity

-   As a user, I can type "Add finish seminar tomorrow" and see the task
    appear immediately.
-   As a user, I can ask "What do I have today?" and receive a concise
    local answer.
-   As a user, I can say "Remind me at 8 PM to send the report" and
    receive the notification at the requested time.
-   As a user, I can see upcoming events and tasks together.

### Memory

-   As a user, DOT remembers active task context across app restarts.
-   As a user, I can clear DOT's local memory.
-   As a user, I can inspect the categories of information DOT stores.

### Inbox Agent

-   As a user, I can connect an email account with explicit
    authorization.
-   As a user, I can manually run Inbox Agent.
-   As a user, Inbox Agent can periodically check at an OS-appropriate
    interval.
-   As a user, I receive a concise summary of important/new messages.
-   As a user, DOT never sends an email in v0.1 without an explicit
    future feature and confirmation design.

### Android actions

-   As a user, I can ask DOT to open a supported installed application.
-   As a user, DOT reports failure instead of pretending an action
    succeeded.
-   As a user, risky actions require confirmation.

------------------------------------------------------------------------

## 7. Latency Requirements

### 7.1 Definition

The "under 2 seconds" requirement means **time to first useful
response**, not guaranteed completion of every network or multi-step
task.

### 7.2 Targets

-   Local read command: p50 \< 300 ms; p95 \< 800 ms after UI dispatch.
-   Local write command: p95 \< 1 s.
-   Command classification/router: target \< 150 ms when rule/local
    parser handles it.
-   Remote AI request: first useful UI acknowledgement \< 2 s.
-   Long-running task: immediately show a progress state such as
    "Checking."
-   UI interactions should feel immediate; never freeze the main thread
    for network/DB work.

### 7.3 Fast-path examples

Must remain local: - list today's tasks - create simple task - complete
task - list cached events - create local reminder - save quick note -
search local notes/tasks - retrieve current session context

Use AI only when natural-language parsing is ambiguous or reasoning is
actually required.

------------------------------------------------------------------------

## 8. Architecture

High-level:

    User
      |
      v
    Voice / Text / UI
      |
      v
    Command Router
      |-----------------------|
      v                       v
    Local Fast Path        AI Agent
      |                       |
      |                 Intent / planning
      |                 ambiguity handling
      |                 tool selection
      |                       |
      |-----------|-----------|
                  v
              Tool Registry
                  |
        |---------|----------|-----------|
        v         v          v           v
      Tasks    Reminders   Integrations Android Actions
        |         |          |           |
        |---------|----------|-----------|
                  v
             Result/Observe
                  |
             Success?
              /     \
            yes      no
            done   retry/replan/
                   ask user

Background:

    WorkManager / system scheduling
                |
         Agent Scheduler
                |
        Specialized Agents
          |           |
      Inbox Agent   future agents
          |
      Integration APIs
          |
       Local cache
          |
     Home / notification

------------------------------------------------------------------------

## 9. Recommended Android Stack

-   Kotlin
-   Jetpack Compose
-   Material 3
-   Coroutines + Flow
-   Room for structured persistent local data
-   DataStore for small preferences/settings
-   WorkManager for deferrable periodic background work
-   AlarmManager/exact-alarm path only where justified by user-facing
    exact reminder requirements and current Android rules
-   Android notification APIs
-   Android Keystore-backed key management for secrets requiring local
    protection
-   Retrofit/OkHttp or equivalent for network layer
-   Hilt or another DI solution if it reduces coupling; avoid dependency
    complexity for its own sake
-   Gradle Kotlin DSL
-   JUnit + AndroidX testing
-   Compose UI tests

Use interfaces around external providers so model/email/calendar
providers remain replaceable.

------------------------------------------------------------------------

## 10. Module Boundaries

Suggested repository structure:

    dot/
    ├── app/
    ├── core/
    │   ├── model/
    │   ├── database/
    │   ├── datastore/
    │   ├── network/
    │   ├── security/
    │   └── common/
    ├── feature/
    │   ├── home/
    │   ├── tasks/
    │   ├── reminders/
    │   ├── calendar/
    │   ├── notes/
    │   ├── agents/
    │   ├── command/
    │   ├── voice/
    │   └── settings/
    ├── agent/
    │   ├── runtime/
    │   ├── router/
    │   ├── tools/
    │   ├── memory/
    │   └── policy/
    ├── integrations/
    │   ├── email/
    │   └── calendar/
    ├── android-actions/
    ├── docs/
    └── tests/

Hermes may simplify the physical Gradle module count initially, but
these logical boundaries must remain.

------------------------------------------------------------------------

## 11. Data Model

Minimum entities:

### Task

-   id: UUID
-   title
-   description nullable
-   status
-   priority nullable
-   dueAt nullable
-   createdAt
-   updatedAt
-   completedAt nullable

### Reminder

-   id
-   title
-   remindAt
-   taskId nullable
-   state
-   createdAt

### EventCache

-   id
-   provider
-   providerEventId nullable
-   title
-   startAt
-   endAt nullable
-   location nullable
-   lastSyncedAt

### Note

-   id
-   title nullable
-   body
-   createdAt
-   updatedAt

### AgentDefinition

-   id
-   type
-   enabled
-   schedule/frequency
-   lastRunAt nullable
-   lastSuccessAt nullable
-   lastErrorCode nullable

### AgentRun

-   id
-   agentId
-   startedAt
-   finishedAt
-   status
-   compactResult
-   diagnostic metadata with no sensitive body content by default

### MemoryItem

-   id
-   namespace
-   key
-   value/encrypted payload as appropriate
-   createdAt
-   updatedAt
-   expiresAt nullable
-   source
-   userVisible boolean

### Conversation/Session Context

Short-lived context with TTL. Do not use it as a permanent transcript
archive by default.

------------------------------------------------------------------------

## 12. Memory Design

Do not call all storage "cache."

### Persistent local memory

Use for: - tasks - reminders - notes - agent configuration -
user-approved preferences - useful stable mappings - integration
metadata

### Temporary cache

Use for: - current conversation context - current command - current
screen/action state - recent tool results - recent AI result - cached
event/email summaries - retry state

Every cache category needs a TTL or explicit invalidation rule.

### Memory principles

-   local by default
-   minimal collection
-   user-inspectable categories
-   clearable
-   no silent permanent storage of full email bodies
-   no secret tokens in ordinary Room rows or logs
-   avoid embedding sensitive user data unless a concrete retrieval need
    justifies it
-   separate "user facts/preferences" from execution history

------------------------------------------------------------------------

## 13. Command Router

Routing order:

1.  deterministic local intent/rule
2.  structured local parser
3.  provider integration/tool
4.  remote LLM for ambiguous/complex reasoning
5.  ask one concise clarification

Example intents:

-   GET_TODAY
-   CREATE_TASK
-   COMPLETE_TASK
-   CREATE_REMINDER
-   LIST_EVENTS
-   CREATE_NOTE
-   SEARCH_LOCAL
-   RUN_AGENT
-   OPEN_APP
-   UNKNOWN_COMPLEX

The router returns structured commands, never executable arbitrary
shell/Android code.

Example:

    {
      "intent": "CREATE_REMINDER",
      "arguments": {
        "title": "Send report",
        "remindAt": "2026-09-27T20:00:00+05:30"
      },
      "confidence": 0.98,
      "requiresConfirmation": false
    }

------------------------------------------------------------------------

## 14. Tool System

All actions must be registered typed tools.

Initial tool set:

-   get_today()
-   create_task(...)
-   update_task(...)
-   complete_task(...)
-   list_tasks(...)
-   create_reminder(...)
-   cancel_reminder(...)
-   list_events(...)
-   create_note(...)
-   search_local(...)
-   run_inbox_agent(...)
-   open_app(packageOrAlias)
-   get_current_context()
-   ask_user(question)

Each tool defines: - typed input schema - output schema - risk level -
permissions needed - timeout - retry policy - audit behavior - whether
confirmation is required

The LLM cannot invent new tools at runtime.

------------------------------------------------------------------------

## 15. Agent Loop

For complex supported operations:

    PLAN
      |
    ACTION
      |
    OBSERVE RESULT
      |
    UPDATE STATE
      |
    SUCCESS?
      | yes -> NEXT/DONE
      | no  -> RETRY / REPLAN / ASK

Set hard limits: - maximum steps per command - maximum retries per
tool - total execution timeout - cancellation support - no infinite
autonomous loop

v0.1 should keep the action surface small.

------------------------------------------------------------------------

## 16. Inbox Agent

Inbox Agent is the reference implementation for DOT's specialized-agent
framework.

### v0.1 responsibilities

-   user explicitly connects email account
-   request the narrowest practical read scope
-   fetch recent/new metadata and content only as required
-   classify/summarize important items
-   store a compact local summary/cache
-   show last-run state
-   optionally notify the user about genuinely important results
-   support "Run now"

### Must not do in v0.1

-   send email autonomously
-   delete/archive messages autonomously
-   alter mailbox state unless separately designed and authorized
-   retain full mailbox content indefinitely
-   poll more frequently than platform/integration rules reasonably
    permit

### Scheduling

Use WorkManager for periodic deferrable checks. The UI must not imply
exact continuous monitoring. "Run now" performs an explicit
user-requested refresh.

------------------------------------------------------------------------

## 17. Calendar

Start with: 1. local/upcoming event model 2. optional read-only provider
sync 3. cached events available to fast path 4. conflict/upcoming
summaries

Only later add event creation/editing after authorization, error
handling, and confirmation UX are stable.

------------------------------------------------------------------------

## 18. Voice

### v0.1 required

-   push-to-talk
-   speech-to-text
-   command routing
-   concise visual response
-   optional TTS for short responses

### Experimental

-   "Hey Dot" wake phrase

Do not make v0.1 depend on indefinite background microphone access.
Wake-word research must account for modern Android
background/foreground-service restrictions, battery use, privacy, and
store policy.

------------------------------------------------------------------------

## 19. Android Action Strategy

Priority:

1.  official API/integration
2.  Android Intent/deep link
3.  app-owned UI/action
4.  narrow deterministic system interaction where policy permits
5.  user clarification

AccessibilityService is **not** the foundation of v0.1 autonomous AI
control. Keep any accessibility experiments isolated behind an
interface/feature flag and review current Google Play policy before
distribution.

For v0.1, `open_app` through PackageManager/Intent is enough to prove
the Android action layer.

------------------------------------------------------------------------

## 20. Safety Model

Risk categories:

### Low

-   read local tasks
-   read cached events
-   search local notes
-   open app
-   scroll within DOT
-   create a reversible local task/note

Usually execute directly.

### Medium

-   share data externally
-   modify external calendar/email state
-   rename/move user files
-   send a message/email

Require clear context and usually confirmation.

### High

-   purchases/payments
-   deleting important external data
-   account/security setting changes
-   authentication changes
-   destructive bulk operations

Out of scope for v0.1. Future implementation requires explicit
confirmation immediately before execution.

The policy engine---not the LLM alone---decides whether confirmation is
required.

------------------------------------------------------------------------

## 21. Security & Privacy Requirements

### Secrets

-   Never hard-code API keys, OAuth client secrets, refresh tokens, or
    provider credentials.
-   Keep secrets out of Git.
-   Use Android Keystore-backed protection where appropriate.
-   Redact secrets from logs and crash reports.

### OAuth

-   Use official OAuth authorization.
-   Request least-privilege scopes.
-   Explain requested access.
-   Handle token expiry/revocation.
-   Disconnect must remove locally retained credentials.

### Local data

-   Store only what is needed.
-   Encrypt particularly sensitive persisted payloads where appropriate.
-   Never store raw auth tokens in plain preferences/database rows.
-   Provide clear-data controls.
-   Define migration strategy.

### Network

-   TLS only.
-   Timeouts on every request.
-   No silent certificate bypass.
-   Validate structured AI/tool responses before execution.
-   Treat model output and remote content as untrusted input.

### Prompt/tool injection

Email text, calendar descriptions, webpages, filenames, and documents
are DATA, not instructions. External content must never gain tool
authority merely by containing text such as "ignore previous
instructions."

### Logging

Production logs must not contain: - tokens - passwords - full email
bodies - private note bodies - raw voice recordings - complete AI
prompts containing sensitive user data

Use structured event/error codes instead.

------------------------------------------------------------------------

## 22. Permissions

Request only when needed.

Potential permissions/capabilities: - notifications - microphone for
push-to-talk - calendar only if using device calendar APIs -
storage/document picker only for explicit file workflows - package
visibility only as narrowly as required

Each permission needs: - feature justification - denial behavior - retry
path - settings path - test coverage

The app must remain useful when optional permissions are denied.

------------------------------------------------------------------------

## 23. Reliability

Every external action returns one of: - success - recoverable failure -
permission required - auth required - unavailable - timeout -
cancelled - ambiguous

Never infer success because a request was dispatched.

Use idempotency where duplicate execution would be harmful.

Persist enough state to safely resume scheduled work after process
death, but do not resume consequential user actions silently.

------------------------------------------------------------------------

## 24. Offline Behavior

Must work offline: - task CRUD - notes - cached event display - local
reminders - local search - settings - local memory - deterministic
commands

May require network: - remote LLM - inbox sync - cloud calendar sync -
other provider integrations

When offline, say so directly rather than failing silently.

------------------------------------------------------------------------

## 25. Testing Requirements

### Unit

-   repositories
-   date/time parsing
-   command routing
-   tool schemas
-   risk policy
-   memory TTL/invalidation
-   agent scheduling logic

### Integration

-   Room migrations
-   reminder scheduling
-   WorkManager jobs
-   OAuth state handling
-   provider failures
-   AI structured-output validation

### UI

-   home
-   task create/complete
-   reminder creation
-   permission denial
-   agent enable/disable
-   loading/error/empty states

### Security

-   secrets absent from repo
-   logs redacted
-   malicious tool arguments rejected
-   prompt-injection fixtures treated as data
-   revoked token behavior
-   local clear-data behavior

### Performance

Instrument: - app cold start - command-to-first-response - local query
latency - DB latency - network latency - AI latency - background job
duration

------------------------------------------------------------------------

## 26. Observability

Local debug builds may expose a developer diagnostics screen:

-   command intent
-   route chosen: local vs AI
-   tool invoked
-   duration
-   result code
-   agent run status

Never show or persist secret values.

Production telemetry should be opt-in/minimal until a privacy design is
finalized.

------------------------------------------------------------------------

## 27. Acceptance Criteria for DOT v0.1

A build is v0.1-complete only if all are true:

-   App launches into functional DOT Home.
-   User can create/complete/edit tasks.
-   User can create a local reminder and receive it.
-   Upcoming events render from local/cache source.
-   Quick notes work.
-   "What do I have today?" is answered without remote AI when data is
    local.
-   Natural-language task/reminder creation works through router +
    fallback.
-   Push-to-talk can feed the same command pipeline.
-   Persistent memory survives app restart.
-   Temporary memory expires/invalidate according to policy.
-   User can inspect/clear memory categories.
-   Inbox Agent can connect, run manually, and perform scheduled
    deferrable checks.
-   Inbox Agent returns concise results.
-   `open_app` works for supported installed apps.
-   Tool registry enforces typed inputs and risk policy.
-   No arbitrary LLM-generated Android commands are executed.
-   First useful response is under 2 seconds for normal local flows.
-   Network operations never block UI.
-   Permission denial does not crash the app.
-   Automated tests cover critical paths.
-   No secrets are committed.
-   README explains setup and known limitations.

------------------------------------------------------------------------

## 28. Build Phases

### Phase A --- Foundation

-   initialize project
-   CI/build
-   Compose navigation/theme
-   Room/DataStore
-   domain models
-   repositories
-   test harness

### Phase B --- Productivity Core

-   Home
-   Tasks
-   Reminders
-   Notes
-   Events/cache
-   notification flow

### Phase C --- Command Runtime

-   intent model
-   deterministic router
-   local parser
-   tool registry
-   risk policy
-   structured results
-   command UI

### Phase D --- AI

-   ModelProvider abstraction
-   remote provider implementation
-   strict structured outputs
-   timeout/fallback
-   direct response formatter

### Phase E --- Voice

-   push-to-talk
-   STT adapter
-   command pipeline integration
-   optional TTS
-   wake-word spike kept separate

### Phase F --- Agents/Integrations

-   agent scheduler
-   agent-run model
-   OAuth
-   Inbox Agent
-   optional calendar read sync

### Phase G --- Android Actions

-   app resolution
-   `open_app`
-   action result observation
-   cancellation
-   experimental interaction adapters behind flags

### Phase H --- Hardening

-   security review
-   performance profiling
-   accessibility/UI QA
-   background behavior tests
-   process-death/reboot tests
-   policy review
-   release candidate

------------------------------------------------------------------------

## 29. Hermes Development Strategy

Hermes should operate as the **orchestrator**, not have multiple agents
edit the same files blindly.

Parallelize independent reasoning/research and isolated modules.
Serialize integration points.

Recommended subagent workstreams:

1.  **Android Foundation Agent**
    -   project skeleton, Compose, DI, navigation, core models
2.  **Storage & Memory Agent**
    -   Room, DataStore, memory repositories, TTL, migrations
3.  **Productivity Agent**
    -   tasks, notes, reminders, events, Home UI
4.  **Command/AI Agent**
    -   router, tool registry, ModelProvider, structured outputs,
        latency instrumentation
5.  **Background/Integration Agent**
    -   WorkManager, Agent framework, Inbox Agent, OAuth/provider
        interfaces
6.  **Security Agent**
    -   threat model, secret handling, policy engine, logging/redaction,
        dependency review
7.  **Test/QA Agent**
    -   test strategy, fixtures, performance gates, end-to-end scenarios
8.  **Android Action Agent**
    -   Intents/PackageManager, supported action adapter, policy
        research boundary

Use worktree isolation for concurrent code-writing subagents. Each
subagent must: - read PRD.md and AGENTS.md - own explicit paths - not
rewrite unrelated code - run relevant tests - commit coherent changes -
return summary + changed files + tests + risks

The parent/orchestrator reviews and merges.

------------------------------------------------------------------------

## 30. Recommended First Parallel Batch

Do this before feature coding:

### Worker A --- Android foundation

Deliver: - Gradle project - Compose shell - navigation - base theme - CI
build

### Worker B --- Data architecture

Deliver: - entities - DAO/repository contracts - Room schema - DataStore
settings contract - migration/test plan

### Worker C --- Security/threat model

Deliver: - threat model - secret/OAuth design - log redaction rules -
permission matrix - policy risks

### Worker D --- Command architecture

Deliver: - intent taxonomy - tool schema - router interfaces - risk
policy interfaces - benchmark plan for \<2 s

### Worker E --- Background agent architecture

Deliver: - Agent interface - scheduler abstraction - WorkManager
design - Inbox Agent provider boundary - failure/retry model

After review, integrate contracts before parallel feature
implementation.

------------------------------------------------------------------------

## 31. Decisions Locked for v0.1

-   Android first.
-   Kotlin + Jetpack Compose.
-   Local-first productivity core.
-   Room for structured persistence.
-   DataStore for small settings.
-   WorkManager for deferrable periodic agents.
-   Push-to-talk required; wake word experimental.
-   Direct/simple response style.
-   \<2 seconds means first useful response; local operations should be
    substantially faster.
-   Persistent memory is local by default.
-   Temporary context is cache with TTL.
-   Controlled typed tools only.
-   API/integration/Intent first.
-   AccessibilityService is not the foundation of autonomous v0.1.
-   Inbox Agent is the first specialized agent.
-   Consequential autonomous actions are out of scope.
-   Provider abstractions should prevent lock-in.

------------------------------------------------------------------------

## 32. Open Questions / Spikes

Hermes should research these as time-boxed spikes rather than block the
foundation:

-   Which STT implementation gives the best latency/privacy trade-off?
-   Is an on-device wake-word path viable under current Android
    restrictions without unacceptable battery/policy cost?
-   Which LLM provider/model meets structured-tool reliability and
    latency targets?
-   Exact reminder implementation across target Android versions and
    permission rules.
-   Gmail/provider OAuth verification implications for intended
    distribution.
-   Whether calendar v0.1 should use device calendar APIs, Google
    Calendar API, or both.
-   How much app/package discovery is necessary for `open_app`.
-   Public distribution strategy given Android/Google Play automation
    policies.

------------------------------------------------------------------------

## 33. Definition of Done for Any Task

A task is not done because code exists.

Done means: - compiles - tests pass - no new lint/static-analysis
blocker - error/empty/loading states handled - no secrets introduced -
relevant PRD acceptance criterion is satisfied - docs updated if
contract/behavior changed - telemetry/logging does not leak sensitive
content - subagent reports limitations instead of hiding them

------------------------------------------------------------------------

## 34. Product Thesis

**DOT is a fast, context-aware personal productivity agent that
remembers locally, organizes the user's day, monitors explicitly
authorized services, and safely performs supported Android actions when
requested.**

The v0.1 goal is not universal phone autonomy. It is to prove that
**local productivity + concise AI + specialized agents + controlled
actions** can feel like one reliable assistant.
