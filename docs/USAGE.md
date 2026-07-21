# Odin Usage Guide

Odin gives you a persistent root shell with a Kotlin-coroutine API plus a `RootService` IPC
framework. All entry points live under `com.valhalla.superuser` (core) and
`com.valhalla.superuser.ktx` / `com.valhalla.superuser.utils` (coroutine helpers).

- [Getting a shell & checking root](#getting-a-shell--checking-root)
- [Running commands: `exec()` and `ShellResult`](#running-commands-exec-and-shellresult)
- [Streaming output: `asFlow()` and `ShellLine`](#streaming-output-asflow-and-shellline)
- [Quick one-liners: `fastCmd` / `fastCmdResult`](#quick-one-liners-fastcmd--fastcmdresult)
- [`RootService` IPC](#rootservice-ipc)
- [Migrating from `runCommand` / `runCommands`](#migrating-from-runcommand--runcommands)

---

## Getting a shell & checking root

For UI/repository code, use `ShellRepository`. Inject it (Koin/Hilt) or construct
`RealShellRepository()` directly. `isRootGranted()` is a **bounded, failure-safe** probe: it never
hangs (a 10 s upper bound covers a worker that never returns) and never throws for a shell-init
failure — it simply resolves to `false`.

```kotlin
import com.valhalla.superuser.ktx.RealShellRepository
import com.valhalla.superuser.ktx.ShellRepository

val shell: ShellRepository = RealShellRepository()

if (shell.isRootGranted()) {
    // root is available
}
```

If you need the low-level `Shell` instance directly (e.g. for `fastCmd` extensions), use the
`suspend` accessor `getShellAwait()`. It obtains the process-wide main shell without blocking the
calling thread, and — unlike a naive callback bridge — **resumes exceptionally** if shell init
hard-fails (with the underlying cause, or `NoShellException` if none was reported):

```kotlin
import com.valhalla.superuser.Shell
import com.valhalla.superuser.ktx.getShellAwait

val rootShell: Shell = getShellAwait()   // throws on hard shell-init failure
if (rootShell.isRoot) { /* ... */ }
```

---

## Running commands: `exec()` and `ShellResult`

`ShellRepository.exec(vararg commands: String): ShellResult` is the primary way to run commands.

**Lossless.** `ShellResult` preserves everything the shell reported:

```kotlin
public data class ShellResult(
    val code: Int,             // exit code (0..255), or JOB_NOT_EXECUTED (-1)
    val stdout: List<String>,  // STDOUT lines (never-null elements)
    val stderr: List<String>,  // STDERR lines (never-null elements)
) {
    val isSuccess: Boolean get() = code == 0
    companion object { const val JOB_NOT_EXECUTED: Int = -1 }
}
```

**Never throws for shell/command failure.** A command that exits non-zero returns its real exit
code in `code` (with `isSuccess == false`); it does not throw. A *transport* failure — a dead
shell, a broken pipe, or a `NoShellException` — sets `code == ShellResult.JOB_NOT_EXECUTED` (-1):

```kotlin
ShellResult(ShellResult.JOB_NOT_EXECUTED /* -1 */, emptyList(), /* stderr: */ listOf(errorMessage))
```

`stderr` carries the failure message **only when the shell-init accessor itself throws**; if the
shell dies *after* init, the job returns `JOB_NOT_EXECUTED` with an **empty** `stderr`. So always
detect transport failure by `code == JOB_NOT_EXECUTED`, never by whether `stderr` is populated —
branch on the result rather than wrapping calls in `try/catch`:

```kotlin
val r = shell.exec("id", "getprop ro.build.version.sdk")
when {
    r.code == ShellResult.JOB_NOT_EXECUTED -> log("shell unavailable: ${r.stderr}")
    r.isSuccess -> render(r.stdout)
    else -> log("failed (code=${r.code}): ${r.stderr}")
}
```

> `CancellationException` **always** propagates — cancelling the calling coroutine cancels the
> call; it is never swallowed into a `ShellResult`.

> **`enableLegacyStderrRedirection`.** The `stdout`/`stderr` split (here and the `isError` tag in
> `asFlow`) assumes the default `Shell.enableLegacyStderrRedirection = false`. Setting it `true`
> folds STDERR into `stdout`, collapsing the separation — leave it at the default if you rely on it.

**`vararg` = one combined job.** All commands passed in a single `exec(...)` run as **one** shell
job and produce **one** `ShellResult`. `stdout`/`stderr` are the combined output of every command,
and `code` is the exit code of the **last** command:

```kotlin
// One job, one result. code == exit code of `whoami`; stdout == both commands' output.
val r = shell.exec("cd /data/local/tmp", "whoami")
```

If you need a separate result per command, call `exec()` once per command.

---

## Streaming output: `asFlow()` and `ShellLine`

For long-running or high-volume commands, stream output line-by-line with
`Shell.Job.asFlow(): Flow<ShellLine>`.

```kotlin
public data class ShellLine(
    val text: String,     // the line contents
    val isError: Boolean, // true if from STDERR, false for STDOUT
)
```

```kotlin
import com.valhalla.superuser.Shell
import com.valhalla.superuser.ktx.asFlow
import com.valhalla.superuser.ktx.ShellLine

Shell.cmd("logcat -d").asFlow().collect { line: ShellLine ->
    println(if (line.isError) "E: ${line.text}" else line.text)
}
```

**Lossless & tagged.** Both STDOUT and STDERR are delivered as `ShellLine`s (`isError`
distinguishes them). The backing channel is **unlimited**: the root pipe must be drained
continuously, so output is never dropped and the drain is never back-pressured. The flow completes
normally when the command ends, and closes with the failure cause on a transport error (a
`JOB_NOT_EXECUTED` result closes the flow with `NoShellException`).

**Cancellation contract** (verbatim from the `asFlow` KDoc):

> Cancellation contract: cancelling the collector stops emission and releases references, but a
> command already running on the shared shell **drains to completion in the background** (its
> output is discarded). Interrupting an in-flight command / isolating long-running streams on a
> dedicated shell is not supported in this release.

In practice: `take(n)`, `first()`, or cancelling the collecting coroutine will stop you receiving
further lines immediately, but the command itself keeps running to completion on the shared shell.
Do not rely on collector cancellation to *kill* a command in this release.

---

## Quick one-liners: `fastCmd` / `fastCmdResult`

When you already hold a `Shell` (e.g. from `getShellAwait()`), the `suspend` extension helpers in
`com.valhalla.superuser.utils` are the terse path for single-value queries. Both are `suspend`, so
they never block the main thread.

```kotlin
import com.valhalla.superuser.ktx.getShellAwait
import com.valhalla.superuser.utils.fastCmd
import com.valhalla.superuser.utils.fastCmdResult

val shell = getShellAwait()

// Returns the LAST line of STDOUT, or "" if there was no (valid) output:
val sdk: String = shell.fastCmd("getprop ro.build.version.sdk")

// Returns true iff the command exited 0:
val isRooted: Boolean = shell.fastCmdResult("which su")
```

Use `exec()`/`asFlow()` instead when you need the exit code, STDERR, or multiple output lines.

---

## `RootService` IPC

To run privileged code in a persistent root process, subclass `RootService`, expose an AIDL
`IBinder`, and `bind`/`unbind` it like a bound `Service`.

**1. Subclass and return your AIDL binder from `onBind`.** Guard every AIDL method with
`enforceCaller()` — it allows only root (UID 0), the system server (UID 1000), or the UID that
started this service, and throws `SecurityException` otherwise.

```kotlin
import android.content.Intent
import android.os.IBinder
import com.valhalla.superuser.ipc.RootService

class MyRootService : RootService() {
    override fun onBind(intent: Intent): IBinder = object : IMyAidl.Stub() {
        override fun doPrivilegedThing() {
            enforceCaller()   // reject unauthorized binder callers
            // ... runs as root ...
        }
    }
}
```

Declare it in the manifest like any `Service` (it must NOT be exported to other apps).

**2. Bind / unbind from your app process** (main thread). The connection is delivered to a standard
`ServiceConnection`:

```kotlin
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.valhalla.superuser.ipc.RootService

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val api = IMyAidl.Stub.asInterface(binder)
        api.doPrivilegedThing()
    }
    override fun onServiceDisconnected(name: ComponentName) {}
}

val intent = Intent(context, MyRootService::class.java)

// Bind (starts the root process if needed):
RootService.bind(intent, connection)

// ... later, release it:
RootService.unbind(connection)
```

Notes:

- `RootService.bind(intent, executor, conn)` lets you choose the callback `Executor`
  (the no-executor overload uses the main thread).
- `RootService.stop(intent)` tears the whole root process down (vs. `unbind`, which only detaches
  this connection); a service can also `stopSelf()` from inside the root process.
- Add the `CATEGORY_DAEMON_MODE` category to the intent to keep the service alive across unbinds
  (daemon mode).
- If root is unavailable, `bind`/`stop` are no-ops. The `bindOrTask` / `stopOrTask` variants return
  a `Shell.Task?` instead of running it, for advanced scheduling.

---

## Migrating from `runCommand` / `runCommands`

`ShellRepository.runCommand(cmd)` and `runCommands(vararg)` are **deprecated**. They returned
`kotlin.Result<List<String>>`, which is *lossy*: the success value carried only STDOUT lines, while
the exit code and STDERR were discarded (a non-zero exit collapsed into `Result.failure`).

Replace them with `exec()`, which returns a lossless `ShellResult`.

| Old (`Result<List<String>>`)                 | New (`ShellResult` via `exec()`)              |
|----------------------------------------------|-----------------------------------------------|
| `runCommand("id")`                           | `exec("id")`                                  |
| `runCommands("a", "b")`                      | `exec("a", "b")`                              |
| `result.isSuccess`                           | `result.isSuccess`                            |
| `result.getOrNull()` (the `List<String>`)    | `result.stdout`                               |
| `result.isFailure`                           | `!result.isSuccess`                           |
| *(not available — exit code was dropped)*    | `result.code`                                 |
| *(not available — STDERR was dropped)*       | `result.stderr`                               |
| *(failure only — no way to tell dead shell)* | `result.code == ShellResult.JOB_NOT_EXECUTED` |

**Before:**

```kotlin
val result: Result<List<String>> = shell.runCommand("getprop ro.build.version.sdk")
result
    .onSuccess { lines -> render(lines) }
    .onFailure { e -> log("failed: ${e.message}") }
```

**After:**

```kotlin
val r: ShellResult = shell.exec("getprop ro.build.version.sdk")
if (r.isSuccess) {
    render(r.stdout)
} else {
    // Now you also have the real exit code and STDERR:
    log("failed (code=${r.code}): ${r.stderr}")
}
```

The IDE's `ReplaceWith` quick-fix on the deprecation will rewrite `runCommand(cmd)` → `exec(cmd)`
and `runCommands(*commands)` → `exec(*commands)` for you; adjust the call site to read `.stdout` /
`.isSuccess` off the returned `ShellResult` instead of unwrapping a `Result`.
