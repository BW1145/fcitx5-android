#!/usr/bin/env bash
set -euo pipefail

mode="${1:-full}"
main_id="${MAIN_APPLICATION_ID:-org.fcitx.fcitx5.android.bw1145}"
package="$main_id.debug"
test_package="$package.test"
result=0
# am instrument force-stops the target process between groups. Leave its IME
# unselected first so Android does not keep a stale binding to that process.
baseline_ime=$(adb shell settings get secure default_input_method | tr -d '\r')

run_group() {
    local group="$1"
    local classes="$2"
    local outputs="$output_root/$group"
    mkdir -p "$outputs"
    if [[ -n "$baseline_ime" && "$baseline_ime" != "null" && "$baseline_ime" != "$package/"* ]]; then
        adb shell ime set "$baseline_ime"
    fi
    adb logcat -c || true
    if ! timeout 600s adb shell am instrument -w -r -e class "$classes" \
        "$test_package/androidx.test.runner.AndroidJUnitRunner" | tee "$outputs/instrumentation.txt"; then
        result=1
    fi
    adb logcat -d -v threadtime > "$outputs/logcat.txt" || true
    if ! python3 - "$outputs" "$group" "$classes" <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

output = Path(sys.argv[1])
group = sys.argv[2]
prefix = "org.fcitx.fcitx5.android."
methods = {
    "PersonalExperienceTest": {
        "artThemesRenderDifferentCompleteKeyboards",
        "wordSnapshotsRestoreMergeAndRoundTrip",
        "batchWordManagementPreservesUnselectedRules",
        "chineseCandidateActionsPreserveSelection",
        "japanesePriorityPreservesConversion",
        "candidateActionsRejectAnOldListAndKeepTheCurrentComposition",
        "snapshotMergePreservesDynamicWeightsTicksAndDeletionRecords",
        "privateAiDraftsStayOutOfSavedHistory",
    },
    "PersonalRimeTest": {
        "keyContentSizeScalesTextAndIconsAcrossOrientations",
        "freshInstallProvidesOfflinePinyin",
        "modelToggleChangesCandidatesAndPersists",
        "predictionToggleAndBackspaceWorkImmediately",
        "preeditEditingAndPagingWork",
        "japaneseTypingAndConversionWork",
        "upgradePreservesUserConfig",
        "clipboardLinkCleanupPreservesPayloadAndOriginalContent",
        "bulkDeletionPreservesSuffixAfterRepeatedDeletion",
        "preeditTapMapsToTextWithoutCountingCursorMarker",
        "savedContentCanBeEditedOrderedAndRestoredFromBackup",
        "holdingBackspaceThenSwipingUpClearsOnceAndStopsRepeat",
    },
    "PersonalPackagingTest": {
        "prepareFullUpgrade", "verifyLiteUpgrade", "verifyFullRestore",
        "prepareMaintenanceUpgrade", "verifyMaintenanceUpgrade",
    },
    "PersonalDataSafetyTest": {
        "clearUrlsDecodesOnlyTheOuterRedirectEncoding",
        "clearUrlsPreservesDirectAndSchemalessRedirectTargets",
        "clearUrlsPreservesParameterValuesWhenRemovingTracking",
        "zipRejectsEscapingDirectoryWithoutSideEffects",
        "zipRejectsSiblingPrefixFile",
        "zipExtractsNestedEntriesWithoutExplicitParents",
        "tokenizationPreservesTextOnSupportedAndroidVersions",
    },
    "PersonalPluginContractTest": {
        "matchingNativeAndServicePluginsAreDiscovered",
        "clipboardPluginBindsBackToItsMatchingIme",
    },
    "PersonalKeyboardUiTest": {
        "screenTapEditsPinyinAndJapaneseCandidatesAppearWhileTyping",
    },
    "PersonalContentSearchTest": {
        "searchEditorCompatibility",
        "aiEditorKeepsInputLocalAcrossCancel",
        "editingRulesRefreshesTheActiveComposition",
        "searchActionsStayInTheirEditorAcrossResetAndClose",
    },
}
expected = set()
for selection in sys.argv[3].split(","):
    cls, separator, method = selection.partition("#")
    known = methods[cls.removeprefix(prefix)]
    if separator:
        if method not in known:
            raise SystemExit(f"Unknown required instrumentation test: {selection}")
        expected.add((cls, method))
    else:
        expected.update((cls, method) for method in known)

report = (output / "instrumentation.txt").read_text()
cases = {}
status = {}
current_key = None
errors = []
for line in report.splitlines():
    if line.startswith("INSTRUMENTATION_STATUS: "):
        key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
        status[key] = value
        current_key = key
    elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
        code = int(line.rsplit(": ", 1)[1])
        if code != 1:
            key = (status.get("class"), status.get("test"))
            if all(key):
                if key in cases:
                    errors.append(f"Duplicate completion for {key}")
                cases[key] = (code, status.get("stack") or status.get("stream", ""))
            elif code != 0:
                errors.append(f"Runner status {code}: {status}")
        status = {}
        current_key = None
    elif current_key and not line.startswith("INSTRUMENTATION_"):
        status[current_key] += "\n" + line

completed = {key for key, (code, _) in cases.items() if code == 0}
summary = re.search(r"OK \(([1-9][0-9]*) tests?\)", report)
if not summary or int(summary.group(1)) != len(expected):
    errors.append(f"Runner did not report exactly {len(expected)} successful tests")
if re.search(r"INSTRUMENTATION_FAILED|FAILURES!!!|shortMsg=", report):
    errors.append("Instrumentation reported a failure")
if completed != expected:
    errors.append(f"Missing successful tests: {sorted(expected - completed)}")
if set(cases) - expected:
    errors.append(f"Unexpected test completions: {sorted(set(cases) - expected)}")

suite = ET.Element("testsuite", name=group)
for cls, method in sorted(expected | set(cases)):
    case = ET.SubElement(suite, "testcase", classname=cls, name=method)
    outcome = cases.get((cls, method))
    if outcome is None:
        ET.SubElement(case, "failure", message="Required test did not complete")
    elif outcome[0] != 0:
        code, detail = outcome
        tag = "skipped" if code in (-3, -4) else "failure"
        ET.SubElement(case, tag, message=f"Instrumentation status {code}").text = detail
if errors:
    case = ET.SubElement(suite, "testcase", classname="ci", name="instrumentationContract")
    ET.SubElement(case, "failure", message="Instrumentation execution contract failed").text = "\n".join(errors)
suite.set("tests", str(len(suite.findall("testcase"))))
suite.set("failures", str(len(suite.findall("testcase/failure"))))
suite.set("errors", "0")
suite.set("skipped", str(len(suite.findall("testcase/skipped"))))
ET.indent(suite)
ET.ElementTree(suite).write(output / f"TEST-{group}.xml", encoding="utf-8", xml_declaration=True)
if errors:
    raise SystemExit("\n".join(errors))
counts = {cls: sum(item[0] == cls for item in completed) for cls, _ in sorted(expected)}
print(f"Verified every required test completed successfully: {counts}")
PY
    then
        result=1
    fi
}

if [[ "$mode" == "upgrade" ]]; then
    full_dir="${2:?full APK directory required}"
    lite_dir="${3:?lite APK directory required}"
    baseline_dir="${4:-}"
    output_root=upgrade-results
    mkdir -p "$output_root"
    if [[ -n "$baseline_dir" ]]; then
        adb install -r "$baseline_dir/app-debug.apk"
        adb install -r -t "$baseline_dir/app-debug-androidTest.apk"
        run_group prepare-maintenance 'org.fcitx.fcitx5.android.PersonalPackagingTest#prepareMaintenanceUpgrade'
        [[ "$result" == 0 ]] || exit "$result"
    fi
    adb install -r "$full_dir/app-debug.apk"
    adb install -r -t "$full_dir/app-debug-androidTest.apk"
    if [[ -n "$baseline_dir" ]]; then
        run_group verify-maintenance 'org.fcitx.fcitx5.android.PersonalPackagingTest#verifyMaintenanceUpgrade'
        [[ "$result" == 0 ]] || exit "$result"
    fi
    run_group prepare-full 'org.fcitx.fcitx5.android.PersonalPackagingTest#prepareFullUpgrade'
    [[ "$result" == 0 ]] || exit "$result"
    adb install -r "$lite_dir/app-debug.apk"
    adb install -r -t "$lite_dir/app-debug-androidTest.apk"
    run_group upgrade-lite 'org.fcitx.fcitx5.android.PersonalPackagingTest#verifyLiteUpgrade'
    [[ "$result" == 0 ]] || exit "$result"
    adb install -r "$full_dir/app-debug.apk"
    adb install -r -t "$full_dir/app-debug-androidTest.apk"
    run_group restore-full 'org.fcitx.fcitx5.android.PersonalPackagingTest#verifyFullRestore'
    exit "$result"
fi

if [[ "$mode" == "compat" ]]; then
    apk_dir="${2:-test-apks}"
    output_root=compatibility-results
    mkdir -p "$output_root"
    adb install -r "$apk_dir/app-debug.apk"
    adb install -r -t "$apk_dir/app-debug-androidTest.apk"
    run_group api23-compat 'org.fcitx.fcitx5.android.PersonalContentSearchTest#searchEditorCompatibility,org.fcitx.fcitx5.android.PersonalDataSafetyTest'
    exit "$result"
fi

if [[ "$mode" != "full" ]]; then
    echo "Unknown smoke test mode: $mode" >&2
    exit 2
fi

output_root=app/build/outputs/androidTest-results
mkdir -p "$output_root"
app_apk=$(find app/build/outputs/apk/debug -name '*.apk' -print -quit)
test_apk=$(find app/build/outputs/apk/androidTest/debug -name '*.apk' -print -quit)
python3 - "$app_apk" "$test_apk" <<'PY'
import sys

sys.path.insert(0, "scripts")
from verify_personal_apk import require, verify_signature

app_signers, _ = verify_signature(sys.argv[1])
test_signers, _ = verify_signature(sys.argv[2])
require(app_signers == test_signers, "Instrumentation APK signer differs from the verified IME")
print("Verified instrumentation APK and IME use the same signing identity")
PY

# Install the already verified APKs once. Test execution must not rebuild or re-sign them.
adb install -r "$app_apk"
adb install -r -t "$test_apk"
for plugin in chewing clipboard-filter; do
    apk=$(find "plugin/$plugin/build/outputs/apk/debug" -name '*.apk' -print -quit)
    adb install -r "$apk"
done

run_group personal-native 'org.fcitx.fcitx5.android.PersonalRimeTest,org.fcitx.fcitx5.android.PersonalDataSafetyTest,org.fcitx.fcitx5.android.PersonalPluginContractTest'
run_group personal-experience 'org.fcitx.fcitx5.android.PersonalExperienceTest'
run_group personal-keyboard 'org.fcitx.fcitx5.android.PersonalKeyboardUiTest'
run_group personal-search 'org.fcitx.fcitx5.android.PersonalContentSearchTest'

mkdir -p app/build/reports/androidTests/connected/screens
for image in pinyin-edit japanese-predictions keyboard-final; do
    adb pull "/data/local/tmp/fcitx5-$image.png" "app/build/reports/androidTests/connected/screens/$image.png" || true
done
exit "$result"

