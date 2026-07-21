# Odin

Kotlin-first root shell + `RootService` IPC for Android — inspired by [topjohnwu/libsu](https://github.com/topjohnwu/libsu), reimagined around Kotlin and coroutines.

## Install

```kotlin
dependencies {
    implementation("com.trinadhthatakula:odin:1.0.0")
}
```

Requires `minSdk 24`. Will be published to Maven Central from `1.0.0`.

## What it provides

- A persistent root shell with a coroutine-friendly API (`suspend` command execution, `Flow` of output).
- A generic `RootService` framework (Binder/AIDL) for running privileged code in a root process.

## License

Odin is licensed under the **Apache License 2.0** (see [`LICENSE`](LICENSE)), with credit to [libsu](https://github.com/topjohnwu/libsu) (also Apache-2.0), which inspired its design.

## Releasing

See [`docs/PUBLISHING.md`](docs/PUBLISHING.md). Publishing is triggered by pushing to the `production` branch.
