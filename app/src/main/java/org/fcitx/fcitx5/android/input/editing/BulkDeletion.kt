/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.editing

import android.view.inputmethod.InputConnection

class BulkDeletion {
    fun clear(connection: InputConnection, cursor: Int): Boolean {
        if (cursor < 0) return false
        val before = connection.getTextBeforeCursor(cursor, 0)?.toString() ?: return false
        if (before.length != cursor) return false
        if (cursor > 0 && !connection.deleteSurroundingText(cursor, 0)) return false
        return true
    }
}
