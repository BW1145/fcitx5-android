/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.bar.ui.idle

import android.content.Context
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.view

class ButtonsBarUi(override val ctx: Context, private val theme: Theme) : Ui {

    override val root = view(::FlexboxLayout) {
        alignItems = AlignItems.CENTER
        justifyContent = JustifyContent.SPACE_AROUND
    }

    var onAction: ((org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type) -> Unit)? = null
    val moreButton = ToolButton(ctx,R.drawable.ic_baseline_more_horiz_24,theme).apply { contentDescription=ctx.getString(R.string.status_area) }
    fun refresh() {
        root.removeAllViews()
        val size=ctx.dp(40)
        org.fcitx.fcitx5.android.data.ToolbarLayout.main().forEach { type ->
            val entry=org.fcitx.fcitx5.android.input.status.ToolbarActions.entry(ctx,type)
            root.addView(ToolButton(ctx,entry.icon,theme).apply { contentDescription=entry.label;setOnClickListener { onAction?.invoke(type) } }, FlexboxLayout.LayoutParams(size,size).apply { flexShrink=1f })
        }
        root.addView(moreButton,FlexboxLayout.LayoutParams(size,size))
    }
    init { refresh() }
}
