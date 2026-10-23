# Native attribution instrumentation

This is an isolated test build, not an input-method setting. Build with
`-PcandidateAttribution=true` and Debug. The default option is OFF; CMake rejects
ON for any non-Debug build, and the control JNI is preprocessor-excluded when OFF.
The Java declaration lives in src/debug, not release sources. No user text is
recorded by native code: only nanoseconds and counts on the engine thread.

The independent `candidate-attribution.yml` runs only by explicit dispatch or a
push to `fix/ime-attribution-20261023`. It does not publish an APK release.

`CandidateAttribution.configure(0|1|2)` clears counters and chooses disabled,
normal, or test bypass. Set a mode before reset/retyping the synthetic composition;
changing the mode does not itself refresh the current UI. Mode 2 exits
beginPreferences before file stat/load, scope acquisition or custom scanning,
leaving the same persisted rules and original engine processing intact. Candidate
revision bookkeeping remains for safe API operation, so this is not an unmodified
upstream binary comparison.

`drain()` returns schema 1 followed by the sixteen `(ns,count)` pairs listed in
`PersonalCandidateAttributionTest`. It clears the counters, not engine state.
Each shared object owns its hidden store; the JNI sums corresponding counters
from native-lib, AndroidFrontend and the Rime addon. The host store test verifies
cross-translation-unit sharing and isolation even with RTLD_GLOBAL loading.

Timing containment matters:
- initial_update includes begin_preferences, scans, display materialization and
  the synchronous candidate JNI callback; input_panel is a separate preceding
  frontend operation.
- begin_preferences includes rules_load and scope_resolve.
- raw_scan_fetch can include rime_fetch_miss. A miss is an uncached **adapter**
  fetch/wrapper, not proof that librime translated a new candidate.
- batch_request includes its rule load and requested scan/materialization, but
  candidate_jni conversion occurs after its native return.
- process_key and get_context are timed at their Rime API call boundaries, and
  include any synchronous nested callbacks. Never sum these inclusive values.
- classification measures rule lookup only. Scanning display filtering and other
  orchestration remain in outer-stage residuals, not fabricated exclusive metrics.

No librime core translation or scheduling behavior is modified. Normal macros
expand to their original expressions. Local verification covered both macro
modes for complete frontend and patched Rime source syntax, Kotlin debug API,
patch regeneration, and store/timer behavior. Full JNI linking and actual Android
ABBA execution remain CI work. The local environment has no CMake executable;
the explicit release-gate execution test runs with the SDK CMake in CI.
