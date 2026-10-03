# HARDENING.md — DOT v0.1 (Milestone 7)

What Milestone 7 made verifiable, and — more importantly — what it did not.

The organising principle: a property is only "covered" if a test exists that would
fail when the property is broken. Everything in the *Not covered* section is a
real gap, stated plainly rather than papered over with a passing test that proves
nothing.

---

## 1. What is now covered by a test

### Room v1 → v2 migration

`core/database/src/test/java/com/dot/core/database/MigrationV1ToV2Test.kt` — 5 tests.

| Property | Test |
|---|---|
| Every v1 row survives, in every table | `v1 rows survive the migration to v2` |
| Column *values* survive, not just counts (nulls stay null) | `task column values are unchanged, not just row counts` |
| New `notes.pinned` column exists and defaults to `false` on migrated rows | `new pinned column exists and defaults to false for migrated rows` |
| New `index_tasks_dueAtEpochMs` exists; Room's own schema validation accepts the result | `new tasks index exists and Room schema validation accepts it` |
| Without the migration registered, Room **refuses** rather than wiping data | `without the migration registered Room refuses rather than wiping data` |

**The migration test was verified to actually test the migration.** The
`CREATE INDEX` statement was commented out and the suite re-run: `new tasks index
exists and Room schema validation accepts it` failed with
`expected to contain: index_tasks_dueAtEpochMs but was: [sqlite_autoindex_tasks_1]`.
The statement was restored and the suite returned to green. A test that cannot go
red proves nothing, so this was worth the extra build cycle.

**Deviation, stated plainly:** this does **not** use
`androidx.room.testing.MigrationTestHelper`. `MigrationTestHelper` needs the
exported schema JSON in the module's *assets* folder, and Milestone 7 was not
permitted to add a `src/main/assets` tree or edit `build.gradle.kts` to set
`room.schemaLocation`. The test instead builds the v1 database from the v1 DDL
verbatim and opens it through the real `Room.databaseBuilder`. Room's own
`RoomOpenHelper.onUpgrade` runs the migration and Room's own `onValidateSchema`
runs afterwards, so the coverage is equivalent for the properties that matter.
Swap in `MigrationTestHelper` once the build file can be edited; the assertions
carry over unchanged.

### Process-death recovery

`app/src/test/java/com/dot/app/hardening/ProcessDeathRecoveryTest.kt` — 11 tests.

Modelled the only way that is meaningful on a JVM: the database outlives the
object graph. Each test closes the DB, drops every in-memory reference, reopens,
and asserts what the surviving state implies.

- A scheduled reminder round-trips **identically** — same id, same trigger
  instant, so the re-armed alarm is the *same* alarm and the old `PendingIntent`
  is not orphaned.
- A **cancelled** reminder is not resurrected, even when its timestamp is still
  hours in the future.
- A reminder whose moment passed while the process was dead is reported `Missed`,
  not armed stale and not silently dropped.
- A **pending confirmation is never auto-executed.** `restorePendingAction` has no
  branch that runs the action; `PendingActionRestore.ReaskRequired` exposes no
  execute affordance, and a structural assertion on the type's methods fails if
  one is ever added.
- A `RUNNING` `AgentRun` left by a kill is reconciled to `CANCELLED` with
  `errorCode = "process_died"`. It is deliberately **not** `FAILURE`: the work did
  not fail on its own merits, and recording it as a failure would poison the
  agent's success rate.
- Reconciliation is idempotent; a genuinely-fresh run is left alone; a terminal run
  is never rewritten; a run with a future start time is not cancelled.
- A failure in the agent half does not cost the reminder half its re-arms.

### Reboot / reminder reschedule

`app/src/test/java/com/dot/app/hardening/BootRescheduleTest.kt` — 14 tests.

The *decision* was extracted from `BootReceiver` into `ReminderRecovery`
(`app/src/main/java/com/dot/app/diagnostics/ProcessRecovery.kt`) because the
receiver itself opens a real database and calls `AlarmManager`. The decision is
now a pure function of `(rows, now)`, tested against a real SQLite file.

- Future reminders re-armed, all of them, in chronological order.
- `FIRED` / `CANCELLED` / `DONE` never re-armed. `CANCELLED` wins over a future
  timestamp — otherwise cancelling then rebooting would resurrect it.
- Long-past → `Missed`. Just-past (inside 15 min) → still re-armed, because
  `AlarmManager` takes an absolute `RTC_WAKEUP` time and a trigger two minutes ago
  still fires promptly.
- The threshold boundary is asserted exactly, not approximately.
- A mixed table splits into three buckets with **no overlap and no dropped rows**.
- **Idempotency:** planning twice yields an identical plan; re-arming does not
  mutate the database; the plan survives a real close-and-reopen cycle.

### Offline behaviour (PRD 24)

`app/src/test/java/com/dot/app/hardening/OfflineBehaviorTest.kt` — 13 tests.

Every offline-required capability runs against a real SQLite file with
`aiFallback = null` — the honest offline state, matching `AppContainer` today:
task CRUD, notes, cached event display, local reminders, local search, local
memory (including across a restart), and deterministic commands.

The negative half, which is where the real risk lives:

- No provider → typed `model_not_configured`, never empty success.
- Network error → `model_unavailable`. Throwing provider → `model_unavailable`.
- Revoked/expired credential → `model_auth_required`, explicitly **not** a
  retryable "try again".
- Timeout → `model_timeout`, not "unavailable".
- No offline path returns an empty result that reads as success.

### Diagnostics (PRD 26)

`app/src/test/java/com/dot/app/hardening/DiagnosticsRecorderTest.kt` — 14 tests.

The recorder's field set *is* the PRD 26 list — intent, route, tool, duration,
result code, agent run status — so there is no field a caller could stuff a note
body or a token into. On top of that, every string field still passes through
`LogRedactor`: bearer tokens, `ya29.*` Google tokens and `api_key=…` assignments
are redacted, fields are truncated, the ring buffer is bounded at 200 and evicts
oldest, and the log sink only ever sees already-redacted lines.

### Performance (PRD 25)

`app/src/test/java/com/dot/app/hardening/PerformanceBudgetTest.kt` — 7 tests.

Measured numbers are printed to the test log with a `PERF:` prefix and quoted in
the Milestone 7 report. Budgets are deliberately loose (10–100× the expected
cost): a budget that only passes on an idle machine is a flaky test, not a guard.

---

## 2. What is NOT covered, and why

### Cannot be verified without a device or emulator

| Gap | Why a JVM test would be theatre |
|---|---|
| **OAuth keystore-backed encryption** | Robolectric has no Android Keystore. `security-crypto` silently falls back to a software key, so a test asserting "the token is encrypted on disk by the keystore" would pass while proving nothing about a real device. The *store's* behaviour around it is tested; the cipher is not. |
| **Real keystore deletion on disconnect** | Same reason. Needs a real keystore to observe deletion — and per F-13, the current code would delete the wrong key anyway. |
| **Exact alarms actually firing** | `AlarmManager` under Robolectric does not deliver. `ReminderRecovery` is tested; the *arming* is not. |
| **`BOOT_COMPLETED` delivery** | Requires a reboot. The reschedule *logic* is tested; the broadcast is not. |
| **Notification permission denial at runtime** | `ReminderReceiver` catches `SecurityException` (`ReminderScheduler.kt:140`) but that catch is not exercised. |
| **Cold start** | JVM timing says nothing about ART/JIT warmup, class loading, or real flash I/O. |
| **On-device frame/UI latency** | No macrobenchmark module (not permitted this milestone). |
| **TLS behaviour under a real proxy/MITM** | `GeminiProvider` fails closed on `SSLException`, but no test presents a hostile certificate. |

### Covered only by documentation, not by a test

- **SECURITY.md rule 14, "no infinite agent loops."** `ToolRegistry`'s retry bound
  is `while (attempt <= tool.maxRetries)` and each tool declares its own value.
  Nothing clamps it. A tool declaring `maxRetries = 1000` would be retried 1001
  times — a duplicate-execution bug, not just a latency one. Tracked as F-06.
- **`GeminiProvider.maxRetries`** is a constructor parameter with no clamp. Tracked
  as F-04.
- **SECURITY.md rule 9, "do not persist full email bodies."** Holds by absence —
  no email integration ships. It becomes a real obligation the moment the inbox
  agent fetches content. Tracked as F-07.
- **The repo-wide secret scan.** Run by hand for this review; not a CI gate.
  Tracked as F-09.
- **Data minimisation in practice** (rule 8). The *mechanisms* are tested
  (TTLs, history trim, clear-all); whether an agent stores more than it needs is
  not, because no agent ships yet.

### OAuth: now covered against the real store, with two named gaps

`app/src/test/java/com/dot/app/hardening/OAuthRevokeContractTest.kt` — 18 tests.

`:agent:agents` landed mid-milestone with a real `EncryptedPrefsOAuthTokenStore`
built around an injectable `TokenCipher` seam, and `:app`'s `testImplementation`
extends `implementation`, so the suite drives the **actual production class** —
no local double. Covered: valid grant round-trips; expiry is classified
`AuthRequired` with reason `token_expired` and never as an empty success; the
outcome maps to `AUTH_REQUIRED` (never `UNAVAILABLE`, which would read as "try
again later"); `revoke` removes the prefs entry and the raw key; revoke is
idempotent; reconnecting works; revoking one account leaves another's prefs entry
intact; corrupt ciphertext degrades to null rather than crashing; an
over-privileged or send-capable scope is refused at `save()`.

Two gaps remain, both stated rather than papered over:

1. **`AndroidKeystoreCipher` is never executed.** Robolectric has no Android
   Keystore, so a test claiming hardware-backed encryption would pass while
   proving nothing. The suite injects a test cipher; one test additionally uses a
   real in-process AES-GCM key to show the stored blob is ciphertext, but an
   in-process key is not a keystore.
2. **Keystore deletion on revoke is unverified** — and reading the source turned
   up a real bug while checking: `revoke()` deletes the *fixed* alias
   `dot.oauth.gcm.v1`, which would corrupt every other account's grant. Invisible
   today (one account), a data-loss bug the moment there are two. Tracked as
   **F-13**.

Worth flagging plainly: the test `revoking one account leaves another connected`
**passes for the wrong reason** — the injected cipher makes the `as?` cast null,
so the key-deletion line never runs. It proves prefs isolation, not key isolation.
A green result there is not evidence against F-13.

Also still uncovered: a live server-side revoke (token stored locally but no
longer honoured by Google) cannot be produced without network access.

---

## 3. Known blockers at time of writing

Two defects make the current tree **unlaunchable on upgrade**. Both are one-line
fixes, both are listed copy-pasteable in the parent report, and both are the
*correct* failure mode — a loud crash rather than silent data loss.

1. `AppContainer.kt:41` — `databaseBuilder` with no `.addMigrations(MIGRATION_1_2)`
   against a now-`version = 2` schema. Any existing install crashes on launch.
2. `ReminderScheduler.kt:159` — same omission on the boot path, plus it opens a
   second `RoomDatabase` instance against the same file.

Until both land, `SECURITY_REVIEW.md` records the release as **NOT APPROVED**.
