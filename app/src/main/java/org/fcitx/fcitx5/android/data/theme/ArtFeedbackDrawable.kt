/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Drawable
import org.fcitx.fcitx5.android.data.prefs.AppPrefs

/** Touch-driven accents; the drawable changes appearance without changing the touch target. */
class ArtFeedbackDrawable(
    private val style: ArtStyle,
    private val density: Float,
    private val hInset: Float = 0f,
    private val vInset: Float = 0f
) : Drawable() {
    enum class Action { Hold, Gesture }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val path = Path()
    private var down = false
    private var action: Action? = null
    private var strength = 0f
    private var opacity = 255
    private var fade: ValueAnimator? = null

    override fun isStateful() = true

    override fun onBoundsChange(bounds: Rect) {
        box.set(bounds.left + hInset + 2*density, bounds.top + vInset + 2*density,
            bounds.right - hInset - 2*density, bounds.bottom - vInset - 2*density)
    }

    override fun onStateChange(state: IntArray): Boolean {
        val pressed = android.R.attr.state_pressed in state
        if (pressed == down) return false
        down = pressed
        fade?.cancel()
        if (pressed) {
            action = null
            strength = 1f
        } else if (!AppPrefs.getInstance().advanced.disableAnimation.getValue()) {
            fade = ValueAnimator.ofFloat(strength, 0f).apply {
                duration = when (style) {
                    ArtStyle.Nouveau -> 150L
                    ArtStyle.Constructivism -> 60L
                    ArtStyle.Riso -> 100L
                    ArtStyle.Aero -> 190L
                }
                addUpdateListener { strength = it.animatedValue as Float; invalidateSelf() }
                start()
            }
        } else strength = 0f
        invalidateSelf()
        return true
    }

    fun signal(value: Action) {
        action = value
        strength = 1f
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (strength <= 0f || box.isEmpty) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density * if (action == null) 1f else 1.8f
        paint.alpha = (opacity * strength).toInt()
        val radius = style.radius*density
        val held = action == Action.Hold
        val gesture = action == Action.Gesture
        fun ink(color: Int) { paint.color = color; paint.alpha = (opacity*strength).toInt() }
        when (style) {
            ArtStyle.Nouveau -> {
                ink(0xffe8cd88.toInt())
                canvas.drawRoundRect(box, radius, radius, paint)
                if (held) {
                    canvas.drawArc(box.left+3*density, box.top+3*density,
                        box.right-3*density, box.bottom-3*density, 20f, 140f, false, paint)
                    canvas.drawArc(box.left+3*density, box.top+3*density,
                        box.right-3*density, box.bottom-3*density, 200f, 140f, false, paint)
                }
                if (gesture) for (x in listOf(box.left+5*density,box.right-5*density)) {
                    path.reset(); path.moveTo(x,box.centerY()-4*density)
                    path.lineTo(x+3*density,box.centerY()); path.lineTo(x,box.centerY()+4*density)
                    path.lineTo(x-3*density,box.centerY()); path.close(); canvas.drawPath(path,paint)
                }
            }
            ArtStyle.Constructivism -> {
                ink(0xffe73c28.toInt())
                paint.strokeWidth = if (held || gesture) 4*density else 2*density
                canvas.drawLine(box.left,box.bottom,box.right,box.bottom,paint)
                if (held) {
                    paint.style=Paint.Style.FILL
                    path.reset();path.moveTo(box.right,box.top);path.lineTo(box.right-9*density,box.top)
                    path.lineTo(box.right,box.top+9*density);path.close();canvas.drawPath(path,paint)
                }
                if (gesture) {
                    path.reset();path.moveTo(box.left+3*density,box.bottom-10*density)
                    path.lineTo(box.left+8*density,box.bottom-6*density)
                    path.lineTo(box.left+3*density,box.bottom-2*density);canvas.drawPath(path,paint)
                }
            }
            ArtStyle.Riso -> {
                ink(0xffef624f.toInt())
                canvas.drawLine(box.left+2*density,box.bottom-2*density,box.right,box.bottom-2*density,paint)
                ink(0xff2848b3.toInt())
                canvas.drawLine(box.left,box.bottom,box.right-2*density,box.bottom,paint)
                if (held) for (i in 0..2) canvas.drawLine(box.left+(3+i*3)*density,box.top+2*density,
                    box.left+(i*3)*density,box.top+6*density,paint)
                if (gesture) for (x in listOf(box.left+3*density,box.right-3*density)) {
                    canvas.drawLine(x-3*density,box.centerY(),x+3*density,box.centerY(),paint)
                    canvas.drawLine(x,box.centerY()-3*density,x,box.centerY()+3*density,paint)
                }
            }
            ArtStyle.Aero -> {
                ink(0xfff5ffff.toInt())
                canvas.drawRoundRect(box, radius, radius, paint)
                ink(0xff2389bd.toInt())
                canvas.drawArc(box.left,box.top,box.right,box.bottom,15f,150f,false,paint)
                if (held || gesture) {
                    ink(0xfff5ffff.toInt())
                    val x=box.right-6*density;val y=box.bottom-7*density
                    canvas.drawCircle(x,y,4*density,paint)
                    if (gesture) canvas.drawCircle(x,y,7*density,paint)
                }
            }
        }
    }

    override fun jumpToCurrentState() {
        fade?.cancel(); fade=null; strength=if(down) 1f else 0f; invalidateSelf()
    }
    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        if (!visible) { fade?.cancel(); fade=null; strength=0f; down=false; action=null }
        return super.setVisible(visible,restart)
    }
    override fun setAlpha(alpha: Int) { opacity=alpha;invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter=colorFilter;invalidateSelf() }
    @Deprecated("Drawable opacity") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
