# Same-build custom-layer attribution protocol

Owner: independent verification. Native profiling implementation is owned by candidate lead in a separate worktree; this protocol does not change the running ebe1579 CI.

## Inputs and control

- One profiling-only debug APK, one API35 emulator, same Rime schema and synthetic raw input `shi`.
- Obtain the ordinary first128 candidate strings once, keep the dictionary/userdata unchanged, derive hide rules from first64 strings. No candidate selection/learning between measurement rounds.
- No-rule and dense-hide scenarios separately. For each scenario keep the exact same rule file through all on/bypass conditions.
- Bypass switches before rule load/scope lookup and uses raw first16. It intentionally exposes hidden words inside this isolated test only; no user-visible feature/configuration.
- ABBA per scenario: bypass, enabled, enabled, bypass. Warmup before collection, fixed repeated trials, reset composition before each. Native switches on the engine dispatcher via enqueueInputBarrier.

## Per-sample phases

1. Reset composition, send `s` and `h`, clear counters.
2. Time sendKey('i') wall time; snapshot native inclusive counters immediately after return. This is initial event latency, not fully populated filtered list latency.
3. Clear counters, time explicit getCandidates(0,16) wall time including cooperative continuation delays; snapshot again.
4. Verify enabled returned16 equals unhidden reference prefix and bypass returned16 equals original prefix. Record hashes, not arbitrary user text.

## Attribution

- process_key and engine updateUI/get_context separate counters; document which include frontend callbacks.
- Current counters: combined rule stat/load, scope acquisition, mode lookup, raw scan fetch, output materialization (including output filtering), JNI conversion/callback. Scan-time filterString remains in residual and has no separate counter.
- Raw access count is not a generation count. rime_fetch_miss times adapter uncached fetch/wrapper construction; rime_fetch_hit is a count only. Neither proves new librime generation nor measures internal dictionary cache growth.
- Nested inclusive stages cannot be summed. Report the outer total, individual enclosed times and residual only when coverage is disjoint/known.
- Native instrumentation overhead exists equally in both conditions; include no-rules toggling control and retain all raw samples. No flaky CI percentage gate.
- Main-thread rendering/drawing is outside these JNI counters and must not be inferred as measured UI latency.

## Artifacts and completion

- JSON schema/version, exact APK/source hash, synthetic fixture/count/rule hash, ABBA order, every sample's phase wall and native counters, observed output hashes.
- Independent quantile recalculation and per-condition/trial distributions, no merged headline masking regressions.
- Test feature absent/inert in ordinary/release builds, default disabled and restored on finally. Current production branch unchanged until parent approves test-only publication.
