# RELEASE_CHECKLIST.md — DOT v0.1

Actionable and ordered. Every item is either **automated** (a command, with the
expected result) or **manual** (a human check with a defined pass condition).
Items are ordered so that a failure stops the release rather than being
discovered at the end.

Status legend: `[x]` verified in Milestone 7 · `[ ]` outstanding

---

## 0. Blocking defects (release is NOT APPROVED until these clear)

- [ ] **`AppContainer` registers the v1→v2 migration.** `AppContainer.kt:41`
      builds the database with no `.addMigrations(MIGRATION_1_2)` against a
      `version = 2` schema. Any install upgrading from a v0.1 build crashes on
      launch. Tracked as **F-01**.
- [ ] **`BootReceiver` does not open a second un-migrated database.**
      `ReminderScheduler.kt:159`. Tracked as **F-02**.
- [ ] **Upgrade smoke test passes.** See §2, step 3. This is the check that would
      have caught F-01 before shipping.

---

## 1. Build and test gates (automated)

- [x] `./gradlew test` — full suite green. Baseline before Milestone 7 was 341
      tests / 0 failures; the new count is in the Milestone 7 report.
- [x] `:core:database:testDebugUnitTest` green, including the 5 migration tests.
- [x] `:app:testDebugUnitTest --tests 'com.dot.app.hardening.*'` green.
- [ ] `./gradlew assembleDebug` — debug APK builds.
- [ ] `./gradlew assembleRelease` — release APK builds with R8 enabled
      (`isMinifyEnabled = true`, `isShrinkResources = true`).
- [ ] **R8 does not strip Room/Compose/Hilt-free reflection targets.** Run the
      release APK, not just the debug one — `isMinifyEnabled` is off for debug, so
      a green debug run proves nothing about release.
- [ ] No new compiler warnings in `:app` or `:core:database`. Warnings are not
      errors here, but a wall of them hides the one that matters.

## 2. Migration and data safety (automated + manual)

- [x] MIGRATION_1_2 exists, is registered as a `Migration` object, and is
      exported in `DOT_MIGRATIONS`.
- [x] Migration test creates a v1 database, inserts representative rows in all
      seven tables, migrates, and asserts data survival.
- [x] Migration test asserts the new column and the new index.
- [x] Migration test asserts Room **refuses** rather than wiping when the
      migration is not registered.
- [x] `destructiveMigration()` / `fallbackToDestructiveMigration()` appears
      **nowhere** in the tree. Verify: `grep -rn "destructiveMigration" --include=*.kt .`
      must return nothing outside this doc.
- [ ] **Manual upgrade test on a real device/emulator:**
  1. Install the previous v0.1 build.
  2. Create ≥1 task, ≥1 note, ≥1 reminder, ≥1 memory item.
  3. Install the release build over it (do **not** uninstall — that wipes data and
     hides the bug).
  4. Launch. Must not crash.
  5. Confirm every item is still present.
  6. Confirm the notes screen shows nothing pinned (the `pinned = 0` default).
  7. Confirm a reminder set for a future time still fires.

## 3. Security gates

Full detail in `SECURITY_REVIEW.md`. The release-blocking subset:

- [x] Secret scan: `git ls-files` shows no secret-shaped filename tracked.
- [x] Secret scan: grep for `AIza…`, `sk-…`, `ya29.…` across tracked sources
      returns exactly one hit — `DiagnosticsRecorderTest.kt:76`, a deliberate fake
      fixture proving the redactor fires.
- [x] `local-secrets.properties` is gitignored and untracked.
- [ ] **Run the secret scan in CI**, not by hand (F-09). Until then this is a
      habit, not a gate.
- [ ] `network_security_config.xml` added with `cleartextTrafficPermitted="false"`
      and referenced from the manifest (F-05). `targetSdk = 35` denies cleartext
      by default, so this is defence-in-depth — but the posture is currently
      implicit.
- [ ] **Exported components reviewed** (SECURITY.md release checklist). Current
      state: `MainActivity` exported (required, has a LAUNCHER filter),
      `BootReceiver` exported (required for `BOOT_COMPLETED`),
      `ReminderReceiver` **not** exported (correct). Re-verify after any manifest
      edit.
- [ ] **No debug endpoints reachable in release.** `BuildConfig.GEMINI_API_KEY`
      is baked into *both* build types (`app/build.gradle.kts:45,53`) — see F-12
      below.
- [ ] Dependency scan reviewed (SECURITY.md release checklist). No automated
      dependency scanner is wired up.
- [ ] Logs reviewed on a release build. Confirm `dot.llm.debug=1` is not set and
      that no note body appears in logcat during a normal session.
- [ ] Prompt-injection fixtures pass: `PromptInjectionFixtureTest`,
      `ModelPlanParserTest.prompt injection attempting tool invention is rejected`.
- [ ] Permission denial tested on device: deny `POST_NOTIFICATIONS`, create a
      reminder, confirm the app does not crash and the user is not left with a
      silent failure.
- [ ] Clear-data tested: Settings → "Clear all local memory", confirm every
      namespace is empty afterwards.

### F-12 — release-key exposure (raised during this review, not yet fixed)

`app/build.gradle.kts` bakes `GEMINI_API_KEY` into `BuildConfig` for **both**
`debug` and `release`. The key is gitignored, which is correct and is why the
secret scan is clean — but any user who unpacks the release APK can read the key
out of `BuildConfig`. For a real release the key must come from a Play-upload
time mechanism (Play App Signing secret / app-bound key), or the Gemini call must
move behind a backend proxy that holds the key.

For v0.1 with an internal test audience this is acceptable **only if the key is
rotated before any public distribution**. Track it.

## 4. Functional acceptance (PRD 27)

- [ ] App launches into a functional Home.
- [ ] Create / complete / edit a task.
- [ ] Create a local reminder and **receive it** (device only — Robolectric does
      not deliver alarms).
- [ ] Upcoming events render from the local cache.
- [ ] Quick notes work.
- [ ] "What do I have today?" is answered **without** remote AI.
- [ ] Natural-language task/reminder creation works via router + fallback.
- [ ] Push-to-talk feeds the same command pipeline.
- [ ] Persistent memory survives app restart.
- [ ] Temporary memory expires / invalidates per policy.
- [ ] User can inspect and clear memory categories.
- [ ] Inbox agent can connect, run manually, and perform scheduled checks.
- [ ] Inbox agent returns concise results (never a full email body — F-07).

## 5. Offline verification (PRD 24)

- [x] Offline-required surface covered by tests (task CRUD, notes, cached events,
      reminders, search, settings, memory, deterministic commands).
- [x] Network-dependent paths degrade to typed failures, never silent ones.
- [ ] **Manual: enable airplane mode** and repeat the §4 functional list. The
      automated tests prove the logic; only this proves the device behaviour.
- [ ] Confirm the UI says something honest when offline — not a spinner that
      never resolves.

## 6. Performance (PRD 25)

Budgets are enforced by `PerformanceBudgetTest`. On-device numbers are **not**
covered by any automated gate.

- [x] Command routing p50/p95 measured against a 150 ms budget.
- [x] Local DB / Today aggregation measured over 750 rows.
- [x] Tool dispatch measured, including the rejected path.
- [ ] **Manual on-device:** measure cold start and command-to-first-response on a
      mid-range device. The JVM numbers in the Milestone 7 report are not a
      substitute — there is no macrobenchmark module.
- [ ] Confirm the v2 `dueAtEpochMs` index is actually used. Verify with
      `EXPLAIN QUERY PLAN SELECT * FROM tasks ORDER BY dueAtEpochMs IS NULL, dueAtEpochMs ASC;`
      against a populated DB — it should mention the index, not `SCAN tasks`.

## 7. Diagnostics (PRD 26)

- [x] Recorder implements exactly the six permitted fields.
- [x] Recorder redacts everything, is bounded, and is JVM-testable.
- [ ] **Wire the recorder into `CommandRuntime`** — it is currently unused code
      (F-08). Copy-pasteable wiring is in the Milestone 7 report.
- [ ] Confirm the diagnostics surface is debug-only and absent from release.

## 8. Process death and reboot (PRD 23)

- [x] Reminder re-armed identically after process death.
- [x] Pending confirmation never auto-executes on restore.
- [x] Orphaned `RUNNING` agent runs reconciled to a terminal state.
- [x] Reboot reschedule is idempotent and handles past-due sanely.
- [ ] **Manual: force-stop the app with a reminder pending, reopen, confirm the
      reminder still fires.** Robolectric cannot deliver alarms, so the decision is
      tested and the delivery is not.
- [ ] **Manual: `adb reboot` with a reminder pending, confirm it still fires.**
- [ ] Wire `BootReceiver` to `ReminderRecovery.plan` so missed reminders are
      surfaced rather than silently dropped (F-03).

## 9. Sign-off

- [ ] Every `[ ]` above is either done or explicitly waived in writing.
- [ ] `SECURITY_REVIEW.md` severity-HIGH items are closed.
- [ ] Someone other than the author has run §2's manual upgrade test.
- [ ] The Gemini key is rotated if any build with an embedded key was ever
      distributed (F-12).

**Current status: NOT APPROVED.** Two HIGH findings (F-01, F-02) are open and are
launch-blocking on upgrade.
