# Dependencies, migration and validation

## Published dependency

Add `mavenCentral()` to the existing dependency repository block and pin
`implementation("com.trinadhthatakula:odin:1.1.0")` or a catalog alias in the Android module.
Keep repositories consistent with `repositoriesMode`. Do not add JitPack for Odin or transplant
Odin's AGP/compileSdk into another app. Check the resolved artifact's JVM/Kotlin/Android metadata
against the toolchain. 1.1.0 requires minSdk 24 and uses JVM 21 bytecode; diagnose compiler
mismatches instead of guessing APIs.

For libsu/older Odin migration, inspect initializer, callback, cwd/export, stream, stderr and IPC
behavior. `ShellRepository`/`RealShellRepository`/`ShellResult`/`ShellLine` are in
`com.valhalla.superuser.ktx`. Replace deprecated `runCommand`/`runCommands` with `exec` and handle
code/stdout/stderr. `Shell.Job.await()` returns core `Shell.Result`; repository `exec()` returns
immutable ktx `ShellResult`. Do not globally isolate stateful jobs or jobs needing live output.

## Maven Local for library changes

Use a unique candidate instead of overwriting a released coordinate:

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew publishToMavenLocal -PVERSION_NAME=1.1.0-my-change-SNAPSHOT
```

Scope local resolution to that exact coordinate/version in the consumer's settings:

```kotlin
val odinLocalVersion = providers.gradleProperty("odinLocalVersion").orNull
dependencyResolutionManagement {
    repositories {
        google()
        if (odinLocalVersion != null) {
            exclusiveContent {
                forRepository { mavenLocal() }
                filter { includeVersion("com.trinadhthatakula", "odin", odinLocalVersion) }
            }
        }
        mavenCentral()
    }
}
```

Override the Android module's dependency only when the property exists:

```kotlin
val odinLocalVersion = providers.gradleProperty("odinLocalVersion").orNull
dependencies {
    implementation("com.trinadhthatakula:odin:${odinLocalVersion ?: "1.1.0"}")
}
```

Adapt to the existing catalog/settings scope; do not duplicate dependencies. Disable Odin
composite substitution for artifact validation. Thor already supports `-PodinLocalVersion=...`;
other apps implement their own equivalent. Confirm the dependency:

```sh
./gradlew :app:dependencyInsight --dependency com.trinadhthatakula:odin \
  --configuration debugRuntimeClasspath -PodinLocalVersion=1.1.0-my-change-SNAPSHOT
```

Use the actual module/variant configuration (Thor uses `fossDebugRuntimeClasspath`). After an
authorized release, confirm Central POM/AAR availability, remove local/composite overrides and
rerun dependency inspection and consumer gates. Gradle caches missing artifacts; use
`--refresh-dependencies` for first Central resolution after propagation. A successful workflow
or portal upload alone does not prove Central availability.

## Meaningful acceptance

Run the consumer's required build/unit/lint gates and assemble affected tests. Choose device
checks for the adopted feature; a finite property read does not require IPC acceptance. Record
artifact/version/source, SHA, API/root manager and passed/failed/skipped counts. Skipped opt-in
root tests are not evidence.

For isolated jobs use disposable process/command markers to verify prepared/queued cancellation,
ignored TERM, surviving same-group children, drain/next-job output isolation, shell death,
startup failure/recovery and acknowledgement before lease release. Exercise the app gateway,
not just adb. For refresh test deny/re-grant: existing UID0 differs from fresh authorization.

For IPC test Binder/owner lifecycle and minified loading. For mutations use disposable values,
restore them and retain watchdog/recovery journal until the device matrix establishes replacement.

The local `Odin_Magisk_API36_1` AVD is SDK full 36.1, Magisk 30.7, ARM64/16 KB, SELinux enforcing.
It is useful evidence, not a prerequisite for another app or proof of all managers. Restore host
policy/adbd fixture changes and consult the consumer's existing tests for invocation.

Public 1.1.0 source and artifact references:

- https://github.com/trinadhthatakula/Odin/tree/1741da74bd0154d984eb780b2cbd1a02b0d3e1ac
- https://github.com/trinadhthatakula/Odin/blob/1741da74bd0154d984eb780b2cbd1a02b0d3e1ac/odin/api/odin.api
- https://repo.maven.apache.org/maven2/com/trinadhthatakula/odin/1.1.0/
