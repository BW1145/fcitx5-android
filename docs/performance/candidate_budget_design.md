# Budgeted candidate continuation (step 1)

Only bulk Rime lists with matching preference rules use bounded scanning. No-rule
lists, Anthy's preview/navigation and other paged engines retain their original
path. The saved engine/schema/input/word rules remain the single source of truth.
The immutable provider snapshot API does not mean Rime itself uses early filtering.

## Native contract

Each request classifies at most 32 new original candidates and checks a 2 ms
cooperative deadline between candidates. One source call cannot be preempted.
Returning already classified visible words additionally reads their cached source
objects and applies display filters; 32 is **not** a cap on all calls or the entire
JNI duration. The actual Rime `candidateFromAll` returns an existing
`globalCandidateWords_` object for these already visited indices.

The state carries candidate revision, rule revision, raw cursor, stable visible
count, exhausted, budgeted and stale. A short or empty batch is not EOF. An exact
revision mismatch returns stale without scanning. Input/focus context or changed
rule contents invalidate the old continuation. Rule loading happens at request
entry; output uses the same in-memory revision, never a second file reload paired
with old candidate objects. Identical forced reloads preserve rule revision.

Hidden indices never enter the visible prefix. Downranked indices are buffered
until the original `order.size() >= 32` or exhaustion boundary, then appended in
original order. The existing boundary is preserved exactly (the size includes
previously appended downranked entries). No rule or candidate is dropped to meet
a time budget.

## User demand and ordering

Initial display accepts the partial stable prefix without silently filling 16.
An explicit expand/next/scroll demand may continue through empty batches, yielding
between them, stopping on visible progress or exhaustion. New input cancels that
UI demand; an empty pending list still exposes the expand/continue entry.

Space/digit selection is different: preserving the key order requires resolving
that explicit selection before the following letters. Its coroutine yields between
batches but retains the input FIFO order; this is not a guarantee that the next
letter bypasses a pending Space. Focus changes cancel using the immediate input
target token, not only the native focus event queued behind the selection.
Loading is visible. Exhaustion with no requested index preserves the previous
preferred-selection behavior (no hidden/raw fallback selection).

## Verification boundaries

`run_candidate_budget_contract.sh` uses the real helper and a complete-order oracle.
`run_native_candidate_checks.sh` checks rule replacement/deletion/migration and
immutable snapshots. `check_candidate_frontend_syntax.sh` compiles the frontend
against real pinned upstream headers, but is not Android linking.
`budget_benchmark.sh` measures synthetic host helper work, not Rime or phone latency.
Android JNI, real IME selection ordering, focus cancellation, empty-list UI and
expanded-list behavior require the existing emulator CI before distribution.
