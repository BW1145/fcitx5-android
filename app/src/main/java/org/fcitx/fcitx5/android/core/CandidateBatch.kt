/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

internal val candidateLoading = kotlinx.coroutines.flow.MutableStateFlow(false)

/** A snapshot of one candidate generation. Short/empty batches are not EOF. */
data class CandidateScanState(
    val revision: Long, val ruleRevision: Long, val cursor: Long,
    val available: Int, val exhausted: Boolean, val budgeted: Boolean,
    val stale: Boolean = false
) {
    fun hasMoreAfter(visibleOffset: Int) = pending || visibleOffset < available
    val pending get() = !exhausted && !stale
    fun sameGeneration(other: CandidateScanState) =
        revision == other.revision && ruleRevision == other.ruleRevision && !other.stale
    companion object {
        fun decode(raw: LongArray): CandidateScanState {
            require(raw.size >= 7) { "Invalid candidate scan state" }
            return CandidateScanState(raw[0], raw[1], raw[2], raw[3].toInt(),
                raw[4] != 0L, raw[5] != 0L, raw[6] != 0L)
        }
    }
}
data class CandidateBatch(val candidates: Array<CandidateWord>, val state: CandidateScanState)

/** Explicit user demand only. Never use this to top up the initial candidate event. */
suspend fun awaitCandidateProgress(
    expected: CandidateScanState,
    fetch: suspend () -> CandidateBatch
): CandidateBatch {
    while (true) {
        val batch = fetch()
        if (!expected.sameGeneration(batch.state) || batch.candidates.isNotEmpty() ||
            !batch.state.pending) return batch
        kotlinx.coroutines.delay(1)
    }
}

/** Unbudgeted paging only; use the number actually returned, never an assumed page size. */
fun nextCandidateOffset(start: Int, returned: Int, requested: Int, total: Int): Int? =
    if (returned <= 0 || (total >= 0 && start + returned >= total) ||
        (total < 0 && returned < requested)) null else start + returned

/** Carries the immediate UI target token across the FIFO worker and native dispatcher. */
class InputTargetGuard(val isCurrent: () -> Boolean) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<InputTargetGuard>
}

suspend fun inputTargetIsCurrent(): Boolean =
    kotlinx.coroutines.currentCoroutineContext()[InputTargetGuard]?.isCurrent?.invoke() != false

/** Explicit Space/digit selection stays ordered before later letters in the service FIFO. */
suspend fun awaitCandidateSelection(
    initial: CandidateScanState,
    index: Int,
    fetch: suspend () -> CandidateBatch
): CandidateScanState {
    var state = initial
    while (state.available <= index && state.pending) {
        if (!inputTargetIsCurrent()) return initial.copy(stale = true)
        state = fetch().state
        if (!initial.sameGeneration(state)) return initial.copy(stale = true)
        kotlinx.coroutines.delay(1)
    }
    return if (inputTargetIsCurrent()) state else initial.copy(stale = true)
}

/** A rule refresh is not a canceled key: retry the unsent selection on its original target. */
suspend fun sendPendingCandidateSelection(
    readState: suspend () -> CandidateScanState,
    awaitReady: suspend (CandidateScanState) -> CandidateScanState,
    trySend: suspend (CandidateScanState) -> Boolean
) {
    while (inputTargetIsCurrent()) {
        val current = readState()
        val ready = if (current.budgeted) awaitReady(current) else current
        if (!inputTargetIsCurrent()) return
        if (!ready.stale && trySend(ready)) return
        // External edits can race repeatedly; never busy-loop on the engine dispatcher.
        kotlinx.coroutines.delay(1)
    }
}

/** Matches native preferredKey's fixed Space/1..9/0 mapping. */
fun candidateSelectionIndex(sym: Int, simpleModifiers: Boolean, up: Boolean): Int? =
    if (up || simpleModifiers) null else when (sym) {
        32 -> 0
        in 49..57 -> sym - 49
        48 -> 9
        else -> null
    }

/** First data and state arrive together; never preflight with a separate state request. */
suspend fun collectCandidateRange(
    offset: Int, limit: Int,
    fetch: suspend (Int, Int, Long, Long) -> CandidateBatch
): Array<CandidateWord> {
    if (offset < 0 || limit <= 0 || offset > Int.MAX_VALUE - limit) return emptyArray()
    val first = fetch(offset, limit, -1L, -1L)
    if (first.state.stale) return emptyArray()
    if (!first.state.budgeted || first.state.exhausted || first.candidates.size >= limit)
        return first.candidates
    val result = ArrayList<CandidateWord>(limit.coerceAtMost(64))
    result.addAll(first.candidates)
    var state = first.state
    while (result.size < limit && state.pending) {
        kotlinx.coroutines.delay(1)
        val batch = fetch(offset + result.size, limit - result.size,
            first.state.revision, first.state.ruleRevision)
        if (!first.state.sameGeneration(batch.state)) return emptyArray()
        result.addAll(batch.candidates)
        state = batch.state
    }
    return result.toTypedArray()
}
