/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.shields

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import mozilla.components.browser.state.selector.selectedTab
import org.mozilla.fenix.ext.components

/** Brave-style Shields panel for the current site: pop-ups, tracking protection, ad blocker. */
object ShieldsPanel {
    fun show(activity: Activity) {
        val tab = activity.components.core.store.state.selectedTab
        val host = Shields.hostOf(tab?.content?.url)
        if (tab == null || host == null) {
            Toast.makeText(activity, "Open a website first", Toast.LENGTH_SHORT).show()
            return
        }
        val d = activity.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun row(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
            val r = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
            }
            val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(activity).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            })
            texts.addView(TextView(activity).apply {
                text = summary
                alpha = 0.7f
            })
            r.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val sw = Switch(activity).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, v -> onChange(v) }
            }
            r.addView(sw)
            r.setOnClickListener { sw.toggle() }
            root.addView(r)
        }

        val popupsOn = Shields.blockPopups(activity)
        row(
            "Block pop-ups on $host",
            if (popupsOn) "${Shields.blockedOn(tab.content.url)} blocked on this page" else "Pop-up blocker is off for all sites (Settings)",
            popupsOn && host !in Shields.allowedSites(activity),
        ) { block -> Shields.setAllowed(activity, host, !block) }

        val trackingUseCases = activity.components.useCases.trackingProtectionUseCases
        trackingUseCases.containsException(tab.id) { isException ->
            activity.runOnUiThread {
                row("Block trackers on $host", "Firefox tracking protection", !isException) { block ->
                    if (block) trackingUseCases.removeException(tab.id) else trackingUseCases.addException(tab.id)
                }
            }
        }

        root.addView(Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Ad blocker for this site (uBlock Origin panel)"
            isAllCaps = false
            setOnClickListener { openUblockPanel(activity) }
        })
        root.addView(Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = "All Shields settings"
            isAllCaps = false
            setOnClickListener { activity.startActivity(Intent(activity, ShieldsSettingsActivity::class.java)) }
        })

        AlertDialog.Builder(activity)
            .setTitle("🛡 Shields · $host")
            .setView(root)
            .setPositiveButton("Reload page") { _, _ ->
                activity.components.useCases.sessionUseCases.reload(tab.id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** uBlock's own popup has the per-site power button; open it like its toolbar icon. */
    private fun openUblockPanel(activity: Activity) {
        val state = activity.components.core.store.state
        val ext = state.extensions[Shields.UBLOCK_ID]
        val action = state.selectedTab?.extensionState?.get(Shields.UBLOCK_ID)?.browserAction ?: ext?.browserAction
        if (ext == null || !ext.enabled || action == null) {
            Toast.makeText(activity, "uBlock Origin isn't running. Turn on ad blocking in Shields settings.", Toast.LENGTH_LONG).show()
            return
        }
        action.onClick()
    }
}
