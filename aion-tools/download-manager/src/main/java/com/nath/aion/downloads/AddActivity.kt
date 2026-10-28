package com.nath.aion.downloads

import androidx.compose.foundation.clickable

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The download editor. Opened by Aion ("Download with Aion Downloads"), by any app sharing or
 * opening a link, magnet or .torrent, and by the clipboard monitor.
 *
 * Extras Aion sends: referer, user_agent, cookies, file_name, mime, headers (Bundle).
 */
class AddActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = parse(intent)
        if (request == null) {
            finish()
            return
        }
        val prefs = Prefs(this)
        // "Skip download editor": add straight away like 1DM.
        if (prefs.skipEditor && request.urls.size == 1 && request.torrentBytes == null && !request.urls[0].startsWith("magnet:") &&
            !YouTube.isYouTube(request.urls[0])
        ) {
            Adder.add(this, request.urls[0], request.fileName, prefs.downloadDir, prefs.parts, request, scheduled = false, start = prefs.autoStart)
            finish()
            return
        }
        setContent { AionTheme { Surface { Editor(request, prefs) } } }
    }

    data class Request(
        val urls: List<String>,
        val fileName: String?,
        val referer: String?,
        val userAgent: String?,
        val cookies: String?,
        val mime: String?,
        val headers: Map<String, String>,
        val torrentBytes: ByteArray? = null,
        val audioUrl: String? = null,
    )

    private fun parse(intent: Intent): Request? {
        val extras = intent.extras
        val headers = mutableMapOf<String, String>()
        extras?.getBundle("headers")?.let { b -> b.keySet().forEach { k -> b.getString(k)?.let { headers[k] = it } } }
        var urls = mutableListOf<String>()
        var torrent: ByteArray? = null
        val data = intent.data
        when {
            data != null && (data.scheme == "content" || data.scheme == "file") -> {
                torrent = runCatching { contentResolver.openInputStream(data)?.use { it.readBytes() } }.getOrNull()
                if (torrent == null) return null
                urls.add(data.toString())
            }
            data != null -> urls.add(data.toString())
            intent.action == Intent.ACTION_SEND -> {
                val stream = extras?.let { androidx.core.os.BundleCompat.getParcelable(it, Intent.EXTRA_STREAM, Uri::class.java) }
                if (stream != null) {
                    torrent = runCatching { contentResolver.openInputStream(stream)?.use { it.readBytes() } }.getOrNull()
                    urls.add(stream.toString())
                } else {
                    urls = Links.extract(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "").toMutableList()
                }
            }
            extras?.getString("urls") != null -> urls = extras.getString("urls")!!.lines().filter { it.isNotBlank() }.toMutableList()
        }
        if (urls.isEmpty()) return null
        return Request(
            urls = urls,
            fileName = extras?.getString("file_name"),
            referer = extras?.getString("referer"),
            userAgent = extras?.getString("user_agent"),
            cookies = extras?.getString("cookies"),
            mime = extras?.getString("mime") ?: intent.type,
            headers = headers,
            torrentBytes = torrent,
            audioUrl = extras?.getString("audio_url"),
        )
    }

    @Composable
    private fun Editor(request: Request, prefs: Prefs) {
        val single = request.urls.size == 1
        val first = request.urls[0]
        val isMagnet = first.startsWith("magnet:")
        val isTorrent = request.torrentBytes != null || isMagnet || first.substringBefore('?').endsWith(".torrent")
        var url by remember { mutableStateOf(if (single) first else request.urls.joinToString("\n")) }
        var name by remember { mutableStateOf(request.fileName ?: Http.nameFromUrl(first)?.takeIf { !isMagnet } ?: "") }
        var dir by remember { mutableStateOf(prefs.downloadDir) }
        var parts by remember { mutableStateOf(prefs.parts.toFloat()) }
        var scheduled by remember { mutableStateOf(prefs.scheduleNewLinks && prefs.schedulerEnabled) }
        var start by remember { mutableStateOf(prefs.autoStart) }
        var sizeText by remember { mutableStateOf(if (isTorrent) "" else "Checking size…") }
        var torrentFiles by remember { mutableStateOf<List<TorrentEngine.FileEntry>?>(null) }
        val selected = remember { mutableStateListOf<Int>() }
        var torrentBytes by remember { mutableStateOf(request.torrentBytes) }
        var sequential by remember { mutableStateOf(prefs.sequentialDefault) }
        var seed by remember { mutableStateOf(prefs.seedFinished) }
        var status by remember { mutableStateOf<String?>(null) }
        val isYouTube = single && YouTube.isYouTube(first)
        var ytOptions by remember { mutableStateOf<List<YouTube.Option>?>(null) }
        var ytChoice by remember { mutableStateOf(0) }

        LaunchedEffect(Unit) {
            if (isTorrent) {
                status = if (isMagnet) "Fetching torrent info…" else null
                val bytes = withContext(Dispatchers.IO) {
                    torrentBytes ?: if (isMagnet) {
                        if (prefs.fetchMagnetMetadata) runCatching { DownloadService.torrent(this@AddActivity).fetchMetadata(first, 90) }.getOrNull() else null
                    } else {
                        runCatching { Http.client(prefs).newCall(okhttp3.Request.Builder().url(first).build()).execute().use { it.body.bytes() } }.getOrNull()
                    }
                }
                torrentBytes = bytes
                if (bytes != null) {
                    runCatching {
                        val info = DownloadService.torrent(this@AddActivity).infoFrom(bytes)
                        name = info.name()
                        sizeText = Format.size(info.totalSize())
                        torrentFiles = DownloadService.torrent(this@AddActivity).files(info)
                        selected.addAll(torrentFiles!!.map { it.index })
                    }.onFailure { status = "Invalid torrent" }
                    status = null
                } else if (isMagnet) {
                    status = "Couldn't fetch the file list yet; it will be fetched when the download starts."
                }
            } else if (single && (first.substringBefore('?').endsWith(".m3u8", true) || request.mime?.contains("mpegurl", true) == true)) {
                sizeText = "HLS video stream · saved as .mp4"
                if (request.fileName == null) name = name.substringBeforeLast(".m3u8") + ".mp4"
            } else if (isYouTube) {
                status = "Getting YouTube qualities…"
                runCatching { YouTube.resolve(first) }
                    .onSuccess { result ->
                        if (request.fileName == null || name.isBlank() || name.startsWith("watch")) {
                            name = HttpEngine.sanitize(result.title)
                        }
                        ytOptions = result.options
                        // Default: the smallest MP4 that has 720p or less, to keep data low.
                        ytChoice = result.options.indexOfFirst { it.label.startsWith("720p") && it.ext == "mp4" }
                            .takeIf { it >= 0 } ?: 0
                        sizeText = if (result.options.isEmpty()) "No downloadable formats" else "YouTube · pick a quality"
                    }
                    .onFailure { sizeText = "Couldn't read this YouTube video (${it.message ?: it.javaClass.simpleName})" }
                status = null
            } else if (single) {
                val probe = DownloadItem(0, first, name, dir, Kind.HTTP, referer = request.referer, userAgent = request.userAgent, cookies = request.cookies, headers = request.headers)
                runCatching { HttpEngine(prefs, SpeedLimiter { 0 }).probe(probe) }
                    .onSuccess {
                        if (request.fileName == null) name = probe.fileName
                        sizeText = Format.size(probe.totalBytes) + if (probe.resumable) " · resumable" else " · not resumable"
                    }
                    .onFailure { sizeText = "Size unknown (${it.message})" }
            } else {
                sizeText = "${request.urls.size} links"
            }
        }

        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (isTorrent) "Add torrent" else if (single) "New download" else "Batch download", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(url, { url = it }, label = { Text(if (single) "Link" else "Links (one per line, [1-99] patterns allowed)") },
                modifier = Modifier.fillMaxWidth(), maxLines = if (single) 3 else 8)
            if (single) OutlinedTextField(name, { name = it }, label = { Text("File name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(dir, { dir = it }, label = { Text("Save to folder") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (sizeText.isNotEmpty()) Text(sizeText, style = MaterialTheme.typography.bodyMedium)
            status?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(18.dp))
                    Spacer(Modifier.padding(4.dp))
                    Text(it)
                }
            }
            torrentFiles?.let { files ->
                Text("Files (${selected.size}/${files.size})", style = MaterialTheme.typography.titleSmall)
                Row {
                    TextButton({ selected.clear(); selected.addAll(files.map { it.index }) }) { Text("All") }
                    TextButton({ selected.clear() }) { Text("None") }
                }
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    items(files) { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(f.index in selected, { if (it) selected.add(f.index) else selected.remove(f.index) })
                            Column(Modifier.weight(1f)) {
                                Text(f.path, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(Format.size(f.size), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            ytOptions?.let { options ->
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(options.size) { i ->
                        Row(
                            Modifier.fillMaxWidth().clickable { ytChoice = i }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(ytChoice == i, { ytChoice = i })
                            Text(options[i].label)
                        }
                    }
                }
            }
            if (isTorrent) {
                LabeledSwitch("Sequential download (play while downloading)", sequential) { sequential = it }
                LabeledSwitch("Seed when finished", seed) { seed = it }
            } else if (!(single && (first.substringBefore('?').endsWith(".m3u8", true) || request.mime?.contains("mpegurl", true) == true))) {
                Text("Parts: ${parts.toInt()}")
                Slider(parts, { parts = it }, valueRange = 1f..32f, steps = 30)
            }
            LabeledSwitch("Add to scheduled downloads", scheduled) { scheduled = it }
            LabeledSwitch("Start now", start) { start = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ finish() }) { Text("Cancel") }
                Button({
                    val links = url.lines().map { it.trim() }.filter { it.isNotEmpty() }.flatMap { Links.expandBatch(it) }
                    val yt = ytOptions?.getOrNull(ytChoice)
                    if (isYouTube && yt == null) return@Button
                    if (yt != null) {
                        val base = name.ifBlank { "YouTube video" }.substringBeforeLast(".mp4").substringBeforeLast(".webm")
                        Adder.add(
                            this@AddActivity, yt.videoUrl, "$base.${yt.ext}", dir, parts.toInt(),
                            request.copy(referer = first, audioUrl = yt.audioUrl, mime = null), scheduled, start,
                        )
                    } else if (isTorrent) {
                        Adder.addTorrent(this@AddActivity, first, torrentBytes, name, dir, selected.toList().takeIf { torrentFiles != null }, sequential, seed, scheduled, start)
                    } else {
                        links.forEach { link ->
                            Adder.add(this@AddActivity, link, if (links.size == 1) name else null, dir, parts.toInt(), request, scheduled, start)
                        }
                    }
                    startActivity(Intent(this@AddActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                }) { Text(if (start && !scheduled) "Download" else "Add") }
            }
        }
    }
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

/** Creates download items and wakes the service. */
object Adder {
    fun add(
        context: android.content.Context,
        url: String,
        name: String?,
        dir: String,
        parts: Int,
        request: AddActivity.Request?,
        scheduled: Boolean,
        start: Boolean,
    ): DownloadItem {
        val prefs = Prefs(context)
        val repo = Repository.get(context)
        val isHls = url.substringBefore('?').endsWith(".m3u8", true) ||
            request?.mime?.contains("mpegurl", true) == true
        var fileName = HttpEngine.sanitize(name ?: Http.nameFromUrl(url) ?: "download")
        if (isHls && !fileName.endsWith(".mp4")) fileName = fileName.substringBeforeLast(".m3u8") + ".mp4"
        if (prefs.autoRename) fileName = unique(dir, fileName, repo)
        val item = DownloadItem(
            id = prefs.nextId, url = url, fileName = fileName, dir = dir, kind = if (isHls) Kind.HLS else Kind.HTTP,
            state = if (start && !scheduled) State.QUEUED else State.PAUSED, threads = parts,
            referer = request?.referer, userAgent = request?.userAgent, cookies = request?.cookies,
            headers = request?.headers ?: emptyMap(), mime = request?.mime, scheduled = scheduled,
            audioUrl = request?.audioUrl,
        )
        repo.add(item)
        if (item.state == State.QUEUED || scheduled) DownloadService.kick(context)
        return item
    }

    fun addTorrent(
        context: android.content.Context,
        source: String,
        bytes: ByteArray?,
        name: String,
        dir: String,
        selected: List<Int>?,
        sequential: Boolean,
        seed: Boolean,
        scheduled: Boolean,
        start: Boolean,
    ) {
        val prefs = Prefs(context)
        val engine = DownloadService.torrent(context)
        val hash = bytes?.let { runCatching { engine.infoFrom(it).infoHash().toHex() }.getOrNull() }
            ?: TorrentEngine.hashFromMagnet(source)
        if (hash != null && bytes != null) engine.saveTorrentFile(hash, bytes)
        val repo = Repository.get(context)
        if (hash != null && repo.all().any { it.torrentHash == hash }) return
        val item = DownloadItem(
            id = prefs.nextId, url = if (source.startsWith("magnet:") || source.startsWith("http")) source else "torrent:$hash",
            fileName = name.ifBlank { "magnet-${hash?.take(8)}" }, dir = dir, kind = Kind.TORRENT,
            state = if (start && !scheduled) State.QUEUED else State.PAUSED, torrentHash = hash, selectedFiles = selected,
            sequential = sequential, seed = seed, scheduled = scheduled,
        )
        repo.add(item)
        if (item.state == State.QUEUED) DownloadService.kick(context)
    }

    private fun unique(dir: String, name: String, repo: Repository): String {
        val taken = repo.all().filter { it.dir == dir }.map { it.fileName }.toSet()
        if (name !in taken && !File(dir, name).exists()) return name
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate !in taken && !File(dir, candidate).exists()) return candidate
            i++
        }
    }
}
