/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.min
import kotlin.random.Random

enum class ArtStyle(val title: String, val radius: Float, val font: String, val weight: Int) {
    Nouveau("新艺术 · 鸢尾珐琅", 9f, "serif", Typeface.NORMAL),
    Constructivism("构成主义 · 红与黑", 1f, "sans-serif-condensed", Typeface.BOLD),
    Riso("孔版印刷 · 红蓝套色", 5f, "sans-serif-rounded", Typeface.NORMAL),
    Aero("Frutiger Aero · 水光", 9f, "sans-serif", Typeface.NORMAL);

    companion object { fun of(theme: Theme) = entries.find { it.title == theme.name } }
}

object ArtThemes {
    private fun palette(style: ArtStyle, dark: Boolean, base: Long, bar: Long, key: Long,
                        ink: Long, muted: Long, alt: Long, accent: Long, accentInk: Long) =
        ThemePreset.PixelLight.copy(
            name = style.title, isDark = dark, backgroundColor = base.toInt(),
            keyboardColor = base.toInt(), barColor = bar.toInt(),
            keyBackgroundColor = key.toInt(), keyTextColor = ink.toInt(),
            candidateTextColor = ink.toInt(), candidateLabelColor = muted.toInt(),
            candidateCommentColor = muted.toInt(), altKeyBackgroundColor = alt.toInt(),
            altKeyTextColor = ink.toInt(), accentKeyBackgroundColor = accent.toInt(),
            accentKeyTextColor = accentInk.toInt(), spaceBarColor = key.toInt(),
            keyShadowColor = muted.toInt(), dividerColor = muted.toInt(),
            keyPressHighlightColor = if (dark) 0x35ffffff else 0x25000000,
            popupBackgroundColor = bar.toInt(), popupTextColor = ink.toInt(),
            clipboardEntryColor = key.toInt(), genericActiveBackgroundColor = accent.toInt(),
            genericActiveForegroundColor = accentInk.toInt())

    val all = listOf(
        palette(ArtStyle.Nouveau, true, 0xff102f2b, 0xff193d35, 0xff214b40,
            0xfff2e8cb, 0xffb7954f, 0xff34584a, 0xffa39abb, 0xff172c26),
        palette(ArtStyle.Constructivism, false, 0xfff2ead9, 0xfff2ead9, 0xfff7f1e5,
            0xff171714, 0xff675f51, 0xffe5d6bb, 0xffe73c28, 0xfffdf6e6),
        palette(ArtStyle.Riso, false, 0xfff5ebcf, 0xfff5ebcf, 0xfffaf1d8,
            0xff243258, 0xff606c87, 0xffffc0a4, 0xff2848b3, 0xfffaf1d8),
        palette(ArtStyle.Aero, false, 0xff6bbfed, 0xffe5f8ff, 0xffe0f5ff,
            0xff123e65, 0xff376b8b, 0xffa6ddf5, 0xff6dbd3f, 0xff183e20))
}

/** Small vector materials. Paths and shaders are rebuilt only when bounds change. */
class ArtDrawable(
    private val theme: Theme,
    private val style: ArtStyle,
    private val surface: Surface,
    private val fill: Int,
    private val density: Float = 1f,
    private val hInset: Float = 0f,
    private val vInset: Float = 0f,
    private val pressed: Boolean = false
) : Drawable() {
    enum class Surface { Backdrop, Panel, Key, Space }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val inner = RectF()
    private val shapePath = Path()
    private val vinePaths = if (style == ArtStyle.Nouveau &&
        (surface == Surface.Space || surface == Surface.Backdrop)) Array(7) { Path() } else emptyArray()
    private var shader: Shader? = null
    private var grain = FloatArray(0)
    private val radius get() = style.radius * density
    private val isKey get() = surface == Surface.Key || surface == Surface.Space

    // Drawable can be drawn before its first non-empty bounds assignment.
    init { cacheGeometry() }

    override fun onBoundsChange(bounds: Rect) {
        box.set(bounds.left + hInset, bounds.top + vInset,
            bounds.right - hInset, bounds.bottom - vInset)
        inner.set(box); inner.inset(2.5f * density, 2.5f * density)
        shader = when (style) {
            ArtStyle.Aero -> LinearGradient(0f, box.top, 0f, box.bottom,
                intArrayOf(Color.WHITE, fill, blend(fill, 0xff2076a6.toInt(), .22f)),
                floatArrayOf(0f, .38f, 1f), Shader.TileMode.CLAMP)
            ArtStyle.Nouveau -> LinearGradient(0f, box.top, 0f, box.bottom,
                blend(fill, Color.WHITE, .10f), fill, Shader.TileMode.CLAMP)
            else -> null
        }
        val random = Random(37)
        grain = if (style == ArtStyle.Riso) FloatArray((box.width()*box.height()/420).toInt().coerceIn(0, 1400)*2) {
            if (it%2 == 0) box.left + random.nextFloat()*box.width()
            else box.top + random.nextFloat()*box.height()
        } else FloatArray(0)
        cacheGeometry()
    }

    private fun cacheGeometry() {
        shapePath.reset()
        when (style) {
            ArtStyle.Aero -> shapePath.addRoundRect(inner, radius*.8f, radius*.8f, Path.Direction.CW)
            ArtStyle.Constructivism -> if (surface != Surface.Key) {
                shapePath.moveTo(box.right, box.top)
                shapePath.lineTo(box.right, box.bottom)
                shapePath.lineTo(box.right - box.width()*.22f, box.bottom)
                shapePath.lineTo(box.right - box.width()*.10f, box.top); shapePath.close()
            }
            else -> Unit
        }
        if (vinePaths.isNotEmpty()) {
            val w = min(box.width()*.16f, 32*density)
            val x = box.left+6*density
            vinePaths[0].apply {
                reset(); moveTo(x, box.bottom-5*density)
                cubicTo(x+w*.9f, box.bottom-box.height()*.28f, x-w*.2f, box.top+8*density,
                    x+w*.6f, box.top+10*density)
            }
            for (i in 1..3) {
                val y = box.bottom-box.height()*i/4
                val path = vinePaths[i]
                path.reset(); path.moveTo(x+w*.35f,y)
                path.quadTo(x+w*.95f,y-3*density,x+w*.85f,y-11*density)
                path.quadTo(x+w*.25f,y-8*density,x+w*.35f,y)
            }
            val flowerX=x+w*.6f;val flowerY=box.top+10*density
            for (i in -1..1) {
                val path = vinePaths[i+5]
                path.reset();path.moveTo(flowerX,flowerY+4*density)
                path.cubicTo(flowerX+(i*4-3)*density,flowerY,
                    flowerX+(i*5)*density,flowerY-7*density,
                    flowerX+(i*4+3)*density,flowerY)
                path.quadTo(flowerX+3*density,flowerY+3*density,flowerX,flowerY+4*density)
            }
        }
    }

    private fun color(c: Int, stroke: Float = 0f) {
        paint.shader = null; paint.color = c
        paint.style = if (stroke > 0) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = stroke * density
    }
    override fun draw(canvas: Canvas) {
        color(if (pressed && style == ArtStyle.Constructivism)
            blend(fill,0xffe73c28.toInt(),.27f) else fill)
        paint.shader = if (pressed && style == ArtStyle.Constructivism) null else shader
        canvas.drawRoundRect(box, radius, radius, paint)
        when (style) {
            ArtStyle.Nouveau -> {
                color(if (pressed) 0xffe8cd88.toInt() else 0xffb7954f.toInt(), if (isKey) .65f else 1f)
                canvas.drawRoundRect(inner, radius*.8f, radius*.8f, paint)
                if (surface == Surface.Space || surface == Surface.Backdrop) vines(canvas)
                else {
                    color(0x447ee0aa, .5f)
                    canvas.drawLine(inner.left+radius, inner.top+2*density,
                        inner.right-radius, inner.top+2*density, paint)
                }
            }
            ArtStyle.Constructivism -> {
                color(0xff171714.toInt(), if (isKey) 1f else 0f)
                if (isKey) canvas.drawRect(inner, paint)
                if (surface != Surface.Key) {
                    color(0xffe73c28.toInt())
                    canvas.drawPath(shapePath, paint)
                    color(0xff171714.toInt(), 1.5f)
                    canvas.drawLine(box.left, box.bottom-2*density, box.right, box.bottom-2*density, paint)
                }
            }
            ArtStyle.Riso -> {
                color(0x182848b3)
                paint.strokeWidth = density*.65f
                canvas.drawPoints(grain, paint)
                if (isKey) {
                    color(0xffef624f.toInt(), 1f)
                    canvas.drawRoundRect(inner, radius, radius, paint)
                    canvas.save(); canvas.translate(if(pressed) density else -density, density)
                    color(0x992848b3.toInt(), 1f)
                    canvas.drawRoundRect(inner, radius, radius, paint); canvas.restore()
                } else {
                    color(0x55ef624f, 2f)
                    canvas.drawLine(box.left+8*density, box.bottom-3*density,
                        box.right-8*density, box.bottom-3*density, paint)
                }
            }
            ArtStyle.Aero -> {
                color(0xccffffff.toInt(), 1f)
                canvas.drawRoundRect(inner, radius*.8f, radius*.8f, paint)
                val checkpoint = canvas.save()
                canvas.clipPath(shapePath)
                color(if (isKey) 0x40ffffff else 0x26ffffff)
                canvas.drawOval(inner.left-inner.width()*.15f, inner.top-inner.height()*.7f,
                    inner.right+inner.width()*.15f, inner.top+inner.height()*(if(pressed) .32f else .5f), paint)
                if (surface == Surface.Backdrop) {
                    color(0x77e5f8ff, 1f)
                    for (i in 1..4) canvas.drawCircle(inner.right-i*inner.width()/6,
                        inner.bottom-i*inner.height()/6, (5+i*3)*density, paint)
                }
                canvas.restoreToCount(checkpoint)
            }
        }
        if (pressed) {
            color(when (style) {
                ArtStyle.Nouveau -> 0x18000000
                ArtStyle.Constructivism -> 0x0c000000
                ArtStyle.Riso -> 0x142848b3
                ArtStyle.Aero -> 0x182076a6
            })
            canvas.drawRoundRect(box, radius, radius, paint)
        }
    }

    private fun vines(canvas: Canvas) {
        val checkpoint = canvas.save()
        for (side in 0..1) {
            canvas.save()
            if (side == 1) canvas.scale(-1f, 1f, box.centerX(), box.centerY())
            val w = min(box.width()*.16f, 32*density)
            val x = box.left+6*density
            color(0xffb7954f.toInt(), .85f)
            canvas.drawPath(vinePaths[0], paint)
            for (i in 1..3) {
                color(0xffb7954f.toInt(),.65f)
                canvas.drawPath(vinePaths[i],paint)
            }
            color(0xffa39abb.toInt())
            val flowerX=x+w*.6f;val flowerY=box.top+10*density
            for(i in -1..1) {
                canvas.drawPath(vinePaths[i+5],paint)
            }
            color(0xffd5bc7b.toInt(),.7f)
            canvas.drawLine(flowerX,flowerY-3*density,flowerX,flowerY+4*density,paint)
            canvas.restore()
        }
        canvas.restoreToCount(checkpoint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT

    private fun blend(a: Int,b: Int,t: Float) = Color.rgb(
        (Color.red(a)*(1-t)+Color.red(b)*t).toInt(),
        (Color.green(a)*(1-t)+Color.green(b)*t).toInt(),
        (Color.blue(a)*(1-t)+Color.blue(b)*t).toInt())
}

fun Theme.panelDrawable(density: Float = 1f): Drawable = ArtStyle.of(this)?.let {
    ArtDrawable(this, it, ArtDrawable.Surface.Panel, barColor, density)
} ?: android.graphics.drawable.ColorDrawable(barColor)
