# ARCHITECTURE.md --- DOT v0.1

## Core rule

Use the least expensive, fastest, safest execution path:

1.  local deterministic operation
2.  local structured parser
3.  official integration/API
4.  Android Intent/platform API
5.  remote AI reasoning
6.  concise user clarification

AI is not required for operations that already have deterministic local
answers.

## Runtime components

### CommandRouter

Chooses local fast path or AI path.

### ToolRegistry

Contains typed, registered capabilities. Model output cannot bypass it.

### PolicyEngine

Determines risk and confirmation independent of model preference.

### AgentRuntime

Runs bounded plan/action/observe cycles for supported complex tasks.

### MemoryRepository

Separates persistent local memory from expiring cache.

### AgentScheduler

Runs deferrable background agents using Android-appropriate scheduling.

### Provider interfaces

ModelProvider, EmailProvider, CalendarProvider, SpeechProvider.

## Data flow

    Input
      -> CommandRouter
          -> LocalHandler -> Repository -> Result
          OR
          -> ModelProvider -> Validated ToolCall
              -> PolicyEngine
              -> ToolRegistry
              -> Result
      -> DirectResponseFormatter
      -> UI/Voice

## Background flow

    Scheduler
      -> Specialized Agent
      -> Provider
      -> classify/summarize
      -> local compact cache
      -> optional notification

## Failure philosophy

Failures are typed. No silent success. No unbounded retries. No model
guess that an external side effect succeeded.

## Performance

Instrument every stage with monotonic timings. Optimize routing before
model size. Cache only with explicit invalidation/TTL.
