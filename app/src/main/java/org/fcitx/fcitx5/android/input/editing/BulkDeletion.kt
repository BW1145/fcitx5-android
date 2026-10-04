/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.editing

import android.view.inputmethod.InputConnection

/** Keeps one deletion in memory for the current editor only. */
class BulkDeletion {
    private var originalPrefix: String? = null
    private var deleted: String? = null
    val canRestore get() = deleted != null

    fun begin(connection: InputConnection?, cursor: Int) {
        originalPrefix = if (cursor >= 0) connection?.getTextBeforeCursor(cursor, 0)?.toString()
            ?.takeIf { it.length == cursor } else null
    }

    fun clear(connection: InputConnection, cursor: Int): Boolean {
        if (cursor < 0) return false
        val before = connection.getTextBeforeCursor(cursor, 0)?.toString() ?: return false
        if (before.length != cursor) return false
        val restore = originalPrefix?.takeIf { it.startsWith(before) } ?: before
        originalPrefix = null
        if (cursor > 0 && !connection.deleteSurroundingText(cursor, 0)) return false
        deleted = restore.takeIf { it.isNotEmpty() }
        return true
    }

    fun restore(connection: InputConnection, cursor: Int): Int? {
        val text = deleted ?: return null
        if (cursor != 0 || !connection.commitText(text, 1)) return null
        deleted = null
        return text.length
    }

    fun reset() {
        originalPrefix = null
        deleted = null
    }
}
