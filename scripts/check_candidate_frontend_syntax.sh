#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
# Host syntax check against pinned real Fcitx headers. This is NOT Android linking.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
include="$root/build/personal-ci/frontend-syntax-include"
for spec in 'fcitx/fcitxcore_export.h FCITXCORE' 'fcitx-config/fcitxconfig_export.h FCITXCONFIG' 'fcitx-utils/fcitxutils_export.h FCITXUTILS'; do
  read -r file prefix <<< "$spec"
  mkdir -p "$(dirname "$include/$file")"
  printf '#pragma once\n#define %s_EXPORT\n#define %s_DEPRECATED_EXPORT\n#define %s_DEPRECATED\n' "$prefix" "$prefix" "$prefix" > "$include/$file"
done
"${CXX:-g++}" -std=c++20 -fsyntax-only -I"$include" \
  -I"$root/lib/fcitx5/src/main/cpp/fcitx5/src/lib" \
  "$root/app/src/main/cpp/androidfrontend/androidfrontend.cpp"
echo 'Android frontend host syntax check passed (not an Android build)'
