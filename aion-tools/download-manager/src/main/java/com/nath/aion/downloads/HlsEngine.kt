package com.nath.aion.downloads

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * HLS (m3u8) download: picks the best variant, downloads segments in parallel, decrypts
 * AES-128 segments with the key the playlist points to (like 1DM), joins them into one .ts and
 * remuxes it to .mp4 with MediaMuxer. SAMPLE-AES / Widevine streams are refused.
 */
class HlsEngine(private val prefs: Prefs, private val limiter: SpeedLimiter) {

    private data class Segment(val index: Int, val url: String, val keyUrl: String?, val iv: ByteArray?, val sequence: Long)

    suspend fun run(item: DownloadItem, onProgress: () -> Unit) = withContext(Dispatchers.IO) {
        val media = resolveMediaPlaylist(item)
        val segments = parseSegments(media.first, media.second)
        if (segments.isEmpty()) throw FatalDownloadError("Empty playlist")
        item.segmentsTotal = segments.size
        val work = File(item.dir, ".${item.fileName}.parts").apply { mkdirs() }
        val keys = HashMap<String, ByteArray>()
        val done = AtomicInteger(segments.count { File(work, "${it.index}.ts").exists() })
        val bytes = AtomicLong(work.listFiles()?.sumOf { it.length() } ?: 0)
        item.segmentsDone = done.get()
        val gate = Semaphore(item.threads.coerceIn(1, 16))
        coroutineScope {
            val ticker = async {
                var last = bytes.get()
                while (isActive) {
                    delay(1000)
                    val now = bytes.get()
                    item.speed = now - last
                    item.doneBytes = now
                    item.segmentsDone = done.get()
                    last = now
                    onProgress()
                }
            }
            try {
                segments.filter { !File(work, "${it.index}.ts").exists() }.map { seg ->
                    async {
                        gate.withPermit {
                            var data = fetch(item, seg.url)
                            if (seg.keyUrl != null) {
                                val key = synchronized(keys) { keys[seg.keyUrl] } ?: fetch(item, seg.keyUrl).also {
                                    synchronized(keys) { keys[seg.keyUrl] = it }
                                }
                                data = decrypt(data, key, seg.iv ?: ivFromSequence(seg.sequence))
                            }
                            limiter.take(data.size)
                            val tmp = File(work, "${seg.index}.tmp")
                            tmp.writeBytes(data)
                            tmp.renameTo(File(work, "${seg.index}.ts"))
                            bytes.addAndGet(data.size.toLong())
                            done.incrementAndGet()
                        }
                    }
                }.awaitAll()
            } finally {
                ticker.cancel()
            }
        }
        item.segmentsDone = segments.size
        item.state = State.MERGING
        onProgress()
        val joined = File(item.dir, item.fileName.substringBeforeLast('.') + ".ts")
        joined.outputStream().buffered().use { out ->
            segments.forEach { File(work, "${it.index}.ts").inputStream().use { input -> input.copyTo(out) } }
        }
        work.deleteRecursively()
        val target = if (prefs.convertTs) File(item.dir, item.fileName.substringBeforeLast('.') + ".mp4") else joined
        if (prefs.convertTs && remux(joined, target)) {
            joined.delete()
            item.fileName = target.name
        } else {
            item.fileName = joined.name
        }
        item.totalBytes = item.file.length()
        item.doneBytes = item.totalBytes
        item.speed = 0
    }

    private fun fetch(item: DownloadItem, url: String): ByteArray {
        Http.client(prefs).newCall(Http.request(item, prefs, url).get().build()).execute().use {
            Http.checkStatus(it)
            return it.body.bytes()
        }
    }

    private fun resolveMediaPlaylist(item: DownloadItem): Pair<String, String> {
        var url = item.url
        repeat(3) {
            val text = String(fetch(item, url))
            if (!text.contains("#EXTM3U")) throw FatalDownloadError("Not an HLS playlist")
            if (!text.contains("#EXT-X-STREAM-INF")) return text to url
            // Master playlist: pick the highest bandwidth variant.
            val lines = text.lines()
            var best: String? = null
            var bestRate = -1L
            lines.forEachIndexed { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val rate = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (next != null && rate > bestRate) {
                        bestRate = rate
                        best = next.trim()
                    }
                }
            }
            url = URI(url).resolve(best ?: throw FatalDownloadError("No variant")).toString()
        }
        return String(fetch(item, url)) to url
    }

    private fun parseSegments(text: String, base: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var keyUrl: String? = null
        var iv: ByteArray? = null
        var sequence = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY") -> {
                    val method = Regex("METHOD=([A-Z0-9-]+)").find(line)?.groupValues?.get(1)
                    when (method) {
                        "NONE" -> { keyUrl = null; iv = null }
                        "AES-128" -> {
                            keyUrl = URI(base).resolve(Regex("URI=\"([^\"]+)\"").find(line)!!.groupValues[1]).toString()
                            iv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.let { hex ->
                                hex.padStart(32, '0').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            }
                        }
                        else -> throw FatalDownloadError("Protected content (DRM) can't be downloaded")
                    }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    out.add(Segment(out.size, URI(base).resolve(line).toString(), keyUrl, iv, sequence))
                    sequence++
                }
            }
        }
        return out
    }

    private fun ivFromSequence(sequence: Long) = ByteBuffer.allocate(16).putLong(8, sequence).array()

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    companion object {
        /** Joins a video-only and an audio-only file into one MP4/WebM without re-encoding. */
        fun merge(video: File, audio: File, output: File, webm: Boolean): Boolean = runCatching {
            val format = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            val muxer = MediaMuxer(output.absolutePath, format)
            val sources = listOf(video to "video/", audio to "audio/").map { (file, prefix) ->
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                val index = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith(prefix) == true
                } ?: throw IOException("No $prefix track in ${file.name}")
                extractor.selectTrack(index)
                extractor to muxer.addTrack(extractor.getTrackFormat(index))
            }
            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            for ((extractor, track) in sources) {
                while (true) {
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    info.set(0, size, extractor.sampleTime.coerceAtLeast(0), extractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(track, buffer, info)
                    extractor.advance()
                }
                extractor.release()
            }
            muxer.stop()
            muxer.release()
            true
        }.getOrElse {
            android.util.Log.w("AionDL", "merge failed", it)
            output.delete()
            false
        }

        /** Remuxes an MPEG-TS file into MP4 without re-encoding. Returns false if Android can't read it. */
        fun remux(input: File, output: File): Boolean = runCatching {
            val extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val map = HashMap<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    map[i] = muxer.addTrack(format)
                }
            }
            if (map.isEmpty()) throw IOException("No tracks")
            muxer.start()
            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var firstUs = -1L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = map[extractor.sampleTrackIndex]
                if (track != null) {
                    if (firstUs < 0) firstUs = extractor.sampleTime
                    info.set(0, size, (extractor.sampleTime - firstUs).coerceAtLeast(0), extractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(track, buffer, info)
                }
                extractor.advance()
            }
            muxer.stop()
            muxer.release()
            extractor.release()
            true
        }.getOrElse {
            output.delete()
            false
        }
    }
}
