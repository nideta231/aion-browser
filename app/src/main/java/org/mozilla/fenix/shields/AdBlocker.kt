/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.shields

import android.content.Context
import android.os.Handler
import android.os.Looper
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject

/**
 * Brave-style ad blocking built into Aion: uBlock Origin ships inside the APK (no download) and
 * Aion controls it through a small bridge script: blocking level and per-site on/off.
 *
 * Levels pick uBlock filter lists:
 * - Standard: uBlock + EasyList + EasyPrivacy + malware + Indonesian list (like Brave "Standard").
 * - Moderate (default): adds AdGuard base/mobile and pop-up/overlay/app-banner lists.
 * - Aggressive: adds cookie banners, social widgets, annoyances, experimental filters (can break sites).
 */
object AdBlocker {
    const val ID = Shields.UBLOCK_ID
    private const val URL = "resource://android/assets/extensions/ublock/"
    private const val PORT = "aionShields"
    private const val PREFS = "aion_shields"
    private const val KEY_LEVEL = "ad_level"

    enum class Level(val key: String, val label: String, val summary: String) {
        STANDARD("standard", "Standard", "Blocks ads and trackers; sites rarely break"),
        MODERATE("moderate", "Moderate", "Also mobile ads, pop-up overlays and app banners (recommended)"),
        AGGRESSIVE("aggressive", "Aggressive", "Also cookie banners, social widgets and annoyances; may break some sites"),
    }

    private val controller = BuiltInWebExtensionController(ID, URL, PORT)
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private val siteCallbacks = HashMap<String, MutableList<(Boolean) -> Unit>>()

    fun level(context: Context): Level {
        val key = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LEVEL, Level.MODERATE.key)
        return Level.entries.firstOrNull { it.key == key } ?: Level.MODERATE
    }

    fun setLevel(context: Context, level: Level) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LEVEL, level.key).apply()
        send(JSONObject().put("type", "level").put("level", level.key).put("update", true))
    }

    /** Re-downloads the filter lists now (they also update by themselves every few days). */
    fun updateFilters() = send(JSONObject().put("type", "update"))

    /** Turns ad blocking on/off for one site (uBlock's per-site switch). */
    fun setSiteEnabled(host: String, enabled: Boolean, onResult: (Boolean) -> Unit = {}) {
        siteCallbacks.getOrPut(host) { mutableListOf() }.add(onResult)
        send(JSONObject().put("type", "site").put("host", host).put("enabled", enabled))
    }

    fun isSiteEnabled(host: String, onResult: (Boolean) -> Unit) {
        siteCallbacks.getOrPut(host) { mutableListOf() }.add(onResult)
        if (!send(JSONObject().put("type", "query").put("host", host))) {
            siteCallbacks.remove(host)
            onResult(true)
        }
    }

    private fun send(message: JSONObject): Boolean = runCatching {
        controller.sendBackgroundMessage(message)
        controller.portConnected(null)
    }.getOrDefault(false)

    /**
     * Installs the bundled uBlock (replacing an older copy installed from addons.mozilla.org) and
     * applies the on/off switch and level.
     */
    fun install(context: Context, engine: Engine) {
        appContext = context.applicationContext
        controller.registerBackgroundMessageHandler(
            object : MessageHandler {
                override fun onPortConnected(port: Port) {
                    val ctx = appContext ?: return
                    port.postMessage(JSONObject().put("type", "level").put("level", level(ctx).key))
                }

                override fun onPortMessage(message: Any, port: Port) {
                    val json = message as? JSONObject ?: return
                    if (json.optString("type") == "site") {
                        val host = json.optString("host")
                        val enabled = json.optBoolean("enabled", true)
                        main.post { siteCallbacks.remove(host)?.forEach { it(enabled) } }
                    }
                }
            },
            PORT,
        )
        engine.listInstalledWebExtensions(onSuccess = { list ->
            val old = list.firstOrNull { it.id == ID && !it.isBuiltIn() }
            val start = { installBuiltIn(context, engine) }
            if (old != null) engine.uninstallWebExtension(old, onSuccess = { main.post { start() } }, onError = { _, _ -> main.post { start() } })
            else main.post { start() }
        })
    }

    private fun installBuiltIn(context: Context, engine: Engine) {
        controller.install(
            engine,
            onSuccess = { ext ->
                val on = Shields.blockAds(context)
                if (on && !ext.isEnabled()) engine.enableWebExtension(ext)
                if (!on && ext.isEnabled()) engine.disableWebExtension(ext, onSuccess = {})
            },
        )
    }

    fun setEnabled(context: Context, engine: Engine, on: Boolean) {
        engine.listInstalledWebExtensions(onSuccess = { list ->
            val ext = list.firstOrNull { it.id == ID } ?: return@listInstalledWebExtensions
            if (on && !ext.isEnabled()) engine.enableWebExtension(ext)
            if (!on && ext.isEnabled()) engine.disableWebExtension(ext, onSuccess = {})
        })
    }
}
