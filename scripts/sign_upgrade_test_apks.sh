#!/usr/bin/env bash
set -euo pipefail
# Independently built debug APKs use runner-local debug keys. Re-sign only the
# emulator fixtures together; delivered release APKs retain the personal key.
[[ "$#" -ge 2 ]] || { echo 'At least two fixture directories are required' >&2; exit 2; }
key_dir=$(mktemp -d)
trap 'rm -rf "$key_dir"' EXIT
keytool -genkeypair -keystore "$key_dir/upgrade.jks" -storepass android \
    -keypass android -alias upgrade -keyalg RSA -keysize 2048 -validity 7 \
    -dname 'CN=Full Lite Upgrade Test' -noprompt
sdkmanager 'build-tools;36.1.0'
signer="$ANDROID_HOME/build-tools/36.1.0/apksigner"
for directory in "$@"; do
    for apk in app-debug.apk app-debug-androidTest.apk; do
        "$signer" sign --ks "$key_dir/upgrade.jks" --ks-key-alias upgrade \
            --ks-pass pass:android --key-pass pass:android "$directory/$apk"
        "$signer" verify "$directory/$apk"
    done
done
