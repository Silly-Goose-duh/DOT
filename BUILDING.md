# Building DOT

Everything needed to build this project from a clean machine. If you are setting up
after a gap, this file is the source of truth — not the chat history.

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 or newer | Verified on 21.0.12. The Gradle files target 17. |
| Android SDK platform | 35 | `compileSdk = 35`, `targetSdk = 35` |
| Android build-tools | 35.0.0 | Required by AGP 8.7.3 |
| Gradle | 8.11+ | Wrapper is committed — use `./gradlew`, no manual install |

AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.11.00.

## 1. Android SDK

Install command-line tools, then the packages:

```bash
# from the SDK's cmdline-tools/latest/bin
sdkmanager --sdk_root=<SDK> --licenses
sdkmanager --sdk_root=<SDK> platform-tools "platforms;android-35" "build-tools;35.0.0"
```

Disk note: the SDK plus Gradle caches need roughly 8–10 GB. If `C:` is tight, put
the SDK on another drive — this build used `D:\Android\Sdk` because `C:` had only
13 GB free.

## 2. Point the build at your SDK

`local.properties` is machine-specific and **intentionally gitignored**. Create it
in the repo root:

```properties
sdk.dir=D\:\\Android\\Sdk
```

On Windows the backslashes must be escaped and the colon escaped too. The
alternative is the `ANDROID_SDK_ROOT` environment variable, which needs no file.

## 3. Build and test

```bash
./gradlew test                 # all unit tests
./gradlew :app:assembleDebug   # the APK
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk` (~11 MB,
`com.dot.app`, minSdk 26, targetSdk 35, debug-signed).

Single module:

```bash
./gradlew :core:data:testDebugUnitTest
./gradlew :agent:router:testDebugUnitTest
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Modules

```
:app          Compose UI, DI root, CommandRuntime, AlarmManager reminders
:core:model   domain entities, ActionResult/ActionOutcome, TodayAggregator, ports
:core:common  LogRedactor, structured log events
:core:database Room entities + DAOs (schema v1)
:core:data    Room-backed implementations of the ports
:agent:router deterministic intent routing, date parsing, response formatting
:agent:policy PolicyEngine — risk and confirmation, independent of any model
:agent:tools  typed tool registry, input guards, app allowlist
:agent:memory persistent memory vs TTL cache
```

Dependencies point inward: `:app` → `agent:*` → `core:model`. Repository ports live
in `core:model` so `:core:data` can implement them without depending on the agent
layer.

## Testing

115 tests, all JVM unit tests or Robolectric — no device needed.

- `:core:data` and `:app` use Robolectric with an in-memory Room database.
- `:app` builds `AppContainer` free — `CommandRuntime` takes its collaborators as
  constructor parameters, so tests use fakes (e.g. `FakeScheduler`) instead.
- Nothing needs `allowMainThreadQueries` in production code.

## Things that will bite you

**Gradle wrapper validation.** `gradle/wrapper/gradle-wrapper.properties` has
`validateDistributionUrl=false`. `services.gradle.org` returns a 307 redirect
that Gradle's own URL check rejects, and `gradle wrapper` fails outright while
validating. The distribution still downloads correctly through the wrapper.

**Compose on Kotlin 2.0.** Any module with `buildFeatures { compose = true }` must
apply `libs.plugins.compose.compiler`. Without it, configuration fails with
"the Compose Compiler Gradle plugin is required".

**Room on the compile classpath.** `:core:database` declares Room as `api`, not
`implementation`. `DotDatabase` extends `RoomDatabase`, so any module that
constructs one needs Room types visible at compile time.

**Shell.** In Git Bash on Windows, run Gradle through `gradlew.bat`, not
`gradlew` — the POSIX path translation is not applied to native tool arguments.

## Verification status

Verified by unit tests and Robolectric only. No device or emulator was available,
so real notification delivery, exact-alarm timing, Compose rendering and
PackageManager resolution are all still unverified. See REPORT.txt.
