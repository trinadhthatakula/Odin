---
name: odin
description: Integrate com.trinadhthatakula:odin into Android apps using root shell commands, isolated cancellation, explicit root refresh, streaming output, or RootService IPC. Covers Maven Local validation and the public lifecycle contracts.
---

# Odin integration

Odin is an Android root-shell and Binder RootService library. Coordinate `com.trinadhthatakula:odin`; core package `com.valhalla.superuser`, coroutine extensions `com.valhalla.superuser.ktx`, IPC `com.valhalla.superuser.ipc`. Android minSdk 24; build Odin on JDK 21. Root requires application authorization through a root manager; ADB root is not evidence of application authorization.

Before editing an app, inspect its dependency/version catalog, privilege routing, command lifecycle, and existing shell ownership. Verify exact signatures in the resolved version or `odin/api/odin.api`. The lifecycle API described here is introduced in 1.1.0; do not use it with 1.0.0.

## Dependency and local validation

Use `mavenCentral()` and pin a published version. During cross-repository work, publish a unique candidate rather than overwriting a released coordinate:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew publishToMavenLocal -PVERSION_NAME=1.1.0-my-change-SNAPSHOT
```

Add `mavenLocal()` scoped exclusively to that candidate coordinate/version and override the consumer dependency explicitly. Disable any Odin composite substitution while validating the Maven artifact. Verify `dependencyInsight` resolves the candidate; assemble and test the consumer. Restore/pin the Central release after publication. Existing Thor supports `-PodinLocalVersion=<candidate>` for this workflow.

## Choose execution semantics

- `ShellRepository.exec(vararg commands)` returns `ShellResult(code, stdout, stderr)`, with one combined output and the last-command exit code. Nonzero command exit is an ordinary result. `JOB_NOT_EXECUTED` (-1) indicates transport failure and may occur after side effects; never assume a failed transport proves no mutation happened. `CancellationException` propagates.
- Legacy `Shell.Job.await()` and `asFlow()` cancellation stops waiting/collection; it does not terminate the command. The shared shell stays occupied until execution/drain finishes. `asFlow()` emits tagged `ShellLine`s and uses an unlimited channel; do not use it for an unbounded stream without an explicit lifetime and memory plan.
- Legacy jobs run in the persistent shell: `cd`, exports, functions and sourced scripts can affect later jobs; top-level `exit` kills that shell.
- Opt-in `shell.prepareIsolatedJob(*commands)` prepares a single-use `JobHandle`; `submit()` commits it once. `shell.submitIsolated(*commands)` combines both. Commands share state within that job, inherit cwd/exported environment, and do not mutate the persistent shell state. Functions and other unexported state are not inherited. Output is captured to temporary files and delivered at completion; this is not a streaming API.
- Isolated jobs require Android toybox `setsid -w` and a separately acquired control shell with matching root identity. Control acquisition can be denied even when the original root shell remains alive. In that case user work is not dispatched.

## Cancellation and acknowledgement

```kotlin
import com.valhalla.superuser.ktx.await
import com.valhalla.superuser.ktx.getShellAwait

val shell = getShellAwait()
val handle = shell.prepareIsolatedJob("your bounded command")
handle.submit()
// Another owner can call handle.cancel(). Awaiting coroutine cancellation alone does not do so.
val outcome = handle.await()
```

A handle identifies one execution, not a reusable command builder. Cancel before submission or while queued guarantees it never starts. Cancel during execution requests TERM, then KILL escalation, with a bounded control deadline. `cancel()` is idempotent while pending; await `completion` (Java CompletionStage) or `await()` before releasing a consumer lease.

Branch on `JobOutcome.kind`: `EXITED`, `CANCELLED`, `FAILED`, `TERMINATION_UNCONFIRMED`. Check `terminationConfirmed`, `outputDrained`, `shellReusable`, `started` and nullable `exitCode`; preserve failure details without logging payloads. Confirmed termination covers processes that remain in the job process group. Deliberately detached descendants are outside the guarantee. Odin retires same-group children after normal parent exit too. Cancel does not undo settings, filesystem or other side effects; never automatically retry uncertain mutations.

If a calling coroutine owns the job lifetime, explicitly request cancellation in its catch/finally and await acknowledgement under `NonCancellable`. Preserve the original cancellation. If acknowledgement is uncertain, surface that state and do not silently release a lease as if the command succeeded. Keep existing process watchdogs until the relevant device/root-manager acceptance checks justify removal.

## Root refresh and startup

`Shell.isRoot` describes the cached shell identity; it does not prove current root-manager policy permits new requests. `Shell.isAppGrantedRoot` is a cached observation too. `Shell.invalidateRootAvailability()` marks observations stale without interrupting accepted work. Coroutine `refreshRootAvailability()` returns a fresh `RootAvailability`; the blocking companion method is for Java/worker callers.

Refresh gracefully retires accepted main-shell work, then makes a fresh acquisition. `ROOT` and `NON_ROOT` describe the acquired shell; `BUSY`, `TIMED_OUT` and `FAILED` must not be presented as proven denial. Concurrent requests share an attempt. A denied new request does not revoke the UID of an existing shell. Preserve the app's provider selection/fallback rules. Do not infer loss of root from an arbitrary nonzero exit or permission-denied payload.

Startup publishes the shell only after handshake and initializers complete. One caller cancelling its await does not stop a shared attempt. Initializers must cooperate with deadlines; arbitrary blocking application code cannot be force-stopped safely. Never perform blocking shell acquisition/exec on the UI thread.

## RootService

Subclass `RootService`, return an AIDL Stub from `onBind`, declare the service non-exported, and call `enforceCaller()` in privileged AIDL methods. Bind/unbind according to the existing application lifecycle; `stop()` tears down the service process. RootService is separate from Shell.Job cancellation and does not return ShellResult automatically. Follow the resolved version's RootService signatures and `docs/USAGE.md` examples.

## Evidence before adoption

Test real application/gateway authority. Use disposable commands with submission/process markers and verify next-job output isolation, queued cancellation, ignored TERM, surviving children, shell death, refresh and startup cleanup. Record SHA, artifact version, API/root manager, passed/failed/skipped counts and unsupported cases. A skipped opt-in test is not a pass. Publish to Central only when the user has authorized release and the required gates pass; this skill does not itself authorize publication.

Nested watchdogs such as toybox `timeout` must use `--foreground` inside isolated jobs: the default
creates a new process group and moves helpers outside Odin cancellation scope. Commands that
explicitly detach or change process group require their own termination contract.
