# Candidate preference native microbenchmark

Run from the repository root:

```sh
git submodule update --init lib/fcitx5/src/main/cpp/fcitx5
bash scripts/run_native_candidate_checks.sh
bash scripts/run_candidate_benchmark.sh
```

Requirements: a host C++20 compiler (`CXX`, default `g++`), Git, Bash. No third-party benchmark framework is required. UTF-8 validation uses the repository's pinned upstream Fcitx source, not a test stub.

`run_candidate_benchmark.sh` freezes the original `custom-1.3.5` candidate preference header (commit `6bfc3b5bb68d92a353bcc6fc9bf009a277e6d52a`) and a copy of the modified worktree header, then compiles the same harness against both with `-O2`. `CANDIDATE_BASELINE` can override the original reference. Source/header hashes, compiler and host architecture are printed. Reports and binaries are under `build/candidate-benchmark/`.

The harness uses generated fixture words only. It does not record user input. Before and after must produce the same correctness digest; a mismatch or assertion failure fails the script.

## Coverage

- No rules; 1, 10, 100 and 5,000 hide/downrank rules.
- Warm lookup, forced reload including index construction, and cold first lookup after reload.
- Ten distinct reading scopes with 500 rules each.
- Shared instances, explicit reload, same-size/time atomic replacement, deletion and malformed file preserving the last valid cache.
- Rime scope migration, Anthy single-word migration, existing scoped-rule conflict precedence, merge and save round trips.
- Sixteen consecutive legacy Anthy word migrations with a 1,000-rule file; catches rebuild work moved into repeated saves.
- All-hidden, all-lowered and mixed-mode finite candidate lists. An independent expected-index oracle checks complete ordering and the existing 32-normal-candidate downrank boundary.
- New empty-cache state across reload/save and malformed merge keys.

## Interpretation

Times are microseconds: median (p50), p95, and sample count. Warm paths discard 20 warmup runs; cold paths reload before each measured operation. Reload/save timings include their filesystem work. Only the cold-lookup measurement intentionally excludes the setup reload; the separate reload benchmark reports that cost.

The first-16 workload is an explicitly labeled finite-list simulation of `AndroidFrontend::fillOrder`. It uses pre-generated strings; it does **not** include real Rime candidate generation, Android JNI dispatch, rendering, touch latency, device thermal behavior or OPPO hardware. It cannot establish end-to-end phone responsiveness or identify the user's sole root cause. The no-rule case also does not measure the frontend's avoided engine-context query.

Do not quote the large-rule stress improvement as a universal keyboard speedup. Report cold-path and migration regressions alongside lookup gains. Timing on shared hosts fluctuates; correctness assertions and before/after source hashes are the hard acceptance checks, not a brittle timing threshold.

## Legacy migration batching probe

The `frontend_legacy_16_hits_in_1000` row compares the old frontend's sixteen individual migrations to the new frontend's single batch, including persistence and index maintenance. The separate `legacy_anthy_16_hits_in_1000` row deliberately retains individual calls on both sides to expose their overhead rather than hide it. Duplicate words, scoped-rule precedence, same-scope removal, empty lists and all-hidden legacy candidates have explicit assertions.

Where `strace` is permitted, count atomic saves with the dedicated probe (fixture creation itself does not rename):

```sh
for v in baseline current; do
  strace -qq -e trace=rename,renameat,renameat2 \
    -o build/candidate-benchmark/$v-renames.txt \
    build/candidate-benchmark/$v build/candidate-benchmark/probe-$v --migration-probe
  wc -l build/candidate-benchmark/$v-renames.txt
done
```

Expected: 16 old saves, 1 new save for sixteen matched legacy words. This is a deterministic filesystem-operation check, not a phone responsiveness measurement.

## Measured result (2026-10-23)

Exact raw output, compiler and frozen source hashes: [checked-in result](candidate-preferences-results-20261023.txt). All figures below are **microseconds**, host-native synthetic fixtures.

| Workload | 1.3.5 p50 / p95 | Patched p50 / p95 |
|---|---:|---:|
| `hide_1_first16` | 2.274 / 2.333 | 0.931 / 0.961 |
| `hide_10_first16` | 3.396 / 5.068 | 1.202 / 1.292 |
| `hide_100_first16` | 14.772 / 17.736 | 2.613 / 4.197 |
| `hide_5000_first16` | 988.348 / 1193.232 | 138.446 / 226.267 |
| `lower_5000_first16` | 1073.214 / 1528.550 | 142.211 / 233.788 |
| `cold_scope_lookup_5000` | 1.202 / 1.793 | 0.871 / 2.063 |
| `reload_5000` | 6704.737 / 8761.933 | 8673.168 / 9477.727 |
| `ten_scopes_500_reload` | 7215.166 / 8079.129 | 8898.044 / 13281.613 |
| `legacy_anthy_16_hits_in_1000` | 3812.920 / 5159.055 | 7182.708 / 10694.630 |
| `frontend_legacy_16_hits_in_1000` | 3642.485 / 4138.785 | 699.863 / 752.951 |

The optimized warm lookup paths improve even with 1–10 rules. Index construction now occurs during load/save rather than the first candidate query: reload remains more expensive and is disclosed above. Repeated calls to the compatibility single-word migration API are also slower because each rebuilds the index. The actual frontend uses one batch for the scanned block instead; sixteen legacy matches now perform one atomic save rather than sixteen (verified with a host-only `LD_PRELOAD` rename interceptor, not inferred from timing).

`strace` was installed but denied `PTRACE_TRACEME` by this container; those empty trace files were not used as evidence. The fallback interceptor is `scripts/candidate_rename_probe.cpp` and is never part of the Android build.

Reproduce the interceptor fallback on Linux:

```sh
g++ -std=c++17 -O2 -shared -fPIC scripts/candidate_rename_probe.cpp -ldl \
  -o build/candidate-benchmark/rename-probe.so
for v in baseline current; do
  LD_PRELOAD="$PWD/build/candidate-benchmark/rename-probe.so" \
    build/candidate-benchmark/$v build/candidate-benchmark/probe-$v --migration-probe
done
```

## Raw timing samples

The runner also retains `baseline-samples.csv` and `current-samples.csv` in the build report directory and CI artifacts. Each row contains the synthetic workload name, acquisition-order sample index and duration in microseconds, before percentile sorting. Warmup samples are excluded as specified above. CSV writing happens outside timed regions; no candidate text or user data is written.
