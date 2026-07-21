# Odin shell-execution ABI-shaping (pre-1.0.0 freeze) — design

**Date:** 2026-07-22
**Repo:** Odin (`com.trinadhthatakula:odin`), branch `feat/shell-modernization` (off `main`).
**Status:** Approved design.
**Source analysis:** `docs/audits/2026-07-21-odin-shell-execution-modernization.md` in the Thor repo (Steps A–H; this spec = Steps A/B/C + a full `asFlow` fix + docs + an agent skill).

## Context

Odin is a public Android library (a Kotlin-first root shell + `RootService` IPC, inspired by libsu) that is about to freeze its public API at 1.0.0. This phase reshapes the **ktx / coroutine public surface** into the shape we want frozen, hardens the module against ABI drift, and documents the result — **without** rewriting the internal execution engine.

Key facts from the source map:
- `Shell.Result` already exists as a public `abstract class` carrying `code: Int`, `out`/`err` (`MutableList<String?>`), `isSuccess`, `JOB_NOT_EXECUTED = -1`; `Shell.Job.await()` already returns it. The lossy path is only `ShellRepository.runCommand/runCommands`, which return `kotlin.Result<List<String>>` (dropping code + stderr).
- `RealShellRepository.runInternal` swallows `CancellationException` (`isRootGranted` already rethrows).
- `Shell.ResultCallback` / `Shell.GetShellCallback` are plain `interface`s (SAM-eligible).
- Four `internal/`-package declarations are implicitly `public` (`BuilderImpl`, `MainShell`, `ShellImpl`, `UiThreadHandler`).

## Goal

Freeze a clean, documented, coroutine-first public API for Odin 1.0.0: a lossless `exec()`/`ShellResult`, a lossless `asFlow()`/`ShellLine`, deprecated compatibility shims, module-wide `explicitApi()` + binary-compatibility-validator, full KDoc + usage docs, and an `odin` agent skill for consuming the library in apps.

## Locked decisions (from brainstorming)

- **`exec()` error model:** never-throws — a transport failure maps to `code = ShellResult.JOB_NOT_EXECUTED (-1)`; `CancellationException` still propagates (structured concurrency).
- **Result types:** a new idiomatic `ShellResult` data class **and** a surface-level modernization of `Shell.Result` (its guts stay for the engine phase).
- **`explicitApi()`:** strict, module-wide, plus binary-compatibility-validator (checked-in api dump).
- **`asFlow`:** fixed properly **now** (lossless), not deferred/gated.
- **Docs + skill:** required deliverables of this phase.

## Components

### A. Free correctness fixes
1. `RealShellRepository.runInternal` — rethrow `CancellationException` at the top of its `catch (e: Exception)` (mirror `isRootGranted`) before mapping to `Result.failure`.
2. `Shell.Job.await()` (`ktx/ShellExtensions.kt`) — resume via `submit(null, cb)` (the two-arg overload, executor `null`) so the continuation resumes on the completing worker thread instead of a `Dispatchers.Main` round-trip.

### B. `ShellResult` + `exec()`, and `Shell.Result` modernization
- **New public ktx type** (`ktx/`):
  ```kotlin
  data class ShellResult(
      val code: Int,
      val stdout: List<String>,
      val stderr: List<String>,
  ) {
      val isSuccess: Boolean get() = code == 0
      companion object { const val JOB_NOT_EXECUTED: Int = -1 }
  }
  ```
  Immutable, non-null elements, destructurable. This is the modern result callers get from `exec()`.
- **`exec()` on `ShellRepository`:**
  ```kotlin
  suspend fun exec(vararg commands: String): ShellResult
  ```
  Runs the commands as one job (`Shell.Job.await()`), maps the resulting `Shell.Result` → `ShellResult` (`code`, non-null `stdout`/`stderr`). Never throws for shell/command failure: a dead shell / `NoShellException` / broken pipe → `ShellResult(JOB_NOT_EXECUTED, emptyList(), listOf(<message>))`. `CancellationException` is rethrown. `exec(vararg)` returns ONE `ShellResult` (combined output, last-command exit code) — that is the shell's existing job model; per-command results are a non-goal.
- **Deprecate the lossy shims:** `runCommand`/`runCommands` become `@Deprecated(message, ReplaceWith("exec(...)"))` and delegate to `exec()`, re-wrapping to the old `kotlin.Result<List<String>>` with identical semantics (`Result.success(stdout)` iff `code == 0`, else `Result.failure(IOException("Command failed with code $code: <stderr>"))`).
- **`Shell.Result` surface modernization (no engine change):** add concrete non-null read accessors on the abstract class:
  ```kotlin
  val stdout: List<String> get() = out.filterNotNull()
  val stderr: List<String> get() = err.filterNotNull()
  ```
  keep `out`/`err` (Java-interop + the engine's mutable fill), `code`, `isSuccess`, `JOB_NOT_EXECUTED`. Deep immutability of `out`/`err` is engine work → deferred.

### C. `asFlow` — lossless, fixed now
- **New public type** (`ktx/`): `data class ShellLine(val text: String, val isError: Boolean)` (stdout vs stderr per line).
- **Reshape:** `fun Shell.Job.asFlow(): Flow<ShellLine>` (was `Flow<String>`; zero consumers, pre-1.0.0, so free to change).
- **Losslessness:** back it with `callbackFlow` + `Channel.UNLIMITED` (the root pipe must drain continuously — never backpressure the drain), wire **both** streams via `to(outList, errList)` with `CallbackList`-backed emission tagged `isError`, use a **checked** send (no ignored `trySend` → no dropped lines), and `close(cause)` on transport failure so a failed stream surfaces its error.
- **Cancellation contract (documented):** `awaitClose` closes the channel so collection stops and references are released; the engine's gobbler keeps draining the pipe (pipe stays healthy — no wedge/leak), so an in-flight command **completes in the background** on cancel. Mid-stream interruption of a running command (and dedicated-shell isolation for non-terminating streams like `logcat`) needs the engine abandon-hook / dedicated-shell work and remains engine-phase. This contract is stated in the `asFlow` KDoc.

### D. `fun interface` conversions
`Shell.ResultCallback` (single `onResult(Result)`) and `Shell.GetShellCallback` (abstract `onShell` + default `onShellDied`) → `fun interface` (both binary-compatible SAM conversions). Collapse the ktx object-expressions to lambdas.

### E. explicitApi strict + binary-compatibility-validator (module-wide freeze)
- Enable `explicitApi()` strict in `odin/build.gradle.kts`.
- Resolve the four implicit-public leaks: `BuilderImpl`, `MainShell`, `ShellImpl` → `internal` (only ever surfaced as their public supertypes). **`UiThreadHandler`** is used as a default-arg value in `CallbackList`'s `protected` ctor and `Shell.Job.submit` — resolve so hiding it does not expose an internal type in a public signature (make the executor-taking `CallbackList` ctor `internal`, exposing only the base-list ctor publicly; verify `Shell.Job.submit`'s default still compiles).
- Annotate every remaining public declaration's visibility + return type module-wide (`Shell.kt`, `ipc/RootService.kt`, `CallbackList.kt`, both `ShellUtils` families, `NoShellException`, `ktx/`, `utils/`). Dedupe the duplicated `isValidOutput`/`escapeForShell` across the two `ShellUtils` files (keep one public definition).
- Add the `org.jetbrains.kotlinx.binary-compatibility-validator` plugin; generate + check in `odin/api/odin.api`; run `apiCheck` in `pr-ci.yml`. The api dump is the frozen 1.0.0 contract; CI fails on drift.

### F. Documentation
- **KDoc** on every new/changed public symbol: `ShellResult` (+ `JOB_NOT_EXECUTED` semantics), `ShellLine`, `exec()` (the never-throws model + `vararg` = one combined result), the `@Deprecated` shims (with `ReplaceWith`), `Shell.Result`'s new `stdout`/`stderr`, `asFlow()` (losslessness + the in-flight-drain-on-cancel contract), and the two `fun interface`s. KDoc is published via the existing javadoc jar (`publishJavadocJar = true`).
- **User-facing usage docs:** expand `README.md` with a concise **Usage** section (add-dep + the core coroutine calls) and add `docs/USAGE.md` covering: `getShellAwait`/`isRootGranted`, `exec()`/`ShellResult`, `asFlow()`/`ShellLine` (incl. the cancel contract), `fastCmd` helpers, the `RootService` pattern, and a **`runCommand`/`runCommands` → `exec()` migration** subsection.

### G. `odin` agent skill
- **Canonical source:** `Odin/.claude/skills/odin/SKILL.md` (mirrors `Asgard/.claude/skills/asgard-ui/SKILL.md`).
- **Content:** a consumer-integration skill for building apps *with* Odin — frontmatter (`name: odin` + a trigger description mentioning `com.trinadhthatakula:odin`, root shell, RootService, `exec`, `ShellResult`, `asFlow`), then: add-the-dependency, core rules (root-shell/persistent-pipe model, coroutine-first API, Java-interop, minSdk 24), the API reference (`getShellAwait`, `isRootGranted`, `exec`/`ShellResult`, `asFlow`/`ShellLine`, `fastCmd`, `RootService`), recipes, and gotchas (never-throws `exec` + `JOB_NOT_EXECUTED`; `asFlow` cancel contract; the deprecated `runCommand`/`runCommands`). Targets Odin 1.0.x.
- **Sync:** copy the canonical skill to the same agent dirs `asgard-ui` uses — `~/.claude/skills/odin/`, `~/.codex/skills/odin/`, `~/.gemini/skills/odin/`, `~/.gemini/config/skills/odin/`, `~/.cursor/skills/odin/`.

## Data flow / error handling

- `exec()`: `getShellAwait()` → `newJob().add(*cmds).to(out, err).await()` → map `Shell.Result` to `ShellResult`; any failure except `CancellationException` → `ShellResult(JOB_NOT_EXECUTED, …)`; `CancellationException` rethrown.
- `asFlow()`: unlimited-buffered `callbackFlow`; both streams tagged into `ShellLine`; `close(cause)` on failure; `awaitClose` closes the channel (command drains in background).

## Testing

Unit tests (JVM, no device — these are pure mapping seams):
- `Shell.Result → ShellResult` mapping: code passthrough, `stdout`/`stderr` null-filtering, `isSuccess`.
- `exec()` semantics via a fake `ShellRepository`/`Shell.Job`: success (code 0), non-zero exit returns a `ShellResult` (not a throw), transport failure → `code == JOB_NOT_EXECUTED`, `CancellationException` propagates.
- Deprecated-shim adaptation: `exec()` → old `kotlin.Result<List<String>>` (success iff code 0; failure message format).
- `asFlow()` via a fake `Shell.Job`: no dropped lines under many emissions, stderr tagged `isError = true`, `close(cause)` on failure surfaces the error, completion on command end.
- The BCV api dump (`odin/api/odin.api`) is itself a checked-in regression gate.

## Out of scope

- Engine rewrites (Steps D–H): coroutine gobblers, actor scheduler, `CompletableDeferred`-backed `ResultFuture`, interruptible `SyncTask`, **real in-flight command cancellation**, dedicated-shell streaming.
- Deep `Shell.Result` immutability (its mutable/nullable `out`/`err` guts — engine phase).
- Thor's `RootSystemGateway` migration to `exec()`/`ShellResult` (that is Thor Phase 3, not Odin).
- The real Maven Central 1.0.0 release (`main → production`) and flipping `VERSION_NAME`.

## Notes resolved from the audit critic

- **RootService IPC result path:** `exec()`/`ShellResult` covers the local **shell-command** path (`Shell.Job`) only. `RootService` is a separate Binder/AIDL execution model and is unchanged (it stays in the frozen surface via explicitApi/BCV but does not adopt `ShellResult`).
- **Multi-command granularity:** intentional — `exec(vararg)` returns one combined `ShellResult` (last-command exit code), matching the engine's single-job model.
- **explicitApi narrowing:** making the four leaks `internal` is itself an ABI-relevant change, made deliberately here as part of the freeze (they were never intended public).
