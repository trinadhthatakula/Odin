---
name: odin
description: Integrate or migrate the Odin Android root-shell and RootService library in an app. Use for com.trinadhthatakula:odin, privileged shell execution, isolated job cancellation, root refresh, or Binder code running as root.
---

# Odin integration

Coordinate: `com.trinadhthatakula:odin:1.1.0`. Packages: `com.valhalla.superuser` (core),
`com.valhalla.superuser.ktx` (coroutines/repository), `com.valhalla.superuser.ipc` (RootService).
Odin is Android-only, minSdk 24; 1.1.0 has JVM 21 bytecode. Verify the consumer's Android build
toolchain can consume it. A multiplatform app integrates Odin in its Android source set.
Root comes from the app's authorization through Magisk/KernelSU/etc.; adb root proves nothing
about app authorization. Odin does not grant Android runtime or special-access permissions.

Inspect the app's version catalog, root/provider routing, shell ownership and command lifetimes
before editing. Preserve its DI framework and fallback rules. Prefer its repository or gateway
over static shell calls in UI code. Do not run blocking acquisition/exec on the main thread.
Lifecycle APIs below require 1.1.0 or later; verify the resolved version's signatures.

## Select the execution contract

| App need | Odin API | Contract |
|---|---|---|
| Bounded persistent commands | `ShellRepository.exec(...)` | Combined output, last-command exit; wait cancellation leaves execution running. |
| Stateful shell jobs | `getShellAwait().newJob()` | `cd`, exports/functions/sourcing persist; top-level `exit` kills that shell. |
| Bounded commands with termination | `prepareIsolatedJob(...)` / `submitIsolated(...)` | Single-use handle, explicit cancellation, output delivered at completion. |
| Finite line-by-line output | `Shell.Job.asFlow()` | Tagged streams, unlimited channel; cancelling collection leaves the command running. |
| Privileged Kotlin/Java framework calls | `RootService` + AIDL | Separate Binder process/lifecycle; shell-job cancellation does not cancel IPC work. |

Read [shell recipes](references/shell-recipes.md) for repository wiring, quoting, result handling,
coroutine-owned cancellation and root refresh. Read [RootService integration](references/rootservice.md)
only for Binder IPC. Read [dependencies and validation](references/dependencies-and-validation.md)
for Maven Local, migrations, release verification or device acceptance.

## Preserve lifecycle guarantees

- Nonzero exit is an ordinary command result. `ShellResult.JOB_NOT_EXECUTED` (-1) is transport
  failure and may follow side effects. Never replay an uncertain mutation automatically.
- Legacy `await()`/`asFlow()` cancellation stops observation, not execution. Persistent jobs keep
  the queue until drain completes. Do not promise termination from coroutine timeout.
- An isolated handle identifies one execution. `submit()` commits once; `cancel()` is idempotent
  while pending. Cancel before dispatch prevents execution. Running cancellation uses TERM then
  KILL, with a five-second control deadline. Await acknowledgement before releasing an owned lease.
- Branch on `JobOutcome.kind` (`EXITED`, `CANCELLED`, `FAILED`, `TERMINATION_UNCONFIRMED`) and retain
  `started`, nullable `exitCode`, `terminationConfirmed`, `outputDrained`, `shellReusable`, and
  failure detail. Uncertain termination must not become ordinary success or a proven rollback.
- Isolated cancellation covers descendants remaining in the job process group; deliberately
  detached/process-group-changing descendants need their own contract. Surviving same-group
  children are retired after normal exit too. Nested toybox `timeout` must use `--foreground`.
- Commands share state within an isolated job and inherit cwd/exported environment. Their changes
  never persist into later jobs; functions and unexported state are not inherited. Output uses
  private cache files and is returned as lists; bound output and lifetime for this API.
- Isolation requires toybox `setsid -w` and a fresh control shell with matching root identity.
  An existing root shell can survive policy denial while fresh control acquisition fails before
  user dispatch. Do not weaken the authority check or fall back by replaying a mutation.

## Interpret root and startup accurately

`Shell.isRoot` describes existing identity; `Shell.isAppGrantedRoot` is a cached observation.
Neither proves the manager permits fresh requests. Explicit invalidation marks caches stale
without interrupting accepted work. `refreshRootAvailability()` gracefully retires main-shell
work (five-second BUSY bound), then acquires a fresh ready shell; concurrent requests share an
attempt. `ROOT`/`NON_ROOT` describe acquisition; `BUSY`/`TIMED_OUT`/`FAILED` do not prove denial.
Do not declare revocation from arbitrary command failure or a permission-denied payload.

Startup publishes only after handshake/initializers complete under one builder deadline.
Initializers must cooperate with interruption; arbitrary non-cooperative application code cannot
be forcibly stopped safely. One cancelled waiter does not cancel shared startup or refresh.

Complete the integration and report resolved version/source, validation results and device limits.
Skill use does not expand the user's task into publishing a library or releasing their app;
retain existing authorization when publication is already part of the task.
