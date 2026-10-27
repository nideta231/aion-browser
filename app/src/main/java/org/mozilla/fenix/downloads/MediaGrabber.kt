/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.downloads

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import mozilla.components.browser.state.selector.selectedTab
import org.json.JSONObject
import org.mozilla.fenix.ext.components
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1DM-style media grabber: a built-in extension notes every video/audio stream the page loads
 * (HLS, DASH, MP4, MP3...). [show] lists the ones on the current tab and sends the picked one to
 * Aion Downloads.
 */
object MediaGrabber {
    private const val EXTENSION_ID = "mediagrabber@aion.nath.com"
    private const val EXTENSION_URL = "resource://android/assets/extensions/mediagrabber/"
    private const val PORT = "aionMedia"

    data class Media(val url: String, val kind: String, val mime: String, val size: Long, val page: String)

    private val controller = BuiltInWebExtensionController(EXTENSION_ID, EXTENSION_URL, PORT)
    private val pending = ConcurrentHashMap<Int, (List<Media>) -> Unit>()
    private val nextId = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    fun install(runtime: WebExtensionRuntime) {
        controller.registerBackgroundMessageHandler(
            object : MessageHandler {
                override fun onPortMessage(message: Any, port: Port) {
                    val json = message as? JSONObject ?: return
                    if (json.optString("type") != "media") return
                    val callback = pending.remove(json.optInt("id")) ?: return
                    val array = json.optJSONArray("items")
                    val items = (0 until (array?.length() ?: 0)).mapNotNull { i ->
                        array?.optJSONObject(i)?.let {
                            Media(it.optString("url"), it.optString("kind"), it.optString("mime"),
                                it.optLong("size", -1), it.optString("page"))
                        }
                    }
                    main.post { callback(items) }
                }
            },
            PORT,
        )
        controller.install(runtime)
    }

    private fun request(pageUrl: String?, callback: (List<Media>) -> Unit) {
        val id = nextId.incrementAndGet()
        pending[id] = callback
        controller.sendBackgroundMessage(JSONObject().put("type", "list").put("id", id).put("url", pageUrl ?: ""))
        main.postDelayed({ pending.remove(id)?.invoke(emptyList()) }, 3000)
    }

    /** Shows the media found on the selected tab. */
    fun show(activity: Activity) {
        val tab = activity.components.core.store.state.selectedTab
        if (tab == null) {
            Toast.makeText(activity, "Open a page with a video first", Toast.LENGTH_SHORT).show()
            return
        }
        request(tab.content.url) { items ->
            if (activity.isFinishing || activity.isDestroyed) return@request
            if (items.isEmpty()) {
                AlertDialog.Builder(activity)
                    .setTitle("No media found yet")
                    .setMessage("Play the video or song first, then try again. Protected (DRM) streams can't be downloaded.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@request
            }
            val labels = items.map { label(it) }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("Media on this page (${items.size})")
                .setItems(labels) { _, which -> chooseAction(activity, items[which], tab.content.title) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun chooseAction(activity: Activity, media: Media, title: String) {
        AlertDialog.Builder(activity)
            .setTitle(label(media))
            .setMessage(media.url)
            .setPositiveButton("Download") { _, _ -> download(activity, media, title) }
            .setNeutralButton("Copy link") { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("media", media.url))
                Toast.makeText(activity, "Link copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun label(media: Media): String {
        val name = Uri.parse(media.url).lastPathSegment?.takeIf { it.isNotBlank() } ?: media.url
        val size = if (media.size > 0) " · " + formatSize(media.size) else ""
        return "${media.kind}$size · ${name.take(60)}"
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    private fun download(activity: Activity, media: Media, title: String) {
        if (!AionDownloadsBridge.isInstalled(activity)) {
            Toast.makeText(
                activity,
                "Turn on Settings → Download Settings → Add Aion Download Manager as separate app",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val ext = when (media.kind) {
            "HLS", "DASH" -> "mp4"
            else -> Uri.parse(media.url).lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 }
                ?: if (media.kind == "Audio") "m4a" else "mp4"
        }
        val base = title.ifBlank { "video" }.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)
        val intent = Intent(AionDownloadsBridge.ACTION_ADD, Uri.parse(media.url))
            .setComponent(ComponentName(AionDownloadsBridge.PACKAGE, "${AionDownloadsBridge.PACKAGE}.AddActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("file_name", "$base.$ext")
            .putExtra("referer", media.page)
            .putExtra("mime", media.mime.ifBlank { null })
        runCatching { activity.startActivity(intent) }
            .onFailure { Toast.makeText(activity, "Couldn't open Aion Downloads", Toast.LENGTH_SHORT).show() }
    }
}
