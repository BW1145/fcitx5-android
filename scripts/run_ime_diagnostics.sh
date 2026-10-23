#!/usr/bin/env bash
set -euo pipefail
apk_root="${1:?baseline and candidate APK directory required}"
output="${2:-build/ime-diagnostics}"
package="${MAIN_APPLICATION_ID:-org.fcitx.fcitx5.android.bw1145}.debug"
mkdir -p "$output"
adb shell getprop > "$output/emulator-properties.txt"
# This runner uses a disposable emulator. Never attach it to a personal device.
[[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Disposable emulator required'; exit 1; }
original_ime=$(adb shell settings get secure default_input_method | tr -d '\r')
run_test() {
    local class="$1" count="$2" report="$3"
    # Force-stopping an instrumented IME while selected can leave a stale system binding.
    if [[ -n "$original_ime" && "$original_ime" != null && "$original_ime" != "$package/"* ]]; then
        adb shell ime set "$original_ime"
    fi
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
# Preserve the completed A/B result even if an independent later suite fails.
python3 scripts/summarize_ime_diagnostics.py "$output"
# Same real-keyboard frame fixture compiled into both APKs; separate from engine timings.
for revision in baseline candidate; do
    adb install -r -d "$apk_root/$revision/app.apk"
    adb install -r -d -t "$apk_root/$revision/tests.apk"
    adb shell pm clear "$package" | grep -q Success
    if run_test org.fcitx.fcitx5.android.ArtKeyboardFrameTest 1 "$output/$revision-frames-instrumentation.txt"; then
        adb pull "/sdcard/Android/data/$package/files/art-frame-benchmark" "$output/$revision-frames"
    else
        # Preserve screenshots/frame samples even when the character assertion fails.
        adb pull "/sdcard/Android/data/$package/files/art-frame-benchmark" "$output/$revision-frames" || true
        exit 1
    fi
done
# Functional/UI and deterministic Binder speech tests run against the candidate.
adb install -r -d "$apk_root/candidate/app.apk"
adb install -r -d -t "$apk_root/candidate/tests.apk"
adb shell pm clear "$package" | grep -q Success
adb shell pm grant "$package" android.permission.RECORD_AUDIO
adb shell pm grant "$package.test" android.permission.RECORD_AUDIO
# Disposable emulator, synthetic speech fixtures only. Clear earlier input logs
# so this diagnostic window cannot include another suite's typed-text trace.
adb logcat -c
speech_status=0
run_test org.fcitx.fcitx5.android.SpeechServiceContractTest 2 "$output/speech-instrumentation.txt" || speech_status=$?
adb logcat -d -v threadtime > "$output/speech-system-logcat.txt" || true
adb shell dumpsys speech_recognition > "$output/speech-recognition-service.txt" || true
adb shell dumpsys activity services > "$output/speech-service-bindings.txt" || true
adb shell dumpsys package "$package" > "$output/speech-target-package.txt" || true
adb shell dumpsys package "$package.test" > "$output/speech-test-package.txt" || true
adb shell cmd package query-services --brief -a android.speech.RecognitionService > "$output/speech-service-resolution.txt" || true
if ((speech_status != 0)); then exit "$speech_status"; fi
# Test-only frozen renderers compare exact pixels and software draw CPU cost.
# Capture only the benchmark tag: no global logcat or typed-text trace.
adb logcat -c
run_test org.fcitx.fcitx5.android.data.theme.ArtRenderingEquivalenceTest 2 "$output/art-render-instrumentation.txt"
adb logcat -d -v threadtime ArtRenderBenchmark:I '*:S' > "$output/art-render-benchmark.txt"
run_test org.fcitx.fcitx5.android.input.keyboard.KeyRenderingEquivalenceTest 1 "$output/key-render-instrumentation.txt"
run_test org.fcitx.fcitx5.android.PersonalKeyboardUiTest 1 "$output/keyboard-instrumentation.txt"
for name in pinyin-edit japanese-predictions keyboard-final; do
    adb pull "/data/local/tmp/fcitx5-$name.png" "$output/$name.png"
done
