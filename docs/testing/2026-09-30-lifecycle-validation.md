# Lifecycle validation

Odin topic branch `feat/shell-lifecycle`, based on `c895af1`; Thor dedicated topic checkout
`~/StudioProjects/Thor-odin-lifecycle`, based on merged PR #530 at `8de89948`.

Completed:
- Odin JDK 21 unit tests: 20, zero failures/errors/skips. Covers startup timeout/process/reader cleanup, initializer failure, later recovery, interruption/scheduler progress, output framing, prepared/queued cancellation and observation cancellation.
- Odin release assembly, API compatibility check and debug lint passed. API dump reviewed as additive to the existing public ABI.
- Unique Maven Local candidate `1.1.0-lifecycle-20260930-SNAPSHOT` resolved by Thor dependencyInsight; full Thor unit and FOSS debug / Store release lint passed (6,214 unit tests, zero failures/skips).
- Physical Android 16/API 36 root via Thor application: 10/10 lifecycle checks passed; Settings Editor deadline/lease test passed after foreground watchdog correction.
- Dedicated Magisk 30.7 Android 16/SDK full 36.1 ARM64 16 KB emulator, SELinux enforcing: 10/10 lifecycle checks passed through application-authorized root; Settings Editor deadline/lease test passed after foreground watchdog correction.
- Host-coordinated Magisk policy test passed: existing shell retains UID 0 after deny; refresh acquires NON_ROOT; grant followed by refresh acquires ROOT; both cached observations follow fresh acquisition. 1 test, zero failures/skips. Ordinary app UID is used even though adbd was temporarily rooted only to modify policy.
- Integration skill validator and installer passed. Canonical skill installed into Codex, Claude, shared .agents and Gemini user roots.
- Shared Android SDK image ramdisk SHA-256 remained `10c7807adeb0c99a474e890615a3101ea8d3f6356821cce28a69e1833199dc29` after dedicated emulator provisioning.

Observed failures and corrections:
- setsid without `-w` returned launcher status rather than script exit status; corrected to wait mode.
- Nested toybox timeout creates a separate process group by default, leaving a Settings Editor helper outside cancellation scope. Thor now uses `timeout --foreground`; disposable helper cleaned through its authorized gateway and subsequent tests passed.
- Initial API30 userdebug root attempt failed for application UID because stock su permits root/shell only. This is not root acceptance evidence; replaced by the dedicated Magisk emulator. The temporary API30 emulator was restored to enforcing and stopped.
- First live policy run lost the adb instrumentation watcher while adbd restarted. Android test-runner log confirmed zero failures; repeated with a stable watcher and received full passing instrumentation output.

Limits: deliberately detached/process-group-changing descendants and non-cooperative application initializer code are outside the termination guarantee. Isolated output is completion-only private-file capture. No claim of every root manager/device being validated. Keep Thor watchdogs as defense in depth. Settings Editor journal/recovery UI remains Thor-owned follow-up work.

Release-version Maven Local and Central consumer verification are recorded in the final return handover after those gates finish. Publication must use the GitHub production workflow after the required gates pass.
