/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.shortcuts

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Settings > Keyboard shortcuts. Tap an action, press the new key combination, done.
 * Long-press an action to add a second combination or clear it.
 */
class KeyboardShortcutsActivity : AppCompatActivity() {
    private lateinit var store: KeyboardShortcutStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Keyboard shortcuts"
        store = KeyboardShortcutStore(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        column.addView(SwitchCompat(this).apply {
            text = "Enable keyboard shortcuts"
            textSize = 16f
            isChecked = store.enabled()
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> store.setEnabled(checked) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        column.addView(TextView(this).apply {
            text = "Tap an action, then press the keys you want. Long-press for more options. " +
                "Ctrl+1–8 jumps to a tab and Ctrl+9 to the last tab. Press Meta+/ anywhere in Aion to see the list."
            alpha = 0.7f
            setPadding(0, 0, 0, dp(12))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(list)
        column.addView(Button(this).apply {
            text = "Reset all to defaults"
            setOnClickListener {
                AlertDialog.Builder(this@KeyboardShortcutsActivity)
                    .setMessage("Reset every shortcut to its default?")
                    .setPositiveButton("Reset") { _, _ ->
                        store.resetAll()
                        render()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
        setContentView(ScrollView(this).apply { addView(column) })
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun render() {
        list.removeAllViews()
        ShortcutAction.entries.forEach { action ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener { record(action, replace = true) }
                setOnLongClickListener {
                    showOptions(action)
                    true
                }
            }
            row.addView(TextView(this).apply {
                text = action.label
                textSize = 16f
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val combos = store.combos(action)
            row.addView(TextView(this).apply {
                text = if (combos.isEmpty()) "Off" else combos.joinToString("   ") { it.label() }
                textSize = 14f
                alpha = if (combos.isEmpty()) 0.5f else 0.9f
                typeface = android.graphics.Typeface.MONOSPACE
            })
            list.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            list.addView(View(this).apply { setBackgroundColor(0x22888888) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
        }
    }

    private fun showOptions(action: ShortcutAction) {
        val options = arrayOf("Change", "Add another combination", "Turn off", "Restore default")
        AlertDialog.Builder(this)
            .setTitle(action.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> record(action, replace = true)
                    1 -> record(action, replace = false)
                    2 -> {
                        store.setCombos(action, emptyList())
                        render()
                    }
                    3 -> {
                        store.setCombos(action, action.defaults.toList())
                        render()
                    }
                }
            }
            .show()
    }

    /** Opens a dialog that captures the next key combination. */
    private fun record(action: ShortcutAction, replace: Boolean) {
        val prompt = TextView(this).apply {
            text = "Press the new keys for \"${action.label}\"…\n\nUse Ctrl or Alt with a key, or a function key (F1–F12)."
            setPadding(dp(24), dp(16), dp(24), dp(8))
            textSize = 15f
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("New shortcut")
            .setView(prompt)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener KeyCombo.isModifier(keyCode)
            if (KeyCombo.isModifier(keyCode)) return@setOnKeyListener true
            if (keyCode == KeyEvent.KEYCODE_BACK) return@setOnKeyListener false
            val combo = KeyCombo.from(event)
            val isFunctionKey = keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12
            if (!combo.ctrl && !combo.alt && !isFunctionKey) {
                prompt.text = "${combo.label()} alone would block typing. Hold Ctrl or Alt too, or use F1–F12."
                return@setOnKeyListener true
            }
            if (combo.ctrl && !combo.shift && !combo.alt && keyCode in ShortcutAction.TAB_NUMBER_KEYS) {
                prompt.text = "${combo.label()} is reserved for switching tabs. Try another combination."
                return@setOnKeyListener true
            }
            val owner = store.actionFor(combo)
            if (replace) {
                store.assign(action, combo)
            } else {
                owner?.takeIf { it != action }?.let { store.setCombos(it, store.combos(it) - combo) }
                store.setCombos(action, store.combos(action) + combo)
            }
            dialog.dismiss()
            render()
            if (owner != null && owner != action) {
                AlertDialog.Builder(this)
                    .setMessage("${combo.label()} was used by \"${owner.label}\". It now runs \"${action.label}\".")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            true
        }
        dialog.show()
    }
}
