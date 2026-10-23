# CandidateScan cooperative-budget microbenchmark

Run `bash scripts/budget_benchmark.sh` from the repository root. The runner freezes the actual `candidatescan.h`, builds the synthetic harness with `g++ -std=c++20 -O2`, and writes aggregate CSV, per-sample `samples.csv`, correctness output and environment/source hashes under `build/budget-benchmark/`. `BUDGET_BENCHMARK_OUTPUT` and `CXX` can override those locations/compiler.

Checked-in results: `budget_benchmark_results.csv`; source/compiler/host evidence: `budget_benchmark_environment.txt`. Each row has 501 measured samples after 20 warmups. Times are microseconds, p50 and p95. The source classifier returns hidden for the first N integer indices and normal afterward: **no actual Rime generation, rule parsing, Android callbacks, dispatch waiting, UI rendering or real user text is measured**.

## What each path means

- `first_batch_16`: one real helper advance with the default budget (32 inspections / 2 ms cooperative deadline), targeting 16 visible entries. A large hidden prefix therefore returns zero visible entries and a continuation, rather than completing the whole request.
- `explicit_first_1` and `explicit_first_16`: repeatedly advance the same scan until the requested visible count is present. Their timing includes all helper work but calls continuations immediately, without event-loop sleeps. This is **active helper time, not Android end-to-end completion latency**.
- `unlimited_16`: the same helper with its unlimited budget, for contrast. It completes the entire scan in one call.

The first-batch columns describe the final sampled run, while batch min/max cover every measured run. All runs verify every returned index and the final visible count. A separate full-order oracle checks hide behavior and the existing `order.size() >= 32` insertion boundary across batches. At the first insertion this is a normal-only prefix; later `order` also contains previously inserted lowered entries.

## Measured interpretation (2026-10-23 UTC)

| Leading hidden | First batch p50 / p95 (us) | First-batch visible | Finish 16 p50 / p95 (us) | Batches to finish 16 |
|---:|---:|---:|---:|---:|
| 0 | 0.441 / 0.451 | 16 | 0.441 / 0.451 | 1 |
| 10 | 0.681 / 0.692 | 16 | 0.681 / 0.691 | 1 |
| 100 | 0.801 / 0.812 | 0 | 2.884 / 2.894 | 4 |
| 1,000 | 0.801 / 0.812 | 0 | 24.827 / 44.386 | 32 |
| 10,000 | 0.811 / 0.821 | 0 | 258.084 / 337.462 | 313–314 |

For 10,000 leading hidden candidates, obtaining even the **first** visible candidate still needs 10,001 inspections, with 313 batches observed (the cooperative time budget can split a batch early). Finishing 16 takes 10,016 inspections and 313–314 batches in this run. Unlimited completion took 256.512 / 341.057 us in one call; batching did not eliminate the required total work.

Thus the bounded helper materially limits one uninterrupted unit of synthetic work. It does not promise immediate visible candidates behind a long hidden prefix, nor does it make 313 Android continuation deliveries free. Actual dispatch pacing, cancellation on new input and UI behavior need emulator tests. The 2 ms check is between source callbacks; one slow callback cannot be preempted and can exceed 2 ms.

Correctness: prefix indices, target counts, full order and `order.size() >= 32` boundary PASS; checksum `17996385`.

Raw measured observations are kept in `build/budget-benchmark/samples.csv`: `trial,path,hidden,elapsed_us,inspected,batches`. This run contains 10,020 data rows; the raw file is intended for CI artifacts, not committed to the repository. Warmups are excluded. CSV writes happen after the measured interval.
