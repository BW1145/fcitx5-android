/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ArtThemes
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class KeyRenderingEquivalenceTest {
    @Test fun fastPathKeepsActualKeyPixelsAcrossSizesAndStates() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context=instrumentation.targetContext
            val preference=AppPrefs.getInstance().keyboard.keyContentSize
            val previous=preference.getValue()
            try {
                for(theme in listOf(ThemePreset.PixelLight)+ArtThemes.all) {
                    for(id in listOf(-1,R.id.button_return)) {
                        val def=KeyDef.Appearance.Text("W",23f,viewId=id)
                        val actual=TextKeyView(context,theme,def)
                        val reference=ReferenceTextKeyView(context,theme,def)
                        for(size in listOf(16,23,34)) {
                            preference.setValue(size)
                            for(dimensions in listOf(100 to 140,180 to 90)) {
                                for(view in listOf(actual,reference)) {
                                    view.measure(View.MeasureSpec.makeMeasureSpec(dimensions.first,View.MeasureSpec.EXACTLY),
                                        View.MeasureSpec.makeMeasureSpec(dimensions.second,View.MeasureSpec.EXACTLY))
                                    view.layout(0,0,dimensions.first,dimensions.second)
                                }
                                for(pressed in listOf(false,true)) for(visible in listOf(true,false)) {
                                    actual.mainText.visibility=if(visible) View.VISIBLE else View.INVISIBLE
                                    reference.mainText.visibility=actual.mainText.visibility
                                    actual.isPressed=pressed;reference.isPressed=pressed
                                    actual.jumpDrawablesToCurrentState();reference.jumpDrawablesToCurrentState()
                                    assertArrayEquals("${theme.name}/$id size=$size dimensions=$dimensions pressed=$pressed visible=$visible",
                                        pixels(reference),pixels(actual))
                                }
                            }
                        }
                    }
                }
            } finally { preference.setValue(previous) }
        }
    }
    private fun pixels(view:View):IntArray {
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            return IntArray(view.width*view.height).also { bitmap.getPixels(it,0,view.width,0,0,view.width,view.height) }
        } finally { bitmap.recycle() }
    }
}
