package com.nath.aion.downloads

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

/**
 * Built-in player for "Play" / "Preview": streams a link (or plays a local file) without saving it.
 * YouTube's separate video and audio streams are played together.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = PlayerView(this).apply {
            keepScreenOn = true
            setShowSubtitleButton(true)
            setFullscreenButtonClickListener { full -> setSystemBarsHidden(full) }
        }
        setContentView(view)
        title = intent.getStringExtra(EXTRA_TITLE) ?: "Aion player"
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        view.player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Can't play this: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
            }
        })

        val youtubePage = intent.getStringExtra(EXTRA_YOUTUBE_PAGE)
        if (youtubePage != null) {
            Toast.makeText(this, "Getting YouTube stream…", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                runCatching { YouTube.resolve(youtubePage) }
                    .onSuccess { result ->
                        val option = YouTube.defaultOption(result.options)
                        if (option == null) {
                            Toast.makeText(this@PlayerActivity, "No playable format", Toast.LENGTH_LONG).show()
                        } else {
                            title = result.title
                            start(exo, option.videoUrl, option.audioUrl, youtubePage)
                        }
                    }
                    .onFailure {
                        Toast.makeText(this@PlayerActivity, "Couldn't read this YouTube video: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
        } else {
            val url = intent.getStringExtra(EXTRA_URL) ?: intent.data?.toString()
            if (url == null) {
                finish()
                return
            }
            start(exo, url, intent.getStringExtra(EXTRA_AUDIO_URL), intent.getStringExtra(EXTRA_REFERER))
        }
    }

    private fun start(exo: ExoPlayer, url: String, audioUrl: String?, referer: String?) {
        val prefs = Prefs(this)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(intent.getStringExtra(EXTRA_USER_AGENT) ?: prefs.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                buildMap {
                    referer?.let { put("Referer", it) }
                    intent.getStringExtra(EXTRA_COOKIES)?.let { put("Cookie", it) }
                },
            )
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
        val video = factory.createMediaSource(mediaItem(url))
        val source: MediaSource = if (audioUrl != null) {
            MergingMediaSource(video, factory.createMediaSource(mediaItem(audioUrl)))
        } else {
            video
        }
        exo.setMediaSource(source)
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun mediaItem(url: String): MediaItem {
        val path = url.substringBefore('?').lowercase()
        val mime = when {
            path.endsWith(".m3u8") || intent.getStringExtra(EXTRA_MIME)?.contains("mpegurl") == true -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") || intent.getStringExtra(EXTRA_MIME)?.contains("dash") == true -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        return MediaItem.Builder().setUri(Uri.parse(url)).apply { mime?.let { setMimeType(it) } }.build()
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT < 30) return
        val controller = window.insetsController ?: return
        if (hidden) {
            controller.hide(WindowInsets.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsets.Type.systemBars())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true && Build.VERSION.SDK_INT >= 26) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_AUDIO_URL = "audio_url"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_COOKIES = "cookies"
        const val EXTRA_MIME = "mime"
        const val EXTRA_TITLE = "title"
        const val EXTRA_YOUTUBE_PAGE = "youtube_page"

        fun intentFor(context: Context, url: String, audioUrl: String? = null, referer: String? = null, title: String? = null) =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_AUDIO_URL, audioUrl)
                .putExtra(EXTRA_REFERER, referer).putExtra(EXTRA_TITLE, title)

        fun forItem(context: Context, item: DownloadItem): Intent =
            if (item.state == State.FINISHED && item.file.exists()) {
                intentFor(context, Uri.fromFile(item.file).toString(), title = item.fileName)
            } else {
                intentFor(context, item.url, item.audioUrl, item.referer, item.fileName)
                    .putExtra(EXTRA_USER_AGENT, item.userAgent).putExtra(EXTRA_COOKIES, item.cookies).putExtra(EXTRA_MIME, item.mime)
            }

        /** Links that look like video/audio, for "Play" and the default-action setting. */
        fun looksPlayable(url: String, mime: String?): Boolean {
            if (YouTube.isYouTube(url)) return true
            val m = mime?.lowercase() ?: ""
            if (m.startsWith("video/") || m.startsWith("audio/") || "mpegurl" in m || "dash" in m) return true
            return Regex("\\.(mp4|m4v|webm|mkv|mov|3gp|m3u8|mpd|mp3|m4a|aac|ogg|opus|flac|wav)$", RegexOption.IGNORE_CASE)
                .containsMatchIn(url.substringBefore('?').substringBefore('#'))
        }
    }
}
