# Dedicated Magisk emulator

Created for Odin/Thor lifecycle acceptance on 2026-09-30.

- AVD: `Odin_Magisk_API36_1`, serial `emulator-5582`, port 5582.
- Image: Android 16 / SDK full 36.1, ARM64 Google APIs 16 KB image; SELinux enforcing.
- Root manager: Magisk 30.7. Ordinary app ProcessBuilder("su", "-c", ...) grant produced UID 0 in `u:r:magisk:s0`; deny produced exit 13 / Permission denied. Reboot persistence verified during provisioning.
- Private image copy: `/Users/trinadhthatakula/StudioProjects/Odin-test-emulator/system-image`. Shared SDK images and existing AVDs were not patched.
- Start: `/Users/trinadhthatakula/StudioProjects/Odin-test-emulator/start-emulator.sh -no-window`.
- Configure an installed package's Magisk policy: `/Users/trinadhthatakula/StudioProjects/Odin-test-emulator/set-magisk-policy.sh <package> grant|deny|prompt`. This briefly roots adbd to configure this disposable userdebug AVD, then restores shell UID. Test root through the app/gateway after adbd is unrooted.
- The private image has a persistent Magisk boot script that makes normal `su` resolve to Magisk rather than the stock userdebug root/shell-only su binary.
- After installing Thor's lifecycle integration APKs, run `sh docs/testing/run-magisk-policy-test.sh` from Odin to exercise existing-shell identity, fresh denial and re-grant. The script restores the app grant and shell-UID adbd on exit. Magisk's policy cache has a sliding three-second lifetime; the instrumentation test spaces fresh-su probes accordingly.

The image uses rootAVD's fake boot-image approach with Magisk's own boot patcher to accommodate Magisk 30 binary names. Local provisioning artifacts and probe app are retained in `Odin-test-emulator`. This is an emulator test environment, not evidence for every production root manager or device.
