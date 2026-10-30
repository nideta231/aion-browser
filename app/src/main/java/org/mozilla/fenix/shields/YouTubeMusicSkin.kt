/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.shields

import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.support.webextensions.BuiltInWebExtensionController

/**
 * Built-in extension for music.youtube.com: asks for the full desktop layout (queue, lyrics side panel)
 * instead of the stripped mobile web one, hides "Open App" and the app upsell dialogs, and applies an
 * Apple Music style skin.
 */
object YouTubeMusicSkin {
    private const val ID = "ytmusic@aion.nath.com"
    private const val URL = "resource://android/assets/extensions/ytmusic/"
    private const val PORT = "aionYtMusic"

    private val controller = BuiltInWebExtensionController(ID, URL, PORT)

    fun install(runtime: WebExtensionRuntime) {
        controller.install(runtime)
    }
}
