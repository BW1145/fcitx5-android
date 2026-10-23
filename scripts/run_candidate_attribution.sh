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
status=0
timeout 900s adb shell am instrument -w -r -e candidateAttribution true \
 -e class org.fcitx.fcitx5.android.PersonalCandidateAttributionTest \
 "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$output/instrumentation.txt" || status=$?
adb exec-out run-as "$package" cat files/candidate-attribution.json > "$output/candidate-attribution.json" || true
if ((status != 0)); then exit "$status"; fi
python3 - "$output/instrumentation.txt" <<'PY'
import re,sys
text=open(sys.argv[1]).read()
assert not re.search(r'INSTRUMENTATION_FAILED|FAILURES!!!|shortMsg=',text),text
assert 'OK (1 test)' in text,text
assert len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$',text,re.M))==1,text
PY
python3 scripts/summarize_candidate_attribution.py "$output/candidate-attribution.json" | tee "$output/summary.txt"
