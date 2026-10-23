/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.content.res.Configuration
import android.widget.ProgressBar
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.candidateLoading
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.awaitCandidateProgress
import org.fcitx.fcitx5.android.core.CandidateScanState
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesUpdated
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.decoration.FlexboxVerticalDecoration
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import kotlin.math.max

class HorizontalCandidateComponent :
    UniqueViewComponent<HorizontalCandidateComponent, RecyclerView>(), InputBroadcastReceiver {
    private val context by manager.context()
    private val fcitx by manager.fcitx()
    private val service by manager.inputMethodService()
    private val theme by manager.theme()
    private val inputView by manager.inputView()
    private val bar: KawaiiBarComponent by manager.must()
    private val fillStyle by AppPrefs.getInstance().keyboard.horizontalCandidateStyle
    private val maxSpanCountPref by lazy {
        AppPrefs.getInstance().keyboard.run {
            if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT)
                expandedCandidateGridSpanCount else expandedCandidateGridSpanCountLandscape
        }
    }
    private var minWidth = 0
    private var loadJob: Job? = null
    private var loadingIndicatorJob: Job? = null
    private var generation = 0
    private var more = false
    var scanState: CandidateScanState? = null
        private set
    val hasMore get() = more
    private val offset = MutableSharedFlow<Int>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val expandedCandidateOffset = offset.asSharedFlow()

    val adapter: HorizontalCandidateViewAdapter by lazy {
        object : HorizontalCandidateViewAdapter(theme) {
            override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
                super.onBindViewHolder(holder, position)
                holder.itemView.minimumWidth = max(context.dp(40), minWidth)
                holder.itemView.setOnClickListener {
                    service.selectCandidate(holder.idx, holder.candidate)
                }
                holder.itemView.setOnLongClickListener {
                    inputView.showCandidateActionMenu(holder.idx, holder.candidate, holder.ui.root)
                    true
                }
            }
        }
    }

    val layoutManager by lazy {
        object : LinearLayoutManager(context, HORIZONTAL, false) {
            override fun onLayoutCompleted(state: RecyclerView.State) {
                super.onLayoutCompleted(state)
                val end = findLastVisibleItemPosition() + 1
                if (findFirstVisibleItemPosition() <= 0) offset.tryEmit(end.coerceAtLeast(0))
                bar.expandButtonStateMachine.push(
                    ExpandedCandidatesUpdated,
                    ExpandedCandidatesEmpty to (!more && end >= adapter.itemCount)
                )
            }
        }
    }

    override val view by lazy {
        RecyclerView(context).apply {
            id = R.id.candidate_view
            itemAnimator = null
            val indicator = ProgressBar(context).apply {
                contentDescription = context.getString(R.string.loading)
            }
            loadingIndicatorJob = service.lifecycleScope.launch {
                candidateLoading.collect { loading ->
                    if (loading) {
                        val size = context.dp(24)
                        indicator.layout((width - size) / 2, 0, (width + size) / 2, size)
                        overlay.add(indicator)
                    } else overlay.remove(indicator)
                }
            }
            adapter = this@HorizontalCandidateComponent.adapter
            layoutManager = this@HorizontalCandidateComponent.layoutManager
            val divider = ShapeDrawable(RectShape()).apply {
                intrinsicWidth = max(1, context.dp(1))
                paint.color = theme.dividerColor
            }
            addItemDecoration(FlexboxVerticalDecoration(divider))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dx != 0 && this@HorizontalCandidateComponent.layoutManager.findLastVisibleItemPosition() >=
                        this@HorizontalCandidateComponent.adapter.itemCount - 4) loadMore()
                }
            })
        }
    }

    private fun loadMore() {
        if (!more || loadJob?.isActive == true) return
        val requestedGeneration = generation
        val start = adapter.itemCount
        loadJob = service.lifecycleScope.launch {
            val expected = scanState
            val batch = if (expected != null) awaitCandidateProgress(expected) {
                fcitx.runOnReady { getCandidateBatch(start, 16, expected.revision, expected.ruleRevision) }
            } else fcitx.runOnReady { getCandidateBatch(start, 16) }
            if (requestedGeneration != generation || batch.state.stale ||
                (expected != null && !expected.sameGeneration(batch.state))) return@launch
            scanState = batch.state
            more = batch.state.hasMoreAfter(start + batch.candidates.size)
            adapter.appendCandidates(batch.candidates)
        }
    }

    fun updateScanState(state: CandidateScanState) {
        if (scanState?.sameGeneration(state) != true) return
        scanState = state
        more = state.hasMoreAfter(adapter.itemCount)
        bar.expandButtonStateMachine.push(ExpandedCandidatesUpdated,
            ExpandedCandidatesEmpty to (!more && adapter.itemCount == 0))
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        generation++
        loadJob?.cancel()
        loadJob = null
        val count = data.candidates.size
        val spans = maxSpanCountPref.getValue().coerceAtLeast(1)
        minWidth = when (fillStyle) {
            HorizontalCandidateMode.NeverFillWidth -> 0
            else -> view.width / minOf(spans, count.coerceAtLeast(1))
        }
        scanState = data.scanState
        more = data.scanState?.let { it.hasMoreAfter(count) }
            ?: (count > 0 && (data.total < 0 || count < data.total))
        adapter.updateCandidates(data.candidates, data.total)
        view.scrollToPosition(0)
        if (count == 0) {
            offset.tryEmit(0)
            bar.expandButtonStateMachine.push(ExpandedCandidatesUpdated, ExpandedCandidatesEmpty to !more)
        }
    }

    fun close() {
        loadingIndicatorJob?.cancel()
        generation++
        loadJob?.cancel()
        loadJob = null
    }
}

