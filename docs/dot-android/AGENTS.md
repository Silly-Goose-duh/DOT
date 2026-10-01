# AGENTS.md --- Hermes Build Instructions for DOT

## Mission

Build DOT v0.1 according to `PRD.md`. The PRD is the product source of
truth. Do not silently expand scope.

## Operating model

Use the parent Hermes agent as orchestrator. Delegate independent work
to subagents in parallel. Prefer isolated worktrees for concurrent
code-writing agents.

Before editing: 1. Read `PRD.md`. 2. Identify the acceptance criterion
being implemented. 3. Inspect existing contracts/tests. 4. Declare file
ownership for the subtask.

## Parallelization

Good parallel tasks: - Android shell - database/memory - command/tool
contracts - background agent framework - security review - tests -
provider adapters behind stable interfaces

Do not parallel-edit the same files unless worktree isolation is enabled
and the parent will reconcile intentionally.

## Suggested first batch

-   Foundation agent
-   Storage/memory agent
-   Security agent
-   Command architecture agent
-   Background-agent architecture agent

The parent reviews their contracts before starting broad feature
implementation.

## Coding rules

-   Kotlin first.
-   Jetpack Compose UI.
-   Coroutines/Flow for async work.
-   Never block main thread with DB/network/AI.
-   Prefer interfaces at provider boundaries.
-   Typed tool inputs/outputs only.
-   No arbitrary model-generated Android commands.
-   Keep direct-response UX.
-   Local fast path before remote AI.
-   Handle permission denial and network failure explicitly.
-   No secret values in source, logs, tests, screenshots, or fixtures.

## Subagent completion contract

Every coding subagent returns: - summary - files changed - tests run +
results - acceptance criteria covered - unresolved risks -
migration/config steps

Run tests before claiming completion.

## Integration rule

The orchestrator owns: - cross-module contract changes -
dependency/version decisions - final merges - schema migrations - PRD
deviations

If implementation conflicts with PRD, stop and surface the conflict
rather than silently changing product behavior.

## Scope guard

Do not build universal autonomous Android control in v0.1. Keep
AccessibilityService experiments behind a feature flag and separate
adapter. Official APIs, integrations, and Intents are preferred.

## Security gate

Any code touching OAuth, tokens, local sensitive data, external content,
AI tool execution, or permissions must be reviewed against `SECURITY.md`
before merge.
