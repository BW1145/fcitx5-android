#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
include="${CANDIDATE_SCAN_INCLUDE_DIR:-$root/app/src/main/cpp/androidfrontend}"
out="${CANDIDATE_BUDGET_OUTPUT:-$root/build/candidate-budget-contract}"
mkdir -p "$out"
"${CXX:-g++}" -std=c++20 -O2 -Wall -Wextra -pedantic -pthread -I"$include" \
 "$root/scripts/test_candidate_budget_contract.cpp" -o "$out/contract"
"$out/contract" | tee "$out/result.txt"
