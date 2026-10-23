#!/usr/bin/env bash
set -euo pipefail
apk_root="${1:?baseline and candidate APK directory required}"
output="${2:-build/ime-diagnostics}"
package="${MAIN_APPLICATION_ID:-org.fcitx.fcitx5.android.bw1145}.debug"
mkdir -p "$output"
adb shell getprop > "$output/emulator-properties.txt"
# This runner uses a disposable emulator. Never attach it to a personal device.
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Disposable emulator required'; exit 1; }
run_test() {
    local class="$1" count="$2" report="$3"
    timeout 600s adb shell am instrument -w -r -e class "$class" \
        "$package.test/androidx.test.runner.AndroidJUnitRunner" | tee "$report"
    python3 - "$report" "$count" <<'PY'
import re,sys
text=open(sys.argv[1]).read(); count=int(sys.argv[2])
assert not re.search(r'INSTRUMENTATION_FAILED|FAILURES!!!|shortMsg=',text),text
assert re.search(rf'OK \({count} tests?\)',text),text
assert len(re.findall(r'^INSTRUMENTATION_STATUS_CODE: 0\s*$',text,re.M))==count,text
PY
}
# ABBA reduces first/last-run effects; every trial starts with clean app data.
for entry in baseline:1 candidate:1 candidate:2 baseline:2; do
    revision="${entry%:*}"; trial="${entry#*:}"
    adb install -r -d "$apk_root/$revision/app.apk"
    adb install -r -d -t "$apk_root/$revision/tests.apk"
    adb shell pm clear "$package" | grep -q Success
    run_test org.fcitx.fcitx5.android.PersonalInputLatencyTest 1 "$output/$revision-$trial-instrumentation.txt"
    adb exec-out run-as "$package" cat files/input-latency.json > "$output/$revision-$trial.json"
done
# Functional/UI and deterministic Binder speech tests run against the candidate.
adb install -r -d "$apk_root/candidate/app.apk"
adb install -r -d -t "$apk_root/candidate/tests.apk"
adb shell pm clear "$package" | grep -q Success
adb shell pm grant "$package" android.permission.RECORD_AUDIO
adb shell pm grant "$package.test" android.permission.RECORD_AUDIO
run_test org.fcitx.fcitx5.android.SpeechServiceContractTest 2 "$output/speech-instrumentation.txt"
run_test org.fcitx.fcitx5.android.PersonalKeyboardUiTest 1 "$output/keyboard-instrumentation.txt"
for name in pinyin-edit japanese-predictions keyboard-final; do
    adb pull "/data/local/tmp/fcitx5-$name.png" "$output/$name.png"
done
python3 scripts/summarize_ime_diagnostics.py "$output"
