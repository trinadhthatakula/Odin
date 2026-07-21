---
name: odin
description: >-
  Integrate the Odin root-shell + RootService library (com.trinadhthatakula:odin) into an Android
  app. Use when a task asks to run root/su commands, check for root, stream shell output, or run
  privileged code via a RootService — i.e. reaches for getShellAwait, isRootGranted, exec/ShellResult,
  asFlow/ShellLine, fastCmd, or RootService. Covers dependency setup, the coroutine API, the
  persistent-root-shell model, Java-interop, and gotchas. Targets Odin 1.0.x.
---

# Odin root-shell integration

Odin gives an Android app a **persistent root shell** with a Kotlin-coroutine API, plus a generic
`RootService` (Binder/AIDL) framework for running privileged code in a root process. It is a
Kotlin-first reimagining of [libsu](https://github.com/topjohnwu/libsu). The coroutine surface lives
in `com.valhalla.superuser.ktx` / `com.valhalla.superuser.utils`; the core `Shell` engine and
`RootService` live in `com.valhalla.superuser` / `com.valhalla.superuser.ipc`.

Coordinate: `com.trinadhthatakula:odin` · package `com.valhalla.superuser` · Maven Central.
Targets: **Android** (`minSdk` 24), JDK 21.

## 1. Add the dependency

Ensure `mavenCentral()` is in the repositories, then:

```kotlin
// Android app — build.gradle.kts
dependencies { implementation("com.trinadhthatakula:odin:1.0.0") }
```

Use the latest 1.0.x. Odin depends on Kotlin coroutines transitively, so `Flow`/`suspend` types are
available at your call sites. A device with a working `su` (Magisk/KernelSU/APatch, or an emulator
image with root) is required at runtime — Odin does not grant root, it *drives* it.

## 2. Core rules

- **Single persistent shell, serial pipe.** Odin holds **one** process-wide main root shell. Every
  command is written to that shell's stdin and its output drained from stdout/stderr — a shared
  **serial** resource. Commands do not run in parallel; they queue. Do not architect around
  concurrent shells (per-command / isolated shells are not offered in this release).
- **Coroutine-first — never block the UI.** Inject/hold a `ShellRepository` (via Koin/Hilt, or
  `RealShellRepository()`) and call its `suspend` methods (`isRootGranted()`, `exec()`) from a
  coroutine. The low-level accessors (`getShellAwait()`, the `Shell.fastCmd` extensions) are also
  `suspend`. Never call the blocking `Shell.getShell()` / `ShellUtils.fastCmd(...)` on the main
  thread — prefer the `suspend` equivalents.
- **The shell is a shared serial resource.** Because output must be drained continuously, a
  long-running or high-volume command occupies the shell until it finishes (see the `asFlow`
  cancellation contract). Keep commands bounded, or dedicate a stream and let it complete.
- **Never-throws result model.** `exec()` reports command *and* transport failures through the
  returned `ShellResult` (see Gotchas) — branch on the result, don't wrap in `try/catch`
  (`CancellationException` is the one thing that still propagates).

## 3. API reference (1.0)

Condensed public signatures. `ktx` = `com.valhalla.superuser.ktx`, `utils` =
`com.valhalla.superuser.utils`, core = `com.valhalla.superuser`, ipc = `com.valhalla.superuser.ipc`.

**Repository — the primary entry point (`ktx`)**
```kotlin
interface ShellRepository {
    suspend fun isRootGranted(): Boolean               // bounded (~10s), never hangs, never throws → false on init failure
    suspend fun exec(vararg commands: String): ShellResult   // NEVER throws for shell/command failure

    @Deprecated("lossy; use exec()") suspend fun runCommand(command: String): Result<List<String>>
    @Deprecated("lossy; use exec()") suspend fun runCommands(vararg commands: String): Result<List<String>>
}
class RealShellRepository() : ShellRepository        // the concrete impl — construct or inject
```

**Results (`ktx`)**
```kotlin
data class ShellResult(val code: Int, val stdout: List<String>, val stderr: List<String>) {
    val isSuccess: Boolean                            // code == 0
    companion object { const val JOB_NOT_EXECUTED: Int = -1 }   // transport failure sentinel (dead shell/broken pipe)
}
data class ShellLine(val text: String, val isError: Boolean)    // isError → came from STDERR
```

**Coroutine extensions (`ktx`)**
```kotlin
suspend fun getShellAwait(): Shell                   // process-wide main Shell; resumes EXCEPTIONALLY on hard init failure
suspend fun Shell.Job.await(): Shell.Result          // run one job, suspend for the core Shell.Result
fun Shell.Job.asFlow(): Flow<ShellLine>              // stream STDOUT+STDERR line-by-line (unlimited buffer)
```

**Quick one-liners — `suspend` extensions on a held `Shell` (`utils`)**
```kotlin
suspend fun Shell.fastCmd(vararg commands: String): String     // LAST stdout line, or "" if no valid output
suspend fun Shell.fastCmdResult(vararg commands: String): Boolean   // true iff exit code == 0
fun escapeForShell(s: String): String                          // quote an argument for shell interpolation
// (Blocking equivalents exist on the ShellUtils object in `com.valhalla.superuser` — avoid on the main thread.)
```

**Core engine (`com.valhalla.superuser`) — usually only needed for streaming/`fastCmd`**
```kotlin
Shell.cmd(vararg commands: String): Shell.Job        // Shell.Companion — build a job on the main shell
Shell.cmd(input: InputStream): Shell.Job
Shell.getShell(): Shell                              // BLOCKING accessor (prefer suspend getShellAwait())
val Shell.isRoot: Boolean                            // is this shell a root shell
abstract class Shell.Job { fun exec(): Shell.Result; fun submit(); fun add(vararg String): Job /* ... */ }
abstract class Shell.Result { val code: Int; val out: List<String>; val err: List<String>
    val stdout: List<String>; val stderr: List<String>; val isSuccess: Boolean
    companion object { const val JOB_NOT_EXECUTED: Int } }
```

**RootService IPC (`com.valhalla.superuser.ipc`)**
```kotlin
abstract class RootService : ContextWrapper {
    abstract fun onBind(intent: Intent): IBinder      // return your AIDL Stub
    protected fun enforceCaller()                     // allow only UID 0 / 1000 / the starting UID; else SecurityException
    fun stopSelf()
    companion object {
        fun bind(intent: Intent, conn: ServiceConnection)
        fun bind(intent: Intent, executor: Executor, conn: ServiceConnection)
        fun unbind(conn: ServiceConnection)           // detach this connection
        fun stop(intent: Intent)                      // tear down the whole root process
        fun bindOrTask(intent, executor, conn): Shell.Task?   // advanced: return the task instead of running it
        fun stopOrTask(intent): Shell.Task?
    }
    const val CATEGORY_DAEMON_MODE: String            // add to intent to survive unbinds (daemon mode)
}
```

## 4. Recipes

Root check → run a command → read the lossless result:
```kotlin
val shell: ShellRepository = RealShellRepository()   // or inject it

if (shell.isRootGranted()) {
    val r = shell.exec("id", "getprop ro.build.version.sdk")   // one combined job, one result
    when {
        r.code == ShellResult.JOB_NOT_EXECUTED -> log("shell unavailable: ${r.stderr}")
        r.isSuccess -> render(r.stdout)                         // exit code of the LAST command
        else -> log("failed (code=${r.code}): ${r.stderr}")
    }
}
```

Stream long/high-volume output line-by-line:
```kotlin
Shell.cmd("logcat -d").asFlow().collect { line: ShellLine ->
    println(if (line.isError) "E: ${line.text}" else line.text)
}
```

Terse single-value query off a held `Shell`:
```kotlin
val s = getShellAwait()                               // suspends; throws on hard shell-init failure
val sdk = s.fastCmd("getprop ro.build.version.sdk")   // last stdout line
val rooted = s.fastCmdResult("which su")              // exit code == 0
```

RootService — run privileged code in a root process:
```kotlin
class MyRootService : RootService() {
    override fun onBind(intent: Intent): IBinder = object : IMyAidl.Stub() {
        override fun doPrivilegedThing() { enforceCaller(); /* runs as root */ }
    }
}
// Declare in the manifest as a non-exported <service>. Bind/unbind from the main thread:
val intent = Intent(context, MyRootService::class.java)
RootService.bind(intent, connection)      // starts the root process if needed
// ... later:
RootService.unbind(connection)            // or RootService.stop(intent) to kill the root process
```

## 5. Gotchas

- **`exec()` NEVER throws for shell/command failure — check the result.** A non-zero command exit
  returns the real `code` with `isSuccess == false`. A *transport* failure (dead shell, broken pipe,
  `NoShellException`) sets `code == ShellResult.JOB_NOT_EXECUTED` (-1). `stderr` carries the failure
  message **only when the shell-init accessor itself throws**; if the shell dies after init, `stderr`
  MAY be empty. Detect transport failure by `code == ShellResult.JOB_NOT_EXECUTED` (never by
  whether `stderr` is populated), branch on `isSuccess`, and do not rely on `try/catch`. Only
  `CancellationException` still propagates.
- **`vararg` = one job, one result.** All commands in a single `exec(...)` run as one shell job:
  `stdout`/`stderr` are the combined output, and `code` is the exit code of the **last** command.
  Call `exec()` once per command if you need a result each.
- **`asFlow` cancellation contract.** Cancelling the collector (`take(n)`, `first()`, or cancelling
  the coroutine) stops emission immediately, **but the in-flight command keeps running to completion
  in the background on the shared shell** (its output is discarded). You cannot kill a running
  command by cancelling the collector in this release. The backing channel is unlimited (the pipe
  must drain), so output is never dropped; the flow closes with the failure cause on transport error
  (a `JOB_NOT_EXECUTED` result closes it with `NoShellException`).
- **`enableLegacyStderrRedirection` collapses the STDOUT/STDERR split.** `exec()`'s `stderr` and
  `asFlow()`'s `isError` tagging assume the default `Shell.enableLegacyStderrRedirection = false`.
  Setting it `true` folds STDERR into STDOUT (`exec` yields empty `stderr`; every `asFlow` line is
  tagged `isError = false`). Leave it at the default if you rely on separated streams.
- **`isRootGranted()` is failure-safe**, not a live capability gate: it is bounded (~10 s upper
  bound, never hangs) and resolves to `false` — never throws — on shell-init failure. `getShellAwait()`
  is the opposite: it *resumes exceptionally* on hard init failure (underlying cause, or
  `NoShellException`). Wrap `getShellAwait()` where you need to react to that.
- **`runCommand` / `runCommands` are deprecated** (they returned a lossy `Result<List<String>>` —
  STDOUT only; exit code + STDERR dropped). Migrate to `exec()`: `getOrNull()` → `.stdout`,
  `isFailure` → `!isSuccess`, and you additionally get `.code` / `.stderr`. The IDE `ReplaceWith`
  quick-fix rewrites `runCommand(cmd)` → `exec(cmd)`.
- **Two `fastCmd` families:** the `suspend` extensions on `Shell` in `com.valhalla.superuser.utils`
  (coroutine-safe — use these) vs. the blocking `ShellUtils` object in `com.valhalla.superuser`
  (Java-heritage; never on the main thread).
- **RootService must be non-exported** and every AIDL method should call `enforceCaller()`. Use
  `unbind` to detach, `stop` to tear the root process down, `CATEGORY_DAEMON_MODE` to keep it alive
  across unbinds. If root is unavailable, `bind`/`stop` are no-ops.
- Verify exact current signatures against the sources under `com/valhalla/superuser/` (and the frozen
  `odin/api/odin.api`) if a param is uncertain — this is a condensed reference.
