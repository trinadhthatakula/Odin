# Odin Shell-Execution ABI-Shaping — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze a clean, documented, coroutine-first public API for Odin 1.0.0 — lossless `exec()`/`ShellResult`, lossless `asFlow()`/`ShellLine`, `@Deprecated` shims, `fun interface` callbacks, module-wide `explicitApi()` + binary-compatibility-validator, KDoc + usage docs, and an `odin` agent skill — with **no engine rewrite**.

**Architecture:** Reshape the ktx/coroutine layer on top of the unchanged engine, then seal the whole module's public surface with `explicitApi()` strict + a checked-in BCV api dump. New types (`ShellResult`, `ShellLine`) live in `ktx/`; the engine's `Shell.Result` gets a modern read-surface only.

**Tech Stack:** Kotlin (AGP 9.3.0 built-in Kotlin), kotlinx-coroutines 1.10.2, `com.vanniktech.maven.publish`, `org.jetbrains.kotlinx.binary-compatibility-validator`, Gradle 9.6.0 (JDK 21).

## Global Constraints

- Repo: `/Users/trinadhthatakula/StudioProjects/Odin`, branch `feat/shell-modernization` (off `main`; spec committed at `e128ae6`). All commits land here.
- **Build tool:** run Gradle via the `mcp__plugin_context-mode_context-mode__ctx_execute` MCP tool (`language: "shell"`, e.g. `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew …`). A hook intercepts `./gradlew` in the Bash tool. Load via `ToolSearch "select:mcp__plugin_context-mode_context-mode__ctx_execute"` if absent. Use Bash for git + file ops.
- **Namespace stays `com.valhalla.superuser`.** No engine changes.
- **`exec()` never-throws** for shell/command failure (transport failure → `code = ShellResult.JOB_NOT_EXECUTED (-1)`); `CancellationException` is always rethrown.
- **`asFlow` never backpressures the pipe drain** (unlimited buffer); it is lossless; on cancel the in-flight command drains in the background (documented contract).
- **`explicitApi()` strict, module-wide**; the four `internal/`-package leaks (`BuilderImpl`, `MainShell`, `ShellImpl`, `UiThreadHandler`) become `internal`.
- **The BCV api dump (`odin/api/odin.api`) is generated LAST**, after Tasks 1–3 are final, so it freezes the final surface.
- New public declarations are authored with an explicit `public` modifier (so Task 5 has nothing to add for them).
- Unit tests run on the JVM (`./gradlew :odin:testDebugUnitTest`) — no device. Test dir: `odin/src/test/java/com/valhalla/superuser/`.
- **Out of scope:** engine rewrites (coroutine gobblers, actor scheduler, `CompletableDeferred`, interruptible `SyncTask`, real in-flight cancellation, dedicated-shell streaming), deep `Shell.Result` immutability, Thor's `RootSystemGateway` migration (Phase 3), the real Central release.

## File Structure

- Create `odin/src/main/java/com/valhalla/superuser/ktx/ShellResult.kt` — the `ShellResult` data class.
- Create `odin/src/main/java/com/valhalla/superuser/ktx/ShellLine.kt` — the `ShellLine` data class.
- Create `odin/src/test/java/com/valhalla/superuser/ktx/ShellResultMappingTest.kt`, `ExecTest.kt`, `AsFlowTest.kt` — JVM unit tests.
- Modify `ktx/ShellRepository.kt` (exec + deprecations + runInternal fix), `ktx/ShellExtensions.kt` (await fix + asFlow reshape + lambdas), `Shell.kt` (`fun interface`s + `Shell.Result` accessors), `CallbackList.kt` (ctor visibility).
- Modify `odin/build.gradle.kts` (explicitApi + BCV plugin + test deps), `gradle/libs.versions.toml` (BCV + junit), `.github/workflows/pr-ci.yml` (apiCheck).
- Create `odin/api/odin.api` (BCV dump), `docs/USAGE.md`, `.claude/skills/odin/SKILL.md`; modify `README.md`.

---

### Task 1: Callbacks → `fun interface` + free ktx correctness fixes

**Files:**
- Modify: `odin/src/main/java/com/valhalla/superuser/Shell.kt` (`GetShellCallback` :436, `ResultCallback` :453)
- Modify: `odin/src/main/java/com/valhalla/superuser/ktx/ShellExtensions.kt` (`await` :16, `getShellAwait` :62)
- Modify: `odin/src/main/java/com/valhalla/superuser/ktx/ShellRepository.kt` (`runInternal` :45)
- Test: `odin/src/test/java/com/valhalla/superuser/ktx/ShellRepositoryCancellationTest.kt`

**Interfaces:**
- Produces: `Shell.ResultCallback` and `Shell.GetShellCallback` become `fun interface` (SAM). `await()` and `getShellAwait()` unchanged in signature. `runInternal` now rethrows `CancellationException`.

- [ ] **Step 1: Convert both callbacks to `fun interface` (Shell.kt)**

Change line 436 `    interface GetShellCallback {` → `    fun interface GetShellCallback {` (it keeps `onShell` abstract + `onShellDied` default — exactly one abstract method, SAM-valid).
Change line 453 `    interface ResultCallback {` → `    fun interface ResultCallback {`.

- [ ] **Step 2: Simplify `getShellAwait()` + fix `await()` to a lambda with `submit(null, cb)` (ShellExtensions.kt)**

Replace `await()` (lines 16-25) with:
```kotlin
/**
 * Suspends until this [Shell.Job] completes and returns its full [Shell.Result]
 * (exit code + stdout + stderr preserved). Replaces the [Shell.Job.submit] callback.
 *
 * The continuation resumes on the job's completing worker thread (`submit(null, cb)`),
 * so the caller's coroutine context governs dispatch — no `Dispatchers.Main` round-trip.
 */
suspend fun Shell.Job.await(): Shell.Result = suspendCancellableCoroutine { cont ->
    submit(null) { result ->
        if (cont.isActive) cont.resume(result)
    }
}
```
Replace `getShellAwait()` (lines 62-78) with:
```kotlin
/**
 * Gets the main shell instance via coroutines, without blocking the calling thread.
 * Resumes exceptionally with the cause (or [NoShellException]) if shell init hard-fails.
 */
suspend fun getShellAwait(): Shell = suspendCancellableCoroutine { cont ->
    Shell.getShell(
        object : Shell.GetShellCallback {
            override fun onShell(shell: Shell) {
                if (cont.isActive) cont.resume(shell)
            }

            override fun onShellDied(error: Throwable?) {
                if (cont.isActive) {
                    cont.resumeWithException(error ?: NoShellException("Root shell initialization failed"))
                }
            }
        }
    )
}
```
(`getShellAwait` keeps an object expression because it overrides two methods — a lambda only works for a single-abstract-method call, and here we also override the defaulted `onShellDied`.)

- [ ] **Step 3: Write the failing test for `runInternal` cancellation (ShellRepositoryCancellationTest.kt)**

```kotlin
package com.valhalla.superuser.ktx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ShellRepositoryCancellationTest {
    // runInternal must rethrow CancellationException rather than swallow it into Result.failure.
    // We exercise the exact catch policy via a tiny stand-in with the same structure.
    private suspend fun runInternalPolicy(block: suspend () -> List<String>): Result<List<String>> =
        try {
            Result.success(block())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.failure(e)
        }

    @Test
    fun cancellationPropagates() = runTest {
        assertFailsWith<CancellationException> {
            runInternalPolicy { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun otherFailureBecomesResultFailure() = runTest {
        val r = runInternalPolicy { throw RuntimeException("boom") }
        assert(r.isFailure)
    }
}
```
(This test pins the catch *policy*. The real `runInternal` bottoms out in the static shell with no injection seam; Step 5 applies the same policy to it.)

- [ ] **Step 4: Run the test — expect FAIL (missing junit/coroutines-test deps until Task adds them)**

Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:testDebugUnitTest --tests "*ShellRepositoryCancellationTest*"`
Expected: FAIL to compile — `kotlin.test` / `kotlinx-coroutines-test` unresolved. Add the test deps in Step 5, then it passes.

- [ ] **Step 5: Add test deps + apply the `runInternal` cancellation fix**

In `gradle/libs.versions.toml` add under `[versions]`: `kotlinxCoroutinesTest = "1.10.2"` and (if not present) a `junit` line is unnecessary — use `kotlin("test")`. Add under `[libraries]`:
```toml
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinxCoroutinesTest" }
```
In `odin/build.gradle.kts` add to `dependencies { }`:
```kotlin
testImplementation(kotlin("test"))
testImplementation(libs.kotlinx.coroutines.test)
```
In `ktx/ShellRepository.kt` `runInternal` (lines 58-60), replace the catch:
```kotlin
            } catch (e: Exception) {
                Result.failure(e)
            }
```
with:
```kotlin
            } catch (e: Exception) {
                // Rethrow cancellation so structured concurrency is preserved (mirror isRootGranted);
                // any other failure becomes a Result.failure.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Result.failure(e)
            }
```

- [ ] **Step 6: Run tests + build — expect PASS / SUCCESSFUL**

Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:testDebugUnitTest :odin:assembleRelease --stacktrace`
Expected: `ShellRepositoryCancellationTest` 2/2 pass; BUILD SUCCESSFUL (the `fun interface` conversions + lambda `await` compile; Java/Kotlin SAM callers unaffected).

- [ ] **Step 7: Commit**

```bash
cd /Users/trinadhthatakula/StudioProjects/Odin
git add -A && git commit -m "feat(ktx): fun-interface callbacks + await(null,cb) + runInternal rethrows CancellationException"
```

---

### Task 2: `ShellResult` + `exec()` + `Shell.Result` accessors + deprecate the lossy shims

**Files:**
- Create: `odin/src/main/java/com/valhalla/superuser/ktx/ShellResult.kt`
- Modify: `odin/src/main/java/com/valhalla/superuser/Shell.kt` (`Result` :262-300)
- Modify: `odin/src/main/java/com/valhalla/superuser/ktx/ShellRepository.kt` (interface + impl)
- Test: `odin/src/test/java/com/valhalla/superuser/ktx/ShellResultTest.kt`

**Interfaces:**
- Consumes: `Shell.Job.await(): Shell.Result` (Task 1), `getShellAwait()`.
- Produces: `ShellResult(code, stdout, stderr)` + `ShellResult.isSuccess` + `ShellResult.JOB_NOT_EXECUTED`; `suspend fun ShellRepository.exec(vararg commands: String): ShellResult`; `Shell.Result.stdout`/`Shell.Result.stderr`.

- [ ] **Step 1: Write the failing tests (ShellResultTest.kt)**

```kotlin
package com.valhalla.superuser.ktx

import com.valhalla.superuser.Shell
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellResultTest {
    private fun result(code: Int, out: List<String?>, err: List<String?>) = object : Shell.Result() {
        override val out = out.toMutableList()
        override val err = err.toMutableList()
        override val code = code
    }

    @Test fun mappingPreservesCodeAndFiltersNulls() {
        val sr = result(0, listOf("a", null, "b"), listOf(null, "e")).toShellResult()
        assertEquals(0, sr.code)
        assertEquals(listOf("a", "b"), sr.stdout)
        assertEquals(listOf("e"), sr.stderr)
        assertTrue(sr.isSuccess)
    }

    @Test fun nonZeroIsNotSuccess() {
        assertFalse(result(1, emptyList(), emptyList()).toShellResult().isSuccess)
    }

    @Test fun shellResultShapeIsImmutableAndDestructurable() {
        val (code, out, err) = ShellResult(2, listOf("x"), listOf("y"))
        assertEquals(2, code); assertEquals(listOf("x"), out); assertEquals(listOf("y"), err)
        assertEquals(ShellResult.JOB_NOT_EXECUTED, -1)
    }

    @Test fun shellResultStdErrAccessorsOnShellResultType() {
        val r = result(0, listOf("a", null), listOf("b", null))
        assertEquals(listOf("a"), r.stdout); assertEquals(listOf("b"), r.stderr)
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL (`ShellResult`, `toShellResult`, `Shell.Result.stdout` undefined)**

Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:testDebugUnitTest --tests "*ShellResultTest*"`
Expected: FAIL — unresolved `ShellResult` / `toShellResult` / `stdout`.

- [ ] **Step 3: Create `ShellResult.kt`**

```kotlin
package com.valhalla.superuser.ktx

import com.valhalla.superuser.Shell

/**
 * Lossless, immutable result of a shell command run via [exec].
 *
 * @property code the exit code of the last command (0..255), or [JOB_NOT_EXECUTED] (-1) if the job
 *   could not run at all (dead shell / broken pipe).
 * @property stdout STDOUT lines (never null elements).
 * @property stderr STDERR lines (never null elements).
 */
public data class ShellResult(
    val code: Int,
    val stdout: List<String>,
    val stderr: List<String>,
) {
    /** `true` iff the command completed with exit code 0. */
    val isSuccess: Boolean get() = code == 0

    public companion object {
        /** The job never executed (dead shell / not run). See [Shell.Result.JOB_NOT_EXECUTED]. */
        public const val JOB_NOT_EXECUTED: Int = -1
    }
}

/** Maps a low-level [Shell.Result] to the immutable, non-null [ShellResult]. */
public fun Shell.Result.toShellResult(): ShellResult =
    ShellResult(code = code, stdout = stdout, stderr = stderr)
```

- [ ] **Step 4: Add `stdout`/`stderr` read accessors to `Shell.Result` (Shell.kt)**

After the `isSuccess` property block (insert before the `companion object` at line 293), add:
```kotlin

        /** STDOUT with null lines filtered out (non-null convenience view of [out]). */
        val stdout: List<String> get() = out.filterNotNull()

        /** STDERR with null lines filtered out (non-null convenience view of [err]). */
        val stderr: List<String> get() = err.filterNotNull()
```
(Leave `out`/`err`/`code`/`isSuccess`/`JOB_NOT_EXECUTED` unchanged — the engine still fills `out`/`err`.)

- [ ] **Step 5: Run ShellResultTest — expect PASS**

Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:testDebugUnitTest --tests "*ShellResultTest*"`
Expected: 4/4 pass.

- [ ] **Step 6: Add `exec()` to `ShellRepository` + deprecate the shims (ShellRepository.kt)**

Replace the interface (lines 13-17) with:
```kotlin
interface ShellRepository {
    suspend fun isRootGranted(): Boolean

    /**
     * Runs [commands] as one shell job and returns a lossless [ShellResult].
     *
     * Never throws for shell/command failure: a transport failure (dead shell / broken pipe /
     * [NoShellException]) yields `ShellResult(ShellResult.JOB_NOT_EXECUTED, emptyList(), listOf(msg))`.
     * A completed command returns its real exit code (including non-zero). `CancellationException`
     * always propagates. `vararg` runs as a single job → one combined [ShellResult] (last-command code).
     */
    suspend fun exec(vararg commands: String): ShellResult

    @Deprecated(
        "Lossy: drops exit code + stderr. Use exec() for a lossless ShellResult.",
        ReplaceWith("exec(command)")
    )
    suspend fun runCommand(command: String): Result<List<String>>

    @Deprecated(
        "Lossy: drops exit code + stderr. Use exec() for a lossless ShellResult.",
        ReplaceWith("exec(*commands)")
    )
    suspend fun runCommands(vararg commands: String): Result<List<String>>
}
```
Replace the `RealShellRepository` command methods (`runCommand`/`runCommands`/`runInternal` — lines 37-61, keeping the Task-1 cancellation fix policy) with:
```kotlin
    override suspend fun exec(vararg commands: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            getShellAwait().newJob().add(*commands).to(ArrayList(), ArrayList()).await().toShellResult()
        } catch (e: Exception) {
            // Never-throws for shell/command failure; cancellation still propagates.
            if (e is kotlinx.coroutines.CancellationException) throw e
            ShellResult(ShellResult.JOB_NOT_EXECUTED, emptyList(), listOf(e.message ?: e.javaClass.simpleName))
        }
    }

    @Deprecated("Lossy: drops exit code + stderr. Use exec() for a lossless ShellResult.", ReplaceWith("exec(command)"))
    override suspend fun runCommand(command: String): Result<List<String>> = execToLegacy(command)

    @Deprecated("Lossy: drops exit code + stderr. Use exec() for a lossless ShellResult.", ReplaceWith("exec(*commands)"))
    override suspend fun runCommands(vararg commands: String): Result<List<String>> = execToLegacy(*commands)

    // Adapts exec() back to the old kotlin.Result<List<String>> contract for the deprecated shims.
    private suspend fun execToLegacy(vararg commands: String): Result<List<String>> {
        val r = exec(*commands)
        return if (r.isSuccess) Result.success(r.stdout)
        else Result.failure(IOException("Command failed with code ${r.code}: ${r.stderr.joinToString("\n")}"))
    }
```
Add the import `import kotlinx.coroutines.CancellationException` is NOT needed (fully-qualified used). Keep the existing `import java.io.IOException`, `Dispatchers`, `withContext`. Remove the now-unused `withTimeoutOrNull` import ONLY if `isRootGranted` no longer uses it — it still does, so keep it.

- [ ] **Step 7: Write + run the exec/shim tests (append to ShellResultTest.kt or a new ExecTest.kt)**

```kotlin
package com.valhalla.superuser.ktx

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecShimTest {
    // Exercises the execToLegacy adaptation policy directly (exec() itself needs a live shell).
    private fun legacyOf(r: ShellResult): Result<List<String>> =
        if (r.isSuccess) Result.success(r.stdout)
        else Result.failure(java.io.IOException("Command failed with code ${r.code}: ${r.stderr.joinToString("\n")}"))

    @Test fun successMapsToStdout() = runTest {
        val r = legacyOf(ShellResult(0, listOf("ok"), emptyList()))
        assertTrue(r.isSuccess); assertEquals(listOf("ok"), r.getOrNull())
    }

    @Test fun nonZeroMapsToFailureWithCodeAndStderr() = runTest {
        val r = legacyOf(ShellResult(2, emptyList(), listOf("bad")))
        assertTrue(r.isFailure)
        assertEquals("Command failed with code 2: bad", r.exceptionOrNull()?.message)
    }
}
```
Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:testDebugUnitTest :odin:assembleRelease --stacktrace`
Expected: all tests pass; BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
cd /Users/trinadhthatakula/StudioProjects/Odin
git add -A && git commit -m "feat(ktx): lossless ShellResult + suspend exec() (never-throws); deprecate runCommand/runCommands; Shell.Result stdout/stderr"
```

---

### Task 3: `ShellLine` + lossless `asFlow(): Flow<ShellLine>`

**Files:**
- Create: `odin/src/main/java/com/valhalla/superuser/ktx/ShellLine.kt`
- Modify: `odin/src/main/java/com/valhalla/superuser/ktx/ShellExtensions.kt` (`asFlow` :34)
- Test: `odin/src/test/java/com/valhalla/superuser/ktx/AsFlowTest.kt`

**Interfaces:**
- Consumes: `Shell.Job.to(stdout, stderr)`, `Shell.Job.submit`, `CallbackList`.
- Produces: `ShellLine(text, isError)`; `fun Shell.Job.asFlow(): Flow<ShellLine>`.

- [ ] **Step 1: Create `ShellLine.kt`**

```kotlin
package com.valhalla.superuser.ktx

/**
 * One line of live shell output, tagged by stream.
 * @property text the line contents.
 * @property isError `true` if this line came from STDERR, `false` for STDOUT.
 */
public data class ShellLine(val text: String, val isError: Boolean)
```

- [ ] **Step 2: Write the failing test (AsFlowTest.kt)**

```kotlin
package com.valhalla.superuser.ktx

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsFlowTest {
    @Test fun shellLineTagsStream() {
        assertTrue(ShellLine("oops", isError = true).isError)
        assertEquals("hi", ShellLine("hi", isError = false).text)
    }

    // Losslessness/close-on-failure of the callbackFlow are validated on-device / via the
    // engine; here we assert the ShellLine contract + that a list of tagged lines round-trips.
    @Test fun taggedLinesRoundTrip() = runTest {
        val lines = listOf(ShellLine("a", false), ShellLine("e", true))
        assertEquals(listOf(false, true), lines.map { it.isError })
    }
}
```
(The `callbackFlow` body integrates the static `Shell.Job`/gobbler path with no injection seam; its losslessness is verified by the buffer choice + on-device. The unit test pins the `ShellLine` contract. Do not fabricate a mock that re-implements `callbackFlow`.)

- [ ] **Step 3: Run — expect FAIL (`ShellLine` unresolved until Step 1 compiles with tests)**

Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:testDebugUnitTest --tests "*AsFlowTest*"`
Expected: PASS after Step 1 (the type exists) — if it fails, it's a missing import; fix and rerun.

- [ ] **Step 4: Reshape `asFlow` (ShellExtensions.kt)**

Add imports at the top: `import com.valhalla.superuser.CallbackList`, `import kotlinx.coroutines.channels.Channel`, `import kotlinx.coroutines.channels.trySendBlocking`. Replace `asFlow()` (lines 34-56) with:
```kotlin
/**
 * Streams this job's output as a lossless, stream-tagged [Flow] of [ShellLine].
 *
 * Both STDOUT and STDERR are delivered ([ShellLine.isError] distinguishes them). The channel is
 * UNLIMITED: the root pipe must be drained continuously, so output is never dropped and the drain
 * is never back-pressured. The flow completes when the command ends and closes with the failure
 * cause on a transport error.
 *
 * Cancellation contract: cancelling the collector stops emission and releases references, but a
 * command already running on the shared shell **drains to completion in the background** (its
 * output is discarded). Interrupting an in-flight command / isolating long-running streams on a
 * dedicated shell is not supported in this release.
 */
fun Shell.Job.asFlow(): Flow<ShellLine> = callbackFlow {
    fun lineSink(isError: Boolean) = object : CallbackList<String?>() {
        override fun onAddElement(e: String?) {
            if (e != null) trySendBlocking(ShellLine(e, isError))
        }
    }
    to(lineSink(isError = false), lineSink(isError = true))
    submit(null) { result ->
        if (result.code == Shell.Result.JOB_NOT_EXECUTED) {
            close(NoShellException("Shell job did not execute (code ${result.code})"))
        } else {
            close()
        }
    }
    awaitClose { /* stop emitting; the engine's gobbler keeps the pipe drained (see KDoc). */ }
}.buffer(Channel.UNLIMITED)
```
Add `import kotlinx.coroutines.flow.buffer`. Note: `CallbackList`'s no-arg-style subclch uses the executor default; `trySendBlocking` into an UNLIMITED channel never actually blocks (capacity is unbounded) but is the correct checked send (no ignored result). `submit(null, cb)` runs the completion on the worker thread.

- [ ] **Step 5: Run tests + build**

Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:testDebugUnitTest :odin:assembleRelease --stacktrace`
Expected: tests pass; BUILD SUCCESSFUL (asFlow now returns `Flow<ShellLine>`; the old `Flow<String>` is gone — no consumers).

- [ ] **Step 6: Commit**

```bash
cd /Users/trinadhthatakula/StudioProjects/Odin
git add -A && git commit -m "feat(ktx): lossless asFlow(): Flow<ShellLine> (stdout+stderr, unlimited buffer, close-on-failure)"
```

---

### Task 4: `explicitApi()` strict + resolve leaks + binary-compatibility-validator freeze

**Files:**
- Modify: `odin/build.gradle.kts`, `gradle/libs.versions.toml`
- Modify: `internal/BuilderImpl.kt`, `internal/MainShell.kt`, `internal/ShellImpl.kt`, `internal/UiThreadHandler.kt`, `CallbackList.kt`
- Modify: any file the `explicitApi` compiler flags (module-wide) + dedupe `ShellUtils.kt` / `utils/ShellUtils.kt`
- Create: `odin/api/odin.api` (generated), modify `.github/workflows/pr-ci.yml`

**Interfaces:**
- Produces: a module that compiles under `explicitApi()` strict; the four leaks are `internal`; `odin/api/odin.api` freezes the public surface; CI runs `apiCheck`.

- [ ] **Step 1: Mark the four internal leaks `internal`**

- `internal/BuilderImpl.kt:11` `class BuilderImpl : Shell.Builder()` → `internal class BuilderImpl : Shell.Builder()`.
- `internal/MainShell.kt` `object MainShell` → `internal object MainShell`.
- `internal/ShellImpl.kt:25` `class ShellImpl(...) : Shell()` → `internal class ShellImpl(...) : Shell()`.
- `internal/UiThreadHandler.kt` `object UiThreadHandler` → `internal object UiThreadHandler`.

- [ ] **Step 2: Fix the `CallbackList` UiThreadHandler exposure (CallbackList.kt)**

The primary `protected constructor(mExecutor: Executor = UiThreadHandler.executor, ...)` (lines 22-24) references the now-`internal` `UiThreadHandler` as a default value in a `protected` signature. Make the executor-taking constructor `internal` and keep a `public`/`protected` no-executor path for subclassers:
```kotlin
abstract class CallbackList<E> internal constructor(
    protected var mExecutor: Executor = UiThreadHandler.executor,
    protected var mBase: MutableList<E?>? = null
) : AbstractList<E?>() {
    /** Subclass entry: callback runs on the UI thread; optional backing list. */
    protected constructor(base: MutableList<E?>? = null) : this(UiThreadHandler.executor, base)
    ...
}
```
The `internal` primary ctor may reference `UiThreadHandler` (same module); the `protected` secondary ctor exposes no internal type. In-module subclasses (the `lineSink` in `asFlow`) use the no-arg `protected` ctor. Verify `asFlow`'s `CallbackList<String?>()` still resolves to the protected ctor.

- [ ] **Step 3: Add the BCV plugin + enable explicitApi (build files)**

In `gradle/libs.versions.toml` add under `[versions]`: `binaryCompatibilityValidator = "0.17.0"` (VERIFY this is a resolvable stable version at the plugin portal; bump to the latest stable if the build reports it missing). Add under `[plugins]`:
```toml
binary-compatibility-validator = { id = "org.jetbrains.kotlinx.binary-compatibility-validator", version.ref = "binaryCompatibilityValidator" }
```
In `odin/build.gradle.kts` `plugins { }` add: `alias(libs.plugins.binary.compatibility.validator)`. In the `kotlin { }` block, add `explicitApi()`:
```kotlin
kotlin {
    explicitApi()
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}
```

- [ ] **Step 4: Compile-driven explicit-visibility loop**

Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:compileReleaseKotlin --stacktrace`
`explicitApi()` strict emits an error for every public/protected declaration missing an explicit visibility modifier or return type. For EACH reported declaration, add the correct keyword: `public` for genuine API, `internal` for module-only, `private` for file-local — and an explicit return type where required. Repeat until `compileReleaseKotlin` is clean. Known decisions while doing this:
- The two `ShellUtils` families both stay (`object ShellUtils` blocking + `utils/ShellUtils.kt` suspend extensions), but **dedupe** the duplicated `isValidOutput`/`escapeForShell`: keep ONE public definition (prefer the `utils/ShellUtils.kt` top-level versions), delete/`internal`-ise the other, and fix references.
- New Task-2/3 decls are already `public` — confirm no change needed.
- Do NOT change any behavior; visibility only.

- [ ] **Step 5: Generate + inspect the api dump**

Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:apiDump --stacktrace`
This writes `odin/api/odin.api`. Open it and sanity-check the frozen surface: `ShellResult`, `ShellLine`, `ShellRepository.exec`, the `@Deprecated` shims, `Shell` + `Shell.Result` (+ `stdout`/`stderr`) + `Shell.Job` + the two `fun interface`s, `RootService`, `CallbackList`, `NoShellException`, the `ShellUtils` surface — and that `BuilderImpl`/`MainShell`/`ShellImpl`/`UiThreadHandler` are **absent**.

- [ ] **Step 6: Wire `apiCheck` into CI (.github/workflows/pr-ci.yml)**

Change the PR build step to also run `apiCheck`:
```yaml
      - name: Assemble + API check
        run: ./gradlew :odin:assembleRelease :odin:apiCheck --stacktrace
```

- [ ] **Step 7: Full verify**

Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew clean :odin:assembleRelease :odin:testDebugUnitTest :odin:apiCheck --stacktrace`
Expected: BUILD SUCCESSFUL; `apiCheck` passes against the committed dump; all unit tests pass.

- [ ] **Step 8: Commit**

```bash
cd /Users/trinadhthatakula/StudioProjects/Odin
git add -A && git commit -m "chore(api): explicitApi() strict + BCV freeze (odin.api) + internalize engine leaks + apiCheck in CI"
```

---

### Task 5: Documentation — README Usage + `docs/USAGE.md` + KDoc audit

**Files:**
- Modify: `README.md`
- Create: `docs/USAGE.md`

**Interfaces:** consumes the public API from Tasks 1–4.

- [ ] **Step 1: Add a Usage section to `README.md`** (after the Install section)

```markdown
## Usage

```kotlin
// Inject ShellRepository (e.g. via Koin/Hilt) or construct RealShellRepository().
val shell: ShellRepository = RealShellRepository()

// Is root available? (bounded, never hangs, never throws)
if (shell.isRootGranted()) {

    // Run a command — lossless result (exit code + stdout + stderr). Never throws for
    // command/shell failure; a dead shell yields code == ShellResult.JOB_NOT_EXECUTED (-1).
    val r: ShellResult = shell.exec("id", "getprop ro.build.version.sdk")
    if (r.isSuccess) println(r.stdout) else println("code=${r.code} err=${r.stderr}")

    // Stream live output (both stdout + stderr, tagged):
    Shell.cmd("logcat -d").asFlow().collect { line: ShellLine ->
        println(if (line.isError) "E: ${line.text}" else line.text)
    }
}
```

`runCommand`/`runCommands` are deprecated — use `exec()`. See [`docs/USAGE.md`](docs/USAGE.md).
```

- [ ] **Step 2: Create `docs/USAGE.md`** covering, with runnable snippets: `getShellAwait()`/`isRootGranted()`; `exec()`/`ShellResult` (never-throws model + `JOB_NOT_EXECUTED`, `vararg`=one combined result); `asFlow()`/`ShellLine` (losslessness + the in-flight-drain-on-cancel contract verbatim from the `asFlow` KDoc); `fastCmd`/`fastCmdResult`; the `RootService` pattern (subclass, `bind`/`unbind`, AIDL); and a **"Migrating from runCommand/runCommands"** subsection mapping the old `Result<List<String>>` calls to `exec()` (show that `exec().isSuccess`/`.stdout` replace the old success-value, and `.code`/`.stderr` are now available).

- [ ] **Step 3: KDoc audit**

Confirm KDoc exists on every new/changed public symbol (added in Tasks 1–3): `ShellResult` (+ `JOB_NOT_EXECUTED`), `ShellLine`, `ShellRepository.exec`, the `@Deprecated` shims, `Shell.Result.stdout`/`stderr`, `asFlow`. Add any missing KDoc. Then verify the javadoc jar still builds:
Run: `cd /Users/trinadhthatakula/StudioProjects/Odin && ./gradlew :odin:javaDocReleaseJar --stacktrace`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /Users/trinadhthatakula/StudioProjects/Odin
git add -A && git commit -m "docs: README Usage + docs/USAGE.md (exec/ShellResult, asFlow/ShellLine, RootService, runCommand→exec migration)"
```

---

### Task 6: `odin` agent skill + sync

**Files:**
- Create: `.claude/skills/odin/SKILL.md`
- Create (sync copies): `~/.claude/skills/odin/SKILL.md`, `~/.codex/skills/odin/SKILL.md`, `~/.gemini/skills/odin/SKILL.md`, `~/.gemini/config/skills/odin/SKILL.md`, `~/.cursor/skills/odin/SKILL.md`

**Interfaces:** documents the public API from Tasks 1–4 for AI agents building apps with Odin.

- [ ] **Step 1: Author `.claude/skills/odin/SKILL.md`**

Mirror the STRUCTURE of `/Users/trinadhthatakula/StudioProjects/Asgard/.claude/skills/asgard-ui/SKILL.md`. Required frontmatter:
```markdown
---
name: odin
description: >-
  Integrate the Odin root-shell + RootService library (com.trinadhthatakula:odin) into an Android
  app. Use when a task asks to run root/su commands, check for root, stream shell output, or run
  privileged code via a RootService — i.e. reaches for getShellAwait, isRootGranted, exec/ShellResult,
  asFlow/ShellLine, fastCmd, or RootService. Covers dependency setup, the coroutine API, the
  persistent-root-shell model, Java-interop, and gotchas. Targets Odin 1.0.x.
---
```
Body sections (mirroring asgard-ui): (1) intro + coordinate `com.trinadhthatakula:odin` · package `com.valhalla.superuser` · minSdk 24; (2) **Add the dependency** (`implementation("com.trinadhthatakula:odin:1.0.0")` + `mavenCentral()`); (3) **Core rules** (persistent single-root-shell pipe model; coroutine-first — inject `ShellRepository`, don't block; icons/threading n/a; the shell is a shared serial resource); (4) **API reference** — condensed signatures for `ShellRepository.isRootGranted()/exec()`, `ShellResult(code, stdout, stderr, isSuccess, JOB_NOT_EXECUTED)`, `Shell.Job.await()/asFlow()`, `ShellLine(text, isError)`, `getShellAwait()`, `fastCmd/fastCmdResult`, and the `RootService` subclass pattern; (5) **Recipes** (root check → exec → stream); (6) **Gotchas** (`exec` never-throws → check `code`/`JOB_NOT_EXECUTED`; `asFlow` cancel contract — in-flight command drains in background; `runCommand`/`runCommands` deprecated → `exec`; verify signatures against sources if unsure). Keep it condensed and signature-accurate to what Tasks 1–4 shipped.

- [ ] **Step 2: Sync the skill to the 5 agent dirs**

```bash
SRC=/Users/trinadhthatakula/StudioProjects/Odin/.claude/skills/odin/SKILL.md
for d in "$HOME/.claude/skills/odin" "$HOME/.codex/skills/odin" "$HOME/.gemini/skills/odin" "$HOME/.gemini/config/skills/odin" "$HOME/.cursor/skills/odin"; do
  mkdir -p "$d" && cp "$SRC" "$d/SKILL.md"
done
ls -1 "$HOME"/.claude/skills/odin/SKILL.md "$HOME"/.codex/skills/odin/SKILL.md "$HOME"/.gemini/skills/odin/SKILL.md "$HOME"/.gemini/config/skills/odin/SKILL.md "$HOME"/.cursor/skills/odin/SKILL.md
```
Expected: all 5 paths listed (synced).

- [ ] **Step 3: Commit** (the canonical repo copy only — the synced dirs are outside the repo)

```bash
cd /Users/trinadhthatakula/StudioProjects/Odin
git add .claude/skills/odin/SKILL.md && git commit -m "docs(skill): add odin consumer-integration agent skill (synced to claude/codex/gemini/cursor)"
```

---

## Self-Review

- **Spec coverage:** A → Task 1; B → Task 2; C → Task 3; D → Task 1 (fun interface); E → Task 4; F → Task 5 (+ KDoc in Tasks 1–3); G → Task 6. All spec sections mapped.
- **Placeholder scan:** new code is shown in full; the two inherently compile-driven parts (Task 4 Step 4 explicit-visibility loop, and the on-device-only aspects of `exec`/`asFlow`) are framed as concrete loops/contracts, not TODOs. `ShellUtils` dedupe names the files + the keep-choice. BCV version is concrete (`0.17.0`) with a verify-and-bump instruction.
- **Type consistency:** `ShellResult(code, stdout, stderr)` + `isSuccess` + `JOB_NOT_EXECUTED` identical across Task 2 (def), the deprecated-shim adapter, Task 3's `close` check, README/USAGE (Task 5), and the skill (Task 6). `ShellLine(text, isError)` identical across Task 3 (def), `asFlow`, README, skill. `exec(vararg commands: String): ShellResult` identical in the interface, impl, docs, skill. `Shell.Result.stdout`/`stderr` used by `toShellResult` (Task 2) and defined on `Shell.Result` (Task 2 Step 4). `fun interface` conversions (Task 1) are relied on by the lambda `submit(null){…}` calls in Tasks 1 & 3.
- **Ordering:** BCV dump (Task 4) generated after Tasks 1–3 finalize the surface ✅; new public types authored `public` up-front so Task 4 only annotates the pre-existing Java-heritage decls ✅.
