#!/usr/bin/env bash

result=0
./gradlew -PbuildABI=x86_64 -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.PersonalRimeTest :app:connectedDebugAndroidTest || result=$?
cp -r app/build/reports/androidTests/connected app/build/reports/androidTests/personal-native
./gradlew -PbuildABI=x86_64 -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.PersonalKeyboardUiTest :app:connectedDebugAndroidTest || result=$?

if ! grep -r -q 'testcase name="screenTapEditsPinyinAndJapaneseCandidatesAppearWhileTyping"' app/build/outputs/androidTest-results/connected; then
    echo 'The keyboard screen interaction test did not run'
    result=1
fi

mkdir -p app/build/reports/androidTests/connected/screens
for image in pinyin-edit japanese-predictions keyboard-final; do
    adb pull "/data/local/tmp/fcitx5-$image.png" "app/build/reports/androidTests/connected/screens/$image.png" || true
done
exit "$result"
