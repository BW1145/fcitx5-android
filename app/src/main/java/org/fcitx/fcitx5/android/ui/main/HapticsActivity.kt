/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.utils.vibrator

class HapticsActivity : ExperienceActivity() {
    private val prefs = AppPrefs.getInstance().keyboard
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); title="按键触感"
        InputFeedbacks.syncSystemPrefs()
        val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(column) })
        fun label(text:String) { column.addView(TextView(this).apply { this.text=text;textSize=16f;setPadding(0,16,0,8) }) }
        fun render() {
            column.removeAllViews()
            label("选择预设后，下面的参数立即生效。点击试打按键体验短按与长按。")
            listOf("跟随系统" to intArrayOf(0,0,0,0),"轻柔" to intArrayOf(8,28,55,100),
                "清脆" to intArrayOf(12,35,110,160),"明显" to intArrayOf(22,48,185,230)).forEach { (name,v) ->
                column.addView(Button(this).apply { text=name;setOnClickListener {
                    prefs.hapticOnKeyPress.setValue(if(v[0]==0) InputFeedbacks.InputFeedbackMode.FollowingSystem else InputFeedbacks.InputFeedbackMode.Enabled)
                    prefs.buttonPressVibrationMilliseconds.setValue(v[0]);prefs.buttonLongPressVibrationMilliseconds.setValue(v[1])
                    prefs.buttonPressVibrationAmplitude.setValue(v[2]);prefs.buttonLongPressVibrationAmplitude.setValue(v[3]);render()
                } })
            }
            fun slider(name:String, pref:org.fcitx.fcitx5.android.data.prefs.ManagedPreference.PInt,max:Int,unit:String) {
                val text=TextView(this);column.addView(text)
                fun update(v:Int) { text.text="$name：${if(v==0) "系统默认" else "$v $unit"}" }
                update(pref.getValue())
                column.addView(SeekBar(this).apply {
                    this.max=max;progress=pref.getValue()
                    setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(bar:SeekBar?,v:Int,user:Boolean) { if(user) { pref.setValue(v);update(v) } }
                        override fun onStartTrackingTouch(bar:SeekBar?) {}
                        override fun onStopTrackingTouch(bar:SeekBar?) {}
                    })
                })
            }
            slider("短按时长",prefs.buttonPressVibrationMilliseconds,100,"ms")
            slider("长按时长",prefs.buttonLongPressVibrationMilliseconds,100,"ms")
            if(Build.VERSION.SDK_INT>=26 && vibrator.hasAmplitudeControl()) {
                label("强度在自定义时长大于 0 时生效。")
                slider("短按强度",prefs.buttonPressVibrationAmplitude,255,"")
                slider("长按强度",prefs.buttonLongPressVibrationAmplitude,255,"")
            } else label("此设备由系统控制震动强度，可调整时长。")
            listOf("启用触感" to prefs.hapticOnRepeat,"松手反馈" to prefs.hapticOnKeyUp).forEachIndexed { i,(name,pref) ->
                if(i==0) column.addView(Switch(this).apply { text="连续按键反馈";isChecked=pref.getValue();setOnCheckedChangeListener { _,v->pref.setValue(v) } })
                else column.addView(Switch(this).apply { text=name;isChecked=pref.getValue();setOnCheckedChangeListener { _,v->pref.setValue(v) } })
            }
            column.addView(Button(this).apply { text="试打 · 点击 / 长按";setOnClickListener { InputFeedbacks.hapticFeedback(this) }
                setOnLongClickListener { InputFeedbacks.hapticFeedback(this,longPress=true);true } })
            column.addView(Button(this).apply { text="关闭触感";setOnClickListener { prefs.hapticOnKeyPress.setValue(InputFeedbacks.InputFeedbackMode.Disabled) } })
        }
        render()
    }
}
