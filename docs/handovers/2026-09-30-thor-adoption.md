# Odin 1.1.0 adoption handover

Odin 1.1.0 is published through the production GitHub workflow and available from Maven Central.
Release implementation: [Odin #15](https://github.com/trinadhthatakula/Odin/pull/15), merged SHA
`1741da74bd0154d984eb780b2cbd1a02b0d3e1ac`. Thor adoption:
[Thor #531](https://github.com/trinadhthatakula/Thor/pull/531), targeting latest merged-#530 `dev`.

Implemented: bounded owned startup with ready-only publication; opt-in isolated single-use jobs
with explicit cancellation/termination outcomes; explicit cache invalidation and bounded fresh root
refresh. Legacy persistent shell and wait-only coroutine cancellation contracts are retained.

Thor Settings Editor uses isolated jobs, requests termination on caller cancellation, and awaits
completion/output acknowledgement before releasing its root lease. Its toybox watchdog remains
and uses `--foreground` so helpers stay within the job process group. Explicit privilege refresh
invalidates Odin observations before existing provider probes. Other shell consumers retain
persistent execution. The catalog pins Central 1.1.0; `-PodinLocalVersion=<candidate>` supports
exact-coordinate Maven Local validation without Odin composite substitution.

Consumer validation: 6,214 unit tests, zero failures/errors/skips; FOSS debug and Store release
lint have zero errors/warnings; app and androidTest assembly passed against Central. Ten lifecycle
checks, deadline/lease acknowledgement, policy deny/re-grant and disposable Settings Editor values
were tested through app authority on Magisk SDK36.1; physical API36 root passed the lifecycle and
deadline tests during Maven Local acceptance. See [validation](../testing/2026-09-30-lifecycle-validation.md)
for exact evidence and limitations. Thor original checkout remains at `d24b97e6`, clean.

The reusable [Odin skill](../../.claude/skills/odin/SKILL.md) supports other Android apps and contains
portable recipes for repository use, quoting, isolated ownership/cancellation, typed refresh,
finite streaming, RootService/AIDL and candidate/Central validation. Install the entire package
with `python3 scripts/install-integration-skill.py`; invoke `$odin` from the target app checkout.
Codex, Claude, `.agents` and Gemini user copies are installed. RootService examples were compiled
and exercised in a minified sample with app-authorized root.

Remaining Thor-owned work: journal/recovery UI and broader supported root-manager/device acceptance.
Detached/process-group-changing descendants and non-cooperative initializers remain outside Odin's
termination guarantees. Isolated output is completion-only. Do not remove watchdogs or retry uncertain
mutations based on these tests. Review/merge Thor #531 through its normal dev workflow; no Thor
release or versionCode bump was performed.

The dedicated [Magisk emulator](../testing/magisk-api36.1.md) is retained at
`~/StudioProjects/Odin-test-emulator`, serial `emulator-5582`; start with `start-emulator.sh -no-window`.
Shared SDK ramdisk remains unmodified. The supplied untracked `handover.md` was preserved.
