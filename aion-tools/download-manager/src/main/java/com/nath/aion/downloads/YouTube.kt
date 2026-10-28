package com.nath.aion.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.TimeUnit

/**
 * YouTube links: the player streams in a private format, so the real video/audio file links are
 * fetched from the watch page with NewPipeExtractor (GPL-3.0, private use; see ROADMAP).
 */
object YouTube {
    data class Option(
        val label: String,
        val videoUrl: String,
        val audioUrl: String?,
        val size: Long,
        val ext: String,
    )

    data class Result(val title: String, val options: List<Option>)

    private val hostPattern = Regex("^https?://([a-z0-9-]+\\.)?(youtube\\.com|youtu\\.be|youtube-nocookie\\.com)/", RegexOption.IGNORE_CASE)

    fun isYouTube(url: String) = hostPattern.containsMatchIn(url) &&
        (url.contains("watch") || url.contains("youtu.be/") || url.contains("/shorts/") || url.contains("/live/") || url.contains("/embed/"))

    @Volatile private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(OkDownloader)
                ready = true
            }
        }
    }

    suspend fun resolve(url: String): Result = withContext(Dispatchers.IO) {
        init()
        val info = StreamInfo.getInfo(url)
        val progressive = info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val videoOnly = info.videoOnlyStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val audios = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        fun len(s: org.schabi.newpipe.extractor.stream.Stream) =
            ((s as? org.schabi.newpipe.extractor.stream.VideoStream)?.itagItem
                ?: (s as? org.schabi.newpipe.extractor.stream.AudioStream)?.itagItem)?.contentLength ?: -1L
        val bestM4a = audios.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
        val bestWebma = audios.filter { it.format == MediaFormat.WEBMA || it.format == MediaFormat.WEBMA_OPUS }
            .maxByOrNull { it.averageBitrate }

        val options = mutableListOf<Option>()
        // Video-only streams + matching audio (MP4 + M4A, WebM + WebM audio), best quality first.
        videoOnly.sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }
            .thenBy { if (it.format == MediaFormat.MPEG_4) 0 else 1 })
            .distinctBy { it.resolution }
            .forEach { v ->
                val webm = v.format == MediaFormat.WEBM
                val a = if (webm) bestWebma else bestM4a
                if (a != null) {
                    val size = if (len(v) > 0 && len(a) > 0) len(v) + len(a) else -1
                    options += Option("${v.resolution} · ${if (webm) "WebM" else "MP4"}${Format.sizeSuffix(size)}", v.content, a.content, size, if (webm) "webm" else "mp4")
                }
            }
        // Older single-file formats (video+audio together), e.g. 360p MP4.
        progressive.sortedByDescending { it.height }.forEach { v ->
            val ext = v.format?.suffix ?: "mp4"
            options += Option("${v.resolution} · ${ext.uppercase()} (single file)${Format.sizeSuffix(len(v))}", v.content, null, len(v), ext)
        }
        listOfNotNull(bestM4a, bestWebma).forEach { a ->
            val ext = if (a.format == MediaFormat.M4A) "m4a" else "webm"
            options += Option("Audio only · ${a.averageBitrate} kbps · ${ext.uppercase()}${Format.sizeSuffix(len(a))}", a.content, null, len(a), ext)
        }
        Result(info.name ?: "YouTube video", options)
    }

    /** NewPipe's HTTP layer on top of OkHttp. */
    private object OkDownloader : Downloader() {
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).connectTimeout(20, TimeUnit.SECONDS).build()

        override fun execute(request: Request): Response {
            val body = request.dataToSend()?.toRequestBody(null)
                ?: if (request.httpMethod() == "POST") ByteArray(0).toRequestBody(null) else null
            val builder = okhttp3.Request.Builder().url(request.url()).method(request.httpMethod(), body)
                .header("User-Agent", UA)
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            client.newCall(builder.build()).execute().use { r ->
                if (r.code == 429) throw org.schabi.newpipe.extractor.exceptions.ReCaptchaException("YouTube rate limit", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body.string(), r.request.url.toString())
            }
        }
    }
}
