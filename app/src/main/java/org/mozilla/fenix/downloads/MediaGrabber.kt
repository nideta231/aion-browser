/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.downloads

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.PreferenceManager
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtensionRuntime
import mozilla.components.support.webextensions.BuiltInWebExtensionController
import org.json.JSONObject
import org.mozilla.fenix.ext.components
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1DM-style media grabber: a built-in extension notes every video/audio stream the page loads
 * (HLS, DASH, MP4, MP3, YouTube video/audio streams). [show] lists the ones on the current tab and
 * sends the picked one to Aion Downloads.
 */
object MediaGrabber {
    private const val EXTENSION_ID = "mediagrabber@aion.nath.com"
    private const val EXTENSION_URL = "resource://android/assets/extensions/mediagrabber/"
    private const val PORT = "aionMedia"
    const val PREF_AUTO_DETECT = "pref_key_aion_media_auto_detect"

    data class Media(
        val url: String,
        val kind: String,
        val mime: String,
        val size: Long,
        val page: String,
        val youtube: Boolean = false,
        val quality: String = "",
        val container: String = "",
    )

    /** What the extension found on a tab. [generation] changes each time the page (re)loads. */
    data class Found(val items: List<Media>, val generation: Int, val youtubeBlocked: Boolean)

    /** A downloadable choice shown to the user (YouTube video + audio are paired here). */
    private data class Choice(val label: String, val video: Media, val audio: Media?)

    private val controller = BuiltInWebExtensionController(EXTENSION_ID, EXTENSION_URL, PORT)
    private val pending = ConcurrentHashMap<Int, (Found) -> Unit>()
    private val nextId = AtomicInteger()
    private val main = Handler(Looper.getMainLooper())

    /** "Detect video/audio automatically": shows the floating button. On by default. */
    fun autoDetect(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_AUTO_DETECT, true)

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
                            Media(
                                it.optString("url"), it.optString("kind"), it.optString("mime"),
                                it.optLong("size", -1), it.optString("page"), it.optBoolean("youtube"),
                                it.optString("quality"), it.optString("container"),
                            )
                        }
                    }
                    val found = Found(items, json.optInt("generation"), json.optBoolean("ytBlocked"))
                    main.post { callback(found) }
                }
            },
            PORT,
        )
        controller.install(runtime)
    }

    /** Media found so far on the page with [pageUrl] (the selected tab). */
    fun find(pageUrl: String?, callback: (Found) -> Unit) {
        val id = nextId.incrementAndGet()
        pending[id] = callback
        controller.sendBackgroundMessage(JSONObject().put("type", "list").put("id", id).put("url", pageUrl ?: ""))
        main.postDelayed({ pending.remove(id)?.invoke(Found(emptyList(), -1, false)) }, 3000)
    }

    /** (videos, audio-only) choice counts, for the floating button text. */
    fun choiceCount(found: Found, pageUrl: String? = null): Pair<Int, Int> {
        val list = choices(found.items, pageUrl)
        val audio = list.count { it.audio == null && it.video.kind == "Audio" }
        return (list.size - audio) to audio
    }

    private val youtubePage = Regex("^https?://(m\\.|www\\.|music\\.)?(youtube\\.com/(watch|shorts/|live/)|youtu\\.be/)", RegexOption.IGNORE_CASE)

    /** YouTube watch pages: Aion Downloads reads the real file links from the page itself. */
    fun isYouTubePage(url: String?) = url != null && youtubePage.containsMatchIn(url)

    private fun choices(items: List<Media>, pageUrl: String? = null): List<Choice> {
        if (isYouTubePage(pageUrl)) {
            val page = Media(pageUrl!!, "Video", "", -1, pageUrl, youtube = false)
            return listOf(Choice("YouTube video · choose quality in Aion Downloads", page, null)) +
                items.filter { !it.youtube }.map { Choice(label(it), it, null) }
        }
        val normal = items.filter { !it.youtube }.map { Choice(label(it), it, null) }
        val yt = items.filter { it.youtube }
        val audios = yt.filter { it.kind == "Audio" }
        val ytAudio = audios.maxByOrNull { it.size }
        // Merging needs matching containers: MP4 video + M4A audio, WebM video + WebM audio.
        fun audioFor(video: Media) = audios.filter { it.container == video.container }.maxByOrNull { it.size }
        val ytVideo = yt.filter { it.kind == "Video" }
            .sortedByDescending { it.quality.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val ytChoices = ytVideo.map { video ->
            val ytAudio = audioFor(video)
            val total = if (ytAudio != null && video.size > 0 && ytAudio.size > 0) video.size + ytAudio.size else video.size
            Choice("YouTube video · ${video.quality}${sizeText(total)}", video, ytAudio)
        } + listOfNotNull(ytAudio?.let { Choice("YouTube audio only · ${it.quality}${sizeText(it.size)}", it, null) })
        return ytChoices + normal
    }

    /** Shows the media found on the selected tab. [onHide] adds a "Hide icon" button. */
    fun show(activity: Activity, onHide: (() -> Unit)? = null) {
        val tab = activity.components.core.store.state.selectedTab
        if (tab == null) {
            Toast.makeText(activity, "Open a page with a video first", Toast.LENGTH_SHORT).show()
            return
        }
        find(tab.content.url) { found ->
            if (activity.isFinishing || activity.isDestroyed) return@find
            val list = choices(found.items, tab.content.url)
            val builder = AlertDialog.Builder(activity)
            if (list.isEmpty()) {
                builder.setTitle("No media found yet").setMessage(
                    if (found.youtubeBlocked) {
                        "YouTube sent this video in a format that can't be downloaded here. Reload the page and play the video again."
                    } else {
                        "Play the video or song first, then try again. Protected (DRM) streams can't be downloaded."
                    },
                ).setPositiveButton(android.R.string.ok, null)
            } else {
                builder.setTitle("Media on this page (${list.size})")
                    .setItems(list.map { it.label }.toTypedArray()) { _, which ->
                        val choice = list[which]
                        if (isYouTubePage(choice.video.url)) {
                            download(activity, choice, tab.content.title)
                        } else {
                            chooseAction(activity, choice, tab.content.title)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
            }
            if (onHide != null) builder.setNeutralButton("Hide icon") { _, _ -> onHide() }
            builder.show()
        }
    }

    private fun chooseAction(activity: Activity, choice: Choice, title: String) {
        AlertDialog.Builder(activity)
            .setTitle(choice.label)
            .setMessage(choice.video.url.take(300))
            .setPositiveButton("Download") { _, _ -> download(activity, choice, title) }
            .setNeutralButton("Copy link") { _, _ ->
                activity.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("media", choice.video.url))
                Toast.makeText(activity, "Link copied", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun label(media: Media): String {
        val name = Uri.parse(media.url).lastPathSegment?.takeIf { it.isNotBlank() } ?: media.url
        return "${media.kind}${sizeText(media.size)} · ${name.take(60)}"
    }

    private fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1L shl 30 -> " · %.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> " · %.1f MB".format(bytes / (1L shl 20).toDouble())
        else -> " · ${bytes / 1024} KB"
    }

    private fun download(activity: Activity, choice: Choice, title: String) {
        if (!AionDownloadsBridge.isInstalled(activity)) {
            Toast.makeText(
                activity,
                "Turn on Settings → Download Settings → Add Aion Download Manager as separate app",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val media = choice.video
        val ext = when {
            media.youtube && choice.audio != null -> if (media.container == "webm") "webm" else "mp4"
            media.youtube -> if (media.container == "webm") "webm" else "m4a"
            media.kind == "HLS" || media.kind == "DASH" -> "mp4"
            else -> Uri.parse(media.url).lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.length in 2..4 }
                ?: if (media.kind == "Audio") "m4a" else "mp4"
        }
        val base = title.removeSuffix(" - YouTube").ifBlank { "video" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(120)
        val intent = Intent(AionDownloadsBridge.ACTION_ADD, Uri.parse(media.url))
            .setComponent(ComponentName(AionDownloadsBridge.PACKAGE, "${AionDownloadsBridge.PACKAGE}.AddActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("file_name", "$base.$ext")
            .putExtra("referer", media.page)
            .putExtra("mime", media.mime.ifBlank { null })
        // YouTube keeps video and audio apart; Aion Downloads fetches both and merges them.
        choice.audio?.let { intent.putExtra("audio_url", it.url) }
        runCatching { activity.startActivity(intent) }
            .onFailure { Toast.makeText(activity, "Couldn't open Aion Downloads", Toast.LENGTH_SHORT).show() }
    }
}
