# TASKS.md --- DOT v0.1 Build Breakdown

## Milestone 0 --- Architecture contract

-   [ ] Project skeleton
-   [ ] Module/package boundaries
-   [ ] Domain entities
-   [ ] Repository contracts
-   [ ] Tool contracts
-   [ ] Agent contracts
-   [ ] Risk policy contract
-   [ ] Security threat model
-   [ ] CI/test baseline

## Milestone 1 --- Local productivity

-   [ ] Room database
-   [ ] DataStore settings
-   [ ] Tasks CRUD
-   [ ] Notes CRUD
-   [ ] Reminder scheduling
-   [ ] Upcoming/event cache
-   [ ] Home aggregation
-   [ ] notification permission UX
-   [ ] local search

## Milestone 2 --- Command runtime

-   [ ] Intent taxonomy
-   [ ] deterministic fast router
-   [ ] date/time parser
-   [ ] tool registry
-   [ ] structured result model
-   [ ] direct response formatter
-   [ ] risk policy
-   [ ] cancellation/timeouts
-   [ ] latency instrumentation

## Milestone 3 --- AI fallback

-   [ ] ModelProvider interface
-   [ ] one provider implementation
-   [ ] strict structured response schema
-   [ ] validation
-   [ ] timeout/fallback
-   [ ] ambiguity handling
-   [ ] prompt-injection tests

## Milestone 4 --- Voice

-   [ ] push-to-talk UI
-   [ ] microphone permission
-   [ ] STT adapter
-   [ ] route transcript through same command runtime
-   [ ] optional TTS
-   [ ] wake-word research spike

## Milestone 5 --- Background agents

-   [ ] AgentDefinition/AgentRun persistence
-   [ ] Agent scheduler
-   [ ] WorkManager integration
-   [ ] run-now flow
-   [ ] enable/disable UI
-   [ ] retry/backoff
-   [ ] Inbox Agent provider interface
-   [ ] OAuth
-   [ ] inbox read/summarize
-   [ ] compact local cache
-   [ ] important-result notification

## Milestone 6 --- Android action layer

-   [ ] installed-app resolver
-   [ ] open_app tool
-   [ ] action result verification
-   [ ] failure handling
-   [ ] experimental adapters isolated behind flags

## Milestone 7 --- Hardening

-   [ ] process-death tests
-   [ ] reboot/reminder tests
-   [ ] offline tests
-   [ ] OAuth revoke tests
-   [ ] DB migration tests
-   [ ] security review
-   [ ] performance benchmark
-   [ ] accessibility/UI QA
-   [ ] README/setup
-   [ ] release checklist

## MVP demo script

1.  Launch DOT Home.
2.  Type "Remind me to submit my project at 8 PM."
3.  DOT responds directly and reminder appears.
4.  Ask "What do I have today?"
5.  DOT answers from local data without remote AI.
6.  Add a task by voice.
7.  Restart app; data remains.
8.  Run Inbox Agent manually.
9.  Show concise inbox result.
10. Ask DOT to open a supported installed app.
11. Demonstrate permission denial/error without crash.
12. Show latency diagnostics proving local first-response target.
