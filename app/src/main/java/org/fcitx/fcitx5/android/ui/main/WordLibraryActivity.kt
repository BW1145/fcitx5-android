/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.os.Parcelable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.WordLibrary
import java.text.DateFormat
import java.util.Date
import java.util.Locale

internal data class WordLibraryContents(val words: List<WordLibrary.Word>, val rules: List<WordLibrary.Rule>)

/** The screen uses one read after a batch; the source also provides a daemon-free UI test seam. */
internal interface WordLibrarySource {
    suspend fun load(): WordLibraryContents
    suspend fun applyWordActions(words: List<WordLibrary.Word>, action: WordLibrary.WordAction): WordLibrary.BatchResult<WordLibrary.Word>
    suspend fun restoreRules(rules: List<WordLibrary.Rule>): WordLibrary.BatchResult<WordLibrary.Rule>
}

internal class WordLibraryViewState : ViewModel() {
    var contents = WordLibraryContents(emptyList(), emptyList())
    var loaded = false
    var options = WordLibraryPresentation.Options()
    var adjusted = false
    var history = false
    var historyQuery = ""
    var listPosition: Parcelable? = null
    val selection = WordLibraryPresentation.Selection()
    val selectedWords = linkedMapOf<String, WordLibrary.Word>()
    val selectedRules = linkedMapOf<String, WordLibrary.Rule>()
}

class WordLibraryActivity : ExperienceActivity() {
    private val connectionName = "word-library-${java.util.UUID.randomUUID()}"
    private val connection by lazy { FcitxDaemon.connect(connectionName) }
    internal var librarySource: WordLibrarySource = object : WordLibrarySource {
        override suspend fun load() = WordLibraryContents(WordLibrary.words(connection), WordLibrary.rules())
        override suspend fun applyWordActions(words: List<WordLibrary.Word>, action: WordLibrary.WordAction) =
            WordLibrary.applyWordActions(connection, words, action)
        override suspend fun restoreRules(rules: List<WordLibrary.Rule>) = WordLibrary.restoreRules(connection, rules)
    }
    private lateinit var state: WordLibraryViewState
    private lateinit var content: FrameLayout
    private lateinit var libraryView: LinearLayout
    private lateinit var query: EditText
    private lateinit var clearSearch: Button
    private lateinit var footerBar: LinearLayout
    private lateinit var list: RecyclerView
    private lateinit var listLayout: LinearLayoutManager
    private lateinit var rowAdapter: WordLibraryAdapter
    private lateinit var tabs: TabLayout
    private lateinit var ruleStatus: RadioGroup
    private lateinit var summary: TextView
    private lateinit var empty: TextView
    private lateinit var progress: ProgressBar
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionSummary: TextView
    private lateinit var selectButton: Button
    private lateinit var selectAllButton: Button
    private lateinit var cancelSelectionButton: Button
    private lateinit var forgetButton: Button
    private lateinit var lowerButton: Button
    private lateinit var restoreButton: Button
    private lateinit var addButton: Button
    private lateinit var sortButton: Button
    private lateinit var filterButton: Button
    private lateinit var historyButton: Button
    private lateinit var refreshButton: Button
    private lateinit var historyRows: LinearLayout
    private lateinit var historyQuery: EditText
    private val backSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearSelection()
    }
    private var renderJob: Job? = null
    private var refreshJob: Job? = null
    private var renderRevision = 0
    private var busy = false
    private var loading = false
    private var rendering = false
    private var loadError: String? = null
    private var visibleRows: List<WordLibraryRow> = emptyList()
    private var visibleKeys: List<String> = emptyList()
    private var exporting: WordLibrary.Snapshot? = null
    private val exporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val snapshot = exporting
        if (uri != null && snapshot != null) task {
            withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.use { WordLibrary.export(snapshot, it) } }
        }
    }
    private val importer = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) task {
            withContext(Dispatchers.IO) { contentResolver.openInputStream(uri)!!.use { WordLibrary.import(it) } }
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "我的词库与历史备份"
        state = ViewModelProvider(this)[WordLibraryViewState::class.java]
        if (!state.loaded && savedInstanceState != null) {
            state.options = WordLibraryPresentation.Options(
                savedInstanceState.getString("query", ""),
                WordLibraryPresentation.Sort.entries.getOrElse(savedInstanceState.getInt("sort")) { WordLibraryPresentation.Sort.PINYIN },
                savedInstanceState.getBoolean("long"), savedInstanceState.getBoolean("multiple"),
                WordLibraryPresentation.RuleStatus.entries.getOrElse(savedInstanceState.getInt("ruleStatus")) { WordLibraryPresentation.RuleStatus.ALL }
            )
            state.adjusted = savedInstanceState.getBoolean("adjusted")
            state.history = savedInstanceState.getBoolean("history")
            state.historyQuery = savedInstanceState.getString("historyQuery", "")
            @Suppress("DEPRECATION")
            val position: Parcelable? = savedInstanceState.getParcelable("listPosition")
            state.listPosition = position
        }
        content = FrameLayout(this)
        buildLibrary()
        setContentView(content)
        onBackPressedDispatcher.addCallback(this, backSelection)
        if (state.history) showHistory() else showLibrary()
        refresh()
    }

    private fun horizontal() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun inset(view: View, top: Int = 0, bottom: Int = 0): View = view.apply { setPadding(dp(16), dp(top), dp(16), dp(bottom)) }
    private fun control(text: String, tag: String, action: () -> Unit) = button(text, action).apply {
        this.tag = tag
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(44)
        setPadding(dp(10), 0, dp(10), 0)
        textSize = 14f
    }
    private fun mutedText(size: Float = 13f) = TextView(this).apply {
        textSize = size
        val value = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorSecondary, value, true)
        if (value.resourceId != 0) setTextColor(resources.getColorStateList(value.resourceId, theme)) else setTextColor(value.data)
    }

    private fun buildLibrary() {
        libraryView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        libraryView.addView(inset(horizontal().apply {
            query = field("搜索词条或拼音", state.options.query).apply {
                tag = "library.search"
                contentDescription = "搜索词条或拼音"
                textSize = 16f
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            }
            addView(query, LinearLayout.LayoutParams(0, dp(50), 1f))
            clearSearch = control("×", "library.clearSearch") { if (!busy) query.setText("") }.apply {
                contentDescription = "清除搜索"
                textSize = 24f
                val background = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
                setBackgroundResource(background.resourceId)
            }
            addView(clearSearch, LinearLayout.LayoutParams(dp(48), dp(48)))
        }))
        tabs = TabLayout(this).apply {
            tag = "library.tabs"
            addTab(newTab().setText("学习词条"), !state.adjusted)
            addTab(newTab().setText("已调整候选"), state.adjusted)
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    if (busy) return
                    state.adjusted = tab.position == 1
                    clearSelection()
                    state.listPosition = null
                    renderLibrary(resetScroll = true)
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        libraryView.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)))
        ruleStatus = RadioGroup(this).apply {
            tag = "library.ruleStatus"
            orientation = RadioGroup.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
            val labels = listOf("全部", "已忘记", "已降低优先级")
            val tags = listOf("all", "forgotten", "lowered")
            labels.forEachIndexed { index, label ->
                addView(RadioButton(this@WordLibraryActivity).apply {
                    id = View.generateViewId()
                    tag = "library.ruleStatus.${tags[index]}"
                    text = label
                    textSize = 13f
                    minHeight = dp(44)
                    setPadding(0, 0, 0, 0)
                    isChecked = state.options.ruleStatus.ordinal == index
                }, RadioGroup.LayoutParams(0, -2, if (index == 2) 1.5f else 1f))
            }
            setOnCheckedChangeListener { group, id ->
                val index = (0 until group.childCount).indexOfFirst { group.getChildAt(it).id == id }
                if (index >= 0 && !busy) {
                    state.options = state.options.copy(ruleStatus = WordLibraryPresentation.RuleStatus.entries[index])
                    renderLibrary(resetScroll = true)
                }
            }
        }
        libraryView.addView(ruleStatus)
        libraryView.addView(inset(horizontal().apply {
            sortButton = control("", "library.sort") { chooseSort() }
            filterButton = control("", "library.filters") { chooseFilters() }
            selectButton = control("多选", "library.select") { if (!busy && !loading && !rendering) { state.selection.enter(); updateSelection() } }
            refreshButton = control("刷新", "library.refresh") { refresh() }
            addView(sortButton, LinearLayout.LayoutParams(0, -2, 1.25f))
            addView(filterButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(selectButton, LinearLayout.LayoutParams(0, -2, .7f))
            addView(refreshButton, LinearLayout.LayoutParams(0, -2, .7f))
        }))
        summary = mutedText().apply { tag = "library.summary"; setPadding(dp(18), dp(4), dp(18), dp(7)) }
        libraryView.addView(summary)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
        libraryView.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        val listContainer = FrameLayout(this)
        listLayout = LinearLayoutManager(this)
        rowAdapter = WordLibraryAdapter(this, state.selection,
            canInteract = { !busy && !loading && !rendering },
            onClick = { row ->
                if (state.selection.active) toggle(row) else when (row) {
                    is WordLibraryRow.Learned -> wordMenu(row.group)
                    is WordLibraryRow.Adjusted -> confirmRestore(listOf(row.rule))
                }
            },
            onLongClick = ::toggle
        )
        list = RecyclerView(this).apply {
            tag = "library.list"
            layoutManager = listLayout
            adapter = rowAdapter
            setHasFixedSize(true)
            itemAnimator = null
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
        }
        listContainer.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = mutedText(15f).apply {
            tag = "library.empty"
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }
        listContainer.addView(empty, FrameLayout.LayoutParams(-1, -1))
        libraryView.addView(listContainer, LinearLayout.LayoutParams(-1, 0, 1f))
        selectionBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(6), dp(16), dp(8)) }
        selectionSummary = mutedText().apply { tag = "library.selectionSummary"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        selectionBar.addView(selectionSummary)
        selectionBar.addView(horizontal().apply {
            selectAllButton = control("全选当前结果", "library.selectAll") { selectVisible() }
            cancelSelectionButton = control("取消选择", "library.cancelSelection") { clearSelection() }
            addView(selectAllButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(cancelSelectionButton, LinearLayout.LayoutParams(0, -2, 1f))
        })
        selectionBar.addView(horizontal().apply {
            forgetButton = control("忘记", "library.forget") { confirmWords(selectedWords(), WordLibrary.WordAction.FORGET) }
            lowerButton = control("降低优先级", "library.lower") { confirmWords(selectedWords(), WordLibrary.WordAction.LOWER) }
            restoreButton = control("恢复默认候选", "library.restore") { confirmRestore(selectedRules()) }
            addView(forgetButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(lowerButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(restoreButton, LinearLayout.LayoutParams(0, -2, 1f))
        })
        libraryView.addView(selectionBar)
        addButton = control("添加人名或术语", "library.add") { addWord() }
        historyButton = control("历史备份", "library.history") { if (!busy) showHistory() }
        footerBar = horizontal().apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(addButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(historyButton, LinearLayout.LayoutParams(-2, -2))
        }
        libraryView.addView(inset(footerBar, 0, 8))
        query.doAfterTextChanged {
            state.options = state.options.copy(query = it.toString())
            renderLibrary(resetScroll = true, debounce = true)
        }
        updateChrome()
    }

    private fun showLibrary() {
        state.history = false
        content.removeAllViews()
        content.addView(libraryView, FrameLayout.LayoutParams(-1, -1))
        renderLibrary()
    }

    /** Preserve the existing backup page's stacked layout, labels and actions. */
    private fun showHistory() {
        if (!state.history && list.childCount > 0) state.listPosition = listLayout.onSaveInstanceState()
        clearSelection()
        state.history = true
        val panel = column()
        panel.addView(button("我的词库") { showLibrary(); refresh() })
        panel.addView(button("历史备份") { renderHistory() })
        historyQuery = field("搜索词条、拼音或备份名称", state.historyQuery)
        panel.addView(historyQuery)
        historyQuery.doAfterTextChanged { state.historyQuery = it.toString(); renderHistory() }
        historyRows = column()
        panel.addView(historyRows)
        content.removeAllViews()
        content.addView(ScrollView(this).apply { addView(panel) }, FrameLayout.LayoutParams(-1, -1))
        renderHistory()
    }

    private fun task(block: suspend () -> Unit) {
        lifecycleScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e.message ?: "操作未完成") }
        }
    }

    private fun refresh() {
        if (busy || refreshJob?.isActive == true) return
        refreshJob = lifecycleScope.launch {
            loading = true; loadError = null; updateChrome()
            try {
                state.contents = withContext(Dispatchers.IO) { librarySource.load() }
                state.loaded = true
                render()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { loadError = e.message ?: "词库读取失败"; showError("词库读取失败：$loadError\n可点击“刷新”重试。") }
            finally { loading = false; updateChrome() }
        }
    }

    private fun render() {
        if (state.history) renderHistory() else renderLibrary()
    }

    private data class ScrollAnchor(val key: String?, val position: Int, val offset: Int)
    private fun anchor(): ScrollAnchor {
        val position = listLayout.findFirstVisibleItemPosition().coerceAtLeast(0)
        return ScrollAnchor(rowAdapter.currentList.getOrNull(position)?.id, position,
            listLayout.findViewByPosition(position)?.top?.minus(list.paddingTop) ?: 0)
    }

    private fun renderLibrary(resetScroll: Boolean = false, debounce: Boolean = false) {
        val revision = ++renderRevision
        renderJob?.cancel()
        rendering = true
        updateChrome()
        val options = state.options
        val adjusted = state.adjusted
        val contents = state.contents
        renderJob = lifecycleScope.launch {
            if (debounce) delay(160)
            val rows = withContext(Dispatchers.Default) {
                if (adjusted) WordLibraryPresentation.rules(contents.rules, options).map { WordLibraryRow.Adjusted(it) }
                else WordLibraryPresentation.groups(contents.words, options).map { WordLibraryRow.Learned(it) }
            }
            if (revision != renderRevision) return@launch
            val savedAnchor = anchor()
            val previousRows = rowAdapter.currentList
            val positions = rows.withIndex().associate { it.value.id to it.index }
            visibleRows = rows
            visibleKeys = rows.flatMap { it.keys }
            rowAdapter.submitList(rows) {
                if (revision != renderRevision) return@submitList
                rendering = false
                when {
                    resetScroll -> { state.listPosition = null; listLayout.scrollToPositionWithOffset(0, 0) }
                    state.listPosition != null -> { listLayout.onRestoreInstanceState(state.listPosition); state.listPosition = null }
                    rows.isNotEmpty() -> {
                        // If the top row was forgotten, keep its nearest surviving neighbour.
                        val survivingKey = previousRows.asSequence().drop(savedAnchor.position)
                            .map { it.id }.firstOrNull { it in positions }
                            ?: previousRows.asSequence().take(savedAnchor.position).map { it.id }
                                .lastOrNull { it in positions }
                        val index = positions[savedAnchor.key] ?: positions[survivingKey]
                            ?: savedAnchor.position.coerceAtMost(rows.lastIndex)
                        listLayout.scrollToPositionWithOffset(index, savedAnchor.offset)
                    }
                }
                updateChrome()
            }
            updateChrome()
        }.also { job ->
            job.invokeOnCompletion { error ->
                if (error != null && revision == renderRevision) {
                    rendering = false
                    updateChrome()
                }
            }
        }
    }

    private fun chooseSort() {
        if (busy) return
        AlertDialog.Builder(this).setTitle("排序")
            .setSingleChoiceItems(arrayOf("拼音 / 读音 A–Z", "词长：长词在前"), state.options.sort.ordinal) { dialog, which ->
                state.options = state.options.copy(sort = WordLibraryPresentation.Sort.entries[which])
                dialog.dismiss(); renderLibrary(resetScroll = true)
            }.setNegativeButton("取消", null).show()
    }

    private fun chooseFilters() {
        if (busy) return
        val checked = booleanArrayOf(state.options.longOnly, state.options.multipleReadingsOnly)
        AlertDialog.Builder(this).setTitle("筛选当前列表")
            .setMultiChoiceItems(arrayOf("仅长词（4 个字及以上）", "同一词条有多个读音"), checked) { _, index, value -> checked[index] = value }
            .setPositiveButton("应用") { _, _ ->
                state.options = state.options.copy(longOnly = checked[0], multipleReadingsOnly = checked[1])
                renderLibrary(resetScroll = true)
            }.setNeutralButton("清除筛选") { _, _ ->
                state.options = state.options.copy(longOnly = false, multipleReadingsOnly = false)
                renderLibrary(resetScroll = true)
            }.setNegativeButton("取消", null).show()
    }

    private fun updateChrome() {
        if (!::summary.isInitialized) return
        val adjusted = state.adjusted
        val selected = state.selection.active
        val filtered = state.options.query.isNotBlank() || state.options.longOnly || state.options.multipleReadingsOnly ||
            adjusted && state.options.ruleStatus != WordLibraryPresentation.RuleStatus.ALL
        val recordCount = visibleKeys.size
        summary.text = when {
            busy -> "正在处理所选记录…"
            loading && !state.loaded -> "正在读取个人词库…"
            rendering && state.loaded -> "正在筛选…"
            loadError != null -> "列表刷新失败，点击“刷新”重试"
            adjusted -> "${if (filtered) "找到" else "共"} ${visibleRows.size} 条候选调整 · 长按可多选"
            else -> "${if (filtered) "找到" else "共"} ${visibleRows.size} 个词 · $recordCount 条学习记录 · 长按可多选"
        }
        progress.visibility = if (busy || loading) View.VISIBLE else View.GONE
        empty.visibility = if (visibleRows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            loading && !state.loaded -> "正在读取…"
            loadError != null && !state.loaded -> "未能读取词库\n请点击“刷新”重试"
            filtered -> "没有符合条件的${if (adjusted) "候选调整" else "个人词条"}\n试试其他关键词，或清除筛选。"
            adjusted -> "还没有主动调整的候选\n在键盘上长按候选，或在学习词条中选择“忘记”“降低优先级”，即可在这里查看。"
            else -> "还没有个人学习词条\n可添加人名或术语，也可继续输入来积累。\n基础词库和模型候选可在键盘上长按处理。"
        }
        sortButton.text = if (state.options.sort == WordLibraryPresentation.Sort.PINYIN) "读音排序 ▾" else "长词在前 ▾"
        val filters = listOfNotNull("长词".takeIf { state.options.longOnly }, "多读音".takeIf { state.options.multipleReadingsOnly })
        filterButton.text = if (filters.isEmpty()) "筛选 ▾" else filters.joinToString("·") + " ▾"
        filterButton.contentDescription = "筛选：${filters.joinToString("、").ifEmpty { "全部" }}"
        ruleStatus.visibility = if (adjusted) View.VISIBLE else View.GONE
        selectionBar.visibility = if (selected) View.VISIBLE else View.GONE
        footerBar.visibility = if (selected) View.GONE else View.VISIBLE
        clearSearch.visibility = if (state.options.query.isNotEmpty()) View.VISIBLE else View.GONE
        clearSearch.isEnabled = !busy
        addButton.visibility = if (selected || adjusted) View.GONE else View.VISIBLE
        selectButton.visibility = if (selected) View.GONE else View.VISIBLE
        val selectedCount = state.selection.keys.size
        val hiddenCount = state.selection.hiddenCount(visibleKeys)
        val selectedGroupCount = if (adjusted) selectedRules().map { it.text }.distinct().size else selectedWords().map { it.text }.distinct().size
        selectionSummary.text = "已选 $selectedGroupCount 个词 · $selectedCount 条${if (adjusted) "候选调整" else "学习记录"}" +
            if (hiddenCount > 0) "\n其中 $hiddenCount 条不在当前结果，仍会一起处理" else ""
        forgetButton.visibility = if (adjusted) View.GONE else View.VISIBLE
        lowerButton.visibility = if (adjusted) View.GONE else View.VISIBLE
        restoreButton.visibility = if (adjusted) View.VISIBLE else View.GONE
        listOf(forgetButton, lowerButton, restoreButton).forEach { it.isEnabled = selectedCount > 0 && !busy && !loading && !rendering }
        listOf(sortButton, filterButton, selectButton, historyButton, addButton, refreshButton, cancelSelectionButton).forEach { it.isEnabled = !busy && !loading }
        selectAllButton.isEnabled = visibleRows.isNotEmpty() && !busy && !loading && !rendering
        selectButton.isEnabled = !busy && !loading && !rendering
        for (index in 0 until ruleStatus.childCount) ruleStatus.getChildAt(index).isEnabled = !busy
        query.isEnabled = !busy
        tabs.isEnabled = !busy
        for (index in 0 until tabs.tabCount) tabs.getTabAt(index)?.view?.isEnabled = !busy
        backSelection.isEnabled = selected && !state.history && !busy
    }

    private fun remember(row: WordLibraryRow) {
        when (row) {
            is WordLibraryRow.Learned -> row.group.records.forEach { state.selectedWords[WordLibraryPresentation.wordKey(it)] = it }
            is WordLibraryRow.Adjusted -> state.selectedRules[WordLibraryPresentation.ruleKey(row.rule)] = row.rule
        }
    }
    private fun toggle(row: WordLibraryRow) {
        if (busy || loading || rendering) return
        remember(row); state.selection.toggle(row.keys); updateSelection()
    }
    private fun selectVisible() {
        if (busy || loading || rendering) return
        visibleRows.forEach(::remember)
        state.selection.selectAll(visibleKeys); updateSelection()
    }
    private fun clearSelection() {
        if (busy) return
        state.selection.clear(); state.selectedWords.clear(); state.selectedRules.clear(); updateSelection()
    }
    private fun updateSelection() {
        if (::rowAdapter.isInitialized) rowAdapter.notifyItemRangeChanged(0, rowAdapter.itemCount, "selection")
        updateChrome()
    }
    private fun selectedWords() = state.selection.keys.mapNotNull { state.selectedWords[it] }
    private fun selectedRules() = state.selection.keys.mapNotNull { state.selectedRules[it] }

    private fun wordMenu(group: WordLibraryPresentation.WordGroup) {
        val suffix = if (group.recordCount > 1) "（全部 ${group.recordCount} 条记录）" else ""
        AlertDialog.Builder(this).setTitle(group.text)
            .setItems(arrayOf("忘记$suffix", "降低优先级$suffix", if (group.recordCount > 1) "分别管理读音与词典记录" else "学习信息")) { _, index ->
                when (index) {
                    0 -> confirmWords(group.records, WordLibrary.WordAction.FORGET)
                    1 -> confirmWords(group.records, WordLibrary.WordAction.LOWER)
                    2 -> if (group.recordCount == 1) wordInfo(group.records.single()) else recordMenu(group)
                }
            }.setNegativeButton("取消", null).show()
    }
    private fun recordMenu(group: WordLibraryPresentation.WordGroup) {
        AlertDialog.Builder(this).setTitle("${group.text} · ${group.recordCount} 条记录")
            .setItems(group.records.map { "${it.reading}\n词典：${it.dictionary}" }.toTypedArray()) { _, index ->
                val word = group.records[index]
                AlertDialog.Builder(this).setTitle("${word.text} · ${word.reading}")
                    .setItems(arrayOf("忘记此记录", "降低此读音的优先级", "学习信息")) { _, action ->
                        when (action) {
                            0 -> confirmWords(listOf(word), WordLibrary.WordAction.FORGET)
                            1 -> confirmWords(listOf(word), WordLibrary.WordAction.LOWER)
                            2 -> wordInfo(word)
                        }
                    }.setNegativeButton("取消", null).show()
            }.setNegativeButton("关闭", null).show()
    }
    private fun wordInfo(word: WordLibrary.Word) {
        AlertDialog.Builder(this).setTitle(word.text)
            .setMessage("词典：${word.dictionary}\n读音：${word.reading}\n学习权重：${word.weight}\n\n权重是输入引擎的内部记录，用于排序，并非输入次数。")
            .setPositiveButton("知道了", null).show()
    }
    private fun describeWord(word: WordLibrary.Word) = "${word.text} · ${word.reading}\n词典：${word.dictionary}"
    private fun describeRule(rule: WordLibrary.Rule) = "${rule.text} · ${wordLibraryReading(rule.reading)}\n${rule.engine} · ${if (rule.mode == "hide") "已忘记" else "已降低优先级"}"
    private fun preview(lines: List<String>) = lines.take(5).joinToString("\n\n") + if (lines.size > 5) "\n\n另有 ${lines.size - 5} 条记录" else ""

    private fun confirmWords(words: List<WordLibrary.Word>, action: WordLibrary.WordAction) {
        if (words.isEmpty() || busy || loading || rendering) return
        val records = words.distinctBy(WordLibraryPresentation::wordKey)
        if (action == WordLibrary.WordAction.LOWER && records.size == 1 && !state.selection.active) {
            executeWords(records, action)
            return
        }
        val forget = action == WordLibrary.WordAction.FORGET
        val verb = if (forget) "忘记" else "降低优先级"
        val explanation = if (forget) "忘记所选词条，并隐藏对应候选。"
            else "降低所选记录对应候选的优先级。可在“已调整候选”中恢复默认候选行为。"
        AlertDialog.Builder(this).setTitle("$verb · ${records.size} 条学习记录")
            .setMessage("$explanation\n\n${preview(records.map(::describeWord))}")
            .setPositiveButton(verb) { _, _ -> executeWords(records, action) }
            .setNegativeButton("取消", null).show()
    }
    private fun confirmRestore(rules: List<WordLibrary.Rule>) {
        if (rules.isEmpty() || busy || loading || rendering) return
        val records = rules.distinctBy(WordLibraryPresentation::ruleKey)
        AlertDialog.Builder(this).setTitle("恢复 ${records.size} 条候选调整？")
            .setMessage("取消所选调整，让候选恢复正常排序。\n\n${preview(records.map(::describeRule))}")
            .setPositiveButton("恢复") { _, _ -> executeRestore(records) }.setNegativeButton("取消", null).show()
    }

    private data class MutationReport(val succeeded: Int, val failedKeys: List<String>, val details: List<String>)
    private fun executeWords(words: List<WordLibrary.Word>, action: WordLibrary.WordAction) {
        executeMutation(if (action == WordLibrary.WordAction.FORGET) "忘记" else "降低优先级") {
            val result = librarySource.applyWordActions(words, action)
            state.selectedWords.clear()
            result.failed.forEach { state.selectedWords[WordLibraryPresentation.wordKey(it.item)] = it.item }
            MutationReport(result.succeeded.size, result.failed.map { WordLibraryPresentation.wordKey(it.item) },
                result.failed.map { "${describeWord(it.item)}\n${it.message}" })
        }
    }
    private fun executeRestore(rules: List<WordLibrary.Rule>) {
        executeMutation("恢复默认候选") {
            val result = librarySource.restoreRules(rules)
            state.selectedRules.clear()
            result.failed.forEach { state.selectedRules[WordLibraryPresentation.ruleKey(it.item)] = it.item }
            MutationReport(result.succeeded.size, result.failed.map { WordLibraryPresentation.ruleKey(it.item) },
                result.failed.map { "${describeRule(it.item)}\n${it.message}" })
        }
    }
    private fun executeMutation(verb: String, mutation: suspend () -> MutationReport) {
        if (busy || loading) return
        busy = true; updateChrome()
        lifecycleScope.launch {
            try {
                val report = mutation()
                state.selection.retainFailures(report.failedKeys)
                try {
                    state.contents = withContext(Dispatchers.IO) { librarySource.load() }
                    state.loaded = true; loadError = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { loadError = e.message ?: "词库读取失败" }
                renderLibrary()
                val outcome = "已$verb ${report.succeeded} 条" + if (report.failedKeys.isNotEmpty()) "，${report.failedKeys.size} 条未完成" else ""
                if (report.failedKeys.isNotEmpty() || loadError != null) {
                    val details = report.details.take(10).joinToString("\n\n") + if (report.details.size > 10) "\n\n其余未完成项仍保留选择，可再次尝试。" else ""
                    showError(outcome + (if (details.isNotEmpty()) "\n未完成项已保留选择。\n\n$details" else "") +
                        (if (loadError != null) "\n\n操作结果已记录，但列表刷新失败，请点击“刷新”：$loadError" else ""))
                } else Toast.makeText(this@WordLibraryActivity, outcome, Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e.message ?: "操作未完成") }
            finally { busy = false; updateSelection() }
        }
    }
    private fun showError(message: String) = AlertDialog.Builder(this).setMessage(message).setPositiveButton("知道了", null).show()

    private fun renderHistory() {
        if (!::historyRows.isInitialized) return
        historyRows.removeAllViews()
        val q = state.historyQuery
        historyRows.addView(button("创建备份") { snapshot() })
        historyRows.addView(button("导入备份 ZIP") { importer.launch("application/zip") })
        WordLibrary.snapshots().filter { (it.name + it.note).contains(q, true) }.forEach { s ->
            historyRows.addView(button("${s.name}\n${DateFormat.getDateTimeInstance().format(Date(s.time))} · ${s.count} 个词\n${s.note}") { snapshotMenu(s) })
        }
    }
    private fun addWord() {
        val form = column(); val text = field("词条，例如：耍我们玩"); val reading = field("拼音，例如：shua wo men wan")
        reading.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_FORCE_ASCII or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        form.addView(text); form.addView(reading)
        val dictionaries = (state.contents.words.map { it.dictionary }.distinct() + "rime_ice").distinct()
        val dict = Spinner(this).apply { adapter = ArrayAdapter(this@WordLibraryActivity, android.R.layout.simple_spinner_dropdown_item, dictionaries) }; form.addView(dict)
        val dialog = AlertDialog.Builder(this).setTitle("添加词条").setView(form).setPositiveButton("保存", null).setNegativeButton("取消", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            task {
                WordLibrary.add(connection, dictionaries[dict.selectedItemPosition], text.text.toString().trim(), reading.text.toString().trim().lowercase(Locale.ROOT))
                dialog.dismiss(); refresh()
            }
        } }; dialog.show()
    }
    private fun snapshot() {
        val form = column(); val name = field("备份名称", "最近打字很顺手"); val note = field("备注", multiline = true)
        form.addView(name); form.addView(note)
        AlertDialog.Builder(this).setTitle("创建输入习惯备份").setView(form).setPositiveButton("创建") { _, _ -> task { WordLibrary.snapshot(connection, name.text.toString(), note.text.toString()); render() } }.setNegativeButton("取消", null).show()
    }
    private fun snapshotMenu(s: WordLibrary.Snapshot) {
        AlertDialog.Builder(this).setTitle(s.name).setItems(arrayOf("查看词条变化", "导出 ZIP", "合并学习数据", "恢复此时状态", "删除备份")) { _, i -> when (i) {
            0 -> { val (added, removed) = WordLibrary.diff(s)
                AlertDialog.Builder(this).setTitle("相较上次备份").setMessage("新增 ${added.size} 个\n${added.joinToString("\n")}\n\n减少 ${removed.size} 个\n${removed.joinToString("\n")}").setPositiveButton("知道了", null).show() }
            1 -> { exporting = s; exporter.launch("input-habits-${s.id}.zip") }
            2, 3 -> AlertDialog.Builder(this).setMessage(if (i == 2) "保留当前学习，并合入这份备份。操作前自动保存当前状态。" else "回到这份备份中的学习数据和候选偏好。操作前自动保存当前状态。")
                .setPositiveButton(if (i == 2) "合并" else "恢复") { _, _ -> task { WordLibrary.restore(connection, s, i == 2); refresh() } }.setNegativeButton("取消", null).show()
            4 -> AlertDialog.Builder(this).setMessage("删除这份历史备份？").setPositiveButton("删除") { _, _ -> WordLibrary.delete(s); render() }.setNegativeButton("取消", null).show()
        } }.show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (!state.history) state.listPosition = listLayout.onSaveInstanceState()
        outState.putString("query", state.options.query)
        outState.putInt("sort", state.options.sort.ordinal)
        outState.putBoolean("long", state.options.longOnly)
        outState.putBoolean("multiple", state.options.multipleReadingsOnly)
        outState.putInt("ruleStatus", state.options.ruleStatus.ordinal)
        outState.putBoolean("adjusted", state.adjusted)
        outState.putBoolean("history", state.history)
        outState.putString("historyQuery", state.historyQuery)
        outState.putParcelable("listPosition", state.listPosition)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        // A recreated Activity owns a separate connection. Cancelled work may finish recovery first.
        lifecycleScope.coroutineContext.job.invokeOnCompletion { FcitxDaemon.disconnect(connectionName) }
        super.onDestroy()
    }
}
