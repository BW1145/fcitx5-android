#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
output="${BUDGET_BENCHMARK_OUTPUT:-$root/build/budget-benchmark}"
mkdir -p "$output"
cp "$root/app/src/main/cpp/androidfrontend/candidatescan.h" "$output/candidatescan.h"
"${CXX:-g++}" -std=c++20 -O2 -Wall -Wextra -pedantic \
 -DBUDGET_HEADER="\"$output/candidatescan.h\"" "$root/scripts/budget_benchmark.cpp" -o "$output/benchmark"
"$output/benchmark" "$output/samples.csv" > "$output/results.csv" 2> "$output/correctness.txt"
{ date -u +%FT%TZ; git -C "$root" rev-parse HEAD; "${CXX:-g++}" --version | head -1; uname -srm; grep -m1 'model name' /proc/cpuinfo; sha256sum "$output/candidatescan.h" "$root/scripts/budget_benchmark.cpp"; } > "$output/environment.txt"
sha256sum "$output/environment.txt" > "$output/environment.sha256"
cat "$output/correctness.txt"; cat "$output/results.csv"

printf "Per-sample raw CSV: %s\n" "$output/samples.csv"
