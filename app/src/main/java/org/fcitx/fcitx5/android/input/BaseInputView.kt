/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.view.View
import android.view.WindowInsets
import android.widget.PopupMenu
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.core.CandidateWord
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.awaitFrame
import org.fcitx.fcitx5.android.utils.navbarFrameHeight
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import splitties.views.dsl.core.withTheme
import kotlin.math.max

abstract class BaseInputView(
    val service: FcitxInputMethodService,
    val fcitx: FcitxConnection,
    val theme: Theme
) : ConstraintLayout(service) {

    /**
     * Update UI (from cached events in FcitxAPI) to match fcitx's state, before ready to receive real events
     */
    protected abstract fun onStartHandleFcitxEvent()

    protected abstract fun handleFcitxEvent(it: FcitxEvent<*>)

    private var eventHandlerJob: Job? = null

    private fun setupFcitxEventHandler() {
        eventHandlerJob = service.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Keep one latest value per UI state type and one pending wakeup.
            // Text operations are delivered separately by the service.
            val pending = linkedMapOf<FcitxEvent.EventType, FcitxEvent<*>>()
            val updates = Channel<Unit>(Channel.CONFLATED)
            val listener: (FcitxEvent<*>) -> Unit = { event ->
                when (event) {
                    is FcitxEvent.InputPanelEvent, is FcitxEvent.ClientPreeditEvent,
                    is FcitxEvent.CandidateListEvent, is FcitxEvent.PagedCandidateEvent,
                    is FcitxEvent.IMChangeEvent, is FcitxEvent.StatusAreaEvent -> {
                        synchronized(pending) {
                            pending.remove(event.eventType)
                            pending[event.eventType] = event
                        }
                        updates.trySend(Unit)
                    }
                    else -> Unit
                }
            }
            fcitx.runImmediately { addInputEventListener(listener) }
            try {
                onStartHandleFcitxEvent()
                for (ignored in updates) {
                    if (synchronized(pending) { pending.isEmpty() }) continue
                    awaitFrame()
                    val batch = synchronized(pending) {
                        updates.tryReceive()
                        pending.values.toList().also { pending.clear() }
                    }
                    batch.forEach {
                        if (it !is FcitxEvent.StatusAreaEvent) dismissCandidateActionMenu()
                        handleFcitxEvent(it)
                    }
                }
            } finally {
                fcitx.runImmediately { removeInputEventListener(listener) }
                updates.cancel()
                synchronized(pending) { pending.clear() }
            }
        }
    }

    var handleEvents = false
        set(value) {
            field = value
            if (field) {
                if (eventHandlerJob == null) {
                    setupFcitxEventHandler()
                } else onStartHandleFcitxEvent()
            } else {
                dismissCandidateActionMenu()
                eventHandlerJob?.cancel()
                eventHandlerJob = null
            }
        }

    private var candidateActionMenu: PopupMenu? = null
    private var candidateMenuJob: Job? = null

    private fun dismissCandidateActionMenu() {
        candidateMenuJob?.cancel()
        candidateMenuJob = null
        candidateActionMenu?.dismiss()
        candidateActionMenu = null
    }

    val themedContext = context.withTheme(R.style.Theme_InputViewTheme)

    fun showCandidateActionMenu(idx: Int, candidate: CandidateWord, view: View) {
        dismissCandidateActionMenu()
        if (!handleEvents) return
        val target = service.inputActionToken
        candidateMenuJob = service.lifecycleScope.launch {
            val actions = fcitx.runOnReady { getCandidateActions(idx, candidate) }
            if (actions.isEmpty() || !handleEvents || !view.isAttachedToWindow || !service.isCurrentInputTarget(target) ||
                fcitx.runImmediately { candidateRevisionCached } != candidate.revision) return@launch
            InputFeedbacks.hapticFeedback(view, longPress = true)
            candidateActionMenu = PopupMenu(themedContext, view).apply {
                menu.add(buildSpannedString {
                    bold {
                        color(
                            context.styledColorOrDefault(
                                android.R.attr.colorAccent,
                                theme.genericActiveForegroundColor
                            )
                        ) {
                            append(candidate.text)
                        }
                    }
                }).apply {
                    isEnabled = false
                }
                actions.forEach { action ->
                    menu.item(action.text) {
                        service.postInputJob(target) { triggerCandidateAction(idx, action.id, candidate) }
                    }
                }
                setOnDismissListener {
                    candidateActionMenu = null
                }
                show()
            }
        }
    }

    private val navbarBackground by ThemeManager.prefs.navbarBackground

    protected fun getNavBarBottomInset(windowInsets: WindowInsets): Int {
        if (navbarBackground != ThemePrefs.NavbarBackground.Full) {
            return 0
        }
        val insets = WindowInsetsCompat.toWindowInsetsCompat(windowInsets)
        // use navigation bar insets when available
        val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        // in case navigation bar insets goes wrong (eg. on LineageOS 21+ with gesture navigation)
        // use mandatory system gesture insets
        val mandatory = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        var insetsBottom = max(navBars.bottom, mandatory.bottom)
        if (insetsBottom <= 0) {
            // check system gesture insets and fallback to navigation_bar_frame_height just in case
            val gesturesBottom = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            if (gesturesBottom > 0) {
                insetsBottom = max(gesturesBottom, context.navbarFrameHeight())
            }
        }
        return insetsBottom
    }

    private val ignoreSystemWindowInsets by AppPrefs.getInstance().advanced.ignoreSystemWindowInsets

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (ignoreSystemWindowInsets) {
            // suppress view's own onApplyWindowInsets
            setOnApplyWindowInsetsListener { _, insets -> insets }
        } else {
            // on API 35+, we must call requestApplyInsets() manually after replacing views,
            // otherwise View#onApplyWindowInsets won't be called. ¯\_(ツ)_/¯
            requestApplyInsets()
        }
    }

    override fun onDetachedFromWindow() {
        handleEvents = false
        super.onDetachedFromWindow()
    }
}
