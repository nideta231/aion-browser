/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.shortcuts

import android.content.Intent
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.browser.state.action.RecentlyClosedAction
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.EngineSession.LoadUrlFlags
import org.mozilla.fenix.BrowserDirection
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.NavGraphDirections
import org.mozilla.fenix.R
import org.mozilla.fenix.browser.browsingmode.BrowsingMode
import org.mozilla.fenix.components.appstate.AppAction.FindInPageAction
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.webapp.InstallWebAppActivity

/** Runs keyboard shortcuts in the main browser window. */
class KeyboardShortcutHandler(private val activity: HomeActivity, private val navController: () -> NavController) {
    private val store = KeyboardShortcutStore(activity)

    /** Returns true when the key press was a shortcut and has been handled. */
    fun onKeyDown(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || !store.enabled()) return false
        if (KeyCombo.isModifier(event.keyCode)) return false
        val combo = KeyCombo.from(event)

        // Ctrl+1..8 = tab N, Ctrl+9 = last tab.
        if (combo.ctrl && !combo.shift && !combo.alt && event.keyCode in ShortcutAction.TAB_NUMBER_KEYS) {
            selectTabNumber(event.keyCode - KeyEvent.KEYCODE_0)
            return true
        }

        val action = store.actionFor(combo) ?: return false
        // Plain keys (no Ctrl/Alt) would steal typing, except function keys.
        if (!combo.ctrl && !combo.alt && event.keyCode !in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12) return false
        if (event.repeatCount > 0 && action !in REPEATABLE) return true
        run(action)
        return true
    }

    private fun run(action: ShortcutAction) {
        val components = activity.components
        val browserStore = components.core.store
        val tabs = components.useCases.tabsUseCases
        val session = components.useCases.sessionUseCases
        val tab = browserStore.state.selectedTab
        when (action) {
            ShortcutAction.NEW_TAB -> newTab(private = false)
            ShortcutAction.NEW_PRIVATE_TAB -> newTab(private = true)
            ShortcutAction.CLOSE_TAB -> tab?.let { tabs.removeTab(it.id) }
            ShortcutAction.REOPEN_CLOSED_TAB -> reopenClosedTab()
            ShortcutAction.NEXT_TAB -> cycleTab(1)
            ShortcutAction.PREVIOUS_TAB -> cycleTab(-1)
            ShortcutAction.FOCUS_ADDRESS_BAR -> navigate(
                NavGraphDirections.actionGlobalHome(focusOnAddressBar = true, sessionToStartSearchFor = tab?.id),
            )
            ShortcutAction.RELOAD -> session.reload()
            ShortcutAction.HARD_RELOAD -> session.reload(flags = LoadUrlFlags.select(LoadUrlFlags.BYPASS_CACHE))
            ShortcutAction.BACK -> session.goBack()
            ShortcutAction.FORWARD -> session.goForward()
            ShortcutAction.FIND_IN_PAGE -> if (tab != null) {
                activity.openToBrowser(BrowserDirection.FromGlobal)
                components.appStore.dispatch(FindInPageAction.FindInPageStarted)
            }
            ShortcutAction.BOOKMARKS -> navigate(NavGraphDirections.actionGlobalBookmarkFragment(BookmarkRoot.Mobile.id))
            ShortcutAction.HISTORY -> navigate(NavGraphDirections.actionGlobalHistoryFragment())
            ShortcutAction.DOWNLOADS -> if (!org.mozilla.fenix.downloads.AionDownloadsBridge.openApp(activity)) {
                navigate(NavGraphDirections.actionGlobalDownloadsFragment())
            }
            ShortcutAction.TABS_OVERVIEW -> navigate(NavGraphDirections.actionGlobalTabManagementFragment())
            ShortcutAction.ADD_ONS -> navigate(NavGraphDirections.actionGlobalAddonsManagementFragment())
            ShortcutAction.SETTINGS -> navigate(NavGraphDirections.actionGlobalSettingsFragment())
            ShortcutAction.HOME -> navigate(NavGraphDirections.actionGlobalHome())
            ShortcutAction.PRINT -> session.printContent()
            ShortcutAction.INSTALL_AS_APP -> if (tab != null) {
                activity.startActivity(Intent(activity, InstallWebAppActivity::class.java))
            }
            ShortcutAction.GRAB_MEDIA -> org.mozilla.fenix.downloads.MediaGrabber.show(activity)
        }
    }

    private fun navigate(directions: androidx.navigation.NavDirections) {
        runCatching { navController().navigate(directions) }
    }

    private fun newTab(private: Boolean) {
        activity.browsingModeManager.mode = if (private) BrowsingMode.Private else BrowsingMode.Normal
        navigate(NavGraphDirections.actionGlobalHome(focusOnAddressBar = true))
    }

    private fun visibleTabs() = activity.components.core.store.state.let { state ->
        val private = state.selectedTab?.content?.private ?: activity.browsingModeManager.mode.isPrivate
        state.tabs.filter { it.content.private == private }
    }

    private fun cycleTab(step: Int) {
        val tabs = visibleTabs()
        if (tabs.isEmpty()) return
        val current = tabs.indexOfFirst { it.id == activity.components.core.store.state.selectedTabId }
        val next = tabs[((if (current < 0) 0 else current) + step).mod(tabs.size)]
        activity.components.useCases.tabsUseCases.selectTab(next.id)
        activity.openToBrowser(BrowserDirection.FromGlobal)
    }

    private fun selectTabNumber(number: Int) {
        val tabs = visibleTabs()
        if (tabs.isEmpty()) return
        val target = if (number == 9) tabs.last() else tabs.getOrNull(number - 1) ?: return
        activity.components.useCases.tabsUseCases.selectTab(target.id)
        activity.openToBrowser(BrowserDirection.FromGlobal)
    }

    private fun reopenClosedTab() {
        val components = activity.components
        val closed = components.core.store.state.closedTabs.firstOrNull() ?: return
        activity.lifecycleScope.launch {
            components.useCases.tabsUseCases.restore(closed, components.core.recentlyClosedTabsStorage.value.engineStateStorage())
            components.core.store.dispatch(RecentlyClosedAction.RemoveClosedTabAction(closed))
            activity.openToBrowser(BrowserDirection.FromGlobal)
        }
    }

    /** Lists the shortcuts in the system "Keyboard shortcuts" panel (Meta+/). */
    fun provideShortcutGroup(): KeyboardShortcutGroup {
        val items = ShortcutAction.entries.mapNotNull { action ->
            store.combos(action).firstOrNull()?.let { KeyboardShortcutInfo(action.label, it.keyCode, it.metaState()) }
        } + KeyboardShortcutInfo("Go to tab 1–8 (9 = last)", KeyEvent.KEYCODE_1, KeyEvent.META_CTRL_ON)
        return KeyboardShortcutGroup(activity.getString(R.string.app_name), items)
    }

    private companion object {
        val REPEATABLE = setOf(ShortcutAction.NEXT_TAB, ShortcutAction.PREVIOUS_TAB, ShortcutAction.BACK, ShortcutAction.FORWARD)
    }
}
