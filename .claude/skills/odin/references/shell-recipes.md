# Shell recipes for Odin 1.1.0

## Repository and results

Inject `ShellRepository` / `RealShellRepository` through the app's existing DI. A minimal adapter:

```kotlin
import com.valhalla.superuser.ktx.RealShellRepository
import com.valhalla.superuser.ktx.ShellRepository
import com.valhalla.superuser.ktx.ShellResult

class DeviceQueries(private val shell: ShellRepository = RealShellRepository()) {
    suspend fun readSdk(): ShellResult = shell.exec("getprop ro.build.version.sdk")
    suspend fun hasRoot(): Boolean = shell.isRootGranted()
}
```

`isRootGranted()` is a bounded, failure-safe Boolean probe; false can mean startup failure or
timeout rather than manager denial. A shell can fall back to non-root. Require `shell.isRoot`
when an operation requires root; do not dispatch based only on an old Boolean observation.
Preserve the consumer's non-root/provider fallback when applicable.

`exec("a", "b")` is one job: combined output, exit code of `b`. Use separate calls when separate
results matter. Check `code == ShellResult.JOB_NOT_EXECUTED` for transport failure even when
stderr is empty. Otherwise zero is command success and nonzero is an ordinary exit. Cancellation
propagates. Keep stdout/stderr separation at the default
`Shell.enableLegacyStderrRedirection = false`; do not log payloads containing secrets.

Quote each data argument rather than concatenating raw app/user values:

```kotlin
fun shellArgument(value: String): String {
    require('\u0000' !in value) { "Shell arguments cannot contain NUL" }
    return "'" + value.replace("'", "'\\''") + "'"
}
```

Quoting protects data arguments, not a whole executable script. Validate operation/table/path
choices according to the app's contract. Cancellation does not undo filesystem/settings changes;
reconcile uncertain mutations through the app's journal or read-back path.

## Coroutine owns the isolated job lifetime

Use this shape when cancelling the caller should request termination. The acknowledgement callback
records uncertainty; it must not mark a lease successful if `terminationConfirmed` or
`outputDrained` is false. `shellReusable = false` means the transport must not be reused. Do not
insert a short timeout that abandons acknowledgement and releases the app's lease prematurely.

```kotlin
import com.valhalla.superuser.JobOutcome
import com.valhalla.superuser.ktx.await
import com.valhalla.superuser.ktx.getShellAwait
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

suspend fun runRootOperation(
    commands: List<String>,
    recordAcknowledgement: (JobOutcome) -> Unit,
): JobOutcome {
    val shell = getShellAwait()
    check(shell.isRoot) { "This operation requires application-authorized root" }
    val handle = shell.prepareIsolatedJob(*commands.toTypedArray())
    try {
        currentCoroutineContext().ensureActive()
        handle.submit()
        return handle.await().also(recordAcknowledgement)
    } catch (cancelled: CancellationException) {
        handle.cancel()
        withContext(NonCancellable) {
            try {
                recordAcknowledgement(handle.await())
            } catch (failure: Exception) {
                cancelled.addSuppressed(failure)
            }
        }
        throw cancelled
    }
}
```

Hold the consumer's lease around the complete function, including acknowledgement. Preparing
before submit lets cancellation win before dispatch. Repeated submit does not create another
execution; use a new handle for new intended work. Cancelling an observing `completion` future
and suspend `await()` alone do not request termination.

For normal completion inspect `kind` and acknowledgement flags before using `exitCode`. `EXITED`
still permits a nonzero exit. `FAILED` may occur before or after dispatch; retain `started` and
failure detail. `TERMINATION_UNCONFIRMED` requires explicit app recovery state. Keep watchdogs
until device tests justify removal; inside isolation use:
`/system/bin/toybox timeout --foreground -s KILL 30 <quoted command and arguments>`.

## Fresh root observation

```kotlin
import com.valhalla.superuser.RootAvailability
import com.valhalla.superuser.ktx.refreshRootAvailability

suspend fun refreshRoot(): RootAvailability = refreshRootAvailability()
```

Return this typed observation to the existing provider selector/UI. `ROOT` and `NON_ROOT`
describe acquisition; busy retirement, timeout or failure is uncertainty, not proven denial.
`Shell.invalidateRootAvailability()` suits a consumer that already owns a refresh/probe path.
`Shell.refreshRootAvailability()` blocks and belongs on a worker; the top-level ktx function above
suspends. No polling is required on every command.

Existing UID0 can persist after manager denial. A host-coordinated Magisk DB test must allow its
sliding three-second policy cache to expire between fresh-su probes; this is a fixture concern,
not a requirement to delay ordinary app refreshes.

## Finite streaming

```kotlin
import com.valhalla.superuser.ktx.asFlow
import com.valhalla.superuser.ktx.getShellAwait

suspend fun readRecentLogLines(consume: (String, Boolean) -> Unit) {
    getShellAwait().newJob().add("logcat -d -t 100").asFlow().collect {
        consume(it.text, it.isError)
    }
}
```

The backing channel is unlimited so pipes keep draining. Choose bounded output and a consumer
that keeps up. `take()`, `first()` or cancellation stops observation while execution continues.
An indefinite stream on the shared shell needs a separate lifetime/shutdown/memory contract.
