package com.nath.aion.downloads

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * DASH (.mpd) downloads: Media3 Transformer reads the manifest, fetches the best video and audio
 * and writes one MP4 (no re-encoding when the formats fit MP4). DRM-protected DASH is refused by
 * Media3 itself.
 */
@OptIn(UnstableApi::class)
class DashEngine(private val context: Context, private val prefs: Prefs) {

    suspend fun run(item: DownloadItem, onProgress: () -> Unit) {
        val temp = File(context.cacheDir, "dash-${item.id}.mp4").apply { delete() }
        item.segmentsTotal = 100
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val http = DefaultHttpDataSource.Factory()
                    .setUserAgent(item.userAgent ?: prefs.userAgent)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(
                        buildMap {
                            putAll(item.headers)
                            item.referer?.let { put("Referer", it) }
                            item.cookies?.let { put("Cookie", it) }
                        },
                    )
                val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
                val handler = Handler(Looper.getMainLooper())
                lateinit var transformer: Transformer
                val holder = ProgressHolder()
                val tick = object : Runnable {
                    override fun run() {
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            item.segmentsDone = holder.progress
                            onProgress()
                        }
                        handler.postDelayed(this, 1000)
                    }
                }
                transformer = Transformer.Builder(context)
                    .setAssetLoaderFactory(
                        DefaultAssetLoaderFactory(context, DefaultDecoderFactory.Builder(context).build(), Clock.DEFAULT, sources, androidx.media3.datasource.DataSourceBitmapLoader(context)),
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            handler.removeCallbacks(tick)
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            handler.removeCallbacks(tick)
                            if (cont.isActive) cont.resumeWithException(java.io.IOException("DASH: ${exportException.errorCodeName}", exportException))
                        }
                    })
                    .build()
                transformer.start(MediaItem.Builder().setUri(item.url).setMimeType(MimeTypes.APPLICATION_MPD).build(), temp.absolutePath)
                handler.postDelayed(tick, 1000)
                cont.invokeOnCancellation {
                    handler.post {
                        handler.removeCallbacks(tick)
                        transformer.cancel()
                        temp.delete()
                    }
                }
            }
        }
        withContext(Dispatchers.IO) {
            item.file.parentFile?.mkdirs()
            item.file.delete()
            if (!temp.renameTo(item.file)) {
                temp.copyTo(item.file, overwrite = true)
                temp.delete()
            }
            item.segmentsDone = 100
            item.totalBytes = item.file.length()
            item.doneBytes = item.totalBytes
        }
    }
}
