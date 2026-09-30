# Lifecycle validation

Odin topic branch `feat/shell-lifecycle`, based on `c895af1`; Thor dedicated topic checkout
`~/StudioProjects/Thor-odin-lifecycle`, based on merged PR #530 at `8de89948`.

Completed:
- Odin JDK 21 unit tests: 20, zero failures/errors/skips. Covers startup timeout/process/reader cleanup, initializer failure, later recovery, interruption/scheduler progress, output framing, prepared/queued cancellation and observation cancellation.
- Odin release assembly, API compatibility check and debug lint passed. API dump reviewed as additive to the existing public ABI.
- Unique Maven Local candidate `1.1.0-lifecycle-20260930-SNAPSHOT` resolved by Thor dependencyInsight; full Thor unit and FOSS debug / Store release lint passed (6,214 unit tests, zero failures/skips).
- Final release coordinate `1.1.0` published to Maven Local, resolved by Thor dependencyInsight and passed `test lintFossDebug lintStoreRelease` (6,214 unit tests, zero failures/skips).
- Physical Android 16/API 36 root via Thor application: 10/10 lifecycle checks passed; Settings Editor deadline/lease test passed after foreground watchdog correction.
- Dedicated Magisk 30.7 Android 16/SDK full 36.1 ARM64 16 KB emulator, SELinux enforcing: 10/10 lifecycle checks passed through application-authorized root; Settings Editor deadline/lease test passed after foreground watchdog correction.
- Host-coordinated Magisk policy test passed: existing shell retains UID 0 after deny; refresh acquires NON_ROOT; grant followed by refresh acquires ROOT; both cached observations follow fresh acquisition. 1 test, zero failures/skips. Ordinary app UID is used even though adbd was temporarily rooted only to modify policy.
- Automated policy harness `sh docs/testing/run-magisk-policy-test.sh` passed the same grant/deny test with fresh app-su probes, restored grant and restored adbd to shell UID. The latest instrumentation result is `OK (1 test)` in `/tmp/odin-magisk-policy-script-final.log`.
- Settings Editor disposable-key round trip passed on the Magisk emulator: exact system/secure/global values, undo/conflict/deletion/diagnostics and cleanup/restoration (`OK (1 test)` in `/tmp/thor-magisk-settings-roundtrip.log`).
- Integration skill validator and installer passed. Canonical skill installed into Codex, Claude, shared .agents and Gemini user roots.
- Shared Android SDK image ramdisk SHA-256 remained `10c7807adeb0c99a474e890615a3101ea8d3f6356821cce28a69e1833199dc29` after dedicated emulator provisioning.

Observed failures and corrections:
- setsid without `-w` returned launcher status rather than script exit status; corrected to wait mode.
- Nested toybox timeout creates a separate process group by default, leaving a Settings Editor helper outside cancellation scope. Thor now uses `timeout --foreground`; disposable helper cleaned through its authorized gateway and subsequent tests passed.
- Initial API30 userdebug root attempt failed for application UID because stock su permits root/shell only. This is not root acceptance evidence; replaced by the dedicated Magisk emulator. The temporary API30 emulator was restored to enforcing and stopped.
- First live policy run lost the adb instrumentation watcher while adbd restarted. Android test-runner log confirmed zero failures; repeated with a stable watcher and received full passing instrumentation output.
- Automated host setup initially raced adbd's root restart; now waits for the expected identity before policy SQL. Rapid fresh-su polling kept Magisk's sliding three-second policy cache alive and failed the denial precondition; probes now wait 3.5 seconds between requests. The corrected automated test passed without changing Odin.

Limits: deliberately detached/process-group-changing descendants and non-cooperative application initializer code are outside the termination guarantee. Isolated output is completion-only private-file capture. No claim of every root manager/device being validated. Keep Thor watchdogs as defense in depth. Settings Editor journal/recovery UI remains Thor-owned follow-up work.

## Published artifact and consumer verification

- Odin PR [#15](https://github.com/trinadhthatakula/Odin/pull/15) merged at
  `1741da74bd0154d984eb780b2cbd1a02b0d3e1ac`. The production
  [publish workflow](https://github.com/trinadhthatakula/Odin/actions/runs/36731950210) succeeded.
- Central POM and AAR for `com.trinadhthatakula:odin:1.1.0` fetched successfully. AAR SHA-256:
  `1547ad1385572cab19c00ba118139fd0b76c740dd2095ff18ee6156b9ba2fc45`.
- Thor topic commit `21f0d5597e0e7839e38d2d4afed2854eb7b6f96e`, PR
  [#531](https://github.com/trinadhthatakula/Thor/pull/531), resolves Central 1.1.0 with no Maven Local
  override or composite substitution. `test lintFossDebug lintStoreRelease :app:assembleFossDebug
  :app:assembleFossDebugAndroidTest` passed: 6,214 tests, zero failures/errors/skips; both lint reports
  have zero errors/warnings. Existing compiler warnings remain separate from Android lint.
- After host reboot, the dedicated Magisk emulator booted with SDK full 36.1, SELinux enforcing
  and shell-UID adbd. Central-built Thor passed 10/10 lifecycle tests, 1/1 Settings Editor deadline
  test, and 1/1 automated Magisk deny/re-grant policy test. Policy harness restored grant/adbd.
- The first Thor CI attempt ran before Central propagation and failed solely to resolve Odin 1.1.0.
  Its failed job was rerun after artifact availability; no local repository fallback was added to CI.
- New raw consumer logs are retained under the separate Thor checkout's ignored
  `build/odin-validation/` folder. Earlier `/tmp` logs were cleared by the power interruption;
  their recorded results above remain historical evidence.

## Portable integration skill verification

- Canonical `.claude/skills/odin/` now includes shell recipes, RootService and dependency/validation
  references, plus Codex display metadata. Full package installed and byte-verified in Codex,
  Claude, shared `.agents` and both configured Gemini roots; automatic discovery stays enabled.
- Skill frontmatter validator passed. Kotlin/AIDL examples were compiled against Central 1.1.0
  in an isolated generated Android app, in debug and minified release. The compile check caught
  an AndroidX `@Keep` dependency prerequisite, now documented explicitly.
- Minified sample ran through application-authorized Magisk root (ordinary app UID 10227):
  RootService returned UID0, repository query returned code0, isolated quoted apostrophe/space
  data round-tripped, outcome was EXITED and fresh refresh ROOT. No manifest service declaration
  was needed; app-owned reflective class/constructor survived R8 via `@Keep`.
- Corrected usage docs/skill: RootService extends ContextWrapper and uses Odin binding; stop
  addresses a service and the server exits when no active services remain. This verifies the sample
  integration path, not every production IPC ownership, death or caller-rejection scenario.
- Generated sample/build evidence is ignored under Odin's `build/skill-validation/`; no sample
  mutation or release is added to Thor's original checkout.
