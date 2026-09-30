# Odin

Kotlin-first root shell + `RootService` IPC for Android — inspired by [topjohnwu/libsu](https://github.com/topjohnwu/libsu), reimagined around Kotlin and coroutines.

## Install

```kotlin
dependencies {
    implementation("com.trinadhthatakula:odin:1.1.0")
}
```

Requires `minSdk 24`. Published through Maven Central.

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

## What it provides

- A persistent root shell with a coroutine-friendly API (`suspend` command execution, `Flow` of output).
- A generic `RootService` framework (Binder/AIDL) for running privileged code in a root process.

## Isolated cancellation and fresh root checks

Odin 1.1 adds `Shell.prepareIsolatedJob()` / `submitIsolated()`, an idempotent cancellation handle,
termination acknowledgement, and `refreshRootAvailability()`. Existing persistent jobs retain their
shell-state and coroutine-cancellation semantics. See [lifecycle usage](docs/USAGE.md#isolated-jobs-and-cancellation)
for termination scope, output behavior, and root-refresh outcomes.

Agents integrating Odin can use [.claude/skills/odin/SKILL.md](.claude/skills/odin/SKILL.md).
Run `python3 scripts/install-integration-skill.py` to install it into common agent skill directories.
The installer copies the complete skill package, including shell, RootService and artifact-validation
references. Agents can invoke `$odin` from another app checkout; automatic discovery is enabled.

## License

Odin is licensed under the **Apache License 2.0** (see [`LICENSE`](LICENSE)), with credit to [libsu](https://github.com/topjohnwu/libsu) (also Apache-2.0), which inspired its design.

## Releasing

See [`docs/PUBLISHING.md`](docs/PUBLISHING.md). Publishing is triggered by pushing to the `production` branch.
