#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="$root/build/candidate-benchmark"
source="${FCITX_SOURCE_DIR:-$root/lib/fcitx5/src/main/cpp/fcitx5}/src/lib"
base="${CANDIDATE_BASELINE:-6bfc3b5bb68d92a353bcc6fc9bf009a277e6d52a}"
mkdir -p "$output/include/fcitx-utils"
printf '%s\n' '#pragma once' '#define FCITXUTILS_EXPORT' '#define FCITXUTILS_DEPRECATED_EXPORT' > "$output/include/fcitx-utils/fcitxutils_export.h"
git -C "$root" show "$base:app/src/main/cpp/androidfrontend/candidatepreferences.h" > "$output/baseline.h"
cp "$root/app/src/main/cpp/androidfrontend/candidatepreferences.h" "$output/current.h"
for version in baseline current; do
    header="$output/baseline.h"
    [[ "$version" == current ]] && header="$output/current.h"
    flags=()
    [[ "$version" == current ]] && flags=(-DCURRENT_VERSION)
    "${CXX:-g++}" "${flags[@]}" -std=c++20 -O2 -Wall -Wextra -pedantic -Wno-missing-field-initializers \
      -I"$output/include" -I"$source" -DPREF_HEADER="\"$header\"" \
      "$root/scripts/benchmark_candidate_preferences.cpp" "$source/fcitx-utils/cutf8.cpp" -o "$output/$version"
    "$output/$version" "$output/fixture-$version" | tee "$output/$version.txt"
done
diff -u <(grep '^correctness ' "$output/baseline.txt") <(grep '^correctness ' "$output/current.txt")
printf 'baseline=%s\ncurrent_worktree=%s\n' "$base" "$(git -C "$root" rev-parse HEAD)"
"${CXX:-g++}" --version | head -1
uname -sm

sha256sum "$output/baseline.h" "$output/current.h" "$root/scripts/benchmark_candidate_preferences.cpp"
