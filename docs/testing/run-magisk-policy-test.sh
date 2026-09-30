#!/bin/sh
# Run the explicitly coordinated policy test from the dedicated Thor integration checkout.
set -eu
serial=${ANDROID_SERIAL:-emulator-5582}
package=com.valhalla.thor.debug
uid=$(adb -s "$serial" shell cmd package list packages -U "$package" | awk -v expected="package:$package" '$1 == expected {print}' | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | tr -d '\r')
case "$uid" in ''|*[!0-9]*) exit 2;; esac
log=${1:-/tmp/odin-magisk-policy.log}
wait_identity() {
  attempts=0
  until [ "$(adb -s "$serial" shell id -u 2>/dev/null | tr -d '\r')" = "$1" ]; do
    attempts=$((attempts+1))
    [ "$attempts" -lt 30 ] || return 1
    sleep 1
  done
}
adb -s "$serial" root
adb -s "$serial" wait-for-device
wait_identity 0
runner=
restore() {
  if [ -n "$runner" ]; then
    kill "$runner" 2>/dev/null || true
    wait "$runner" 2>/dev/null || true
  fi
  adb -s "$serial" shell am force-stop "$package" >/dev/null
  adb -s "$serial" shell "/debug_ramdisk/magisk --sqlite 'UPDATE policies SET policy=2 WHERE uid=$uid'" >/dev/null
  adb -s "$serial" unroot >/dev/null
  adb -s "$serial" wait-for-device
  wait_identity 2000
}
trap restore EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
adb -s "$serial" shell am force-stop "$package"
adb -s "$serial" shell "run-as $package rm -f cache/odin-policy-ready cache/odin-policy-denied cache/odin-policy-granted"
adb -s "$serial" shell "/debug_ramdisk/magisk --sqlite 'INSERT OR REPLACE INTO policies (uid,policy,until,logging,notification) VALUES ($uid,2,0,1,1)'"
adb -s "$serial" shell am instrument -w -r -e class com.valhalla.thor.data.gateway.root.OdinRootPolicyIntegrationTest -e odinPolicyToggle true "$package.test/com.valhalla.thor.ThorTestRunner" > "$log" 2>&1 &
runner=$!
wait_marker() {
  attempts=0
  until [ "$(adb -s "$serial" shell "run-as $package cat cache/odin-policy-ready" 2>/dev/null | tr -d '\r')" = "$1" ]; do
    kill -0 "$runner" 2>/dev/null || exit 4
    attempts=$((attempts+1))
    [ "$attempts" -lt 90 ] || { kill "$runner"; exit 3; }
    sleep 1
  done
}
wait_marker ready
adb -s "$serial" shell "/debug_ramdisk/magisk --sqlite 'UPDATE policies SET policy=1 WHERE uid=$uid'"
# Magisk caches policy briefly; the app independently waits for fresh su denial.
adb -s "$serial" shell "run-as $package touch cache/odin-policy-denied"
wait_marker denial-verified
adb -s "$serial" shell "/debug_ramdisk/magisk --sqlite 'UPDATE policies SET policy=2 WHERE uid=$uid'"
adb -s "$serial" shell "run-as $package touch cache/odin-policy-granted"
wait "$runner"
runner=
rg 'OK \(1 test\)' "$log"
