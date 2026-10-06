/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.status

import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.saved.SavedContentWindow
import org.fcitx.fcitx5.android.input.editing.TextEditingWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.KeyMacroActivity
import org.fcitx.fcitx5.android.ui.main.ToolbarActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type

object ToolbarActions {
    fun entry(context: Context, type: Type): StatusAreaEntry.Android {
        val (label, icon) = when(type) {
            Type.Undo -> R.string.undo to R.drawable.ic_baseline_undo_24
            Type.Redo -> R.string.redo to R.drawable.ic_baseline_redo_24
            Type.SavedContent -> R.string.saved_content to R.drawable.ic_saved_content
            Type.Clipboard -> R.string.clipboard to R.drawable.ic_clipboard
            Type.TextEditing -> R.string.text_editing to R.drawable.ic_cursor_move
            Type.ThemeList -> R.string.theme to R.drawable.ic_baseline_palette_24
            Type.InputMethod -> R.string.input_method_options to R.drawable.ic_baseline_language_24
            Type.ReloadConfig -> R.string.reload_config to R.drawable.ic_baseline_sync_24
            Type.Keyboard -> R.string.virtual_keyboard to R.drawable.ic_baseline_keyboard_24
            Type.KeyMacros -> R.string.key_macros to R.drawable.ic_baseline_code_24
            Type.ToolbarLayout -> R.string.toolbar_layout to R.drawable.ic_baseline_settings_24
        }
        return StatusAreaEntry.Android(context.getString(label),icon,type)
    }
    fun perform(type: Type, context: Context, service: FcitxInputMethodService, wm: InputWindowManager, fcitx: FcitxConnection) {
        service.closeContentSearch()
        when(type) {
            Type.Undo -> service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl=true)
            Type.Redo -> service.sendCombinationKeyEvents(KeyEvent.KEYCODE_Z, ctrl=true,shift=true)
            Type.SavedContent -> wm.attachWindow(SavedContentWindow())
            Type.Clipboard -> wm.attachWindow(ClipboardWindow())
            Type.TextEditing -> wm.attachWindow(TextEditingWindow())
            Type.Keyboard -> AppUtil.launchMainToKeyboard(context)
            Type.ThemeList -> AppUtil.launchMainToThemeList(context)
            Type.InputMethod -> fcitx.runImmediately { inputMethodEntryCached }.let { AppUtil.launchMainToInputMethodConfig(context,it.uniqueName,it.displayName) }
            Type.ReloadConfig -> fcitx.launchOnReady { it.reloadConfig() }
            Type.KeyMacros,Type.ToolbarLayout -> context.startActivity(Intent(context,if(type==Type.KeyMacros) KeyMacroActivity::class.java else ToolbarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
