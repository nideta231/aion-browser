/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.shortcuts

import android.content.Context
import android.view.KeyEvent

/** One keyboard combination: a key plus Ctrl / Shift / Alt. */
data class KeyCombo(val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {

    /** Stored form, e.g. "C:S:-:46". */
    fun encode(): String = "${flag(ctrl, "C")}:${flag(shift, "S")}:${flag(alt, "A")}:$keyCode"

    /** Readable form, e.g. "Ctrl+Shift+T". */
    fun label(): String = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        add(keyName(keyCode))
    }.joinToString("+")

    /** Modifier mask for KeyboardShortcutInfo. */
    fun metaState(): Int =
        (if (ctrl) KeyEvent.META_CTRL_ON else 0) or
            (if (shift) KeyEvent.META_SHIFT_ON else 0) or
            (if (alt) KeyEvent.META_ALT_ON else 0)

    companion object {
        private fun flag(on: Boolean, letter: String) = if (on) letter else "-"

        fun decode(value: String): KeyCombo? {
            val parts = value.split(":")
            if (parts.size != 4) return null
            val code = parts[3].toIntOrNull() ?: return null
            return KeyCombo(code, parts[0] == "C", parts[1] == "S", parts[2] == "A")
        }

        fun from(event: KeyEvent) = KeyCombo(event.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed)

        fun keyName(keyCode: Int): String = when (keyCode) {
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_SPACE -> "Space"
            KeyEvent.KEYCODE_ENTER -> "Enter"
            KeyEvent.KEYCODE_ESCAPE -> "Esc"
            KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
            KeyEvent.KEYCODE_DPAD_UP -> "Up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
            KeyEvent.KEYCODE_PAGE_UP -> "PgUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PgDn"
            KeyEvent.KEYCODE_MOVE_HOME -> "Home"
            KeyEvent.KEYCODE_MOVE_END -> "End"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_EQUALS -> "="
            KeyEvent.KEYCODE_PLUS -> "+"
            KeyEvent.KEYCODE_MINUS -> "-"
            KeyEvent.KEYCODE_COMMA -> ","
            KeyEvent.KEYCODE_PERIOD -> "."
            KeyEvent.KEYCODE_SLASH -> "/"
            KeyEvent.KEYCODE_LEFT_BRACKET -> "["
            KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").lowercase().replaceFirstChar { it.uppercase() }
        }

        /** Keys that only modify others and can't be a shortcut on their own. */
        fun isModifier(keyCode: Int) = KeyEvent.isModifierKey(keyCode) ||
            keyCode == KeyEvent.KEYCODE_FUNCTION || keyCode == KeyEvent.KEYCODE_CAPS_LOCK
    }
}

/** Every action that can be bound to a key, with its default binding(s). */
enum class ShortcutAction(val label: String, vararg val defaults: KeyCombo) {
    NEW_TAB("New tab", KeyCombo(KeyEvent.KEYCODE_T, ctrl = true)),
    NEW_PRIVATE_TAB("New private tab", KeyCombo(KeyEvent.KEYCODE_N, ctrl = true, shift = true)),
    CLOSE_TAB("Close tab", KeyCombo(KeyEvent.KEYCODE_W, ctrl = true), KeyCombo(KeyEvent.KEYCODE_F4, ctrl = true)),
    REOPEN_CLOSED_TAB("Reopen closed tab", KeyCombo(KeyEvent.KEYCODE_T, ctrl = true, shift = true)),
    NEXT_TAB("Next tab", KeyCombo(KeyEvent.KEYCODE_TAB, ctrl = true), KeyCombo(KeyEvent.KEYCODE_PAGE_DOWN, ctrl = true)),
    PREVIOUS_TAB(
        "Previous tab",
        KeyCombo(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true),
        KeyCombo(KeyEvent.KEYCODE_PAGE_UP, ctrl = true),
    ),
    FOCUS_ADDRESS_BAR(
        "Focus address bar",
        KeyCombo(KeyEvent.KEYCODE_L, ctrl = true),
        KeyCombo(KeyEvent.KEYCODE_D, alt = true),
        KeyCombo(KeyEvent.KEYCODE_K, ctrl = true),
    ),
    RELOAD("Reload", KeyCombo(KeyEvent.KEYCODE_R, ctrl = true), KeyCombo(KeyEvent.KEYCODE_F5)),
    HARD_RELOAD(
        "Reload without cache",
        KeyCombo(KeyEvent.KEYCODE_R, ctrl = true, shift = true),
        KeyCombo(KeyEvent.KEYCODE_F5, ctrl = true),
    ),
    BACK("Back", KeyCombo(KeyEvent.KEYCODE_DPAD_LEFT, alt = true)),
    FORWARD("Forward", KeyCombo(KeyEvent.KEYCODE_DPAD_RIGHT, alt = true)),
    FIND_IN_PAGE("Find in page", KeyCombo(KeyEvent.KEYCODE_F, ctrl = true), KeyCombo(KeyEvent.KEYCODE_F3)),
    BOOKMARKS("Bookmarks", KeyCombo(KeyEvent.KEYCODE_B, ctrl = true, shift = true)),
    HISTORY("History", KeyCombo(KeyEvent.KEYCODE_H, ctrl = true)),
    DOWNLOADS("Downloads", KeyCombo(KeyEvent.KEYCODE_J, ctrl = true)),
    TABS_OVERVIEW("Show all tabs", KeyCombo(KeyEvent.KEYCODE_A, ctrl = true, shift = true)),
    ADD_ONS("Add-ons", KeyCombo(KeyEvent.KEYCODE_A, ctrl = true, alt = true)),
    SETTINGS("Settings", KeyCombo(KeyEvent.KEYCODE_COMMA, ctrl = true)),
    HOME("Home page", KeyCombo(KeyEvent.KEYCODE_MOVE_HOME, alt = true)),
    PRINT("Print / save as PDF", KeyCombo(KeyEvent.KEYCODE_P, ctrl = true)),
    INSTALL_AS_APP("Install site as app", KeyCombo(KeyEvent.KEYCODE_I, ctrl = true, shift = true)),
    ;

    /** Ctrl+1..8 jump to tab N and Ctrl+9 to the last tab. These are fixed, like every desktop browser. */
    companion object {
        val TAB_NUMBER_KEYS = (KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9)
    }
}

/** Saved bindings. Each action keeps a list of combos; an empty list means "disabled". */
class KeyboardShortcutStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aion_keyboard_shortcuts", Context.MODE_PRIVATE)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun combos(action: ShortcutAction): List<KeyCombo> {
        val stored = prefs.getString(action.name, null) ?: return action.defaults.toList()
        if (stored.isEmpty()) return emptyList()
        return stored.split(",").mapNotNull { KeyCombo.decode(it) }
    }

    fun setCombos(action: ShortcutAction, combos: List<KeyCombo>) {
        val value = combos.distinct().joinToString(",") { it.encode() }
        if (combos.distinct() == action.defaults.toList()) {
            prefs.edit().remove(action.name).apply()
        } else {
            prefs.edit().putString(action.name, value).apply()
        }
    }

    /** Assigns [combo] to [action] (replacing its bindings) and removes it from any other action. */
    fun assign(action: ShortcutAction, combo: KeyCombo) {
        ShortcutAction.entries.filter { it != action && combo in combos(it) }.forEach { other ->
            setCombos(other, combos(other) - combo)
        }
        setCombos(action, listOf(combo))
    }

    fun actionFor(combo: KeyCombo): ShortcutAction? = ShortcutAction.entries.firstOrNull { combo in combos(it) }

    fun resetAll() {
        val enabled = enabled()
        prefs.edit().clear().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}
