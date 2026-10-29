/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.shields

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import mozilla.components.browser.state.selector.selectedTab
import org.mozilla.fenix.ext.components

/** Settings → Ad & pop-up blocking: the two shields, the allowed-sites list and a counter. */
class ShieldsSettingsActivity : Activity() {
    private lateinit var sites: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Ad & pop-up blocking"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(note("Aion's shields, like Brave. They work on every site in Aion."))

        root.addView(switchRow("Block ads and trackers", "Built in, like Brave Shields: ads (including YouTube), trackers, malware. Filters update by themselves.", Shields.blockAds(this)) {
            Shields.setBlockAds(this, components.core.engine, it)
        })
        root.addView(TextView(this).apply {
            text = "Ad blocking level"
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        })
        val group = android.widget.RadioGroup(this)
        val currentLevel = AdBlocker.level(this)
        AdBlocker.Level.entries.forEach { level ->
            group.addView(android.widget.RadioButton(this).apply {
                id = View.generateViewId()
                text = "${level.label}\n${level.summary}"
                isChecked = level == currentLevel
                setPadding(dp(8), dp(8), 0, dp(8))
                setOnClickListener {
                    AdBlocker.setLevel(this@ShieldsSettingsActivity, level)
                    android.widget.Toast.makeText(this@ShieldsSettingsActivity, "${level.label} · filters updating", android.widget.Toast.LENGTH_SHORT).show()
                }
            })
        }
        root.addView(group)
        root.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Update filter lists now"
            isAllCaps = false
            setOnClickListener {
                AdBlocker.updateFilters()
                android.widget.Toast.makeText(this@ShieldsSettingsActivity, "Updating filter lists…", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(switchRow(
            "Block pop-ups",
            "Blocks windows a page opens without your tap. ${Shields.popupsBlocked(this)} blocked so far.",
            Shields.blockPopups(this),
        ) { Shields.setBlockPopups(this, it) })

        root.addView(TextView(this).apply {
            text = "Sites allowed to open pop-ups"
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(4))
        })
        val current = Shields.hostOf(components.core.store.state.selectedTab?.content?.url)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "example.com"
            setSingleLine(true)
            if (current != null) setText(current)
        }
        row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "Allow"
            setOnClickListener {
                val host = Shields.hostOf("https://" + input.text.toString().trim().removePrefix("https://").removePrefix("http://"))
                if (!host.isNullOrBlank()) {
                    Shields.setAllowed(this@ShieldsSettingsActivity, host, true)
                    input.setText("")
                    renderSites()
                }
            }
        })
        root.addView(row)
        sites = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sites)
        renderSites()

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun renderSites() {
        sites.removeAllViews()
        val list = Shields.allowedSites(this).sorted()
        if (list.isEmpty()) {
            sites.addView(note("None. Every site's pop-ups are blocked."))
            return
        }
        list.forEach { host ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = host
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "Remove"
                setOnClickListener {
                    AlertDialog.Builder(this@ShieldsSettingsActivity)
                        .setMessage("Block pop-ups on $host again?")
                        .setPositiveButton("Block") { _, _ ->
                            Shields.setAllowed(this@ShieldsSettingsActivity, host, false)
                            renderSites()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            })
            sites.addView(row)
        }
    }

    private fun switchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        })
        texts.addView(note(summary))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }
        row.addView(toggle)
        row.setOnClickListener { toggle.toggle() }
        return row
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        alpha = 0.7f
    }

    private fun dp(value: Int) = Math.round(value * resources.displayMetrics.density)
}
