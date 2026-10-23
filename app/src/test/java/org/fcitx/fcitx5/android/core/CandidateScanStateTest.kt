/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CandidateScanStateTest {
    private val initial = CandidateScanState(7, 4, 0, 0, false, true)
    @Test fun repeatedSameTargetRuleEditsRetryUnsentSelectionExactlyOnce() = runBlocking {
        var current = initial
        var sends = 0
        var attempts = 0
        sendPendingCandidateSelection({ current }, { it.copy(available = 1) }) { ready ->
            attempts++
            if (attempts <= 3) {
                current = current.copy(ruleRevision = current.ruleRevision + 1)
                false
            } else {
                assertTrue(current.sameGeneration(ready))
                sends++
                true
            }
        }
        assertEquals(4, attempts)
        assertEquals(1, sends)
    }
    @Test fun focusChangeDuringRuleRetryNeverSendsIntoNewTarget() = runBlocking {
        var target = 1
        var sends = 0
        withContext(InputTargetGuard { target == 1 }) {
            sendPendingCandidateSelection({ initial }, {
                target = 2
                it.copy(stale = true)
            }) { sends++; true }
        }
        assertEquals(0, sends)
    }
    @Test fun disabledRulesUseRawSendAndLettersNeverEnterSelectionWait() = runBlocking {
        var waits = 0
        var sends = 0
        sendPendingCandidateSelection({ initial.copy(budgeted = false) }, { waits++; it }) {
            sends++; true
        }
        assertEquals(0, waits)
        assertEquals(1, sends)
        assertNull(candidateSelectionIndex('a'.code, false, false))
        assertNull(candidateSelectionIndex(32, true, false))
        assertNull(candidateSelectionIndex(32, false, true))
        assertEquals(0, candidateSelectionIndex(32, false, false))
        assertEquals(9, candidateSelectionIndex('0'.code, false, false))
    }
    @Test fun selectionWaitKeepsIndexAndCancelsOldTargetBeforeNativeFocusJob() = runBlocking {
        val token = java.util.concurrent.atomic.AtomicLong(1)
        var calls = 0
        val state = withContext(InputTargetGuard { token.get() == 1L }) {
            awaitCandidateSelection(initial, 9) {
                calls++
                if (calls == 3) token.incrementAndGet()
                CandidateBatch(emptyArray(), initial.copy(cursor = calls * 32L))
            }
        }
        assertTrue(state.stale)
        assertEquals(3, calls)
    }
    @Test fun selectionWaitStopsAtRequestedIndex() = runBlocking {
        var calls = 0
        withContext(InputTargetGuard { true }) {
            val state = awaitCandidateSelection(initial, 1) {
                calls++
                CandidateBatch(emptyArray(), initial.copy(available = calls, cursor = calls * 32L))
            }
            assertEquals(2, state.available)
        }
        assertEquals(2, calls)
    }
    @Test fun targetGuardSurvivesDispatcherAndObservesImmediateFocusChange() = runBlocking {
        val token = java.util.concurrent.atomic.AtomicLong(1)
        withContext(InputTargetGuard { token.get() == 1L }) {
            assertTrue(inputTargetIsCurrent())
            withContext(Dispatchers.Default) {
                assertTrue(inputTargetIsCurrent())
                token.incrementAndGet() // The native focus job may still be queued.
                assertFalse(inputTargetIsCurrent())
            }
        }
    }
    @Test fun pendingEmptyKeepsContinuationAndExhaustedPrefixRemainsAccessible() {
        assertTrue(initial.hasMoreAfter(0))
        assertTrue(initial.copy(available = 17, exhausted = true).hasMoreAfter(16))
        assertFalse(initial.copy(available = 17, exhausted = true).hasMoreAfter(17))
    }
    @Test fun unbudgetedPagingKeepsSeventeenthAndUsesActualCount() {
        assertEquals(16, nextCandidateOffset(0, 16, 16, 17))
        assertNull(nextCandidateOffset(16, 1, 16, 17))
        assertEquals(3, nextCandidateOffset(0, 3, 16, 17))
        assertNull(nextCandidateOffset(0, 0, 16, 17))
    }
    @Test fun emptyPendingIsNotEnd() {
        assertTrue(initial.pending)
        assertFalse(initial.copy(exhausted = true).pending)
        assertFalse(initial.copy(stale = true).pending)
    }
    @Test fun revisionsBothInvalidate() {
        assertFalse(initial.sameGeneration(initial.copy(revision = 8)))
        assertFalse(initial.sameGeneration(initial.copy(ruleRevision = 5)))
        assertFalse(initial.sameGeneration(initial.copy(stale = true)))
    }
    @Test fun tenThousandHiddenNeedsOneDemandAndStopsAtFirstVisible() = runBlocking {
        var calls = 0
        val batch = awaitCandidateProgress(initial) {
            calls++
            CandidateBatch(if (calls == 313) arrayOf(CandidateWord("", "visible", "")) else emptyArray(),
                initial.copy(cursor = calls * 32L, available = if (calls == 313) 1 else 0))
        }
        assertEquals(313, calls)
        assertEquals(1, batch.candidates.size)
        assertTrue(batch.state.pending) // A short batch does not say the list ended.
    }
    @Test fun newInputStopsBeforePublishingOldResult() = runBlocking {
        var calls = 0
        val batch = awaitCandidateProgress(initial) {
            calls++
            CandidateBatch(emptyArray(), initial.copy(revision = 8, stale = true))
        }
        assertEquals(1, calls)
        assertTrue(batch.state.stale)
    }
    @Test fun closeOrNewInputCancelsBetweenChunks() = runBlocking {
        var calls = 0
        val job = launch {
            awaitCandidateProgress(initial) {
                calls++
                CandidateBatch(emptyArray(), initial.copy(cursor = calls * 32L))
            }
        }
        delay(8)
        job.cancelAndJoin()
        val atCancel = calls
        delay(5)
        assertEquals(atCancel, calls)
        assertTrue(calls in 1..99)
    }
    @Test fun emptyExhaustedStopsImmediately() = runBlocking {
        var calls = 0
        awaitCandidateProgress(initial) {
            calls++
            CandidateBatch(emptyArray(), initial.copy(exhausted = true))
        }
        assertEquals(1, calls)
    }
}
