#!/usr/bin/env bash
set -euo pipefail

mode="${1:-full}"
main_id="${MAIN_APPLICATION_ID:-org.fcitx.fcitx5.android.bw1145}"
package="$main_id.debug"
test_package="$package.test"

if [[ "$mode" == "compat" ]]; then
    apk_dir="${2:-test-apks}"
    mkdir -p compatibility-results
    adb install -r "$apk_dir/app-debug.apk"
    adb install -r "$apk_dir/app-debug-androidTest.apk"
    classes='org.fcitx.fcitx5.android.PersonalContentSearchTest#searchEditorCompatibility,org.fcitx.fcitx5.android.PersonalDataSafetyTest'
    adb shell am instrument -w -r -e class "$classes" \
        "$test_package/androidx.test.runner.AndroidJUnitRunner" | tee compatibility-results/instrumentation.txt
    python3 - <<'PY'
from pathlib import Path
import re

report = Path("compatibility-results/instrumentation.txt").read_text()
if not re.search(r"OK \([1-9][0-9]* tests?\)", report):
    raise SystemExit("API 23 instrumentation did not finish successfully")
if re.search(r"INSTRUMENTATION_FAILED|FAILURES!!!|shortMsg=", report):
    raise SystemExit("API 23 instrumentation reported a failure")
completed = set()
status = {}
for line in report.splitlines():
    if line.startswith("INSTRUMENTATION_STATUS: "):
        key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
        status[key] = value
    elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
        if line.endswith(": 0"):
            completed.add((status.get("class"), status.get("test")))
        status = {}
search = ("org.fcitx.fcitx5.android.PersonalContentSearchTest", "searchEditorCompatibility")
if search not in completed:
    raise SystemExit("The Android 6 search editing test did not complete")
data_tests = {method for cls, method in completed
              if cls == "org.fcitx.fcitx5.android.PersonalDataSafetyTest"}
if len(data_tests) < 7:
    raise SystemExit(f"Expected all seven Android 6 data safety tests, found {data_tests}")
print(f"API 23 compatibility passed: {len(completed)} tests")
PY
    exit 0
fi

if [[ "$mode" != "full" ]]; then
    echo "Unknown smoke test mode: $mode" >&2
    exit 2
fi

adb install -r "$(find app/build/outputs/apk/debug -name '*.apk' -print -quit)"
for plugin in chewing clipboard-filter; do
    apk=$(find "plugin/$plugin/build/outputs/apk/debug" -name '*.apk' -print -quit)
    adb install -r "$apk"
done

result=0
run_group() {
    local group="$1"
    local classes="$2"
    if ! ./gradlew -PbuildABI=x86_64 "-Pandroid.testInstrumentationRunnerArguments.class=$classes" :app:connectedDebugAndroidTest; then
        result=1
    fi
    local reports="app/build/reports/androidTests"
    local outputs="app/build/outputs/androidTest-results"
    mkdir -p "$reports/$group" "$outputs/$group"
    if [[ -d "$reports/connected" && -d "$outputs/connected" ]]; then
        cp -a "$reports/connected/." "$reports/$group/"
        cp -a "$outputs/connected/." "$outputs/$group/"
    else
        echo "Missing instrumentation reports for $group" >&2
        result=1
    fi
    if ! python3 - "$outputs/$group" "$classes" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

completed = set()
for path in Path(sys.argv[1]).rglob("*.xml"):
    root = ET.parse(path).getroot()
    for case in root.iter("testcase"):
        if not any(case.find(tag) is not None for tag in ("failure", "error", "skipped")):
            completed.add((case.get("classname"), case.get("name")))
for expected in sys.argv[2].split(","):
    if not any(cls == expected for cls, _ in completed):
        raise SystemExit(f"No completed instrumentation tests for {expected}")
print(f"Verified executed test classes: {sys.argv[2]} ({len(completed)} tests)")
PY
    then
        result=1
    fi
}

run_group personal-native 'org.fcitx.fcitx5.android.PersonalRimeTest,org.fcitx.fcitx5.android.PersonalDataSafetyTest,org.fcitx.fcitx5.android.PersonalPluginContractTest'
run_group personal-keyboard 'org.fcitx.fcitx5.android.PersonalKeyboardUiTest'
run_group personal-search 'org.fcitx.fcitx5.android.PersonalContentSearchTest'

if ! grep -r -q 'testcase name="screenTapEditsPinyinAndJapaneseCandidatesAppearWhileTyping"' app/build/outputs/androidTest-results/personal-keyboard; then
    echo 'The keyboard screen interaction test did not run'
    result=1
fi

mkdir -p app/build/reports/androidTests/connected/screens
for image in pinyin-edit japanese-predictions keyboard-final; do
    adb pull "/data/local/tmp/fcitx5-$image.png" "app/build/reports/androidTests/connected/screens/$image.png" || true
done
exit "$result"

