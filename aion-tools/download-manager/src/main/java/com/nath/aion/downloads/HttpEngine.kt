package com.nath.aion.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Thrown for errors that retrying won't fix (404, 410, 401...). */
class FatalDownloadError(message: String) : IOException(message)

/** Server said "too many requests" / "busy" (429, 503). [retryAfterMs] comes from Retry-After. */
class ThrottledError(val retryAfterMs: Long) : IOException("Server busy, slowing down")

/** Shared OkHttp client built from the current settings. */
object Http {
    @Volatile private var cached: Pair<String, OkHttpClient>? = null

    fun client(prefs: Prefs): OkHttpClient {
        val key = "${prefs.timeoutSec}|${prefs.proxyEnabled}|${prefs.proxyType}|${prefs.proxyHost}|${prefs.proxyPort}|${prefs.proxyUser}"
        cached?.let { if (it.first == key) return it.second }
        val builder = OkHttpClient.Builder()
            .connectTimeout(prefs.timeoutSec.toLong(), TimeUnit.SECONDS)
            .readTimeout(prefs.timeoutSec.toLong(), TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            // Many home networks advertise broken IPv6; try IPv4 addresses first, then IPv6.
            .dns { host -> okhttp3.Dns.SYSTEM.lookup(host).sortedBy { if (it is java.net.Inet4Address) 0 else 1 } }
            .fastFallback(true)
        if (prefs.proxyEnabled && prefs.proxyHost.isNotBlank() && prefs.proxyPort > 0) {
            val type = if (prefs.proxyType == "SOCKS") Proxy.Type.SOCKS else Proxy.Type.HTTP
            builder.proxy(Proxy(type, InetSocketAddress.createUnresolved(prefs.proxyHost, prefs.proxyPort)))
            if (prefs.proxyUser.isNotBlank()) {
                val credential = Credentials.basic(prefs.proxyUser, prefs.proxyPass)
                builder.proxyAuthenticator { _, response ->
                    response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            }
        }
        return builder.build().also { cached = key to it }
    }

    fun request(item: DownloadItem, prefs: Prefs, url: String = item.url): Request.Builder =
        Request.Builder().url(url).apply {
            header("User-Agent", item.userAgent ?: prefs.userAgent)
            item.referer?.let { header("Referer", it) }
            item.cookies?.let { header("Cookie", it) }
            item.headers.forEach { (k, v) -> header(k, v) }
            header("Accept-Encoding", "identity")
        }

    fun fileNameFrom(response: Response, url: String): String? {
        val cd = response.header("Content-Disposition")
        if (cd != null) {
            Regex("filename\\*=(?:UTF-8'')?\"?([^\";]+)", RegexOption.IGNORE_CASE).find(cd)?.let {
                return runCatching { URLDecoder.decode(it.groupValues[1], "UTF-8") }.getOrNull()
            }
            Regex("filename=\"?([^\";]+)", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1].trim() }
        }
        return nameFromUrl(response.request.url.toString()) ?: nameFromUrl(url)
    }

    fun nameFromUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        return decoded.takeIf { it.isNotBlank() && it.length < 200 }
    }

    fun checkStatus(response: Response) {
        val code = response.code
        if (code in 200..299) return
        val message = "HTTP $code"
        if (code == 429 || code == 503) {
            val after = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: 0
            throw ThrottledError(after)
        }
        if (code == 404 || code == 410 || code == 401 || code == 403 || code == 400) throw FatalDownloadError(message)
        throw IOException(message)
    }
}

/**
 * Limits how many parts of one download talk to the server at once. Starts at the planned part
 * count and halves every time the server throttles (never below 1).
 */
class ConnectionGate(initial: Int) {
    @Volatile var limit = initial.coerceAtLeast(1)
        private set
    private var active = 0
    private val lock = Object()

    suspend fun acquire() {
        while (true) {
            synchronized(lock) {
                if (active < limit) {
                    active++
                    return
                }
            }
            delay(250)
        }
    }

    fun release() = synchronized(lock) { active-- }

    fun throttle() = synchronized(lock) { limit = (limit / 2).coerceAtLeast(1) }
}

/** Simple token bucket shared by the parts of one download (and optionally all downloads). */
class SpeedLimiter(private val bytesPerSecond: () -> Long) {
    private var windowStart = System.nanoTime()
    private var used = 0L

    suspend fun take(bytes: Int) {
        val limit = bytesPerSecond()
        if (limit <= 0) return
        val wait: Long
        synchronized(this) {
            val now = System.nanoTime()
            if (now - windowStart > 1_000_000_000L) {
                windowStart = now
                used = 0
            }
            used += bytes
            wait = if (used > limit) (1_000_000_000L - (now - windowStart)) / 1_000_000 else 0
        }
        if (wait > 0) delay(wait)
    }
}

/**
 * Multipart HTTP download: probes the size and range support, splits the file into parts and
 * downloads them in parallel into one pre-sized file. Progress is kept in [DownloadItem.parts] so
 * a paused or killed download resumes where each part stopped.
 */
class HttpEngine(private val prefs: Prefs, private val globalLimiter: SpeedLimiter) {

    /**
     * Probes size/range support. Some networks stall requests that carry a browser user agent, so
     * if the first attempt can't connect it retries once with a plain one and keeps whichever works.
     */
    suspend fun probe(item: DownloadItem) = withContext(Dispatchers.IO) {
        try {
            probeOnce(item)
        } catch (e: FatalDownloadError) {
            throw e
        } catch (e: IOException) {
            if (item.userAgent == Prefs.PLAIN_UA) throw e
            val previous = item.userAgent
            item.userAgent = Prefs.PLAIN_UA
            try {
                probeOnce(item)
            } catch (second: IOException) {
                item.userAgent = previous
                throw second
            }
        }
    }

    private fun probeOnce(item: DownloadItem) {
        val client = Http.client(prefs).newBuilder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
        val response = client.newCall(Http.request(item, prefs).header("Range", "bytes=0-").get().build()).execute()
        response.use {
            Http.checkStatus(it)
            val length = it.header("Content-Length")?.toLongOrNull() ?: -1
            val rangeTotal = it.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
            item.totalBytes = rangeTotal ?: length
            item.resumable = it.code == 206 || it.header("Accept-Ranges")?.contains("bytes") == true
            item.mime = it.header("Content-Type")?.substringBefore(';')
            if (item.fileName.isBlank() || item.fileName == "download") {
                Http.fileNameFrom(it, item.url)?.let { name -> item.fileName = sanitize(name) }
            }
            item.url = it.request.url.toString()
        }
    }

    suspend fun run(item: DownloadItem, onProgress: () -> Unit) = withContext(Dispatchers.IO) {
        if (item.parts.isEmpty() || item.doneBytes == 0L && item.parts.all { it.done == 0L }) {
            probe(item)
            planParts(item)
        }
        val file = item.file
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf -> if (item.totalBytes > 0 && raf.length() < item.totalBytes) raf.setLength(item.totalBytes) }

        val itemLimiter = SpeedLimiter { item.speedLimit }
        val counter = AtomicLong(item.parts.sumOf { it.done })
        item.doneBytes = counter.get()
        coroutineScope {
            val speedJob = async {
                var last = counter.get()
                while (isActive) {
                    delay(1000)
                    val now = counter.get()
                    item.speed = now - last
                    item.doneBytes = now
                    last = now
                    onProgress()
                }
            }
            val gate = ConnectionGate(item.parts.count { !it.complete })
            try {
                item.parts.filter { !it.complete }.map { part ->
                    async { downloadPartWithBackoff(item, part, file, counter, itemLimiter, gate) }
                }.awaitAll()
            } finally {
                speedJob.cancel()
            }
        }
        item.doneBytes = counter.get()
        if (item.totalBytes < 0) item.totalBytes = item.doneBytes
        item.speed = 0
    }

    private fun planParts(item: DownloadItem) {
        val host = runCatching { java.net.URI(item.url).host }.getOrNull()
        val count = if (item.resumable && item.totalBytes > 1024 * 1024) {
            minOf(prefs.partsFor(host), item.threads, 32, (item.totalBytes / (512 * 1024)).toInt()).coerceAtLeast(1)
        } else {
            1
        }
        item.parts.clear()
        if (item.totalBytes <= 0 || count == 1) {
            item.parts.add(Part(0, if (item.totalBytes > 0) item.totalBytes - 1 else -1))
            return
        }
        val size = item.totalBytes / count
        for (i in 0 until count) {
            val start = i * size
            val end = if (i == count - 1) item.totalBytes - 1 else start + size - 1
            item.parts.add(Part(start, end))
        }
    }

    /**
     * Runs one part through the gate. When the server throttles (429/503) the gate drops to fewer
     * simultaneous connections and the part waits and tries again, keeping its progress. Other
     * errors still fail the download so the normal retry policy applies.
     */
    private suspend fun downloadPartWithBackoff(
        item: DownloadItem,
        part: Part,
        file: File,
        counter: AtomicLong,
        itemLimiter: SpeedLimiter,
        gate: ConnectionGate,
    ) {
        var attempt = 0
        while (true) {
            gate.acquire()
            val throttled = try {
                downloadPart(item, part, file, counter, itemLimiter)
                null
            } catch (e: ThrottledError) {
                e
            } finally {
                gate.release()
            }
            if (throttled == null) return
            attempt++
            if (attempt > 30) throw throttled
            gate.throttle()
            item.error = "Server limits connections, using ${gate.limit} part(s)"
            val wait = if (throttled.retryAfterMs > 0) throttled.retryAfterMs else minOf(1000L shl minOf(attempt, 5), 30_000L)
            delay(wait)
        }
    }

    private suspend fun downloadPart(item: DownloadItem, part: Part, file: File, counter: AtomicLong, itemLimiter: SpeedLimiter) {
        val client = Http.client(prefs)
        val from = part.start + part.done
        val request = Http.request(item, prefs).apply {
            if (item.resumable) header("Range", if (part.end >= 0) "bytes=$from-${part.end}" else "bytes=$from-")
        }.get().build()
        val call = client.newCall(request)
        try {
            call.execute().use { response ->
                Http.checkStatus(response)
                if (from > 0 && response.code != 206) {
                    // Server ignored the range: restart this part from zero.
                    counter.addAndGet(-part.done)
                    part.done = 0
                }
                val body = response.body
                RandomAccessFile(file, "rw").use { raf ->
                    raf.seek(part.start + part.done)
                    val buffer = ByteArray(64 * 1024)
                    val input = body.byteStream()
                    while (true) {
                        if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw CancellationException()
                        val wanted = if (part.end >= 0) minOf(buffer.size.toLong(), part.length - part.done).toInt() else buffer.size
                        if (wanted <= 0) break
                        val read = input.read(buffer, 0, wanted)
                        if (read < 0) break
                        raf.write(buffer, 0, read)
                        part.done += read
                        counter.addAndGet(read.toLong())
                        itemLimiter.take(read)
                        globalLimiter.take(read)
                    }
                }
                if (part.end >= 0 && part.done < part.length) throw IOException("Connection closed early")
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        }
    }

    companion object {
        fun sanitize(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().take(180).ifBlank { "download" }
    }
}
