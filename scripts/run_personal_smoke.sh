#!/usr/bin/env bash

result=0
./gradlew -PbuildABI=x86_64 -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.PersonalRimeTest,org.fcitx.fcitx5.android.PersonalKeyboardUiTest :app:connectedDebugAndroidTest || result=$?

mkdir -p app/build/reports/androidTests/connected/screens
for image in pinyin-edit japanese-predictions keyboard-final; do
    adb exec-out run-as org.fcitx.fcitx5.android.bw1145.debug cat "cache/$image.png" > "app/build/reports/androidTests/connected/screens/$image.png" || true
done
exit "$result"
