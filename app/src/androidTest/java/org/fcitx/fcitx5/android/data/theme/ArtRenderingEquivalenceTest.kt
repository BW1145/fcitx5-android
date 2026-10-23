/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ArtRenderingEquivalenceTest {
    @Test fun cachedGeometryMatchesFrozenRendererAcrossBoundsAndStates() {
        val bounds = listOf(Rect(0,0,0,0), Rect(0,0,220,160), Rect(7,9,207,139),
            Rect(5,3,91,63), Rect(0,0,220,160), Rect(0,0,0,0))
        for ((index, style) in ArtStyle.entries.withIndex()) {
            val theme = ArtThemes.all[index]
            for (surface in ArtDrawable.Surface.entries) for (pressed in listOf(false,true)) {
                for (density in listOf(1f,2.75f)) for (inset in listOf(0f,3f)) {
                    val actual = ArtDrawable(theme,style,surface,theme.keyBackgroundColor,density,inset,inset+1f,pressed)
                    val reference = ReferenceArtDrawable(theme,style,
                        ReferenceArtDrawable.Surface.valueOf(surface.name),theme.keyBackgroundColor,density,inset,inset+1f,pressed)
                    assertArrayEquals("Initial bounds $style/$surface",pixels(reference),pixels(actual))
                    for (box in bounds) {
                        actual.bounds = box; reference.bounds = box
                        for (filtered in listOf(false,true)) {
                            val filter = if(filtered) PorterDuffColorFilter(Color.CYAN,PorterDuff.Mode.SRC_ATOP) else null
                            actual.colorFilter=filter; reference.colorFilter=filter
                            actual.alpha=if(filtered) 137 else 255
                            reference.alpha=if(filtered) 137 else 255
                            repeat(2) {
                                assertArrayEquals("$style/$surface pressed=$pressed density=$density inset=$inset bounds=$box filter=$filtered repeat=$it",
                                    pixels(reference),pixels(actual))
                            }
                        }
                    }
                }
            }
        }
    }


    /** Same-process ABBA comparison: software Canvas CPU drawing, not touchscreen latency. */
    @Test fun compareStableBoundsSoftwareDrawCost() {
        val bitmap=Bitmap.createBitmap(360,240,Bitmap.Config.ARGB_8888)
        try {
            val canvas=Canvas(bitmap)
            for ((index,style) in ArtStyle.entries.withIndex()) {
                val theme=ArtThemes.all[index]
                for(surface in ArtDrawable.Surface.entries) {
                    val actual=ArtDrawable(theme,style,surface,theme.keyBackgroundColor,2f)
                    val reference=ReferenceArtDrawable(theme,style,
                        ReferenceArtDrawable.Surface.valueOf(surface.name),theme.keyBackgroundColor,2f)
                    actual.setBounds(0,0,360,240);reference.setBounds(0,0,360,240)
                    repeat(100) { reference.draw(canvas);actual.draw(canvas) }
                    val oldTimes=mutableListOf<Long>()
                    val newTimes=mutableListOf<Long>()
                    fun sample(drawable:Drawable,destination:MutableList<Long>) {
                        val start=System.nanoTime()
                        repeat(100) { drawable.draw(canvas) }
                        destination += (System.nanoTime()-start)/100
                    }
                    repeat(5) {
                        sample(reference,oldTimes);sample(actual,newTimes)
                        sample(actual,newTimes);sample(reference,oldTimes)
                    }
                    android.util.Log.i("ArtRenderBenchmark",
                        "$style/$surface softwareDrawNs oldMedian=${oldTimes.sorted()[oldTimes.size/2]} " +
                            "newMedian=${newTimes.sorted()[newTimes.size/2]} old=$oldTimes new=$newTimes")
                }
            }
        } finally { bitmap.recycle() }
    }

    private fun pixels(drawable: Drawable): IntArray {
        val bitmap=Bitmap.createBitmap(240,180,Bitmap.Config.ARGB_8888)
        try {
            drawable.draw(Canvas(bitmap))
            return IntArray(240*180).also { bitmap.getPixels(it,0,240,0,0,240,180) }
        } finally { bitmap.recycle() }
    }
}
