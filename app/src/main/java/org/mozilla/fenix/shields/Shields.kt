/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.shields

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import mozilla.components.browser.state.action.BrowserAction
import mozilla.components.browser.state.action.ContentAction
import mozilla.components.browser.state.action.WebExtensionAction
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.extension.WebExtensionPromptRequest
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.prompt.PromptRequest
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.concept.engine.webextension.PermissionPromptResponse
import mozilla.components.lib.state.Middleware
import mozilla.components.lib.state.Store

/**
 * Aion "Shields", like Brave's: a popup blocker with a per-site allow list and a built-in ad blocker
 * (uBlock Origin, installed and enabled automatically on first run).
 */
object Shields {
    const val UBLOCK_ID = "uBlock0@raymondhill.net"
    private const val UBLOCK_URL = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    private const val PREFS = "aion_shields"
    private const val KEY_POPUPS = "block_popups"
    private const val KEY_ADS = "block_ads"
    private const val KEY_ALLOWED = "popup_allowed_sites"
    private const val KEY_BLOCKED_COUNT = "popups_blocked"

    /** True while Aion itself is installing uBlock, so its permission prompt is approved silently. */
    @Volatile var installingAdBlocker = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun blockPopups(context: Context) = prefs(context).getBoolean(KEY_POPUPS, true)
    fun setBlockPopups(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_POPUPS, value).apply()
        org.mozilla.fenix.downloads.MediaGrabber.pushShields(context)
    }

    /** Pop-ups blocked on each page since it loaded, for the Shields panel. */
    private val blockedOnPage = HashMap<String, Int>()
    private var lastToastAt = 0L

    fun blockedOn(pageUrl: String?) = pageUrl?.let { blockedOnPage[it] } ?: 0

    fun onPopupBlocked(context: Context, pageUrl: String) {
        countBlocked(context)
        blockedOnPage[pageUrl] = (blockedOnPage[pageUrl] ?: 0) + 1
        val now = System.currentTimeMillis()
        if (now - lastToastAt > 4000) {
            lastToastAt = now
            Toast.makeText(context, "Pop-up blocked · Shields: menu → Shields for this site", Toast.LENGTH_SHORT).show()
        }
    }

    fun blockAds(context: Context) = prefs(context).getBoolean(KEY_ADS, true)

    fun popupsBlocked(context: Context) = prefs(context).getInt(KEY_BLOCKED_COUNT, 0)

    fun allowedSites(context: Context): Set<String> = prefs(context).getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet()

    fun setAllowed(context: Context, host: String, allowed: Boolean) {
        val set = allowedSites(context).toMutableSet()
        if (allowed) set.add(host) else set.remove(host)
        prefs(context).edit().putStringSet(KEY_ALLOWED, set).apply()
        org.mozilla.fenix.downloads.MediaGrabber.pushShields(context)
    }

    fun hostOf(url: String?): String? = url?.let { Uri.parse(it).host?.lowercase()?.removePrefix("www.") }

    /** Installs the bundled uBlock Origin (Aion's built-in ad blocker) and applies the switch. */
    fun applyAdBlocker(context: Context, engine: Engine) = AdBlocker.install(context, engine)

    fun setBlockAds(context: Context, engine: Engine, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ADS, value).apply()
        AdBlocker.setEnabled(context, engine, value)
    }

    private fun siteOf(host: String?): String? {
        val parts = host?.lowercase()?.removePrefix("www.")?.split('.') ?: return null
        return if (parts.size > 2) parts.takeLast(2).joinToString(".") else parts.joinToString(".")
    }

    /**
     * True for a top-frame jump to a different site that the user didn't ask for: no tap, not typed
     * in the address bar, not a server redirect of a link the user tapped.
     */
    fun isForcedRedirect(
        context: Context,
        uri: String,
        lastUri: String?,
        hasUserGesture: Boolean,
        isSameDomain: Boolean,
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        isSubframeRequest: Boolean,
    ): Boolean {
        if (!blockPopups(context) || isSubframeRequest || isDirectNavigation || isSameDomain || hasUserGesture) return false
        if (lastUri.isNullOrBlank() || !lastUri.startsWith("http") || !uri.startsWith("http")) return false
        if (isRedirect) return false // server-side redirects (logins, short links) stay allowed
        val from = siteOf(Uri.parse(lastUri).host) ?: return false
        val to = siteOf(Uri.parse(uri).host) ?: return false
        if (from == to) return false
        if (hostOf(lastUri) in allowedSites(context)) return false
        Handler(Looper.getMainLooper()).post { onPopupBlocked(context, lastUri) }
        return true
    }

    internal fun countBlocked(context: Context) =
        prefs(context).edit().putInt(KEY_BLOCKED_COUNT, popupsBlocked(context) + 1).apply()
}

/**
 * Denies pop-up windows the page opened without the user's tap (Gecko only asks Aion about those),
 * unless the site is on the allow list, and approves the built-in ad blocker's install prompt.
 */
class ShieldsMiddleware(private val context: Context) : Middleware<BrowserState, BrowserAction> {
    private val main = Handler(Looper.getMainLooper())

    override fun invoke(store: Store<BrowserState, BrowserAction>, next: (BrowserAction) -> Unit, action: BrowserAction) {
        if (action is ContentAction.UpdatePromptRequestAction) {
            val request = action.promptRequest
            if (request is PromptRequest.Popup && Shields.blockPopups(context)) {
                val host = Shields.hostOf(store.state.findTabOrCustomTab(action.sessionId)?.content?.url)
                if (host == null || host !in Shields.allowedSites(context)) {
                    val page = store.state.findTabOrCustomTab(action.sessionId)?.content?.url ?: ""
                    main.post {
                        request.onDeny()
                        Shields.onPopupBlocked(context, page)
                    }
                    return
                }
            }
        }
        if (action is WebExtensionAction.UpdatePromptRequestWebExtensionAction) {
            val request = action.promptRequest
            if (Shields.installingAdBlocker &&
                request is WebExtensionPromptRequest.AfterInstallation.Permissions.Required &&
                request.extension.id == Shields.UBLOCK_ID
            ) {
                main.post { request.onConfirm(PermissionPromptResponse(isPermissionsGranted = true, isPrivateModeGranted = true)) }
                return
            }
            if (request is WebExtensionPromptRequest.AfterInstallation.PostInstallation &&
                request.extension.id == Shields.UBLOCK_ID && Shields.installingAdBlocker
            ) {
                Shields.installingAdBlocker = false
                return
            }
        }
        next(action)
    }
}
