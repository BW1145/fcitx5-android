# Middle-pinyin edit / whole-input candidates — local review

## Status and scope

- Base: released 1.3.7, `4eeaf1d3156cb03f058ad27e18f1df1db1da857f`.
- Initial local branch: `fix/pinyin-full-refresh-local-20261024`.
- Authorized validation branch: `verify/pinyin-full-refresh-20261024`.
- Worktree: `/workspace/scratch/33cf95385f89/ime-pinyin-edit-work`.
- The first checkpoint was local implementation plus host tests only. The user
  subsequently authorized an isolated test branch and test-only Actions run.
  The new workflow builds x86_64/lite/debug and runs API 35 instrumentation;
  it has read-only contents permission, no release/signing secrets/deployment,
  and uploads reports rather than APKs. Its outcome must be checked separately.
- No change to `personal-ice`, PR, merge or release is authorized.
- Worktree is approximately 14 MiB; available filesystem space was 5.3 GiB after
  the host tests. Existing source-only submodule checkout was read, not copied.

## Behavior implemented

The native Rime adapter keeps the real session at the end of the raw input while
showing a separate editable pinyin snapshot. Before the next editing key it
restores the saved raw caret, lets Rime process the edit, captures Rime's own
preedit, and moves the real caret back to the end before publishing UI. The
candidate list, its lazy pages, and commit preview all use that real whole-input
state. No candidate text is directly committed or raw input cleared/replayed.

The production key-routing fast path for ordinary inactive end typing never
calls the edit backend: no additional input/caret/context reads, input copies,
or caret writes. Only active edits, caret-navigation keys or a tracked legacy
middle-caret state need finalization. Middle editing is scoped to alphabetic/apostrophe input in
`rime_ice`, outside ASCII/disabled mode. Both cases of the alphabet are accepted,
matching the pinned schema; numeric/symbol/backtick/custom-schema behavior keeps
the legacy path. Alphabetic special commands may still be recognized by Rime;
the adapter does not claim to distinguish all translator types.

Pinyin-bar taps have a dedicated API, separate from system client-preedit cursor
coordinates. They carry the displayed string and reject a queued tap if that
string has changed. Native positioning starts from the current caret, not raw
zero. It first resolves the raw target entirely from the OLD displayed snapshot,
including verified raw suffixes, separators, explicit apostrophes, and the pinned
schema's `qv -> qu` / `nue -> nve` / `lue -> lve` transformations. Movement then
compares only raw positions; newly formatted snapshots never redefine the target.
Unknown transformations or a click inside a previously selected Chinese prefix fall back to the
existing behavior rather than scanning through that prefix speculatively.
Before that fallback, any hidden end caret is restored to the saved editing caret.

Selection keydown, explicit candidate selection, and commits clear the saved
caret. Explicit selection covers the preferred/hidden-candidate route which
bypasses `keyEvent`. Key-up does not restore it. Modifier-only keydown and menu
paging preserve it; a real mode change is checked when updating the presentation. End
is restored as an editing operation so it really exits middle editing; Home
retains upstream Selector-first behavior. Reset, release, mode/schema changes,
input mismatch and session mismatch invalidate the snapshot.

## Review findings fixed locally

1. A displayed end offset can change as Rime adds segmentation spaces. Treating
   that as a fixed byte target stopped short of the raw end. Displayed end now
   maps explicitly to raw end; host regression passes.
2. End was initially treated purely as menu navigation. Since the hidden caret
   is already at the end, it left the visible caret in the middle. End/KP_End
   now restore the edit caret before normal engine handling; host routing/state
   regression passes.
3. Uppercase editing keys initially disagreed with lowercase-only eligibility.
   Eligibility now matches the alphabetic key classifier, including mixed-case
   insert/delete coverage. ASCII mode remains excluded.
4. The new patch module is included in the patch signature, so changing it
   cannot leave a stale generated-source cache.
5. Independent review caught a second-tap error hidden by an incorrect fake-test
   expectation: `ni|ho` (raw2, displayed `niho`) followed by old-display cp3 must
   become `nih|o` (raw3), even though the new preedit is `ni ho`. Raw targets are
   now fixed from the old snapshot before movement; both directions and the
   distinct Rime zero-caret translation exception have regression coverage.
6. Independent review caught standalone Shift/Control keydown clearing the edit
   caret before Shift+A / Ctrl+Backspace. A shared production routing decision
   now preserves modifier-only events; complete press/edit/release sequences are
   tested, not merely single events carrying modifier flags.
7. The initial finish helper still read/copied input on ordinary end appends.
   Production routing now skips the helper entirely on that path, with all
   input/caret/context counters asserted to remain zero for 100 appends.
8. First API 35 validation (`40049255241`, `ad6d0f2`) passed compilation, unit
   tests, real screen-tap editing and real candidate-budget checks, but exposed
   a direct-call UI flush gap. Kotlin read the previous `ni ho` cache immediately
   after the first tap; logcat shows the correct `niho` snapshot was delivered
   only after the failing assertion began shutdown. The dedicated native path
   now calls `Instance::flushUI()` once before returning, as normal key events
   already do. The immediate-cache assertions are retained, not delayed or
   weakened. Later assertions in that failed method did not run; a new CI run
   is required to establish them.
9. Second CI (`40050947787`, `1755d14`) passed the immediate-cache/two-tap,
   repeated-edit, complete selection and modifier assertions, then exposed an
   off-by-one in the long-sentence TEST fixture: `yiduanzida` contains ten letters,
   not nine. Inserting after nine correctly produced `yiduanzidwaanlejiu`.
   The test now derives the tap from the named prefix length and verifies that
   exact prefix before inserting. A host long-input fixture covers raw10 as
   well. No production mapping change was needed for this test error.

## Executed checks

All following checks passed against the final local files:

```sh
python3 scripts/test_rime_edit.py --fcitx-headers \
  ../ime-candidate-work/lib/fcitx5/src/main/cpp/fcitx5/src/lib
python3 scripts/apply_personal_patches.py --component fcitx5-rime \
  --source ../ime-candidate-work/plugin/rime/src/main/cpp/fcitx5-rime/src \
  --output build/pinyin-edit-review/fcitx5-rime
python3 scripts/test_rime_personal_context.py
g++ -std=c++17 -Wall -Wextra -I app/src/main/cpp/androidfrontend \
  scripts/test_candidate_budget_contract.cpp -o build/pinyin-edit-review/test_candidate_budget
build/pinyin-edit-review/test_candidate_budget
g++ -std=c++17 -Wall -Wextra scripts/test_preferred_candidate.cpp \
  -o build/pinyin-edit-review/test_preferred_candidate
build/pinyin-edit-review/test_preferred_candidate
python3 -m py_compile scripts/patches/rime_edit.py scripts/patches/fcitx5_rime.py \
  scripts/test_rime_edit.py scripts/apply_personal_patches.py
git diff --check
```

- Production caret helper compiled with C++17, `-Wall -Wextra -Werror` and ran
  against a counting, deterministic fake backend: repeated insertion, backspace,
  Delete, uppercase, full-input state, end/formatting boundaries, Unicode positions,
  selected-prefix preservation, session/input invalidation, failure safety, and
  100 normal end edits with zero extra context reads/caret writes.
- Actual injected key-classification functions compiled against pinned Fcitx
  key symbols, covering key-up, space/digits/keypad, paging, Home/End, complete
  left/right Shift+A and Control+Backspace sequences, and 100 ordinary inactive
  appends with zero backend input/caret/context reads or writes.
- Patch generation succeeded against fcitx5-rime commit
  `00be19a18ea5b5d1748f9fe6f6bad50f42f96781`.
- Existing pure-context read test passed: 400 mode-preserving reads, no option
  writes/recompositions.
- Existing candidate-budget contract passed: 86 scenarios, 72,807 raw calls;
  preferred bulk/paged/hidden/wrap contract also passed.
- One budget-test compile invocation initially omitted its include path; the
  corrected command above passed. This was a harness invocation issue, not an
  Android/native build result.

## Android acceptance changes and first CI outcome

`PersonalRimeTest.preeditEditingAndPagingWork` now uses the dedicated pinyin API
and checks `niho` → insert `a` → full `你好` candidates without moving to the end,
stale candidate rejection, key-up caret stability, repeated backspace/reinsert,
complete candidate commit without a trailing raw suffix, and the user's
`yiduanzidaanlejiu` → insert `w` example against a normal end-typing baseline,
then Space commits that whole-input candidate without leaving a raw suffix.
It explicitly performs the two-click `ni|ho` then `nih|o` sequence using the old
display string, plus full hardware Shift+A and Control+Backspace press/release
sequences. The first CI method stopped at the immediate first-tap cache assertion;
these later real-engine assertions therefore remain unverified pending rerun.

`PersonalKeyboardUiTest` no longer moves to the end before clicking `你好` after
the actual screen tap and insertion. The UI mapping test checks the source string
passed with the touch position as well as the cursor-marker coordinate behavior.
Both the real screen-tap test and marker-mapping test passed in the first CI run,
as did `PersonalCandidateBudgetTest`. Explicit selection checks use native
`hasPreedit()` to prove no raw suffix and wait with a timeout for the existing
asynchronously published UI cache to clear.

## Required real-engine / UI gate before shipping

The first isolated CI run compiled Android/JNI/the full adapter and passed unit
tests, but did not pass all runtime gates. Host helper tests alone cannot establish
candidate ranking, actual segmentation, latency, or UI correctness. Keep this off
the release branch until these gates pass on authorized independent CI resources:

1. Compile JNI/Kotlin/generated native adapter and run the updated two tests.
2. Repeat `niho` repair using click, space, numeric/keypad selection; no suffix,
   double commit, or unexpected caret jump. Check End then another letter appends.
3. Actual paging, candidate expand/collapse, hidden and demoted first candidate,
   stale row click and pending revision continuation during rapid edits.
4. Select a Chinese prefix, edit only its pinyin suffix, then select a partial
   candidate whose raw input remains unchanged. Confirm selected prefix and all
   suffix input survive. Explicit taps inside selected text need legacy coverage.
5. Apostrophes, fuzzy/corrected spellings, mixed case, first/end taps, rapidly
   queued taps, Backspace/Delete, long strings, and unsupported/special inputs.
6. Reset, focus/target change, search-panel target switch, ASCII/schema switch,
   deploy/session recreation, and privacy-mode changes.
7. All three preedit modes. CommitPreview uses Chinese client coordinates and
   must not accidentally be interpreted as pinyin-bar positions. Its legacy
   generic cursor path has not been redesigned in this change.
8. Compare normal end typing and middle-edit latency. The former adds no
   recomposition in the helper; middle edits necessarily add engine work.

This is an implementation-and-host-test checkpoint, not release acceptance.
