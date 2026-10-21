#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$root/build/personal-ci"
fcitx_source="${FCITX_SOURCE_DIR:-$root/lib/fcitx5/src/main/cpp/fcitx5}"
fcitx_lib="$fcitx_source/src/lib"
host_include="$output/native-include"

if [[ ! -f "$fcitx_lib/fcitx-utils/cutf8.cpp" ]]; then
  echo 'Missing pinned Fcitx sources. Initialize the recursive submodules first.' >&2
  exit 1
fi

mkdir -p "$host_include/fcitx-utils"
# CMake normally generates this export-annotation header. These standalone
# executables need no shared-library annotations or Android configuration.
# UTF-8 validation still uses the pinned upstream headers and implementation.
printf '%s\n' '#pragma once' '#define FCITXUTILS_EXPORT' \
  '#define FCITXUTILS_DEPRECATED_EXPORT' \
  > "$host_include/fcitx-utils/fcitxutils_export.h"

"${CXX:-g++}" -std=c++20 -O2 -Wall -Wextra -pedantic \
  -I"$host_include" -I"$fcitx_lib" \
  "$root/scripts/test_candidate_preferences.cpp" \
  "$fcitx_lib/fcitx-utils/cutf8.cpp" -o "$output/candidate-cache-check"
"$output/candidate-cache-check" "$output/candidate-fixture" \
  | tee "$output/candidate-cache.txt"

"${CXX:-g++}" -std=c++20 -O2 -Wall -Wextra -pedantic \
  "$root/scripts/test_preferred_candidate.cpp" -o "$output/preferred-candidate-check"
"$output/preferred-candidate-check" | tee "$output/preferred-candidate.txt"
