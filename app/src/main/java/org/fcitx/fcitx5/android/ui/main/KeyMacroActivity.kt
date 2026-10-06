/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.KeyMacros

class KeyMacroActivity : AppCompatActivity() {
    private lateinit var rows: LinearLayout
    private val triggerNames get() = resources.getStringArray(R.array.macro_triggers)
    private val actionNames get() = resources.getStringArray(R.array.macro_actions)
    private fun keyName(key: String) = when(key) {
        "backspace" -> getString(R.string.macro_backspace); "space" -> getString(R.string.macro_space)
        "return" -> getString(R.string.macro_return); "caps" -> getString(R.string.macro_caps)
        "language" -> getString(R.string.macro_language); "symbols" -> getString(R.string.macro_symbols); else -> key.uppercase()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.key_macros)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        setContentView(ScrollView(this).apply { addView(rows) }); render()
    }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
    private fun render() {
        rows.removeAllViews()
        rows.addView(button(getString(R.string.macro_add)) { edit() })
        rows.addView(TextView(this).apply { setText(R.string.macro_help) })
        KeyMacros.load().forEach { binding ->
            rows.addView(button("${keyName(binding.key)} · ${triggerNames[KeyMacros.triggers.indexOf(binding.trigger)]} (${binding.steps.size})") { edit(binding) })
        }
        rows.addView(button(getString(R.string.macro_restore)) {
            AlertDialog.Builder(this).setMessage(R.string.macro_restore_confirm).setPositiveButton(android.R.string.ok) { _,_ -> KeyMacros.restore(); render() }.setNegativeButton(android.R.string.cancel,null).show()
        })
    }
    private fun spinner(items: List<String>, selected: Int = 0) = Spinner(this).apply {
        adapter = ArrayAdapter(this@KeyMacroActivity, android.R.layout.simple_spinner_dropdown_item, items); setSelection(selected.coerceAtLeast(0))
    }
    private fun edit(original: KeyMacros.Binding? = null) {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,8,24,8) }
        val key = spinner(KeyMacros.keys.map(::keyName), original?.let { KeyMacros.keys.indexOf(it.key) } ?: 0)
        val trigger = spinner(triggerNames.toList(), original?.let { KeyMacros.triggers.indexOf(it.trigger) } ?: 0)
        panel.addView(key); panel.addView(trigger)
        val steps = original?.steps?.toMutableList() ?: mutableListOf()
        val stepRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; panel.addView(stepRows)
        fun renderSteps() {
            stepRows.removeAllViews()
            steps.forEachIndexed { index, step ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row.addView(TextView(this).apply { text = "${index+1}. ${actionNames[KeyMacros.actions.indexOf(step.action)]} ${step.text}" }, LinearLayout.LayoutParams(0,-2,1f))
                row.addView(button("↑") { if(index>0) { steps.removeAt(index);steps.add(index-1,step);renderSteps() } })
                row.addView(button("↓") { if(index<steps.lastIndex) { steps.removeAt(index);steps.add(index+1,step);renderSteps() } })
                row.addView(button("×") { steps.removeAt(index);renderSteps() });stepRows.addView(row)
            }
        }
        panel.addView(button(getString(R.string.macro_add_step)) {
            val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL;setPadding(24,8,24,8) }
            val type = spinner(actionNames.toList())
            val text = EditText(this).apply { setHint(R.string.macro_step_text) }
            val ctrl = CheckBox(this).apply { this.text="Ctrl" }; val alt=CheckBox(this).apply { this.text="Alt" }; val shift=CheckBox(this).apply { this.text="Shift" }
            form.addView(type);form.addView(text);form.addView(ctrl);form.addView(alt);form.addView(shift)
            val dialog = AlertDialog.Builder(this).setTitle(R.string.macro_add_step).setView(form).setPositiveButton(android.R.string.ok,null).setNegativeButton(android.R.string.cancel,null).create()
            dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val action=KeyMacros.actions[type.selectedItemPosition]; val value=text.text.toString()
                if(action=="key" && android.view.KeyEvent.keyCodeFromString("KEYCODE_"+value.uppercase().removePrefix("KEYCODE_"))==android.view.KeyEvent.KEYCODE_UNKNOWN) { text.error=getString(R.string.macro_invalid_key);return@setOnClickListener }
                steps.add(KeyMacros.Step(action,value,ctrl.isChecked,alt.isChecked,shift.isChecked)); renderSteps();dialog.dismiss()
            } };dialog.show()
        })
        renderSteps()
        val dialog = AlertDialog.Builder(this).setTitle(R.string.key_macros).setView(ScrollView(this).apply { addView(panel) }).setPositiveButton(R.string.save,null).setNegativeButton(android.R.string.cancel,null).apply {
            if(original!=null) setNeutralButton(R.string.delete) { _,_ -> KeyMacros.remove(original.key,original.trigger);render() }
        }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if(steps.isEmpty()) return@setOnClickListener
            original?.let { KeyMacros.remove(it.key,it.trigger) }
            KeyMacros.set(KeyMacros.Binding(KeyMacros.keys[key.selectedItemPosition],KeyMacros.triggers[trigger.selectedItemPosition],steps.toList()));render();dialog.dismiss()
        } };dialog.show()
    }
}
