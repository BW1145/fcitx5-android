/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.expanded.window

import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingData
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.core.awaitCandidateProgress
import org.fcitx.fcitx5.android.core.CandidateScanState
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.core.CandidateAction
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.BooleanKey.ExpandedCandidatesEmpty
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesAttached
import org.fcitx.fcitx5.android.input.bar.ExpandButtonStateMachine.TransitionEvent.ExpandedCandidatesDetached
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import org.fcitx.fcitx5.android.input.candidates.expanded.CandidateTabActionsAdapter
import org.fcitx.fcitx5.android.input.candidates.expanded.CandidatesPagingSource
import org.fcitx.fcitx5.android.input.candidates.expanded.ExpandedCandidateLayout
import org.fcitx.fcitx5.android.input.candidates.expanded.PagingCandidateViewAdapter
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.inputView
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.views.recyclerview.verticalLayoutManager
import kotlin.math.max

abstract class BaseExpandedCandidateWindow<T : BaseExpandedCandidateWindow<T>> :
    InputWindow.SimpleInputWindow<T>(), InputBroadcastReceiver {

    protected val service by manager.inputMethodService()
    protected val theme by manager.theme()
    protected val fcitx by manager.fcitx()
    protected val inputView by manager.inputView()
    private val commonKeyActionListener: CommonKeyActionListener by manager.must()
    private val bar: KawaiiBarComponent by manager.must()
    private val horizontalCandidate: HorizontalCandidateComponent by manager.must()
    private val windowManager: InputWindowManager by manager.must()
    private val returnKeyDrawable: ReturnKeyDrawableComponent by manager.must()

    protected val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private lateinit var candidateLayout: ExpandedCandidateLayout

    protected val dividerDrawable by lazy {
        ShapeDrawable(RectShape()).apply {
            val intrinsicSize = max(1, context.dp(1))
            intrinsicWidth = intrinsicSize
            intrinsicHeight = intrinsicSize
            paint.color = theme.dividerColor
        }
    }

    abstract fun onCreateCandidateLayout(): ExpandedCandidateLayout

    final override fun onCreateView(): View {
        candidateLayout = onCreateCandidateLayout().apply {
            scrollableTabs.apply {
                adapter = tabsAdapter
                layoutManager = verticalLayoutManager()
            }
            pinnedTabs.apply {
                adapter = pinnedTabsAdapter
                layoutManager = verticalLayoutManager()
            }
        }
        return candidateLayout
    }

    private val keyActionListener = KeyActionListener { it, source ->
        if (it is KeyAction.LayoutSwitchAction) {
            when (it.act) {
                ExpandedCandidateLayout.Keyboard.UpBtnLabel -> prevPage()
                ExpandedCandidateLayout.Keyboard.DownBtnLabel -> {
                    if (budgetState != null && !candidateLayout.recyclerView.canScrollVertically(1)) loadBudgetPage() else nextPage()
                }
            }
        } else {
            commonKeyActionListener.listener.onKeyAction(it, source)
        }
    }

    abstract val adapter: PagingCandidateViewAdapter
    abstract val layoutManager: RecyclerView.LayoutManager

    val tabsAdapter by lazy {
        object : CandidateTabActionsAdapter(theme, false) {
            override fun onTriggerTabAction(id: Int) {
                fcitx.launchOnReady { it.triggerCandidateListTabAction(id) }
            }
        }
    }

    val pinnedTabsAdapter by lazy {
        object : CandidateTabActionsAdapter(theme, true) {
            override fun onTriggerTabAction(id: Int) {
                fcitx.launchOnReady { it.triggerCandidateListTabAction(id) }
            }
        }
    }

    private fun updateTabs(newTabs: Array<CandidateAction>) {
        val tabs = newTabs.takeWhile { !it.isSeparator }
        val pinnedTabs = newTabs.drop(tabs.size + 1).filter { !it.isSeparator }
        if (tabs.isEmpty() && pinnedTabs.isEmpty()) {
            candidateLayout.tabsContainer.visibility = View.GONE
        } else {
            candidateLayout.tabsContainer.visibility = View.VISIBLE
        }
        tabsAdapter.updateTabs(tabs)
        pinnedTabsAdapter.updateTabs(pinnedTabs)
    }

    private var offsetJob: Job? = null
    private var budgetState: CandidateScanState? = null
    protected val budgetHasMore get() = budgetState?.let {
        it.hasMoreAfter(adapter.offset + budgetWords.size)
    } ?: false
    private var budgetJob: Job? = null
    private val budgetWords = ArrayList<CandidateWord>()

    /** A user demand may span empty chunks, but stops at the first visible progress.
     * Every chunk yields; new input, focus changes, and detach cancel the old generation.
     */
    private fun loadBudgetPage() {
        val expected = budgetState ?: return
        if (budgetJob?.isActive == true || !budgetHasMore) return
        budgetJob = service.lifecycleScope.launch {
            candidateLayout.loading.visibility = View.VISIBLE
            try {
                val batch = awaitCandidateProgress(expected) {
                    fcitx.runOnReady {
                        getCandidateBatch(adapter.offset + budgetWords.size, 48,
                            expected.revision, expected.ruleRevision)
                    }
                }
                if (budgetState?.sameGeneration(expected) != true ||
                    !expected.sameGeneration(batch.state)) return@launch
                budgetState = batch.state
                horizontalCandidate.updateScanState(batch.state)
                budgetWords.addAll(batch.candidates)
                adapter.submitData(PagingData.from(budgetWords.toList()))
                candidateLayout.pageDnBtn.isEnabled = budgetHasMore ||
                    candidateLayout.recyclerView.canScrollVertically(1)
            } finally {
                candidateLayout.loading.visibility = View.GONE
            }
        }
    }

    private val candidatesPager by lazy {
        Pager(
            config = PagingConfig(
                pageSize = 48,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                CandidatesPagingSource(
                    fcitx,
                    total = horizontalCandidate.adapter.total,
                    offset = adapter.offset
                )
            }
        )
    }
    private var candidatesSubmitJob: Job? = null

    abstract fun prevPage()

    abstract fun nextPage()

    override fun onAttached() {
        bar.expandButtonStateMachine.push(ExpandedCandidatesAttached)
        candidateLayout.embeddedKeyboard.also {
            it.onReturnDrawableUpdate(returnKeyDrawable.resourceId)
            it.keyActionListener = keyActionListener
        }
        updateTabs(fcitx.runImmediately { inputPanelCached.tabs })
        offsetJob = service.lifecycleScope.launch {
            horizontalCandidate.expandedCandidateOffset.collect {
                if (it <= 0 && !horizontalCandidate.hasMore) {
                    windowManager.attachWindow(KeyboardWindow)
                } else {
                    candidateLayout.resetPosition()
                    if (horizontalCandidate.scanState?.budgeted == true) {
                        budgetJob?.cancel()
                        budgetState = horizontalCandidate.scanState
                        budgetWords.clear()
                        adapter.refreshWithOffset(it)
                        adapter.submitData(PagingData.empty())
                        candidateLayout.pageDnBtn.isEnabled = true
                        loadBudgetPage()
                    } else {
                        budgetState = null
                        adapter.refreshWithOffset(it)
                    }
                }
            }
        }
        if (horizontalCandidate.scanState?.budgeted != true) {
            candidatesSubmitJob = service.lifecycleScope.launch {
                candidatesPager.flow.collectLatest { adapter.submitData(it) }
            }
        }
    }

    fun bindCandidateUiViewHolder(holder: CandidateViewHolder) {
        holder.itemView.setOnClickListener {
            service.selectCandidate(holder.idx, holder.candidate)
        }
        holder.itemView.setOnLongClickListener {
            inputView.showCandidateActionMenu(holder.idx, holder.candidate, holder.ui.root)
            true
        }
    }

    fun recycleCandidateViewHolder(holder: CandidateViewHolder) {
        holder.itemView.setOnClickListener(null)
        holder.itemView.setOnLongClickListener(null)
    }

    override fun onDetached() {
        bar.expandButtonStateMachine.push(
            ExpandedCandidatesDetached,
            ExpandedCandidatesEmpty to (!horizontalCandidate.hasMore && horizontalCandidate.adapter.itemCount <= adapter.offset)
        )
        budgetJob?.cancel()
        budgetState = null
        budgetWords.clear()
        candidatesSubmitJob?.cancel()
        offsetJob?.cancel()
        candidateLayout.embeddedKeyboard.keyActionListener = null
    }

    override fun onCandidateUpdate(data: FcitxEvent.CandidateListEvent.Data) {
        val previous = budgetState ?: return
        if (data.scanState?.sameGeneration(previous) != true) {
            budgetJob?.cancel()
            budgetState = null
            // Reopen on demand with the new list, never publish an old in-flight result.
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onPreeditEmptyStateUpdate(empty: Boolean) {
        if (empty) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onInputPanelUpdate(data: FcitxEvent.InputPanelEvent.Data) {
        updateTabs(data.tabs)
    }

}
