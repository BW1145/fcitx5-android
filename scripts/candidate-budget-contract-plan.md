# Independent budgeted candidate contract verification

Owner: verify_android_ime_optimization. Production code and existing tests belong to implementation lead. New verification files only; base 32b2a37.

## Native production-helper tests

Use the production scanner with a counted lazy source; do not copy its algorithm into the test. A separate exhaustive oracle reproduces legacy full-list ordering for expected final output.

- No rules: no scope/mode lookup, normal fast path retained.
- Exact bounds: 0, 1, 15, 16, 17, 31, 32, 33, 63, 64, 65 items; zero/negative request validation; finite and unknown total.
- 10,000 hidden prefix followed by 40 normal candidates: each call's source accesses and materializations <= budget, intermediate empty result has continuation, eventual result complete and indexes 10000..10039 preserved.
- Entire finite/unknown list hidden: finite termination, no false visible data, no endless continuation.
- Partial visible batch then hidden prefix: continuation offset advances by delivered entries, not requested size; no skipped or duplicate candidates.
- Lowered candidates before/mixed with normals: 0, 1, 31, 32, 33 normal counts; lowered entries appear after first 32 normals or true end; budget boundary alone must not flush lowered list.
- Duplicate text with distinct original indexes retains each candidate; hidden duplicate behavior and first-longpress index mapping remain unchanged.
- Repeated offset/backtracking uses cached selection order; expansion completes every visible candidate, with no eager total materialization.
- Unknown-total source end via invalid_argument versus non-end exception: end recognized, other exception not silently swallowed, cursor consistency on retry.
- Reset/input revision during continuation drops old source/order/pending lower; new input starts at index zero. Hidden-rule restoration resets generation/cache.
- Anthy finite page behavior preserved; hidden-page skipping terminates; original per-page indexes remain valid.
- Per-call report: raw accesses, generated candidates, mode lookups, cache hits, accepted indexes, pending/end state. Raw access ceiling is a scheduling bound, not a bound on individual Rime candidate generation time.

## Kotlin consumer tests

- Empty but pending first batch schedules continuation even without a scroll event.
- Partial batch remains pending; expanded paging uses actual accepted count for next visible offset.
- Known total 17/page16 delivers last item (legacy +1 end boundary regression).
- Cancellation/new revision prevents old completion from appending; one in-flight continuation maximum; closing keyboard cancels work.
- Empty truly-ended result terminates without busy loop; pending continuation yields dispatcher so new input can run.
- Selection/longpress/action uses correct original candidate and revision after partial delivery.

## Android end-to-end evidence

- Same harness baseline/candidate, synthetic data, fresh ABBA. No-rule, one-hide/lower, and dense 10k hidden prefix fixtures where real engine supports enough matching candidates.
- Report first-visible latency, send/return duration, each continuation duration, raw source-generation counts, final candidate order hashes, new-input takeover latency and stale-result count.
- Keep no-rule p50/p95 per trial and raw timings. Report regressions rather than a flaky speed CI gate.
- Actual touchscreen/editor tests for expansion, rapid input, hide/restore; existing speech/pixel/UI checks retained where affected.
- Use counted synthetic native source for exact 10k proof if Rime corpus lacks 10k candidates; label separately from actual Rime timings.
- All tests and artifacts pinned to exact published commit. No old-head CI rerun.

## Stop condition

Production helper + consumer contracts pass, exact-head CI completes, independent raw checks agree, and any performance/regression tradeoff is reported. No claim of phone touch-to-display acceleration from component timing alone.
