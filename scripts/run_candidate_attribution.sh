#!/usr/bin/env bash
set -euo pipefail
apk_root="${1:?profiling APK root required}"
output="${2:-build/candidate-attribution}"
package="${MAIN_APPLICATION_ID:-org.fcitx.fcitx5.android.bw1145}.debug"
mkdir -p "$output"
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Disposable emulator required'; exit 1; }
adb shell getprop > "$output/emulator-properties.txt"
adb install -r -d "$apk_root/candidate/app.apk"
adb install -r -d -t "$apk_root/candidate/tests.apk"
adb shell pm clear "$package" | grep -q Success
adb logcat -c
adb logcat -b all -v threadtime > "$output/logcat.txt" 2>&1 &
logcat_pid=$!
collect_diagnostics() {
  result=$?
  trap - EXIT
  adb logcat -b crash -d -v threadtime > "$output/crash-logcat.txt" 2>&1 || true
  adb shell dumpsys activity exit-info "$package" > "$output/process-exit-info.txt" 2>&1 || true
  adb shell dumpsys dropbox --print SYSTEM_TOMBSTONE > "$output/dropbox-tombstone.txt" 2>&1 || true
  adb shell dumpsys dropbox --print data_app_native_crash > "$output/dropbox-native-crash.txt" 2>&1 || true
  adb shell ls -l /data/tombstones > "$output/tombstone-access.txt" 2>&1 || true
  adb pull /data/tombstones "$output/tombstones" >> "$output/tombstone-access.txt" 2>&1 || true
  adb exec-out run-as "$package" cat files/candidate-attribution-progress.txt > "$output/progress.txt" 2>&1 || true
  kill "$logcat_pid" 2>/dev/null || true
  wait "$logcat_pid" 2>/dev/null || true
  exit "$result"
}
trap collect_diagnostics EXIT
status=0
timeout 900s adb shell am instrument -w -r -e candidateAttribution true \
 -e class org.fcitx.fcitx5.android.PersonalCandidateAttributionTest \
 "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$output/instrumentation.txt" || status=$?
if adb exec-out run-as "$package" cat files/candidate-attribution.json > "$output/candidate-attribution.json.partial" 2> "$output/report-pull-error.txt"; then
  mv "$output/candidate-attribution.json.partial" "$output/candidate-attribution.json"
fi
if ((status != 0)); then exit "$status"; fi
python3 - "$output/instrumentation.txt" <<'PY'
import re,sys
text=open(sys.argv[1]).read()
assert not re.search(r'INSTRUMENTATION_FAILED|FAILURES!!!|shortMsg=',text),text
assert 'OK (1 test)' in text,text
assert len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$',text,re.M))==1,text
PY
python3 scripts/summarize_candidate_attribution.py "$output/candidate-attribution.json" | tee "$output/summary.txt"
